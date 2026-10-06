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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.lsp.LspClient;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link JythonCodeCompleter}'s use of a Python language server, with a
 * fake one.
 *
 * @author Gabriel Selzer
 */
public class JythonServerCompletionTest {

	private Context context;
	private ScriptLanguage jython;
	private CodeCompletionService service;
	private JythonCodeCompleter completer;
	private File stubsDir;
	private final List<ScriptDocument> asked = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		context = new Context(ScriptService.class, CodeCompletionService.class);
		jython = context.service(ScriptService.class).getLanguageByName("Jython");
		service = context.service(CodeCompletionService.class);
		completer = (JythonCodeCompleter) service.getCompleterPlugin(jython);
		stubsDir = Files.createTempDirectory("jython-stubs").toFile();
		completer.setStubs(new JavaStubs(stubsDir, getClass().getClassLoader(),
			pkg -> Collections.emptyList()));
	}

	@After
	public void tearDown() {
		context.dispose();
		stubsDir.delete();
	}

	@Test
	public void testServerCompletionsMerged() {
		final Completion dumps = Completion.builder("dumps").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("obj", null))).build();
		fake(doc -> Arrays.asList(dumps, Completion.of("dump"), Completion.of(
			"loads")));
		final List<Completion> completions = completions("import json\njson.du");
		final List<String> texts = completions.stream().map(
			Completion::insertionText).collect(Collectors.toList());
		// With what was typed before the word; filtered by the word.
		assertTrue(texts.toString(), texts.contains("json.dumps"));
		assertTrue(texts.contains("json.dump"));
		assertFalse(texts.contains("json.loads"));
		final Completion merged = completions.stream().filter(c -> c
			.insertionText().equals("json.dumps")).findFirst().get();
		assertEquals(1, merged.parameters().size());

		// The server sees the script as Python, with the stubs to search.
		final ScriptDocument doc = asked.get(asked.size() - 1);
		assertEquals(Collections.singletonList(stubsDir.getAbsolutePath()), doc
			.environment().searchPaths());
	}

	@Test
	public void testJythonFirst() {
		// Jython knows s is a String: its own completion comes once.
		fake(doc -> Collections.singletonList(Completion.of("length")));
		final String code = "#@ String s\ns.len";
		final List<String> texts = completions(code).stream().map(
			Completion::insertionText).collect(Collectors.toList());
		assertEquals(texts.toString(), 1, texts.stream().filter(t -> t.equals(
			"s.length")).count());
		// Parameters declared for the server, as Python types.
		assertTrue(asked.get(0).text().startsWith("s: str\n"));
	}

	@Test
	public void testImportsAreNames() {
		final Completion arrayList = Completion.builder("ArrayList").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("capacity", "int"))).build();
		fake(doc -> Collections.singletonList(arrayList));
		final Completion imported = completions("from java.util import ArrayLi")
			.stream().filter(c -> c.insertionText().endsWith("ArrayList")).findFirst()
			.get();
		assertFalse(imported.parameters().size() > 0 && imported.kind() ==
			Completion.Kind.METHOD);
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
	}

	// -- Helper methods --

	private void fake(final Function<ScriptDocument, List<Completion>> answers) {
		completer.setLspClient(new LspClient(null) {

			@Override
			public CompletableFuture<List<Completion>> complete(
				final ScriptDocument doc, final String uri, final int offset,
				final int resolve)
			{
				asked.add(doc);
				return CompletableFuture.completedFuture(answers.apply(doc));
			}
		});
	}

	private List<Completion> completions(final String code) {
		return service.complete(new CompletionRequest(code, jython, null))
			.completions();
	}
}
