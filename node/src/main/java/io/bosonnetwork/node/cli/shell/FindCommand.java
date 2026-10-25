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

import java.util.List;
import java.util.Optional;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;

/**
 * {@code find}: looks a node, a value or a peer up on the DHT.
 */
@Command(name = "find", description = "Look up a node, a value or a peer.",
		subcommands = {FindCommand.NodeCommand.class, FindCommand.ValueCommand.class, FindCommand.PeerCommand.class})
public class FindCommand extends ShellGroup {
	/** How thorough a lookup is, for the help of each command. */
	static final String MODE_DESCRIPTION = "How thorough the lookup is: arbitrary, optimistic or conservative. "
			+ "Default: ${DEFAULT-VALUE}.";

	@Command(name = "node", description = "Find a node, and show what the DHT knows of it.")
	public static class NodeCommand extends ShellCommandBase {
		@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative",
				description = MODE_DESCRIPTION)
		LookupOption mode;

		@Parameters(paramLabel = "<node-id>", description = "The node to find.")
		Id target;

		@Override
		protected void run() throws Exception {
			Optional<NodeInfo> found = await(node().findNode(target, mode));
			if (found.isPresent())
				output().message(found.get().toString());
			else
				output().message("No node " + target + " on the DHT.");
		}
	}

	@Command(name = "value", description = "Find a value, and show it if the DHT has it.")
	public static class ValueCommand extends ShellCommandBase {
		@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative",
				description = MODE_DESCRIPTION)
		LookupOption mode;

		@Parameters(paramLabel = "<value-id>", description = "The value to find.")
		Id target;

		@Override
		protected void run() throws Exception {
			Optional<Value> value = await(node().findValue(target, mode));
			if (value.isPresent())
				output().message(value.get().toString());
			else
				output().message("No value " + target + " on the DHT.");
		}
	}

	@Command(name = "peer", description = "Find the peers announced for a service.")
	public static class PeerCommand extends ShellCommandBase {
		@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative",
				description = MODE_DESCRIPTION)
		LookupOption mode;

		@Option(names = {"-s", "--sequence-number"}, paramLabel = "<n>", defaultValue = "-1",
				description = "Only peers from this sequence number on.")
		int sequenceNumber;

		@Option(names = {"-x", "--expected"}, paramLabel = "<n>", defaultValue = "1",
				description = "How many peers to look for. Default: ${DEFAULT-VALUE}.")
		int expected;

		@Parameters(paramLabel = "<peer-id>", description = "The peer to find.")
		Id target;

		@Override
		protected void run() throws Exception {
			List<PeerInfo> peers = await(node().findPeer(target, sequenceNumber, expected, mode));
			if (peers.isEmpty()) {
				output().message("No peers announced for " + target + ".");
				return;
			}

			for (PeerInfo peer : peers)
				output().message(peer.toString());
		}
	}
}
