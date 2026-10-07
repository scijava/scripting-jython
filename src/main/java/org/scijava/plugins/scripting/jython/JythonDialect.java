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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.scijava.code.lsp.ScriptDialect;

/**
 * How Jython scripts see their script parameters: as the Java objects
 * themselves (primitives boxed), e.g. {@code image: net.imglib2.img.Img}; or
 * as Python values, where Jython converts them (e.g. strings).
 * <p>
 * Run-time types are Java class names (e.g. {@code java.lang.String}), as
 * Jython's own completion needs them; declarations are Python, for a Python
 * language server knowing the Java packages from stubs (see
 * {@link JavaStubs}), e.g. {@code import net.imglib2.img; image:
 * net.imglib2.img.Img; name: str}.
 * </p>
 *
 * @author Gabriel Selzer
 */
class JythonDialect implements ScriptDialect {

	@Override
	public String runtimeType(final Class<?> type) {
		return boxed(type).getName();
	}

	@Override
	public String declare(final String name, final String type) {
		return name + ": " + pythonType(type);
	}

	@Override
	public List<String> imports(final Collection<String> types) {
		final List<String> imports = new ArrayList<>();
		for (final String p : packages(types)) imports.add("import " + p);
		return imports;
	}

	/** The Java packages of the given types that a declaration refers to. */
	static Set<String> packages(final Collection<String> types) {
		final Set<String> packages = new TreeSet<>();
		for (final String t : types) {
			if (pythonType(t).equals(t.replace('$', '.')) && t.lastIndexOf(
				'.') > 0) packages.add(t.substring(0, t.lastIndexOf('.')));
		}
		return packages;
	}

	/**
	 * The Python type of a Java class name, as Jython converts its values:
	 * e.g. {@code str} for {@code java.lang.String}.
	 */
	static String pythonType(final String type) {
		switch (type) {
			case "java.lang.String":
			case "java.lang.Character":
				return "str";
			case "java.lang.Integer":
			case "java.lang.Long":
			case "java.lang.Short":
			case "java.lang.Byte":
			case "java.math.BigInteger":
				return "int";
			case "java.lang.Double":
			case "java.lang.Float":
			case "java.math.BigDecimal":
				return "float";
			case "java.lang.Boolean":
				return "bool";
			default:
				return type.startsWith("[") ? "list" : type.replace('$', '.');
		}
	}

	/** Maps primitive types to their wrappers, whose members can be listed. */
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
