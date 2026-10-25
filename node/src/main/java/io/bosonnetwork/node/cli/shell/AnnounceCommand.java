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

import java.nio.charset.StandardCharsets;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.vertx.ContextualFuture;

/**
 * {@code announce}: tells the DHT that a service can be reached at an endpoint.
 */
@Command(name = "announce", description = "Announce a service peer on the DHT.",
		subcommands = {AnnounceCommand.PeerCommand.class})
public class AnnounceCommand extends ShellGroup {

	@Command(name = "peer", description = {"Announce a service peer on the DHT.",
			"Without a key, a new one is generated and printed: it is what updating this announcement later needs."})
	public static class PeerCommand extends ShellCommandBase {
		@Option(names = {"-k", "--private-key"}, paramLabel = "<key>",
				description = "The peer's private key, Base58 or 0x hex. Without it, a new key.")
		String privateKey;

		@Option(names = {"-a", "--authenticated"}, description = "Sign the announcement with this node's key.")
		boolean authenticated;

		@Option(names = {"-e", "--extra"}, paramLabel = "<json>",
				description = "Extra information to carry with the peer, as JSON.")
		String extra;

		@Option(names = {"-p", "--persistent"}, description = "Keep the peer announced, rather than letting it expire.")
		boolean persistent;

		@Option(names = {"-l", "--local-only"}, description = "Store it on this node only, without announcing it.")
		boolean localOnly;

		@Parameters(paramLabel = "<endpoint>", description = "Where the service is reachable, as a URI.")
		String endpoint;

		@Override
		protected void run() throws Exception {
			PeerInfo.Builder builder = PeerInfo.builder().endpoint(endpoint);

			if (privateKey != null) {
				Signature.KeyPair key = Keys.privateKey(privateKey, "peer private key");
				builder.key(key);
			}
			if (authenticated)
				builder.node(node());
			if (extra != null)
				builder.extra(extraData());

			PeerInfo peer = builder.build();
			output().message("Peer id:     " + peer.getId());
			output().message("Private key: " + Keys.encode(peer.getPrivateKey(), false));

			if (localOnly) {
				await(ContextualFuture.of(StorageCommand.storage(node()).putPeer(peer)));
				output().message("Stored peer " + peer.getId() + " on this node only.");
				return;
			}

			AnnounceResult result = await(node().announcePeer(peer, persistent));
			StoreCommand.report(output(), "Peer " + peer.getId(), result);
		}

		private byte[] extraData() {
			try {
				return Json.toBytes(Json.parse(extra));
			} catch (RuntimeException e) {
				output().warning("The extra information is not JSON; it is carried as text.");
				return extra.getBytes(StandardCharsets.UTF_8);
			}
		}
	}
}
