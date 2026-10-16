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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonDialect}.
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
	public void testDeclarations() {
		final JythonDialect dialect = new JythonDialect();
		assertEquals("img = net.imglib2.img.Img()", dialect.declare("img",
			"net.imglib2.img.Img"));
		// As Jython converts them: e.g. strings and numbers to Python's.
		assertEquals("str", dialect.runtimeType(String.class));
		assertEquals("int", dialect.runtimeType(int.class));
		assertEquals("int", dialect.runtimeType(Long.class));
		assertEquals("float", dialect.runtimeType(double.class));
		assertEquals("bool", dialect.runtimeType(boolean.class));
		assertEquals("array.array", dialect.runtimeType(double[].class));
		assertEquals("java.math.BigDecimal", dialect.runtimeType(
			java.math.BigDecimal.class));
		assertEquals("n = int()", dialect.declare("n", "int"));
		assertEquals("e = java.util.Map.Entry()", dialect.declare("e",
			"java.util.Map$Entry"));
		assertEquals(Arrays.asList("import array", "import java.util",
			"import net.imglib2.img"), dialect.imports(Arrays.asList(
				"net.imglib2.img.Img", "str", "java.util.Map$Entry", "array.array")));
		assertEquals("net", dialect.imported("import net.imglib2.img"));
	}
}
