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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;

import org.junit.Test;
import org.scijava.code.api.ClassIndex;
import org.scijava.Context;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.SignatureHelp;

/**
 * Tests that the Jython {@link JythonCodeCompleter} is discovered for the Jython
 * language and produces member completions via the SciJava completion SPI, with
 * no dependency on the Swing script editor.
 *
 * @author Curtis Rueden
 */
public class JythonCompletionTest {

	@Test
	public void testMemberCompletionViaService() {
		final Context ctx = new Context(ScriptService.class,
			CodeCompletionService.class);
		try {
			final ScriptService scriptService = ctx.service(ScriptService.class);
			final CodeCompletionService completion = ctx.service(
				CodeCompletionService.class);

			final ScriptLanguage jython = scriptService.getLanguageByName("Jython");
			assertNotNull("Jython language not found", jython);

			// The Jython completer must be discovered and claim the Jython language.
			assertNotNull("JythonCodeCompleter not registered for Jython", //
				completion.getCompleterPlugin(jython));

			// Static analysis: 's' is a String, so 's.' completes to String members.
			final String code = "s = \"hello\"\ns.";
			final CompletionResult result = completion.complete(//
				new CompletionRequest(code, jython, null));
			final List<String> texts = result.completions().stream().map(
				Completion::insertionText).collect(Collectors.toList());

			assertTrue("expected s.length among " + texts, texts.contains("s.length"));
			assertTrue("expected s.charAt among " + texts, texts.contains("s.charAt"));
		}
		finally {
			ctx.dispose();
		}
	}

	@Test
	public void testNameAtStartOfLine() {
		final List<String> texts = complete("greeting = \"hi\"\ngree");
		assertTrue("expected greeting among " + texts, texts.contains("greeting"));
	}

	@Test
	public void testVariableNameNotDuplicated() {
		// A String variable must not also offer String's constructors.
		final List<String> texts = complete("greeting = \"hi\"\nx = gree");
		assertEquals(texts.toString(), 1, texts.stream().filter(
			"greeting"::equals).count());
		assertFalse("unexpected leading space in " + texts, texts.contains(
			" greeting"));
	}

	@Test
	public void testKeywordsAndBuiltins() {
		assertTrue(complete("imp").contains("import"));
		final List<String> texts = complete("x = le");
		assertTrue("expected len among " + texts, texts.contains("len"));
		assertFalse("unexpected builtin member in " + texts, texts.stream()
			.anyMatch(t -> t.contains(".")));
	}

	@Test
	public void testImportedClassName() {
		ClassIndex.ensureCache();
		final List<String> texts = complete(
			"from java.util import ArrayList\nArrayList");
		assertTrue("expected ArrayList among " + texts, texts.contains(
			"ArrayList"));
		assertEquals("expected one plain ArrayList among " + texts, 1, texts
			.stream().filter("ArrayList"::equals).count() - constructorCount(
				"from java.util import ArrayList\nArrayList"));
	}

	@Test
	public void testJdkClassImport() {
		ClassIndex.ensureCache();
		final List<String> texts = complete("from java.util import ArrayLi");
		assertTrue("expected ArrayList import among " + texts, texts.contains(
			"from java.util import ArrayList"));
	}

	@Test
	public void testScriptParameters() {
		final String header = "" + //
			"#@ String (label=\"Please enter your name\") name\n" + //
			"#@output String greeting\n" + //
			"#@ int count\n" + //
			"#@ NoSuchType mystery\n";
		assertTrue(complete(header + "x = na").contains("name"));
		assertTrue(complete(header + "myst").contains("mystery"));
		final List<String> nameMembers = complete(header + "name.toUpp");
		assertTrue("expected name.toUpperCase among " + nameMembers, nameMembers
			.contains("name.toUpperCase"));
		// Outputs are known too, even before the script assigns them.
		assertTrue(complete(header + "greeting.len").contains("greeting.length"));
		// Primitive parameters complete as their wrapper type.
		assertTrue(complete(header + "count.intV").contains("count.intValue"));
	}

	@Test
	public void testAssignmentShadowsScriptParameter() {
		final List<String> texts = complete(
			"#@ String name\nfrom java.util import ArrayList\nname = ArrayList()\nname.ensureCap");
		assertTrue("expected ArrayList member among " + texts, texts.contains(
			"name.ensureCapacity"));
	}

