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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.SignatureHelp;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.jvm.ClassIndex;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonLanguageServer}, through the
 * {@link LanguageServerService}, as editors use it.
 *
 * @author Curtis Rueden
 */
public class JythonCompletionTest {

	@Test
	public void testMemberCompletionViaService() {
		final Context ctx = new Context(ScriptService.class,
			LanguageServerService.class);
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			assertNotNull("Jython language not found", jython);

			// A Jython server must be offered for the Jython language.
			final LanguageServerService servers = ctx.service(
				LanguageServerService.class);
			assertTrue(servers.supports(jython));

			// Static analysis: 's' is a String, so 's.' completes to String members.
			final List<String> texts = labels(items(servers.session(jython),
				"s = \"hello\"\ns."));
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
		final List<CompletionItem> items = completions(
			"from java.util import ArrayList\nArrayList");
		final List<String> texts = labels(items);
		assertTrue("expected ArrayList among " + texts, texts.contains(
			"ArrayList"));
		// Once as a name; otherwise as its constructors.
		assertEquals("expected one plain ArrayList among " + texts, 1, items
			.stream().filter(c -> c.getLabel().equals("ArrayList") && c
				.getLabelDetails() == null).count());
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
	public void testCallablesAreSnippets() {
		final CompletionItem charAt = completions("s = 'x'\ns.charA").stream()
			.filter(c -> c.getLabel().equals("s.charAt")).findFirst().orElse(null);
		assertNotNull(charAt);
		assertEquals("(int arg0)", charAt.getLabelDetails().getDetail().replaceAll(
			" \\w+\\)$", " arg0)"));
		assertTrue(charAt.getTextEdit().getLeft().getNewText().startsWith(
			"s.charAt(${1:"));
	}

	@Test
	public void testArgumentChoices() {
		// In a call's arguments: the variables of the parameter's type first.
		final List<CompletionItem> items = completions(
			"from java.lang import Math\nx = 1.5\ny = 'a'\nMath.abs(");
		assertTrue(items.toString(), !items.isEmpty());
		assertEquals("x", items.get(0).getLabel());
		assertEquals(Boolean.TRUE, items.get(0).getPreselect());
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
			final LanguageServerService servers = ctx.service(
				LanguageServerService.class);
			final Supplier<ScriptSession> live = () -> servers.session(jython, engine
				.getContext());

			assertTrue(labels(items(live.get(), "m.putIfAb")).contains(
				"m.putIfAbsent"));
			assertTrue(labels(items(live.get(), "injected.si")).contains(
				"injected.size"));
			assertTrue(labels(items(live.get(), "x = hel")).contains("helper"));
			// An imported Java class offers its static members.
			assertTrue(labels(items(live.get(), "Collections.emptyL")).contains(
				"Collections.emptyList"));
			// Python internals are not offered.
			assertFalse(labels(items(live.get(), "x = __")).contains("__name__"));
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
		assertEquals(Integer.valueOf(1), max.getActiveParameter());
		assertEquals("[double, float, int, long]", describe(max));
		// A Python int fits the integral ones, and converts to the others.
		assertEquals("[int, long, double, float]", describe(help(
			"from java.lang import Math\nMath.max(1, 2")));
		// Arguments' types come from the analysis, e.g. of variables.
		final SignatureHelp indexOf = help("s = 'abc'\nc = 'b'\n" +
			"s.indexOf(c, ");
		assertTrue(describe(indexOf), describe(indexOf).startsWith(
			"[java.lang.String"));
		// Constructors.
		assertTrue(describe(help("from java.util import ArrayList\n" +
			"ArrayList(")).contains("java.util.Collection"));
		// None outside calls.
		assertNull(help("x = 1"));
	}

	// -- Helper methods --

	private static SignatureHelp help(final String code) {
		final Context ctx = new Context(ScriptService.class,
			LanguageServerService.class);
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			try (ScriptSession session = ctx.service(LanguageServerService.class)
				.session(jython))
			{
				return session.signatureHelp(code, code.length()).get(30,
					TimeUnit.SECONDS);
			}
		}
		catch (final Exception exc) {
			throw new RuntimeException(exc);
		}
		finally {
			ctx.dispose();
		}
	}

	/** Each signature's first parameter type, in order. */
	private static String describe(final SignatureHelp help) {
		return help.getSignatures().stream().map(s -> {
			final String first = s.getParameters().isEmpty() ? "()" : s
				.getParameters().get(0).getLabel().getLeft();
			final String type = first.contains(" ") ? first.substring(0, first
				.lastIndexOf(' ')) : first;
			return type;
		}).collect(Collectors.toList()).toString();
	}

	private static List<String> complete(final String code) {
		return labels(completions(code));
	}

	private static List<String> labels(final List<CompletionItem> items) {
		return items.stream().map(CompletionItem::getLabel).collect(Collectors
			.toList());
	}

	private static List<CompletionItem> completions(final String code) {
		final Context ctx = new Context(ScriptService.class,
			LanguageServerService.class);
		try {
			final ScriptLanguage jython = ctx.service(ScriptService.class)
				.getLanguageByName("Jython");
			return items(ctx.service(LanguageServerService.class).session(jython),
				code);
		}
		finally {
			ctx.dispose();
		}
	}

	private static List<CompletionItem> items(final ScriptSession session,
		final String code)
	{
		try (ScriptSession s = session) {
			final List<CompletionItem> items = new java.util.ArrayList<>(s.completion(
				code, code.length()).get(30, TimeUnit.SECONDS).getItems());
			items.sort((a, b) -> a.getSortText().compareTo(b.getSortText()));
			return items;
		}
		catch (final Exception exc) {
			throw new RuntimeException(exc);
		}
	}
}
