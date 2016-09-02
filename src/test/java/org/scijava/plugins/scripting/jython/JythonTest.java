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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.junit.Test;
import org.scijava.Context;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;

/**
 * Jython unit tests.
 * 
 * @author Johannes Schindelin
 */
public class JythonTest {

	@Test
	public void testBasic() throws InterruptedException, ExecutionException,
		IOException, ScriptException
	{
		final Context context = new Context();
		final ScriptService scriptService = context.getService(ScriptService.class);
		final String script = "1 + 2";
		final ScriptModule m = scriptService.run("add.py", script, true).get();
		final Object result = m.getReturnValue();
		assertSame(Integer.class, result.getClass());
		assertEquals(3, result);
	}

	@Test
	public void testLocals() throws ScriptException {
		final Context context = new Context();
		final ScriptService scriptService = context.getService(ScriptService.class);

		final ScriptLanguage language = scriptService.getLanguageByExtension("py");
		final ScriptEngine engine = language.getScriptEngine();
		assertEquals(JythonScriptEngine.class, engine.getClass());
		engine.put("hello", 17);
		assertEquals("17", engine.eval("hello").toString());
		assertEquals("17", engine.get("hello").toString());

		final Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
		bindings.clear();
		assertNull(engine.get("hello"));
	}

	@Test
	public void testParameters() throws InterruptedException, ExecutionException,
		IOException, ScriptException
	{
		final Context context = new Context();
		final ScriptService scriptService = context.getService(ScriptService.class);

		final String script = "" + //
			"# @ScriptService ss\n" + //
			"# @OUTPUT String language\n" + //
			"language = ss.getLanguageByName('jython').getLanguageName()\n";
		final ScriptModule m = scriptService.run("hello.py", script, true).get();

		final Object actual = m.getOutput("language");
		final String expected =
			scriptService.getLanguageByName("jython").getLanguageName();
		assertEquals(expected, actual);
	}

	/**
	 * Tests that variables assigned a primitive long value have the expected
	 * type.
	 * <p>
	 * There is a crazy bug in {@link org.python.jsr223.PyScriptEngine}, which
	 * results in variables assigned a long primitive to somehow end up as (or
	 * appearing to end up as) {@link java.math.BigInteger} instances instead. See
	 * <a href=
	 * "http://sourceforge.net/p/jython/mailman/jython-users/thread/54370FE9.5010603%40farowl.co.uk/"
	 * >this thread on the jython-users mailing list</a> for discussion.
	 * </p>
	 * <p>
	 * This test ensures that that specific problem gets flagged if it occurs. As
	 * long as we keep using our own Jython {@code ScriptEngine} implementation
	 * (i.e.: {@link org.scijava.plugins.scripting.jython.JythonScriptEngine}),
	 * the problem does not occur. But if we switch to the stock JSR-223 Jython
	 * {@code ScriptEngine} (i.e.: {@link org.python.jsr223.PyScriptEngine}), the
	 * problem manifests. See {@link JythonScriptLanguage#getScriptEngine()}.
	 * </p>
	 */
	@Test
	public void testLongType() throws InterruptedException, ExecutionException,
		IOException, ScriptException
	{
		final Context context = new Context();
		final ScriptService scriptService = context.getService(ScriptService.class);

		final String script = "" + //
			"# @OUTPUT String varType\n" + //
			"a = 10L\n" + //
			"varType = type(a)\n";
		final ScriptModule m = scriptService.run("longType.py", script, true).get();

		final Object actual = m.getOutput("varType");
		final String expected = "<type 'long'>";
		assertEquals(expected, actual);
	}
}
