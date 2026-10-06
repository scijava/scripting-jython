/*-
 * #%L
 * JSR-223-compliant Jython scripting language plugin.
 * %%
 * Copyright (C) 2020 - 2025 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.scijava.plugins.scripting.jython;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.script.Bindings;
import javax.script.ScriptContext;

import org.python.core.PyJavaType;
import org.python.core.PyModule;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.scijava.Context;
import org.scijava.Priority;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptLanguage;
import org.scijava.code.api.AbstractCodeCompleterPlugin;
import org.scijava.code.api.ClassIndex;
import org.scijava.code.api.CodeCompleterPlugin;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.EnvironmentContext;
import org.scijava.code.api.ScriptDialect;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.lsp.LspClient;
import org.scijava.code.lsp.LspServers;
import org.scijava.code.lsp.LspService;
import org.scijava.code.lsp.PythonLspClient;
import org.scijava.code.api.SignatureHelp;
import org.scijava.code.api.Signatures;

/**
 * Toolkit-agnostic code completion for the Jython language, contributed by the
 * Jython scripting plugin itself. Depends only on scijava-common and jython, so
 * it works in any front end that consumes the SciJava completion SPI (the Swing
 * script editor, a REPL, etc.) without any editor- or RSyntaxTextArea-specific
 * code.
 *
 * @author Albert Cardona
 * @author Curtis Rueden
 * @see JythonAutoCompletions
 */
@Plugin(type = CodeCompleterPlugin.class, name = "Jython",
	priority = Priority.HIGH)
public class JythonCodeCompleter extends AbstractCodeCompleterPlugin {

	/** How scripts see their parameters: as the Java objects themselves. */
	private static final JythonDialect DIALECT = new JythonDialect();

	/**
	 * How long to wait for the language server before answering without it
	 * (and updating the answer once its arrives), in milliseconds.
	 */
	private static final long BUDGET = Long.getLong(
		"scijava.jython.completion.budget", 25);

	/** How many completions get signatures, once a word is begun. */
	private static final int SIGNATURES = 30;

	private LspClient lsp;

	private JavaStubs stubs;

	private final JythonAutoCompletions engine = new JythonAutoCompletions();

	public JythonCodeCompleter() {
		// Warm the class index in the background, as completion needs it.
		new Thread(ClassIndex::ensureCache, "Jython-ClassIndex-warmup").start();
	}

	@Override
	public boolean supports(final ScriptLanguage language) {
		if (language == null) return false;
		final String name = language.getLanguageName();
		// Matches "Jython" and "Python (Jython)" without hijacking CPython.
		return name != null && name.toLowerCase().contains("jython");
	}

	@Override
	public CompletionResult complete(final CompletionRequest request) {
		final String textToCaret = request.textToCaret();
		final int caret = request.offset();
		final String lastLine = request.lineToCaret();
		final String codeWithoutLastLine = textToCaret.substring(0, textToCaret
			.length() - lastLine.length());
		final String alreadyEntered = alreadyEnteredText(lastLine);
		final int replaceStart = caret - alreadyEntered.length();

		final JythonAutoCompletions.Result result = engine.completionsFor(
			codeWithoutLastLine, lastLine, alreadyEntered, predefinedVariables(
				request));
		final CompletionResult local = new CompletionResult(result.completions,
			replaceStart, result.parameterChoices);
		if (lastLine.trim().startsWith("#")) return local;

		// Also ask a Python language server, if there is one, knowing the Java
		// packages from stubs: its completions follow Jython's own.
		final LspClient client = lspClient();
		if (client == null) return local;
		// NB: What was typed is replaced as a whole, e.g. "os.pa": the server's
		// completions of the word ("path") get the qualifier ("os.").
		int w = alreadyEntered.length();
		while (w > 0 && Character.isJavaIdentifierPart(alreadyEntered.charAt(w -
			1))) w--;
		final String qualifier = alreadyEntered.substring(0, w);
		final String word = alreadyEntered.substring(w);
		if (word.isEmpty() && !qualifier.endsWith(".")) return local;
		final CompletableFuture<List<Completion>> answer;
		try {
			// NB: Asked about the word's first character, so that later keystrokes
			// reuse the answer.
			answer = client.complete(document(request), uri(request), caret - word
				.length() + Math.min(1, word.length()), word.isEmpty() ? 0
					: SIGNATURES);
		}
		catch (final RuntimeException exc) {
			return local; // NB: E.g. no stubs directory.
		}
		try {
			return merge(local, answer.get(BUDGET, TimeUnit.MILLISECONDS), qualifier,
				word);
		}
		catch (final TimeoutException exc) {
			return local.withUpdate(answer.handle((server, error) -> server == null
				? null : merge(local, server, qualifier, word)));
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final ExecutionException | CancellationException exc) {
			// NB: The server failed; the next request asks again.
		}
		return local;
	}

	@Override
	public void prepare(final CompletionRequest request) {
		// Warm up: write the stubs of the packages the script uses, and start
		// the server, in the background.
		final LspClient client = lspClient();
		if (client != null) client.prepare(document(request), uri(request));
	}

