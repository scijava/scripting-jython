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

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.scijava.Priority;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerPlugin;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.TransformingLanguageServer;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptLanguage;

/**
 * Offers a Python language server for Jython scripts, if one is offered for
 * Python (e.g. jedi-language-server, by the {@code appose-python} scripting
 * plugin): for Python modules, and for Java classes, from Python stubs of the
 * Java packages the script imports and its parameters' types (see
 * {@link JavaStubs}, written in the background). Its completions follow
 * Jython's own.
 * <p>
 * The server analyzes the script as CPython (Jython's standard library is
 * Python 2's, but mostly the same): so it is asked for completions only, not
 * for problems.
 * </p>
 *
 * @author Gabriel Selzer
 */
@Plugin(type = LanguageServerPlugin.class, name = "Jython via Python",
	priority = Priority.LOW)
public class JythonPythonServerPlugin implements LanguageServerPlugin {

	@Parameter
	private LanguageServerService servers;

	@Parameter(required = false)
	private CodeCompletionService completion;

	private JavaStubs stubs;

	@Override
	public boolean supports(final ScriptLanguage language) {
		return JythonServerPlugin.isJython(language) && servers.launch("python",
			Environment.NONE) != null;
	}

	@Override
	public Environment environment(final TextDocumentItem document) {
		// NB: Writes the stubs the script needs, in the background.
		final JavaStubs s = stubs();
		final Set<String> packages = new TreeSet<>(JavaStubs.imports(document
			.getText()));
		final ScriptDocument doc = ScriptDocument.of(document.getText(),
			parameters(document.getText()), JythonLanguageServer.DIALECT, null);
		packages.addAll(JythonDialect.packages(doc.parameters().values().stream()
			.filter(t -> t != null).collect(Collectors.toList())));
		s.ensure(packages);
		// NB: The server's own Python (CPython).
		return new Environment(null, Collections.singletonList(s.dir()
			.getAbsolutePath()), null, null);
	}

	@Override
	public LanguageServer create(final Environment environment) {
		final LanguageServer python = servers.launch("python", environment);
		return new TransformingLanguageServer(python, text -> ScriptDocument.of(
			text, parameters(text), JythonLanguageServer.DIALECT, environment
				.toContext()))
		{

			@Override
			public void connect(final LanguageClient client) {
				super.connect(new NoProblems(client));
			}
		};
	}

	/** Sets the stubs, e.g. to write them elsewhere in tests. */
	synchronized void setStubs(final JavaStubs stubs) {
		this.stubs = stubs;
	}

	/** Gets the stubs of Java packages, creating them on first use. */
	synchronized JavaStubs stubs() {
		if (stubs != null) return stubs;
		final String dir = System.getProperty("scijava.jython.stubs");
		stubs = new JavaStubs(dir != null ? new File(dir) : new File(System
			.getProperty("user.home"), ".cache" + File.separator + "scijava" +
				File.separator + "jython-stubs"), Thread.currentThread()
					.getContextClassLoader());
		return stubs;
	}

	private Map<String, Class<?>> parameters(final String text) {
		return completion == null ? Collections.emptyMap() : completion
			.scriptParameters(text);
	}

	/**
	 * Passes a client the server's messages, but no problems: the server reads
	 * Jython's Python 2 as Python 3. (An empty list still says the server is
	 * done looking.)
	 */
	private static final class NoProblems implements LanguageClient {

		private final LanguageClient client;

		private NoProblems(final LanguageClient client) {
			this.client = client;
		}

		@Override
		public void publishDiagnostics(final PublishDiagnosticsParams p) {
			client.publishDiagnostics(new PublishDiagnosticsParams(p.getUri(),
				Collections.emptyList(), p.getVersion()));
		}

		@Override
		public void telemetryEvent(final Object object) {
			client.telemetryEvent(object);
		}

		@Override
		public void showMessage(final MessageParams m) {
			client.showMessage(m);
		}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(
			final ShowMessageRequestParams r)
		{
			return client.showMessageRequest(r);
		}

		@Override
		public void logMessage(final MessageParams m) {
			client.logMessage(m);
		}
	}
}
