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

package io.bosonnetwork.cli.common.testing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;

import io.bosonnetwork.cli.common.CliApp;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.json.Json;

/**
 * Runs a tool in the test's own process, with no terminal, a configuration directory of its own, and
 * standard input, output and error captured.
 */
public final class CliRunner {
	/**
	 * What a run of a tool left behind.
	 *
	 * @param exitCode the exit code
	 * @param outBytes standard output, as written
	 * @param err      standard error
	 */
	public record Result(int exitCode, byte[] outBytes, String err) {
		/**
		 * Returns standard output as text.
		 *
		 * @return standard output, decoded as UTF-8
		 */
		public String out() {
			return new String(outBytes, StandardCharsets.UTF_8);
		}

		/**
		 * Parses standard output as a JSON object.
		 *
		 * @return the object
		 */
		public Map<String, Object> json() {
			return Json.parse(out());
		}

		/**
		 * Parses standard output as a JSON array.
		 *
		 * @return the array
		 */
		public List<Object> jsonArray() {
			return Json.parse(out(), new TypeReference<List<Object>>() { });
		}

		@Override
		public String toString() {
			return "exit " + exitCode + "\n--- out ---\n" + out() + "--- err ---\n" + err;
		}
	}

	private final Function<CliEnvironment, CliApp> factory;
	private final Path configDir;
	private final Map<String, String> variables = new HashMap<>();

	/**
	 * Creates a runner.
	 *
	 * @param factory   creates the tool
	 * @param configDir the directory standing for the user's configuration directory
	 */
	public CliRunner(Function<CliEnvironment, CliApp> factory, Path configDir) {
		this.factory = factory;
		this.configDir = configDir;
	}

	/**
	 * Sets an environment variable for the runs.
	 *
	 * @param name  the variable
	 * @param value the value
	 * @return this runner
	 */
	public CliRunner env(String name, String value) {
		variables.put(name, value);
		return this;
	}

	/**
	 * Runs the tool with nothing on standard input.
	 *
	 * @param args the command line
	 * @return the result
	 */
	public Result run(String... args) {
		return runWithInput("", args);
	}

	/**
	 * Runs the tool with text on standard input.
	 *
	 * @param input the standard input
	 * @param args  the command line
	 * @return the result
	 */
	public Result runWithInput(String input, String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StringWriter err = new StringWriter();
		PrintWriter text = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
		CliEnvironment environment = new CliEnvironment(variables, configDir,
				new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), null,
				text, new PrintWriter(err, true), out);

		int exitCode = factory.apply(environment).execute(args);
		text.flush();
		return new Result(exitCode, out.toByteArray(), err.toString());
	}
}
