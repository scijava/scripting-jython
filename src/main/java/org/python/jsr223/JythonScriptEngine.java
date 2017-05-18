/*-
 * #%L
 * JSR-223-compliant Jython scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2017 Board of Regents of the University of
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

package org.python.jsr223;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;

import org.python.core.PyException;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;
import org.scijava.util.ClassUtils;

/**
 * A {@link PyScriptEngine} whose {@link #eval} methods return the result of the
 * last line of code, like other scripting languages do. For technical
 * discussion, see
 * <a href="https://github.com/jythontools/jython/issues/72">jythontools/jython#72</a>.
 * <p>
 * NB: This class needs to reside in package {@code org.python.jsr223} to access
 * the package-private constructor of the superclass.
 * </p>
 *
 * @author Curtis Rueden
 */
public class JythonScriptEngine extends PyScriptEngine {

	private final PythonInterpreter interp;

	public JythonScriptEngine(final ScriptEngineFactory factory) {
		super(factory);

		// HACK: extract the private PythonInterpreter from the superclass.
		final Field f = ClassUtils.getField(PyScriptEngine.class, "interp");
		interp = (PythonInterpreter) ClassUtils.getValue(f, this);
	}

	// -- PyScriptEngine methods --

	@Override
	public Object eval(final String script, final ScriptContext ctx)
		throws ScriptException
	{
//		if (true) return super.eval(script, ctx);
		try {
			interp.setIn(ctx.getReader());
			interp.setOut(ctx.getWriter());
			interp.setErr(ctx.getErrorWriter());
			interp.setLocals(new PyScriptEngineScope(this, ctx));
			return doEval(script).__tojava__(Object.class);
		}
		catch (final PyException pye) {
			throw scriptException(pye);
		}
	}

	@Override
	public Object eval(final Reader reader, final ScriptContext ctx)
		throws ScriptException
	{
		try {
			return eval(readerToString(reader), ctx);
		}
		catch (final IOException exc) {
			throw new ScriptException(exc);
		}
	}

	// -- Helper methods --

	private String readerToString(final Reader reader) throws IOException {
		final char[] buf = new char[64 * 1024];
		final StringBuilder sb = new StringBuilder();
		int r;
		while ((r = reader.read(buf, 0, buf.length)) != -1) {
			sb.append(buf, 0, r);
		}
		reader.close();
		return sb.toString();
	}

	private PyObject doEval(final String script) {
		final int nl = script.lastIndexOf('\n');
		if (nl < 0) return interp.eval(script);
		interp.exec(script.substring(0, nl + 1));
		return interp.eval(script.substring(nl + 1));
	}

	private Method scriptExceptionMethod;

	private ScriptException scriptException(final PyException pye) {
		// HACK: Call the private PyScriptEngine.scriptEngine(PyException) method.
		// We do it because the method is very complicated. No thread safety.
		try {
			if (scriptExceptionMethod == null) {
				scriptExceptionMethod = PyScriptEngine.class.getDeclaredMethod(
					"scriptException", PyException.class);
				scriptExceptionMethod.setAccessible(true);
			}
			return (ScriptException) scriptExceptionMethod.invoke(null, pye);
		}
		catch (final NoSuchMethodException | SecurityException
				| IllegalAccessException | IllegalArgumentException
				| InvocationTargetException exc)
		{
			return new ScriptException(exc);
		}
	}
}
