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

package io.bosonnetwork.node.cli.shell;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.bosonnetwork.cli.common.ExitCode;

/**
 * A group of shell commands, such as {@code find} or {@code storage}. Typed without one of its
 * commands, it lists them.
 */
public abstract class ShellGroup implements Callable<Integer> {
	/** {@code -h} / {@code --help}. Only help: a version per command means nothing inside the shell. */
	@Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
	boolean helpRequested;

	/** The group's specification, injected by picocli. */
	@Spec
	protected CommandSpec spec;

	@Override
	public Integer call() {
		CommandLine commandLine = spec.commandLine();
		commandLine.getOut().println("'" + spec.name() + "' needs one of the commands below.");
		commandLine.usage(commandLine.getOut());
		return ExitCode.USAGE;
	}
}
