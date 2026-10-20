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
package org.scijava.plugins.scripting.jython.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptDialect.Header;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonDialect}: Jython scripts, as plain Jython for
 * scijava-code-lsp-jython's server (which its own tests test).
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
	public void testScriptsAreServed() {
		// Jython scripts become plain Jython, which scijava-code-lsp-jython
		// serves.
		final JythonDialect dialect = new JythonDialect();
		assertTrue(dialect.supports(jython));
		assertEquals("jython", dialect.languageId());
		assertTrue(context.service(LanguageServerService.class).supports(jython));
	}

	@Test
	public void testParameterType() {
		// As Jython converts them: e.g. strings and numbers to Python's.
		assertEquals("str", JythonDialect.parameterType(String.class));
		assertEquals("int", JythonDialect.parameterType(int.class));
		assertEquals("int", JythonDialect.parameterType(Long.class));
		assertEquals("float", JythonDialect.parameterType(double.class));
		assertEquals("bool", JythonDialect.parameterType(boolean.class));
		assertEquals("array.array", JythonDialect.parameterType(
			double[].class));
		// Or else as the Java objects themselves.
		assertEquals("java.math.BigDecimal", JythonDialect.parameterType(
			java.math.BigDecimal.class));
	}

	@Test
	public void testHeader() {
		// Each parameter is declared as an instance of its type, whose package
		// is imported.
		final Map<String, Class<?>> parameters = new LinkedHashMap<>();
		parameters.put("d", java.math.BigDecimal.class);
		parameters.put("n", int.class);
		parameters.put("e", Map.Entry.class);
		parameters.put("a", double[].class);
		final Header header = new JythonDialect().header(parameters);
		assertEquals("import array; import java.math; import java.util; " +
			"d = java.math.BigDecimal(); n = int(); " +
			"e = java.util.Map.Entry(); a = array.array()", header.code());
		// The names the imports bind ("import a.b" binds a) are the header's
		// alone.
		assertEquals(Arrays.asList("array", "java"), Arrays.asList(header
			.hiddenNames().toArray()));
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
		// As the Python values Jython converts them to: e.g. strings are str,
		// whose members are Python's, not java.lang.String's.
		final List<String> nameMembers = complete(header + "name.upp");
		assertTrue("expected name.upper among " + nameMembers, nameMembers
			.contains("name.upper"));
		assertTrue(complete(header + "name.toUpp").isEmpty());
		// Outputs are known too, even before the script assigns them.
		assertTrue(complete(header + "greeting.st").contains("greeting.strip"));
		// Numbers are int, not java.lang.Integer.
		assertFalse(complete(header + "count.intV").contains("count.intValue"));
		// Packages imported only to declare those are not completed.
		assertFalse(complete(header + "j").contains("java.lang"));
	}


	@Test
	public void testAssignmentShadowsScriptParameter() {
		final List<String> texts = complete(
			"#@ String name\nfrom java.util import ArrayList\nname = ArrayList()\nname.ensureCap");
		assertTrue("expected ArrayList member among " + texts, texts.contains(
			"name.ensureCapacity"));
	}


	// -- Helper methods --

	private List<String> complete(final String code) {
		try (ScriptSession session = new ScriptSession(context.service(
			LanguageServerService.class), jython))
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
