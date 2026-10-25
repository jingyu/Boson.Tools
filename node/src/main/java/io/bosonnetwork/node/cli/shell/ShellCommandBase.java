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
import java.util.concurrent.CompletableFuture;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.common.Futures;
import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.kademlia.KadNode;

/**
 * A command typed at the shell's prompt: it works with the node the shell started.
 */
public abstract class ShellCommandBase implements Callable<Integer> {
	/** {@code -h} / {@code --help}. Only help: a version per command means nothing inside the shell. */
	@Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
	boolean helpRequested;

	/** The command's specification, injected by picocli. */
	@Spec
	protected CommandSpec spec;

	@Override
	public final Integer call() throws Exception {
		run();
		return ExitCode.OK;
	}

	/**
	 * Does what the command does.
	 *
	 * @throws Exception any failure; the shell reports it and carries on
	 */
	protected abstract void run() throws Exception;

	/**
	 * Returns the session of the shell this command was typed at.
	 *
	 * @return the session
	 */
	protected final ShellSession session() {
		return ((Shell) spec.root().userObject()).session();
	}

	/**
	 * Returns the running node.
	 *
	 * @return the node
	 */
	protected final KadNode node() {
		return session().node();
	}

	/**
	 * Returns the output.
	 *
	 * @return the output
	 */
	protected final Output output() {
		return session().output();
	}

	/**
	 * Waits for a call the node makes.
	 *
	 * @param future the call
	 * @param <T>    the result type
	 * @return the result
	 * @throws Exception the failure of the call
	 */
	protected final <T> T await(CompletableFuture<T> future) throws Exception {
		return Futures.await(future);
	}
}