	@Test
	public void testInheritedMembers() {
		final List<String> texts = complete(
				"from java.util import ArrayList\nlist = ArrayList()\nlist.");
		// members come from superinterfaces
		assertTrue(texts.contains("list.add"));
		// members come from superclasses
		assertTrue(texts.contains("list.toString"));
	}

	@Test
	public void testInterpreterBindings() throws Exception {
		final Context ctx = new Context();
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			final ScriptEngine engine = jython.getScriptEngine();
			engine.put("injected", new java.util.ArrayList<String>());
			engine.eval("from java.util import Collections, HashMap\n" +
				"m = HashMap()\n" +
				"def helper(): pass\n");
			final CodeCompletionService service = ctx.service(
				CodeCompletionService.class);

			assertTrue(complete(service, jython, engine, "m.putIfAb").contains(
				"m.putIfAbsent"));
			assertTrue(complete(service, jython, engine, "injected.si").contains(
				"injected.size"));
			assertTrue(complete(service, jython, engine, "x = hel").contains(
				"helper"));
			// An imported Java class offers its static members.
			assertTrue(complete(service, jython, engine, "Collections.emptyL")
				.contains("Collections.emptyList"));
			// Python internals are not offered.
			assertFalse(complete(service, jython, engine, "x = __").contains(
				"__name__"));
		}
		finally {
			ctx.dispose();
		}
	}

	@Test
	public void testSignatureHelp() {
		// A Python float fits the floating-point overloads, which come first.
		final SignatureHelp max = help("from java.lang import Math\n" +
			"Math.max(1.5, ");
		assertEquals(1, max.activeParameter());
		assertEquals("[double:MATCH, float:MATCH, int:MISMATCH, long:MISMATCH]",
			describe(max));
		// A Python int fits the integral ones, and converts to the others.
		assertEquals("[int:MATCH, long:MATCH, double:CONVERSION, " +
			"float:CONVERSION]", describe(help("from java.lang import Math\n" +
				"Math.max(1, 2")));
		// Arguments' types come from the analysis, e.g. of variables.
		final SignatureHelp indexOf = help("s = 'abc'\nc = 'b'\n" +
			"s.indexOf(c, ");
		assertTrue(describe(indexOf), describe(indexOf).startsWith(
			"[java.lang.String:MATCH"));
		// Constructors.
		assertTrue(describe(help("from java.util import ArrayList\n" +
			"ArrayList(")).contains("java.util.Collection"));
		// None outside calls.
		assertTrue(help("x = 1").isEmpty());
	}

	private static SignatureHelp help(final String code) {
		final Context ctx = new Context(ScriptService.class,
			CodeCompletionService.class);
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			return ctx.service(CodeCompletionService.class).getCompleter(jython)
				.signatureHelp(new CompletionRequest(code, jython, null));
		}
		finally {
			ctx.dispose();
		}
	}

	/** Each signature's first parameter type and fit, in order. */
	private static String describe(final SignatureHelp help) {
		return help.signatures().stream().map(s -> (s.callable().parameters()
			.isEmpty() ? "()" : s.callable().parameters().get(0).type()) + ":" + s
				.fit()).collect(Collectors.toList()).toString();
	}

	// -- Helper methods --

	private static List<String> complete(final CodeCompletionService service,
		final ScriptLanguage language, final ScriptEngine engine,
		final String code)
	{
		return service.complete(new CompletionRequest(code, language, engine))
			.completions().stream().map(Completion::insertionText).collect(Collectors
				.toList());
	}

	private static List<String> complete(final String code) {
		return completions(code).stream().map(Completion::insertionText).collect(
			Collectors.toList());
	}

	private static long constructorCount(final String code) {
		return completions(code).stream().filter(c -> c.kind() ==
			Completion.Kind.METHOD).count();
	}

	private static List<Completion> completions(final String code) {
		final Context ctx = new Context(ScriptService.class,
			CodeCompletionService.class);
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			return ctx.service(CodeCompletionService.class).complete(
				new CompletionRequest(code, jython, null)).completions();
		}
		finally {
			ctx.dispose();
		}
	}
}
