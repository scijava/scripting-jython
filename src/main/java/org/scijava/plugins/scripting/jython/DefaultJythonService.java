/*
 * #%L
 * JSR-223-compliant Jython scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2016 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
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

import javax.script.ScriptEngine;

import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.thread.ThreadService;

/**
 * Default {@link JythonService} implementation. Maintains a
 * {@link JythonReferenceCleaner} instance.
 *
 * @author Mark Hiner hinerm at gmail.com
 */
@Plugin(type = Service.class)
public class DefaultJythonService extends AbstractService implements
	JythonService
{

	// -- Fields --

	private JythonReferenceCleaner cleaner;

	// -- Parameters --

	@Parameter
	private ThreadService threadService;

	@Parameter
	private LogService logService;

	// -- JythonService methods --

	@Override
	public ScriptEngine getScriptEngine() {
		// TODO: Consider adapting the wrapped ScriptEngineFactory's
		// ScriptEngine.
		final JythonScriptEngine engine = new JythonScriptEngine();
		cleaner.queueCleanup(engine, threadService, logService);
		return engine;
	}

	// -- Service methods --

	@Override
	public void initialize() {
		cleaner = new JythonReferenceCleaner();
	}

	// -- Disposable methods --

	@Override
	public void dispose() {
		cleaner.shutDown();
	}
}
