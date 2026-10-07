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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerPlugin;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.plugin.PluginService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonPythonServerPlugin}: a Python language server's
 * environment for Jython scripts (Java stubs); and {@link JythonDialect}.
 *
 * @author Gabriel Selzer
 */
public class JythonServerCompletionTest {

	private Context context;
	private ScriptLanguage jython;
	private JythonPythonServerPlugin plugin;
	private File stubsDir;

	@Before
	public void setUp() throws Exception {
		context = new Context(ScriptService.class, CodeCompletionService.class,
			LanguageServerService.class);
		jython = context.service(ScriptService.class).getLanguageByName("Jython");
		plugin = context.service(PluginService.class).createInstancesOfType(
			LanguageServerPlugin.class).stream().filter(
				p -> p instanceof JythonPythonServerPlugin).map(
					p -> (JythonPythonServerPlugin) p).findFirst().get();
		stubsDir = Files.createTempDirectory("jython-stubs").toFile();
		plugin.setStubs(new JavaStubs(stubsDir, getClass().getClassLoader(),
			pkg -> Collections.emptyList()));
	}

	@After
	public void tearDown() {
		context.dispose();
		stubsDir.delete();
	}

	@Test
	public void testOnlyWithAPythonServer() {
		// NB: No plugin offers a Python server here (scripting-appose-python
		// does): Jython is served by its own server alone.
		assertFalse(plugin.supports(jython));
	}

	@Test
	public void testEnvironment() {
		// The server's own Python, searching the Java stubs.
		final Environment env = plugin.environment(new TextDocumentItem(
			"untitled:/a.py", "python", 1, "from java.util import ArrayList\n"));
		assertEquals(null, env.interpreter());
		assertEquals(Collections.singletonList(stubsDir.getAbsolutePath()), env
			.searchPaths());
	}

	@Test
	public void testDeclarations() {
		final JythonDialect dialect = new JythonDialect();
		assertEquals("img: net.imglib2.img.Img", dialect.declare("img",
			"net.imglib2.img.Img"));
		assertEquals("n: int", dialect.declare("n", dialect.runtimeType(
			int.class)));
		assertEquals("e: java.util.Map.Entry", dialect.declare("e",
			"java.util.Map$Entry"));
		assertEquals(Arrays.asList("import java.util", "import net.imglib2.img"),
			dialect.imports(Arrays.asList("net.imglib2.img.Img", "java.lang.String",
				"java.util.Map$Entry")));
		assertTrue(JythonServerPlugin.isJython(jython));
	}
}
