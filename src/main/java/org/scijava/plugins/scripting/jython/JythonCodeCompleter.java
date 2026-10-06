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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
		return new CompletionResult(result.completions, replaceStart,
			result.parameterChoices);
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
				type) -> vars.put(name, new VarDotAutocompletions(boxed(type)
					.getName())));
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

	/** Maps primitive types to their wrappers, whose members can be listed. */
	private static Class<?> boxed(final Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == double.class) return Double.class;
		if (type == float.class) return Float.class;
		if (type == boolean.class) return Boolean.class;
		if (type == char.class) return Character.class;
		if (type == short.class) return Short.class;
		if (type == byte.class) return Byte.class;
		return Object.class; // void
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
