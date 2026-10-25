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

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.kademlia.KadNode;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.vertx.ContextualFuture;

/**
 * {@code storage}: what this node holds locally, as opposed to what the DHT holds ({@code find}).
 */
@Command(name = "storage", description = "Show the values and peers stored on this node.",
		subcommands = {StorageCommand.ValuesCommand.class, StorageCommand.ValueCommand.class,
				StorageCommand.PeersCommand.class, StorageCommand.PeerCommand.class})
public class StorageCommand extends ShellGroup {

	@Command(name = "values", description = "List the values stored on this node.")
	public static class ValuesCommand extends ShellCommandBase {
		@Override
		protected void run() throws Exception {
			List<Value> values = await(ContextualFuture.of(storage(node()).getValues()));
			if (values.isEmpty()) {
				output().message("This node stores no values.");
				return;
			}

			for (Value value : values)
				output().message(value.getId() + "  " + (value.isMutable() ? "mutable" : "immutable"));
			output().blank();
			output().message(Formats.count(values.size(), "value", "values") + ".");
		}
	}

	@Command(name = "value", description = "Show a value stored on this node.")
	public static class ValueCommand extends ShellCommandBase {
		@Parameters(paramLabel = "<value-id>", description = "The value.")
		Id valueId;

		@Override
		protected void run() throws Exception {
			Value value = await(ContextualFuture.of(storage(node()).getValue(valueId)));
			if (value == null)
				output().message("This node stores no value " + valueId + ".");
			else
				output().message(value.toString());
		}
	}

	@Command(name = "peers", description = "List the peers stored on this node.")
	public static class PeersCommand extends ShellCommandBase {
		@Override
		protected void run() throws Exception {
			List<PeerInfo> peers = await(ContextualFuture.of(storage(node()).getPeers()));
			if (peers.isEmpty()) {
				output().message("This node stores no peers.");
				return;
			}

			for (PeerInfo peer : peers)
				output().message(peer.getId() + "  announced by " + peer.getNodeId());
			output().blank();
			output().message(Formats.count(peers.size(), "peer", "peers") + ".");
		}
	}

	@Command(name = "peer", description = "Show the peers stored for one service.")
	public static class PeerCommand extends ShellCommandBase {
		@Parameters(paramLabel = "<peer-id>", description = "The peer.")
		Id peerId;

		@Override
		protected void run() throws Exception {
			List<PeerInfo> peers = await(ContextualFuture.of(storage(node()).getPeers(peerId)));
			if (peers.isEmpty()) {
				output().message("This node stores no peers for " + peerId + ".");
				return;
			}

			for (PeerInfo peer : peers)
				output().message(peer.toString());
			output().blank();
			output().message(Formats.count(peers.size(), "peer", "peers") + ".");
		}
	}

	static DataStorage storage(KadNode node) {
		return node.unwrap(DataStorage.class)
				.orElseThrow(() -> new CliException(ExitCode.FAILED, "The node has no local storage.",
						"The node is starting or stopping; try again."));
	}
}
