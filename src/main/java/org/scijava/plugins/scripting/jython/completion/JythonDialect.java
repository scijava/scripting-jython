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

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.python.core.PyFunction;
import org.python.core.PyJavaType;
import org.python.core.PyModule;
import org.python.core.PyObject;
import org.scijava.code.lsp.ScriptDialect;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.jython.JythonScriptLanguage;
import org.scijava.script.ScriptLanguage;

/**
 * Describes how to turn Jython scripts into plain Jython code. Scripts see
 * their parameters as the Python values Jython converts them to (e.g.
 * {@code str} for strings, {@code int} for integers, {@code array.array} for
 * arrays), or else as the Java objects themselves (e.g.
 * {@code net.imglib2.img.Img}).
 * <p>
 * Each is declared as an instance of its type, in code both Jython's own
 * analysis (Python 2) and a Python language server knowing the Java packages
 * from stubs (see scijava-code-lsp-jython) read: e.g.
 * {@code import net.imglib2.img; image = net.imglib2.img.Img(); name = str()}.
 * </p>
 * <p>
 * A live interpreter's variables are declared by their values, where those
 * say more: its imported Java classes as the classes themselves, its modules
 * imported again, its functions as functions, and its other Python values as
 * instances of their builtin types.
 * </p>
 *
 * @author Gabriel Selzer
 */
@Plugin(type = ScriptDialect.class, name = "Jython")
public class JythonDialect implements ScriptDialect {

	/** Names Python reserves, which cannot be declared. */
	private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
		"False", "None", "True", "and", "as", "assert", "async", "await", "break",
		"class", "continue", "def", "del", "elif", "else", "except", "exec",
		"finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
		"nonlocal", "not", "or", "pass", "print", "raise", "return", "try",
		"while", "with", "yield"));

	@Override
	public boolean supports(final ScriptLanguage language) {
		return language instanceof JythonScriptLanguage;
	}

	@Override
	public String languageId() {
		return "jython";
	}

	@Override
	public Header header(final Map<String, Variable> variables) {
		// Declare each variable, for type reasoning
		final List<String> types = new ArrayList<>();
		final List<String> declarations = new ArrayList<>();
		variables.forEach((name, v) -> {
			// NB: Names Python reserves cannot be declared.
			if (!KEYWORDS.contains(name)) declarations.add(declaration(name, v,
				types));
		});
		// And import the packages of those types, before the declarations
		final List<String> code = new ArrayList<>();
		final Set<String> hidden = new TreeSet<>();
		for (final String p : packages(types)) {
			code.add("import " + p);
			// NB: "import a.b" binds a.
			hidden.add(p.split("\\.")[0]);
		}
		code.addAll(declarations);
		// Combine into a Header
		return new Header(String.join("; ", code), hidden);
	}

	/**
	 * Declares a variable: by its value, if known (e.g. a live interpreter's
	 * imported Java class, as the class itself), or else as an instance of its
	 * type.
	 *
	 * @param types Where to note the types the declaration refers to (see
	 *          {@link #packages}).
	 */
	static String declaration(final String name, final Variable v,
		final List<String> types)
	{
		final Object value = v.value().orElse(null);
		// A Java class (e.g. after "from java.util import ArrayList"): itself
		final Class<?> c = value instanceof PyJavaType ? ((PyJavaType) value)
			.getProxyType() : value instanceof Class ? (Class<?>) value : null;
		if (c != null) {
			if (!isNamed(c)) return name + " = " + any(types);
			types.add(c.getName());
			return name + " = " + c.getName().replace('$', '.');
		}
		// A Python module (e.g. after "import os"): imported again
		if (value instanceof PyModule) {
			final PyObject module = ((PyModule) value).__findattr__("__name__");
			if (module != null && module.toString().matches("[\\w.]+")) {
				return "import " + module + " as " + name;
			}
		}
		// A function (e.g. after "def f(): ...")
		if (value instanceof PyFunction) {
			return name + " = lambda *args, **kwargs: None";
		}
		// Another Python value (e.g. a dict): an instance of its builtin type
		if (value instanceof PyObject) {
			final String builtin = pythonType(((PyObject) value).getType()
				.getName());
			return name + " = " + (builtin != null ? builtin + "()" : any(types));
		}
		// A Java value, or a script parameter: an instance of its type
		final String t = parameterType(v.type());
		types.add(t);
		return name + " = " + t.replace('$', '.') + "()";
	}

	/**
	 * A value of any type (noting the module that says so), for variables
	 * without a type to declare.
	 */
	private static String any(final List<String> types) {
		types.add("typing.Any");
		return "typing.cast(typing.Any, None)";
	}

	/**
	 * The Python 3 type of a Python 2 builtin one (as the Python server knows
	 * Python 3's), e.g. {@code str} for {@code unicode}; or null if not a
	 * builtin one.
	 */
	private static String pythonType(final String type) {
		switch (type) {
			case "int":
			case "long":
				return "int";
			case "unicode":
				return "str";
			case "bool":
			case "float":
			case "str":
			case "list":
			case "dict":
			case "tuple":
			case "set":
				return type;
			default:
				return null;
		}
	}

	/** Whether code can refer to a class by its name. */
	private static boolean isNamed(final Class<?> c) {
		if (c.isPrimitive() || c.isArray() || c.isAnonymousClass() || c
			.isLocalClass() || c.getName().indexOf('.') < 0) return false;
		for (Class<?> t = c; t != null; t = t.getEnclosingClass()) {
			if (!Modifier.isPublic(t.getModifiers())) return false;
		}
		return true;
	}

	/**
	 * @param type the Java type (of a SciJava Script parameter) declared in the
	 *          script
	 * @return the type of the object given to the script by Jython: a Python
	 *         type for the values Jython converts (e.g. {@code str}), or else
	 *         the Java type itself
	 */
	static String parameterType(final Class<?> type) {
		final Class<?> t = boxed(type);
		// NB: As Jython converts them (Py.java2py).
		if (t == String.class || t == Character.class) return "str";
		if (t == Integer.class || t == Long.class || t == Short.class ||
			t == Byte.class || t == BigInteger.class) return "int";
		if (t == Double.class || t == Float.class) return "float";
		if (t == Boolean.class) return "bool";
		if (t.isArray()) return "array.array";
		return t.getName();
	}

	/**
	 * The packages or modules of the given types that a declaration refers to
	 * (none for builtins, e.g. {@code str}).
	 */
	static Set<String> packages(final Collection<String> types) {
		final Set<String> packages = new TreeSet<>();
		for (final String t : types) {
			if (t.lastIndexOf('.') > 0) packages.add(t.substring(0, t.lastIndexOf(
				'.')));
		}
		return packages;
	}

	/** Maps primitive types to their wrappers. */
	private static Class<?> boxed(final Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == double.class) return Double.class;
		if (type == float.class) return Float.class;
		if (type == boolean.class) return Boolean.class;
		if (type == char.class) return Character.class;
		if (type == short.class) return Short.class;
		if (type == byte.class) return Byte.class;
		return Object.class; // void
	}
}
