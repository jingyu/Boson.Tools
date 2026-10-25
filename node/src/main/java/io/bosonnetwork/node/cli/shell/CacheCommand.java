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

import java.nio.file.Path;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.node.cli.RoutingCache;

/**
 * {@code cache}: the routing table saved on disk, as opposed to the one the node has now
 * ({@code routing}). The same view as {@code boson-node cache}, from the prompt.
 */
@Command(name = "cache", description = "Show a routing table saved in a data directory.")
public class CacheCommand extends ShellCommandBase {
	@Parameters(paramLabel = "<dir>", arity = "0..1", defaultValue = ".",
			description = "The data directory holding dht4.cache and dht6.cache. Default: the current directory.")
	Path dataDir;

	@Override
	protected void run() {
		boolean found = false;
		for (String file : new String[] { RoutingCache.IPV4_FILE, RoutingCache.IPV6_FILE }) {
			String family = file.equals(RoutingCache.IPV4_FILE) ? "IPv4" : "IPv6";
			RoutingCache.Table table = RoutingCache.read(dataDir.resolve(file), family);
			if (table == null)
				continue;

			if (found)
				output().blank();
			output().message(family + " routing table (" + table.file() + "):");
			RoutingCache.print(output(), table);
			found = true;
		}

		if (!found)
			output().message("No saved routing table in " + dataDir.toAbsolutePath() + ".");
	}
}
