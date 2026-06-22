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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.scijava.Context;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;
import org.scijava.script.complete.CodeCompletionService;
import org.scijava.script.complete.Completion;
import org.scijava.script.complete.CompletionRequest;
import org.scijava.script.complete.CompletionResult;

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
}
