/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.cli.common;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.utils.FileUtils;

/**
 * Everything a tool takes from the process it runs in: environment variables, the user's
 * configuration directory, standard input and output, and the terminal. Tests replace it.
 */
public class CliEnvironment {
	private final Map<String, String> variables;
	private final Path userConfigDir;
	private final Path userStateDir;
	private final InputStream binaryIn;
	private final BufferedReader stdin;
	private final Console console;
	private final PrintWriter out;
	private final PrintWriter err;
	private final OutputStream binaryOut;

	/**
	 * Creates an environment whose standard output takes text only: binary output is written to it
	 * as ISO-8859-1 characters, one per byte.
	 *
	 * @param variables     the environment variables
	 * @param userConfigDir the user's configuration directory, such as {@code ~/.config}
	 * @param stdin         standard input
	 * @param console       the terminal, or {@code null} when not attached to one
	 * @param out           standard output
	 * @param err           standard error
	 */
	public CliEnvironment(Map<String, String> variables, Path userConfigDir, InputStream stdin, Console console,
			PrintWriter out, PrintWriter err) {
		this(variables, userConfigDir, FileUtils.getUserStateDir(), stdin, console, out, err, new OutputStream() {
			@Override
			public void write(int b) {
				out.write(b & 0xff);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				out.write(new String(b, off, len, StandardCharsets.ISO_8859_1));
			}

			@Override
			public void flush() {
				out.flush();
			}
		});
	}

	/**
	 * Creates an environment.
	 *
	 * @param variables     the environment variables
	 * @param userConfigDir the user's configuration directory, such as {@code ~/.config}
	 * @param userStateDir  the user's state directory, such as {@code ~/.local/state}
	 * @param stdin         standard input
	 * @param console       the terminal, or {@code null} when not attached to one
	 * @param out           standard output
	 * @param err           standard error
	 * @param binaryOut     standard output, for bytes; text written to {@code out} is flushed before
	 *                      anything is written here
	 */
	public CliEnvironment(Map<String, String> variables, Path userConfigDir, Path userStateDir, InputStream stdin,
			Console console, PrintWriter out, PrintWriter err, OutputStream binaryOut) {
		this.variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
		this.userConfigDir = Objects.requireNonNull(userConfigDir, "userConfigDir");
		this.userStateDir = Objects.requireNonNull(userStateDir, "userStateDir");
		this.binaryIn = Objects.requireNonNull(stdin, "stdin");
		this.stdin = new BufferedReader(new InputStreamReader(binaryIn,
				console != null ? console.charset() : Charset.defaultCharset()));
		this.console = console;
		this.out = Objects.requireNonNull(out, "out");
		this.err = Objects.requireNonNull(err, "err");
		this.binaryOut = Objects.requireNonNull(binaryOut, "binaryOut");
	}

	/**
	 * Returns the environment of this process.
	 *
	 * @return the environment
	 */
	public static CliEnvironment system() {
		Console console = System.console();
		Charset charset = console != null ? console.charset() : Charset.defaultCharset();
		return new CliEnvironment(System.getenv(), FileUtils.getUserConfigDir(), FileUtils.getUserStateDir(), System.in, console,
				new PrintWriter(new OutputStreamWriter(System.out, charset), true),
				new PrintWriter(new OutputStreamWriter(System.err, charset), true),
				System.out);
	}

	/**
	 * Returns an environment variable.
	 *
	 * @param name the variable name
	 * @return the value, or {@code null} if it is not set or blank
	 */
	public String variable(String name) {
		String value = variables.get(name);
		return value == null || value.isBlank() ? null : value.strip();
	}

	/**
	 * Returns the user's configuration directory, such as {@code ~/.config}.
	 *
	 * @return the directory
	 */
	public Path userConfigDir() {
		return userConfigDir;
	}

	/**
	 * Returns the user's state directory, such as {@code ~/.local/state}: where a running tool keeps what
	 * lasts only as long as it runs, such as a lock file.
	 *
	 * @return the directory
	 */
	public Path userStateDir() {
		return userStateDir;
	}

	/**
	 * Returns standard input.
	 *
	 * @return standard input
	 */
	public BufferedReader stdin() {
		return stdin;
	}

	/**
	 * Returns standard input for bytes, such as a file's content. A command reads standard input either
	 * as bytes or as text ({@link #stdin()}), never both.
	 *
	 * @return standard input
	 */
	public InputStream binaryIn() {
		return binaryIn;
	}

	/**
	 * Returns the terminal.
	 *
	 * @return the terminal, or {@code null} when not attached to one
	 */
	public Console console() {
		return console;
	}

	/**
	 * Returns standard output.
	 *
	 * @return standard output
	 */
	public PrintWriter out() {
		return out;
	}

	/**
	 * Returns standard output for bytes, such as a file's content. Text already written to
	 * {@link #out()} is flushed first, so that the two keep their order.
	 *
	 * @return standard output
	 */
	public OutputStream binaryOut() {
		out.flush();
		return binaryOut;
	}

	/**
	 * Returns standard error.
	 *
	 * @return standard error
	 */
	public PrintWriter err() {
		return err;
	}
}
