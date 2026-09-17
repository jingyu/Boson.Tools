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

package io.bosonnetwork.cli.director;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import io.bosonnetwork.cli.common.CliApp;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.cli.common.ErrorTranslator;

/**
 * The root command of a tool that talks to a Director: adds the connection and identity options, the
 * context its commands work with, and the Director's failures to what the user is told.
 */
@Command
public abstract class DirectorApp extends CliApp {
	@Mixin
	ConnectionOptions connectionOptions;

	private CliContext context;
	private ToolSpec tool;

	/**
	 * Creates the root command.
	 *
	 * @param environment the environment the tool runs in
	 */
	protected DirectorApp(CliEnvironment environment) {
		super(environment);
	}

	/**
	 * Describes the tool: where its configuration and identity live, and what its identity is to the
	 * Director.
	 *
	 * @param environment the environment the tool runs in
	 * @return the tool
	 */
	protected abstract ToolSpec createTool(CliEnvironment environment);

	/**
	 * Returns the tool.
	 *
	 * @return the tool
	 */
	public final ToolSpec tool() {
		if (tool == null)
			tool = createTool(environment());
		return tool;
	}

	/**
	 * Returns the context of this run.
	 *
	 * @return the context
	 */
	public final CliContext context() {
		if (context == null)
			context = new CliContext(tool(), environment(), connectionOptions, deviceOptions(), output(), terminal());
		return context;
	}

	/**
	 * Returns the device option of a tool that acts as a device as well, which declares it as a
	 * mixin of its own.
	 *
	 * @return the option, or {@code null} for a tool that acts with no device
	 */
	protected DeviceOptions deviceOptions() {
		return null;
	}

	@Override
	protected List<String> globalOptionNames() {
		List<String> names = new ArrayList<>(List.of("--config", "--url", "--node-id", "--resolve", "--identity"));
		names.addAll(COMMON_OPTIONS);
		return names;
	}

	@Override
	protected ErrorTranslator errorTranslator() {
		return new DirectorErrors(context());
	}

	@Override
	protected void close() {
		if (context != null)
			context.close();
	}
}