	@Override
	public void closed(final CompletionRequest request) {
		final LspClient client;
		synchronized (this) {
			client = lsp;
		}
		if (client != null) client.close(uri(request));
	}

	/** Sets the language server client, e.g. to use a fake one in tests. */
	synchronized void setLspClient(final LspClient lsp) {
		this.lsp = lsp;
	}

	/** Sets the stubs, e.g. to write them elsewhere in tests. */
	synchronized void setStubs(final JavaStubs stubs) {
		this.stubs = stubs;
	}

	/**
	 * Gets the language server client, if a Python language server is offered
	 * (see {@link LspService}); creating it on first use.
	 */
	private synchronized LspClient lspClient() {
		if (lsp != null) return lsp;
		final Context context = getContext();
		final LspService service = context == null ? null : context.getService(
			LspService.class);
		final LspServers servers = service == null ? null : service.servers(
			"python");
		if (servers == null) return null;
		lsp = new PythonLspClient(servers);
		return lsp;
	}

	/** Gets the stubs of Java packages, creating them on first use. */
	private synchronized JavaStubs stubs() {
		if (stubs != null) return stubs;
		final String dir = System.getProperty("scijava.jython.stubs");
		stubs = new JavaStubs(dir != null ? new File(dir) : new File(System
			.getProperty("user.home"), ".cache" + File.separator + "scijava" +
				File.separator + "jython-stubs"), Thread.currentThread()
					.getContextClassLoader());
		return stubs;
	}

	/**
	 * Gets the script as plain Python, for the server: its parameters
	 * declared; searching the stubs of Java packages (written for those it
	 * imports, and its parameters' types, in the background).
	 */
	private ScriptDocument document(final CompletionRequest request) {
		final Map<String, Class<?>> params = scriptParameters(request.text());
		final ScriptDocument doc = ScriptDocument.of(request.text(), params,
			DIALECT, null);
		final JavaStubs s = stubs();
		final Set<String> packages = new TreeSet<>(JavaStubs.imports(request
			.text()));
		packages.addAll(JythonDialect.packages(doc.parameters().values().stream()
			.filter(t -> t != null).collect(Collectors.toList())));
		s.ensure(packages);
		// NB: The server's own Python (CPython): Jython's standard library is
		// Python 2's, but most of it is the same.
		return ScriptDocument.of(request.text(), params, DIALECT,
			new EnvironmentContext(null, Collections.singletonList(s.dir()
				.getAbsolutePath()), null));
	}

	private Map<String, Class<?>> scriptParameters(final String text) {
		final Context context = getContext();
		final CodeCompletionService service = context == null ? null : context
			.getService(CodeCompletionService.class);
		return service == null ? Collections.emptyMap() : service
			.scriptParameters(text);
	}

	/** The URI of the script, for the language server. */
	private static String uri(final CompletionRequest request) {
		return LspClient.uri(request.path(), "jython-script.py");
	}

	/**
	 * Merges the server's completions (of the word being typed) after Jython's
	 * own: those Jython does not know already, with the qualifier typed.
	 */
	private static CompletionResult merge(final CompletionResult local,
		final List<Completion> server, final String qualifier, final String word)
	{
		if (server == null || server.isEmpty()) return local;
		final List<Completion> out = new ArrayList<>(local.completions());
		final Set<String> seen = new HashSet<>();
		for (final Completion c : out) seen.add(stripParens(c.insertionText()));
		final boolean importing = qualifier.trim().startsWith("from ") || qualifier
			.trim().startsWith("import ");
		for (final Completion c : server) {
			final String name = c.insertionText();
			if (!name.toLowerCase(Locale.ROOT).startsWith(word.toLowerCase(
				Locale.ROOT)) || name.equals(word)) continue;
			if (name.startsWith("_") && !word.startsWith("_")) continue;
			if (!seen.add(qualifier + name)) continue;
			// NB: An import names things rather than calling them.
			final Completion.Kind kind = importing && c.isCallable()
				? Completion.Kind.OTHER : c.kind();
			out.add(Completion.builder(qualifier + name).kind(kind)
				.parameters(importing ? Collections.emptyList() : c.parameters())
				.returnType(c.returnType()).summary(c.summary()).lazyDescription(
					c::description).build());
		}
		return new CompletionResult(out, local.replaceStart(), local
			.parameterChoices());
	}

