/*
 * #%L
 * JSR-223-compliant Jython scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2015 Board of Regents of the University of
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

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.script.ScriptEngine;

import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.AdaptedScriptLanguage;
import org.scijava.script.ScriptLanguage;
import org.scijava.thread.ThreadService;

/**
 * An adapter of the Jython interpreter to the SciJava scripting interface.
 * 
 * @author Johannes Schindelin
 * @author Mark Hiner <hinerm@gmail.com>
 * @see ScriptEngine
 */
@Plugin(type = ScriptLanguage.class, name = "Python")
public class JythonScriptLanguage extends AdaptedScriptLanguage {

	// List of PhantomReferences and corresponding ReferenceQueue to
	// facilitate proper PhantomReference use.
	// See http://resources.ej-technologies.com/jprofiler/help/doc/index.html

	private final LinkedList<JythonEnginePhantomReference> phantomReferences =
		new LinkedList<JythonEnginePhantomReference>();
	private final ReferenceQueue<JythonScriptEngine> queue =
		new ReferenceQueue<JythonScriptEngine>();

	@Parameter
	private ThreadService threadService;

	public JythonScriptLanguage() {
		super("jython");
	}

	@Override
	public ScriptEngine getScriptEngine() {
		// TODO: Consider adapting the wrapped ScriptEngineFactory's ScriptEngine.
		final JythonScriptEngine engine = new JythonScriptEngine();

		synchronized (phantomReferences) {
			// NB: This phantom reference is used to clean up any local variables
			// created by evaluation of scripts via this ScriptEngine. We need
			// to use PhantomReferences because the "scope" of a script extends
			// beyond its eval method - a consumer may still want to inspect
			// the state of variables after evaluation.
			// By using PhantomReferences we are saying that the scope of 
			// script evaluation equals the lifetime of the ScriptEngine.
			phantomReferences.add(new JythonEnginePhantomReference(engine, queue));

			// NB: The use of PhantomReferences requires a paired polling thread.
			// We poll instead of blocking for input to avoid leaving lingering
			// threads that would need to be interrupted - instead only starting
			// threads running when there is actually a PhantomReference that
			// will eventually be enqueued.
			// Here we check if there is already a polling thread in operation -
			// if not, we start a new thread.
			if (phantomReferences.size() == 1) {
				threadService.run(new Runnable() {

					@Override
					public void run() {
						boolean done = false;

						// poll the queue
						while (!done) {
							try {
								Thread.sleep(100);

								synchronized (phantomReferences) {
									// poll the queue
									JythonEnginePhantomReference ref =
										(JythonEnginePhantomReference) queue.poll();

									// if we have a ref, clean it up
									if (ref != null) {
										ref.cleanup();
										phantomReferences.remove(ref);
									}

									// Once we're done with our known phantom refs
									// we can let this thread shut down
									done = phantomReferences.size() == 0;
								}

							}
							catch (final Exception ex) {
								// log exception, continue
							}
						}
					}
				});

			}
		}
		return engine;
	}

	@Override
	public Object decode(final Object object) {
		if (object instanceof PyNone) return null;
		if (object instanceof PyObject) {
			// Unwrap Python objects when they wrap Java ones.
			final PyObject pyObj = (PyObject) object;
			final Class<?> javaType = pyObj.getType().getProxyType();
			if (javaType != null) return pyObj.__tojava__(javaType);
		}
		if (object instanceof PyString) {
			return ((PyString) object).getString();
		}
		return object;
	}

	/**
	 * Helper class to clean up {@link PythonInterpreter} local variables when a
	 * parent {@link JythonScriptEngine} leaves scope.
	 */
	private static class JythonEnginePhantomReference extends
		PhantomReference<JythonScriptEngine>
	{

		public PythonInterpreter interpreter;

		public JythonEnginePhantomReference(JythonScriptEngine engine,
			ReferenceQueue<JythonScriptEngine> queue)
		{
			super(engine, queue);
			interpreter = engine.interpreter;
		}

		public void cleanup() {
			final List<String> scriptLocals = new ArrayList<String>();
			PythonInterpreter interp = interpreter;
			if (interp == null) return;

			// NB: This method for cleaning up local variables was taken from:
			// http://python.6.x6.nabble.com/Cleaning-up-PythonInterpreter-object-td1777184.html
			// Because Python is an interpreted language, when a Python script creates new
			// variables they stick around in a static org.python.core.PySystemState  variable
			// (defaultSystemState) the org.python.core.Py class.
			// Thus an implicit static state is created by script evaluation, so we must manually
			// clean up local variables known to the interpreter when the scope of an executed
			// script is over.
			// See original bug report for the memory leak that prompted this solution:
			// http://fiji.sc/bugzilla/show_bug.cgi?id=1203
			final PyObject locals = interp.getLocals();
			for (final PyObject item : locals.__iter__().asIterable()) {
				final String localVar = item.toString();
				// Ignore __name__ and __doc__ variables, which are special and known not
				// to be local variables created by evaluation.
				if (!localVar.contains("__name__") && !localVar.contains("__doc__")) {
					// Build list of variables to clean
					scriptLocals.add(item.toString());
				}
			}
			// Null out local variables
			for (final String string : scriptLocals) {
				interp.set(string, null);
			}
		}
	}
}
