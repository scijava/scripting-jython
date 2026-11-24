/*-
 * #%L
 * JSR-223-compliant Jython scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2026 SciJava developers.
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
package org.scijava.plugins.scripting.jython.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;

import org.eclipse.lsp4j.CompletionItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.lsp.LanguageServerService;
import org.python.jsr223.PyScriptEngineFactory;
import org.scijava.lsp.ScriptDialect.Header;
import org.scijava.lsp.ScriptSession;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonDialect}: Jython scripts, as plain Jython for
 * scijava-lsp-jython's server (which its own tests test).
 *
 * @author Gabriel Selzer
 */
public class JythonDialectTest {

	private Context context;
	private ScriptLanguage jython;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, LanguageServerService.class);
		jython = context.service(ScriptService.class).getLanguageByName("Jython");
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testSupports() {
		// JythonDialect supports JythonScriptLanguage
		final JythonDialect dialect = new JythonDialect();
		assertTrue(dialect.supports(jython));
		// JythonLanguageServer supports JythonDialect's output
		assertTrue(context.service(LanguageServerService.class).supports(dialect.outputLanguage()));
	}

	@Test
	public void testParameterType() {
		// Ensure Java primitives are converted to Python primitives
		assertEquals("str", JythonDialect.parameterType(String.class));
		assertEquals("int", JythonDialect.parameterType(int.class));
		assertEquals("int", JythonDialect.parameterType(Long.class));
		assertEquals("float", JythonDialect.parameterType(double.class));
		assertEquals("bool", JythonDialect.parameterType(boolean.class));
		assertEquals("array.array", JythonDialect.parameterType(
			double[].class));
		// Ensure Java classes stay the same
		assertEquals("java.math.BigDecimal", JythonDialect.parameterType(
			java.math.BigDecimal.class));
	}

	@Test
	public void testScriptParameters() {
		final String scijavaScriptPrefix = //
			"#@ String (label=\"Please enter your name\") name\n" + //
			"#@output String greeting\n" + //
			"#@ java.util.Map$Entry entry\n" + //
			"#@ double[] values\n" + //
			"#@ NoSuchType mystery\n";
		// Ensure script parameter names can be autocompleted
		assertTrue(complete(scijavaScriptPrefix + "x = na").contains("name"));
		assertTrue(complete(scijavaScriptPrefix + "myst").contains("mystery"));
		// Primitive script parameters have the members of their JYTHON types...
		final List<String> nameMembers = complete(scijavaScriptPrefix + "name.upp");
		assertTrue("expected upper among " + nameMembers, nameMembers.contains(
			"upper"));
		// ...but NOT the members of their JAVA types
		assertTrue(complete(scijavaScriptPrefix + "name.toUpp").isEmpty());
		// Outputs are known too, even before the script assigns them.
		assertTrue(complete(scijavaScriptPrefix + "greeting.st").contains("strip"));
		// Other Java objects are instances of their classes, nested ones included.
		assertTrue(complete(scijavaScriptPrefix + "entry.getK").contains("getKey"));
		// Java arrays are Python arrays.
		assertTrue(complete(scijavaScriptPrefix + "values.app").contains("append"));
		// Modules imported only to declare those are not completed.
		// TODO: Also check "j" (for java), once scijava-lsp ignores #@ lines
		// when deciding whether the script mentions a name.
		assertFalse(complete(scijavaScriptPrefix + "arr").contains("array"));	}


	/**
	 * Tests that bindings in an {@link org.scijava.lsp.Environment} make it into the
	 * {@link Header}.
	 */
	@Test
	public void testInterpreterBindings() throws Exception {
		// Derive a ScriptContext with some bindings
		final ScriptEngine engine = new PyScriptEngineFactory().getScriptEngine();
		engine.put("injected", new java.util.ArrayList<String>());
		engine.eval("from java.util import Collections\n" + //
			"import os.path as osp\n" + //
			"def helper(): pass\n" + //
			"pairs = {1: 2}\n");
		final ScriptContext live = engine.getContext();
		// Pass that ScriptContext to completion and ensure it can be used.
		// A Java object offers its instance members.
		assertTrue(complete("injected.si", live).contains("size"));
		// An imported Java class offers its static members.
		assertTrue(complete("Collections.emptyL", live).contains("emptyList"));
		// A module bound under another name is the module itself.
		assertTrue(complete("osp.joi", live).contains("join"));
		// A function is known by name.
		assertTrue(complete("x = hel", live).contains("helper"));
		// A Python value is an instance of its builtin type.
		assertTrue(complete("pairs.ke", live).contains("keys"));
	}

	// -- Helper methods --

	private List<String> complete(final String code) {
		return complete(code, null);
	}

	/** Completions at the end of the code, typed into the given interpreter. */
	private List<String> complete(final String code, final ScriptContext live) {
		final LanguageServerService servers = context.service(
			LanguageServerService.class);
		try (ScriptSession session = live == null ? new ScriptSession(servers,
			jython) : new ScriptSession(servers, jython, live))
		{
			return session.completion(code, code.length()).get(30, TimeUnit.SECONDS)
				.getItems().stream().map(CompletionItem::getLabel).collect(Collectors
					.toList());
		}
		catch (final Exception exc) {
			throw new RuntimeException(exc);
		}
	}
}
