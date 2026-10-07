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

import org.eclipse.lsp4j.services.LanguageServer;
import org.scijava.Priority;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerPlugin;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptLanguage;

/**
 * Offers Jython's in-process language server (see
 * {@link JythonLanguageServer}).
 *
 * @author Gabriel Selzer
 */
@Plugin(type = LanguageServerPlugin.class, name = "Jython",
	priority = Priority.HIGH)
public class JythonServerPlugin implements LanguageServerPlugin {

	@Parameter(required = false)
	private CodeCompletionService completion;

	@Override
	public boolean supports(final ScriptLanguage language) {
		return isJython(language);
	}

	@Override
	public LanguageServer create(final Environment environment) {
		return new JythonLanguageServer(completion, environment);
	}

	/** Matches "Jython" and "Python (Jython)", without claiming CPython. */
	static boolean isJython(final ScriptLanguage language) {
		if (language == null) return false;
		final String name = language.getLanguageName();
		return name != null && name.toLowerCase().contains("jython");
	}
}