	@Override
	public SignatureHelp signatureHelp(final CompletionRequest request) {
		final String text = request.text();
		final int caret = request.offset();
		if (request.lineToCaret().trim().startsWith("#")) return SignatureHelp.NONE;
		final int open = Signatures.callStart(text, caret);
		if (open < 0) return SignatureHelp.NONE;
		final List<String> args = Signatures.arguments(text, open, caret);
		if (args == null) return SignatureHelp.NONE;

		// The callee, e.g. "Math.max" or "ArrayList".
		final String callee = alreadyEnteredText(text.substring(text.lastIndexOf(
			'\n', open - 1) + 1, open)).trim();
		if (callee.isEmpty()) return SignatureHelp.NONE;

		// Its signatures: what completion offers for it, as if the caret were
		// just before the "(" (methods: "Math.max"), or just after it
		// (constructors: offered by their class's simple name, "ArrayList").
		final String simpleName = callee.substring(callee.lastIndexOf('.') + 1);
		JythonAutoCompletions.Result result = null;
		List<Completion> callables = Collections.emptyList();
		for (final int at : new int[] { open, open + 1 }) {
			final String name = at == open ? callee : simpleName;
			result = completionsAt(request, at);
			callables = result.completions.stream().filter(c -> c.isCallable() &&
				stripParens(c.insertionText()).equals(name)).collect(Collectors
					.toList());
			if (!callables.isEmpty()) break;
		}
		if (callables.isEmpty()) return SignatureHelp.NONE;

		// The types of the arguments before the caret's (which is being typed).
		final List<String> argTypes = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			final String arg = args.get(i).trim();
			argTypes.add(i == args.size() - 1 || arg.isEmpty() ? null : result.types
				.apply(arg));
		}
		// NB: Reflection lists methods in no particular order: sort them by
		// their parameter types, so that equally good fits come out the same.
		final List<Completion> sorted = new ArrayList<>(callables);
		sorted.sort(Comparator.comparing(c -> c.parameters().stream().map(
			p -> String.valueOf(p.type())).collect(Collectors.joining(","))));
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return new SignatureHelp(Signatures.rateAll(sorted, argTypes, (a,
			p) -> Signatures.fit(a, p, loader)), args.size() - 1, open);
	}

	/** Computes completions as if the caret were at the given offset. */
	private JythonAutoCompletions.Result completionsAt(
		final CompletionRequest request, final int offset)
	{
		final CompletionRequest there = new CompletionRequest(request.text(),
			offset, request.language(), request.engine(), request.context(), request
				.path());
		final String textToCaret = there.textToCaret();
		final String lastLine = there.lineToCaret();
		return engine.completionsFor(textToCaret.substring(0, textToCaret
			.length() - lastLine.length()), lastLine, alreadyEnteredText(lastLine),
			predefinedVariables(request));
	}

	private static String stripParens(final String name) {
		return name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
	}

	/**
	 * Gets the variables defined before the script's code runs: script
	 * parameters declared via {@code #@} lines, and, when completing in a live
	 * interpreter, the engine's bindings (which reflect actual runtime values, so
	 * they take precedence).
	 */
	private Map<String, DotAutocompletions> predefinedVariables(
		final CompletionRequest request)
	{
		final Map<String, DotAutocompletions> vars = new LinkedHashMap<>();
		final Context context = getContext();
		final CodeCompletionService completionService = context == null ? null
			: context.getService(CodeCompletionService.class);
		if (completionService != null) {
			completionService.scriptParameters(request.text()).forEach((name,
				type) -> vars.put(name, new VarDotAutocompletions(DIALECT.runtimeType(
					type))));
		}
		final ScriptContext scriptContext = request.context();
		final Bindings bindings = scriptContext == null ? null : scriptContext
			.getBindings(ScriptContext.ENGINE_SCOPE);
		if (bindings != null) {
			for (final Map.Entry<String, Object> entry : bindings.entrySet()) {
				final String name = entry.getKey();
				if (name.startsWith("__") && name.endsWith("__")) continue;
				vars.put(name, bindingCompletions(entry.getValue()));
			}
		}
		return vars.isEmpty() ? Collections.emptyMap() : vars;
	}

	/** Describes a live binding value for completion purposes. */
	private static DotAutocompletions bindingCompletions(final Object value) {
		if (value instanceof PyJavaType) {
			// An imported Java class: offer its constructors and static members.
			final Class<?> c = ((PyJavaType) value).getProxyType();
			if (c != null) return new StaticDotAutocompletions(c.getName());
		}
		if (value instanceof PyModule) {
			final PyObject name = ((PyModule) value).__findattr__("__name__");
			if (name instanceof PyString) {
				return new StaticDotAutocompletions(name.toString());
			}
		}
		if (value == null || value instanceof PyObject) {
			// A python function, class or instance: known by name only.
			return new VarDotAutocompletions(null);
		}
		return new VarDotAutocompletions(value.getClass().getName());
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	/**
	 * The portion of the current line the editor should replace. Mirrors the
	 * legacy RSTA logic: a maximal suffix of letters, digits, {@code '.'},
	 * {@code '_'} and spaces (spaces let "from x import Y" complete as a unit),
	 * excluding leading spaces.
	 */
	private static String alreadyEnteredText(final String lastLine) {
		int start = lastLine.length();
		while (start > 0) {
			final char c = lastLine.charAt(start - 1);
			if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == ' ') //
				start--;
			else break;
		}
		// Leading whitespace is not part of what the user is completing.
		while (start < lastLine.length() && lastLine.charAt(start) == ' ') start++;
		return lastLine.substring(start);
	}
}
