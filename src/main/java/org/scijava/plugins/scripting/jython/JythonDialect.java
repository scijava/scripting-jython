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

import org.scijava.code.api.ScriptDialect;

/**
 * How Jython scripts see their script parameters: as the Java objects
 * themselves (primitives boxed), e.g. {@code image: net.imglib2.img.Img}.
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
		return name + ": " + type;
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
