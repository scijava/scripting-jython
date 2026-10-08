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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.script.Bindings;
import javax.script.ScriptContext;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.SignatureHelp;
import org.python.core.PyJavaType;
import org.python.core.PyModule;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.scijava.code.lsp.jvm.ClassIndex;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.jvm.Callable;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.jvm.ScriptLanguageServer;
import org.scijava.code.lsp.jvm.Signatures;

/**
 * An in-process language server for Jython, driven by Jython's own analysis
 * (see {@link JythonAutoCompletions}): Java classes, their members and
 * constructors, Python names, keywords and imports; script parameters and, in
 * a live interpreter, its variables. In a call's arguments, with nothing
 * typed, the variables of the parameter's type come first. Signature help
 * rates the overloads against the arguments typed.
 *
 * @author Albert Cardona
 * @author Curtis Rueden
 * @author Gabriel Selzer
 */
public class JythonLanguageServer extends ScriptLanguageServer {

	/** How scripts see their parameters: as the Java objects themselves. */
	static final JythonDialect DIALECT = new JythonDialect();

	static {
		// Warm the class index in the background, as completion needs it.
		new Thread(ClassIndex::ensureCache, "Jython-ClassIndex-warmup").start();
	}

	private final LanguageServerService servers;
	private final JythonAutoCompletions engine = new JythonAutoCompletions();

	/**
	 * @param servers Reads the scripts' parameters.
	 * @param environment Where the scripts run (e.g. a live interpreter).
	 */
	public JythonLanguageServer(final LanguageServerService servers,
		final Environment environment)
	{
		super(environment);
		this.servers = servers;
	}

	@Override
	protected CompletionList complete(final Document doc, final int caret) {
		final String text = doc.text();
		final String lastLine = doc.lineTo(caret);
		if (lastLine.trim().startsWith("#")) {
			return list(doc, caret, caret, new ArrayList<>());
		}
		final String alreadyEntered = alreadyEnteredText(lastLine);
		final int replaceStart = caret - alreadyEntered.length();
		final JythonAutoCompletions.Result result = completionsAt(text, caret);
		final List<CompletionItem> items = new ArrayList<>();
		if (alreadyEntered.isEmpty()) items.addAll(argumentChoices(text, caret));
		items.addAll(result.completions);
		return list(doc, replaceStart, caret, items);
	}

	@Override
	protected SignatureHelp signatureHelp(final Document doc, final int caret) {
		final String text = doc.text();
		if (doc.lineTo(caret).trim().startsWith("#")) return null;
		final Call call = call(text, caret);
		if (call == null || call.callables.isEmpty()) return null;

		// The types of the arguments before the caret's (which is being typed).
		final List<String> argTypes = new ArrayList<>();
		for (int i = 0; i < call.args.size(); i++) {
			final String arg = call.args.get(i).trim();
			argTypes.add(i == call.args.size() - 1 || arg.isEmpty() ? null
				: call.result.types.apply(arg));
		}
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return new SignatureHelp(Signatures.rateAll(call.callables, argTypes, (a,
			p) -> Signatures.fit(a, p, loader)), 0, call.args.size() - 1);
	}

	// -- Helper methods --

	/** The call around the caret: its signatures, and the arguments typed. */
	private static final class Call {

		private final JythonAutoCompletions.Result result;
		private final List<Callable> callables;
		private final List<String> args;

		private Call(final JythonAutoCompletions.Result result,
			final List<Callable> callables, final List<String> args)
		{
			this.result = result;
			this.callables = callables;
			this.args = args;
		}
	}

	/** Finds the call around the caret, or returns null if none. */
	private Call call(final String text, final int caret) {
		final int open = Signatures.callStart(text, caret);
		if (open < 0) return null;
		final List<String> args = Signatures.arguments(text, open, caret);
		if (args == null) return null;

		// The callee, e.g. "Math.max" or "ArrayList".
		final String callee = alreadyEnteredText(text.substring(text.lastIndexOf(
			'\n', open - 1) + 1, open)).trim();
		if (callee.isEmpty()) return null;

		// Its signatures: what completion offers for it, as if the caret were
		// just before the "(" (methods: "Math.max"), or just after it
		// (constructors: offered by their class's simple name, "ArrayList").
		final String simpleName = callee.substring(callee.lastIndexOf('.') + 1);
		JythonAutoCompletions.Result result = null;
		List<Callable> callables = Collections.emptyList();
		for (final int at : new int[] { open, open + 1 }) {
			final String name = at == open ? callee : simpleName;
			result = completionsAt(text, at);
			callables = new ArrayList<>();
			for (final CompletionItem item : result.completions) {
				final Callable c = Callable.of(item);
				if (c != null && c.name().equals(name)) callables.add(c);
			}
			if (!callables.isEmpty()) break;
		}
		// NB: Reflection lists methods in no particular order: sort them by
		// their parameter types, so that equally good fits come out the same.
		callables.sort(Comparator.comparing(Callable::parameterTypes));
		return new Call(result, callables, args);
	}

	/**
	 * In a call's arguments, with nothing typed: the variables of the
	 * parameter's type (the first preselected).
	 */
	private List<CompletionItem> argumentChoices(final String text,
		final int caret)
	{
		final Call call = call(text, caret);
		if (call == null || call.result.variablesOfType == null) {
			return Collections.emptyList();
		}
		final int active = call.args.size() - 1;
		final Set<String> names = new LinkedHashSet<>();
		for (final Callable c : call.callables) {
			if (active >= c.params().size()) continue;
			names.addAll(call.result.variablesOfType.apply(c.params().get(active)
				.type()));
		}
		final List<CompletionItem> out = new ArrayList<>();
		for (final String name : names) {
			final CompletionItem item = new CompletionItem(name);
			item.setKind(CompletionItemKind.Variable);
			item.setInsertText(name);
			item.setPreselect(out.isEmpty());
			out.add(item);
		}
		return out;
	}

	/** Computes completions as if the caret were at the given offset. */
	private JythonAutoCompletions.Result completionsAt(final String text,
		final int offset)
	{
		final String textToCaret = text.substring(0, Math.min(offset, text
			.length()));
		final String lastLine = textToCaret.substring(textToCaret.lastIndexOf(
			'\n') + 1);
		return engine.completionsFor(textToCaret.substring(0, textToCaret
			.length() - lastLine.length()), lastLine, alreadyEnteredText(lastLine),
			predefinedVariables(text));
	}

	/**
	 * Gets the variables defined before the script's code runs: script
	 * parameters declared via {@code #@} lines, and, when completing in a live
	 * interpreter, its bindings (which reflect actual runtime values, so they
	 * take precedence).
	 */
	private Map<String, DotAutocompletions> predefinedVariables(
		final String text)
	{
		final Map<String, DotAutocompletions> vars = new LinkedHashMap<>();
		if (servers != null) {
			servers.scriptParameters(text).forEach((name,
				type) -> vars.put(name, new VarDotAutocompletions(DIALECT.runtimeType(
					type))));
		}
		final ScriptContext live = environment().live();
		final Bindings bindings = live == null ? null : live.getBindings(
			ScriptContext.ENGINE_SCOPE);
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

	/**
	 * The portion of the current line to replace: a maximal suffix of letters,
	 * digits, {@code '.'}, {@code '_'} and spaces (spaces let "from x import Y"
	 * complete as a unit), excluding leading spaces.
	 */
	static String alreadyEnteredText(final String lastLine) {
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
