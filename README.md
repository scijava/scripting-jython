[![](https://github.com/scijava/scripting-jython/actions/workflows/build-main.yml/badge.svg)](https://github.com/scijava/scripting-jython/actions/workflows/build-main.yml)

# Jython Scripting

This library provides a
[JSR-223-compliant](https://en.wikipedia.org/wiki/Scripting_for_the_Java_Platform)
scripting plugin for the [Jython](http://jython.org/) language.

It is implemented as a `ScriptLanguage` plugin for the [SciJava
Common](https://github.com/scijava/scijava-common) platform, which means that
in addition to being usable directly as a `javax.script.ScriptEngineFactory`,
it also provides some functionality on top, such as the ability to generate
lines of script code based on SciJava events.

## Code completion

The Script Editor completes code in Jython scripts: Java classes, their
members and constructors (with their overloads), imports, the script's `#@`
parameters and, in a live interpreter, its variables. This is Jython's own
completion, which knows Java exactly.

If a Python language server is available (e.g. jedi-language-server, as
offered by the `appose-python` scripting plugin), its completions are added
too: for Python modules, and for Java classes, from Python type stubs
(`.pyi`) of the Java packages the script imports, written in the background
to `~/.cache/scijava/jython-stubs` (or `-Dscijava.jython.stubs=<dir>`). The
server analyzes the script as CPython (Jython's standard library is Python
2's, but mostly the same); so it is asked for completions only, not for
problems.

For a complete list of scripting languages available as part of the SciJava
platform, see the
[Scripting](https://github.com/scijava/scijava-common/wiki/Scripting) page on
the SciJava Common wiki.

See also:
* [Jython Scripting](http://wiki.imagej.net/Jython_Scripting)
  on the ImageJ wiki.
