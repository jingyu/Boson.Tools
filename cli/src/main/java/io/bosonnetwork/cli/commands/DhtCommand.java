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

package io.bosonnetwork.cli.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.Listing;
import io.bosonnetwork.cli.common.PageOptions;
import io.bosonnetwork.cli.director.CliContext;
import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.cli.services.ServiceViews;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.higgs.HiggsNode;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.web.PaginatedResult;

/**
 * The {@code dht} commands of {@code boson-cli}: the Boson DHT, used through the super node's web
 * gateway by a Higgs node - a DHT client with no routing table of its own, acting as this machine's
 * device.
 * <p>
 * Compared with the interactive shell of {@code boson-node}, a value or peer is named by the key file
 * that signs it rather than by a private key on the command line: storing again with the same file
 * updates it, and the sequence number is worked out here.
 */
@Command(name = "dht", aliases = "higgs",
		description = {"Use the Boson DHT through the super node's web gateway.",
				"Finds nodes, values and peers, stores values and announces peers, as this machine's device. What you "
						+ "store or announce with --persistent is kept announced by the gateway: 'dht value' and "
						+ "'dht peer' manage it."},
		subcommands = {DhtCommand.IdCommand.class, DhtCommand.FindCommand.class, DhtCommand.StoreCommand.class,
				DhtCommand.AnnounceCommand.class, DhtCommand.ValueCommand.class, DhtCommand.PeerCommand.class})
public class DhtCommand extends CliGroup {
	private static final String MODE_DESCRIPTION = "How thorough the lookup is: arbitrary (the first answer), "
			+ "optimistic, or conservative (the newest). Default: ${DEFAULT-VALUE}.";
	private static final String KEY_FILE_HINT = "Created if it does not exist; keep it to update what it signs.";

	@Command(name = "id", description = {"Show who this machine is on the DHT, and the gateway it uses.",
			"The DHT sees this device: its node id is the device id."})
	public static class IdCommand extends DirectorCommand {
		@Override
		protected void run() throws Exception {
			HiggsNode node = context().higgsNode();
			Id userId = context().userId();
			String gatewayUrl = context().serviceUrl(NodeStatus.Service.WEB_GATEWAY).toString();
			Id gatewayPeerId = context().nodeStatus().getService(NodeStatus.Service.WEB_GATEWAY).orElseThrow().getPeerId();

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("nodeId", node.getId());
				json.put("userId", userId);
				json.put("gatewayUrl", gatewayUrl);
				json.put("gatewayPeerId", gatewayPeerId);
				json.put("version", node.getVersion());
				output().json(json);
				return;
			}

			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("Node id", node.getId() + " (this device)");
			rows.put("User id", userId.toBase58String());
			rows.put("Gateway", gatewayUrl);
			rows.put("Gateway peer id", gatewayPeerId.toBase58String());
			rows.put("Version", node.getVersion());
			output().details(rows);
		}
	}

	@Command(name = "find", description = "Look up a node, a value or a peer on the DHT.",
			subcommands = {FindCommand.NodeCommand.class, FindCommand.ValueCommand.class, FindCommand.PeerCommand.class})
	public static class FindCommand extends CliGroup {
		@Command(name = "node", description = "Find a node, and show its addresses.")
		public static class NodeCommand extends DirectorCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative", description = MODE_DESCRIPTION)
			LookupOption mode;

			@Override
			protected void run() throws Exception {
				NodeInfo node = await(context().higgsNode().findNode(nodeId, mode)).orElseThrow(() ->
						CliException.notFound("No node " + nodeId + " was found on the DHT.", null));
				if (output().isJson())
					output().json(ServiceViews.nodeJson(node));
				else
					output().details(ServiceViews.node(node));
			}
		}

		@Command(name = "value", description = {"Find a value, and show it.",
				"A value encrypted for your user or this device is decrypted."})
		public static class ValueCommand extends DirectorCommand {
			@Parameters(paramLabel = "<value-id>", description = "The value.")
			Id valueId;

			@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative", description = MODE_DESCRIPTION)
			LookupOption mode;

			@Option(names = {"-s", "--sequence"}, paramLabel = "<n>",
					description = "Only a version of a mutable value at least this new.")
			Integer sequence;

			@Option(names = {"-o", "--output"}, paramLabel = "<file>", description = "Also write the value's data to this file.")
			Path file;

			@Override
			protected void run() throws Exception {
				checkSequence(sequence);
				HiggsNode node = context().higgsNode();
				Value value = await(node.findValue(valueId, sequence != null ? sequence : -1, mode)).orElseThrow(() ->
						CliException.notFound("No value " + valueId + " was found on the DHT" +
								(sequence != null ? " at sequence " + sequence + " or newer." : "."), null));

				byte[] plainText = decrypt(context(), value);
				if (file != null) {
					byte[] data = plainText != null ? plainText : value.getData();
					writeNew(file, data);
				}

				if (output().isJson())
					output().json(ServiceViews.valueJson(value, plainText));
				else
					output().details(ServiceViews.value(value, plainText));
			}
		}

		@Command(name = "peer", description = {"Find the peers announced for an id.",
				"A peer id can be announced at several endpoints, told apart by their fingerprints."})
		public static class PeerCommand extends DirectorCommand {
			@Parameters(paramLabel = "<peer-id>", description = "The peer.")
			Id peerId;

			@Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "conservative", description = MODE_DESCRIPTION)
			LookupOption mode;

			@Option(names = {"-s", "--sequence"}, paramLabel = "<n>", description = "Only versions at least this new.")
			Integer sequence;

			@Option(names = "--count", paramLabel = "<n>", defaultValue = "8",
					description = "How many peers to look for. Default: ${DEFAULT-VALUE}.")
			int count;

			@Override
			protected void run() throws Exception {
				checkSequence(sequence);
				if (count < 1)
					throw CliException.usage("--count must be 1 or more.", null);

				List<PeerInfo> peers = await(context().higgsNode().findPeer(peerId, sequence != null ? sequence : -1, count, mode));
				if (peers.isEmpty() && !output().isJson())
					throw CliException.notFound("No peer " + peerId + " was found on the DHT.", null);

				Listing.list(output(), peers, ServiceViews.PEER_HEADERS, ServiceViews::peerRow, ServiceViews::peerJson, "");
			}
		}
	}

	@Command(name = "store", description = {"Store a value on the DHT.",
			"Without --key, the value is immutable: its id is the hash of its data. With --key, it is mutable: its id is "
					+ "the key's, and storing again with the same key file updates it. With --recipient, only the "
					+ "recipient can read it."})
	public static class StoreCommand extends DirectorCommand {
		@ArgGroup(exclusive = true, multiplicity = "1")
		Data data;

		static class Data {
			@Parameters(paramLabel = "<text>", description = "The value's data, as text.")
			String text;

			@Option(names = {"-f", "--file"}, paramLabel = "<file>", description = "Read the value's data from this file.")
			Path file;

			byte[] bytes() throws IOException {
				return text != null ? text.getBytes(StandardCharsets.UTF_8) : Files.readAllBytes(file);
			}
		}

		@Option(names = {"-k", "--key"}, paramLabel = "<file>",
				description = "The identity file of a mutable value. " + KEY_FILE_HINT)
		Path keyFile;

		@Option(names = {"-r", "--recipient"}, paramLabel = "<id>",
				description = "Encrypt the value for this user or device. Needs --key.")
		Id recipient;

		@Option(names = {"-p", "--persistent"},
				description = "Have the gateway keep the value announced, rather than letting it expire from the DHT.")
		boolean persistent;

		@Override
		protected void run() throws Exception {
			if (recipient != null && keyFile == null)
				throw CliException.usage("An encrypted value is mutable, so it needs a key.",
						"Pass --key <file>: the file is created if it does not exist.");

			byte[] bytes = data.bytes();
			if (bytes.length == 0)
				throw CliException.usage("The value has no data.", null);

			HiggsNode node = context().higgsNode();
			KeyFile key = keyFile != null ? KeyFile.open(keyFile, "value") : null;

			Value value;
			int expected = -1;
			if (key == null) {
				value = build(() -> Value.immutableBuilder().data(bytes).build());
			} else {
				Value existing = current(node, key.id());
				Id to = recipient;
				if (existing != null) {
					expected = existing.getSequenceNumber();
					if (existing.isEncrypted() && to == null)
						to = existing.getRecipient();
					else if (existing.isEncrypted() && !existing.getRecipient().equals(to))
						throw CliException.usage("Value " + key.id() + " is encrypted for " + existing.getRecipient() +
								", and a value keeps its recipient.", "Store it for another recipient with a new key file.");
					else if (!existing.isEncrypted() && to != null)
						throw CliException.usage("Value " + key.id() + " is not encrypted, and cannot become so.",
								"Store an encrypted value with a new key file.");
				}

				int sequence = existing != null ? existing.getSequenceNumber() + 1 : 0;
				Id recipientId = to;
				value = build(() -> (recipientId != null ? Value.encryptedBuilder().recipient(recipientId) : Value.signedBuilder())
						.key(key.key())
						.sequenceNumber(sequence)
						.data(bytes)
						.build());
			}

			if (key != null && key.created())
				output().progress("Created the key of value " + key.id() + " in " + key.file() + ".");
			AnnounceResult result = await(node.storeValue(value, expected, persistent));

			if (output().isJson()) {
				Map<String, Object> json = ServiceViews.valueJson(value, null);
				json.put("persistent", persistent);
				json.put("keyFile", key != null ? key.file().toString() : null);
				json.put("result", ServiceViews.announcedJson(result));
				output().json(json);
				return;
			}

			output().message("Stored " + ServiceViews.kind(value) + " value " + value.getId() +
					(value.isMutable() ? ", sequence " + value.getSequenceNumber() : "") + ".");
			ServiceViews.announced(output(), result);
			if (persistent)
				output().message("The gateway keeps it announced; stop that with " +
						tool().command("dht value remove " + value.getId()) + ".");
			if (key != null && key.created())
				output().message("Its key is in " + key.file() + ": keep it, and store with it again to update the value.");
		}

		// The newest version of a mutable value: the one the gateway keeps for the user, or the DHT's.
		private Value current(HiggsNode node, Id id) throws Exception {
			Optional<Value> kept = await(node.getValue(id));
			if (kept.isPresent())
				return kept.get();
			return await(node.findValue(id, LookupOption.CONSERVATIVE)).orElse(null);
		}
	}

	@Command(name = "announce", description = {"Announce a peer on the DHT: an id, and an endpoint where it is reached.",
			"Without --key, the peer is this device, whose id is the device id. Announcing again with the same key and "
					+ "fingerprint updates the endpoint."})
	public static class AnnounceCommand extends DirectorCommand {
		@Parameters(paramLabel = "<endpoint>", description = "Where the peer is reached, as a URI, such as https://example.com:8443.")
		String endpoint;

		@Option(names = {"-k", "--key"}, paramLabel = "<file>",
				description = "The identity file of the peer. " + KEY_FILE_HINT)
		Path keyFile;

		@Option(names = "--fingerprint", paramLabel = "<n>", defaultValue = "0",
				description = "Tells apart several endpoints of one peer id. Default: ${DEFAULT-VALUE}.")
		long fingerprint;

		@Option(names = {"-a", "--authenticated"},
				description = "Have this device sign the announcement too, vouching that it runs the peer.")
		boolean authenticated;

		@Option(names = {"-e", "--extra"}, paramLabel = "<json>",
				description = "Information to carry with the peer, as a JSON object.")
		String extra;

		@Option(names = {"-p", "--persistent"},
				description = "Have the gateway keep the peer announced, rather than letting it expire from the DHT.")
		boolean persistent;

		@Override
		protected void run() throws Exception {
			Map<String, Object> extraData = extra != null ? parseExtra(extra) : null;
			HiggsNode node = context().higgsNode();
			KeyFile key = keyFile != null ? KeyFile.open(keyFile, "peer") :
					new KeyFile(context().deviceKey(), context().settings().deviceIdentityFile(), false);

			PeerInfo existing = current(node, key.id());
			boolean sign = authenticated;
			if (existing != null && existing.getNodeId() != null && !sign) {
				if (!existing.getNodeId().equals(node.getId()))
					throw CliException.usage("Peer " + key.id() + " was announced signed by node " + existing.getNodeId() +
							", which this device is not.", "Announce it from that node, or use another fingerprint.");
				// Once signed, always signed: the DHT refuses an update that drops the signature.
				sign = true;
			}

			PeerInfo.Builder builder = PeerInfo.builder()
					.key(key.key())
					.endpoint(endpoint)
					.fingerprint(fingerprint)
					.sequenceNumber(existing != null ? existing.getSequenceNumber() + 1 : 0);
			if (sign)
				builder.node(node);
			if (extraData != null)
				builder.extra(extraData);
			PeerInfo peer = build(builder::build);

			if (key.created())
				output().progress("Created the key of peer " + key.id() + " in " + key.file() + ".");
			AnnounceResult result = await(node.announcePeer(peer, existing != null ? existing.getSequenceNumber() : -1,
					persistent));

			if (output().isJson()) {
				Map<String, Object> json = ServiceViews.peerJson(peer);
				json.put("persistent", persistent);
				json.put("keyFile", key.file() != null ? key.file().toString() : null);
				json.put("result", ServiceViews.announcedJson(result));
				output().json(json);
				return;
			}

			output().message("Announced peer " + peer.getId() + " at " + peer.getEndpoint() +
					(fingerprint != 0 ? " (fingerprint " + fingerprint + ")" : "") + ", sequence " + peer.getSequenceNumber() + ".");
			ServiceViews.announced(output(), result);
			if (persistent)
				output().message("The gateway keeps it announced; stop that with " +
						tool().command("dht peer remove " + peer.getId() + (fingerprint != 0 ? " --fingerprint " + fingerprint : "")) + ".");
			if (key.created())
				output().message("Its key is in " + key.file() + ": keep it, and announce with it again to update the peer.");
		}

		// The newest announcement of this peer and fingerprint: the gateway's, or the DHT's.
		private PeerInfo current(HiggsNode node, Id id) throws Exception {
			Optional<PeerInfo> kept = await(node.getPeer(id, fingerprint));
			if (kept.isPresent())
				return kept.get();
			return await(node.findPeer(id, -1, 16, LookupOption.CONSERVATIVE)).stream()
					.filter(p -> p.getFingerprint() == fingerprint)
					.max((a, b) -> Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()))
					.orElse(null);
		}

		private static Map<String, Object> parseExtra(String text) {
			try {
				return Json.parse(text);
			} catch (RuntimeException e) {
				throw CliException.usage("--extra is not a JSON object.", "Quote it for the shell, as in --extra '{\"name\": \"web\"}'.");
			}
		}
	}

	@Command(name = "value", description = {"Manage the values the gateway keeps announced for you.",
			"These are the values stored with --persistent."},
			subcommands = {ValueCommand.ListCommand.class, ValueCommand.ShowCommand.class, ValueCommand.RemoveCommand.class})
	public static class ValueCommand extends CliGroup {
		@Command(name = "list", description = "List the values the gateway keeps announced for you.")
		public static class ListCommand extends DirectorCommand {
			@Mixin
			PageOptions page;

			@Override
			protected void run() throws Exception {
				HiggsNode node = context().higgsNode();
				PaginatedResult<Value> values = page.fetch(this, node::getAllValues);
				Listing.page(output(), values, page, "values", ServiceViews.VALUE_HEADERS, ServiceViews::valueRow,
						v -> ServiceViews.valueJson(v, null), "The gateway keeps no values for you. Store one with " +
								tool().command("dht store --persistent <text>") + ".");
			}
		}

		@Command(name = "show", description = "Show a value the gateway keeps announced for you.")
		public static class ShowCommand extends DirectorCommand {
			@Parameters(paramLabel = "<value-id>", description = "The value.")
			Id valueId;

			@Override
			protected void run() throws Exception {
				Value value = await(context().higgsNode().getValue(valueId)).orElseThrow(() -> notKept("value", valueId));
				byte[] plainText = decrypt(context(), value);
				if (output().isJson())
					output().json(ServiceViews.valueJson(value, plainText));
				else
					output().details(ServiceViews.value(value, plainText));
			}
		}

		@Command(name = "remove", aliases = "delete", description = {"Stop the gateway keeping a value announced.",
				"The DHT drops the value once its copies expire, within two hours."})
		public static class RemoveCommand extends DirectorCommand {
			@Parameters(paramLabel = "<value-id>", description = "The value.")
			Id valueId;

			@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
			boolean yes;

			@Override
			protected void run() throws Exception {
				HiggsNode node = context().higgsNode();
				terminal().confirm("Stop keeping value " + valueId + " announced?", yes);
				if (!await(node.removeValue(valueId)))
					throw notKept("value", valueId);

				if (output().isJson())
					output().json(Map.of("valueId", valueId, "removed", true));
				else
					output().message("The gateway no longer keeps value " + valueId + " announced; the DHT drops it " +
							"once its copies expire.");
			}
		}
	}

	@Command(name = "peer", description = {"Manage the peers the gateway keeps announced for you.",
			"These are the peers announced with --persistent."},
			subcommands = {PeerCommand.ListCommand.class, PeerCommand.ShowCommand.class, PeerCommand.RemoveCommand.class})
	public static class PeerCommand extends CliGroup {
		@Command(name = "list", description = "List the peers the gateway keeps announced for you.")
		public static class ListCommand extends DirectorCommand {
			@Mixin
			PageOptions page;

			@Override
			protected void run() throws Exception {
				HiggsNode node = context().higgsNode();
				PaginatedResult<PeerInfo> peers = page.fetch(this, node::getAllPeers);
				Listing.page(output(), peers, page, "peers", ServiceViews.PEER_HEADERS, ServiceViews::peerRow,
						ServiceViews::peerJson, "The gateway keeps no peers for you. Announce one with " +
								tool().command("dht announce --persistent <endpoint>") + ".");
			}
		}

		@Command(name = "show", description = "Show a peer the gateway keeps announced for you, at each of its fingerprints.")
		public static class ShowCommand extends DirectorCommand {
			@Parameters(paramLabel = "<peer-id>", description = "The peer.")
			Id peerId;

			@Option(names = "--fingerprint", paramLabel = "<n>", description = "Only the endpoint with this fingerprint.")
			Long fingerprint;

			@Override
			protected void run() throws Exception {
				HiggsNode node = context().higgsNode();
				List<PeerInfo> peers = fingerprint != null ?
						await(node.getPeer(peerId, fingerprint)).map(List::of).orElse(List.of()) :
						await(node.getPeers(peerId));
				if (peers.isEmpty())
					throw notKept("peer", peerId);

				if (output().isJson()) {
					output().json(peers.stream().map(ServiceViews::peerJson).toList());
					return;
				}

				for (int i = 0; i < peers.size(); i++) {
					if (i > 0)
						output().blank();
					output().details(ServiceViews.peer(peers.get(i)));
				}
			}
		}

		@Command(name = "remove", aliases = "delete", description = {"Stop the gateway keeping a peer announced.",
				"Without --fingerprint, every endpoint of the peer. The DHT drops them once their copies expire, "
						+ "within two hours."})
		public static class RemoveCommand extends DirectorCommand {
			@Parameters(paramLabel = "<peer-id>", description = "The peer.")
			Id peerId;

			@Option(names = "--fingerprint", paramLabel = "<n>", description = "Only the endpoint with this fingerprint.")
			Long fingerprint;

			@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
			boolean yes;

			@Override
			protected void run() throws Exception {
				HiggsNode node = context().higgsNode();
				String what = "peer " + peerId + (fingerprint != null ? " (fingerprint " + fingerprint + ")" : "");
				terminal().confirm("Stop keeping " + what + " announced" + (fingerprint == null ? ", at every fingerprint?" : "?"), yes);
				boolean removed = await(fingerprint != null ? node.removePeer(peerId, fingerprint) : node.removePeers(peerId));
				if (!removed)
					throw notKept("peer", peerId);

				if (output().isJson()) {
					Map<String, Object> json = new LinkedHashMap<>();
					json.put("peerId", peerId);
					json.put("fingerprint", fingerprint);
					json.put("removed", true);
					output().json(json);
				} else {
					output().message("The gateway no longer keeps " + what + " announced; the DHT drops it once its " +
							"copies expire.");
				}
			}
		}
	}

	/**
	 * The key a value or peer is signed with, and the file it is kept in.
	 *
	 * @param key     the key pair
	 * @param file    the identity file, or {@code null} for a key given inline
	 * @param created whether the file was created for this command
	 */
	record KeyFile(Signature.KeyPair key, Path file, boolean created) {
		static KeyFile open(Path file, String what) {
			if (Files.exists(file))
				return new KeyFile(IdentityFile.read(file, what + " key file"), file, false);

			Signature.KeyPair key = Signature.KeyPair.random();
			IdentityFile.create(file, key);
			return new KeyFile(key, file, true);
		}

		Id id() {
			return Id.of(key.publicKey().bytes());
		}
	}

	@FunctionalInterface
	private interface Builder<T> {
		T build();
	}

	// Builds a value or peer, reporting what the builder refuses - too much data, say - as a usage error.
	private static <T> T build(Builder<T> builder) {
		try {
			return builder.build();
		} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
			throw CliException.usage(e.getMessage() != null ? e.getMessage() + "." : "Invalid input.", null);
		}
	}

	// Decrypts a value encrypted for the user or this device, when the key is at hand.
	private static byte[] decrypt(CliContext context, Value value) {
		if (!value.isEncrypted())
			return null;

		Signature.KeyPair device = context.settings().deviceKeyIfConfigured();
		Signature.KeyPair user = context.settings().identityKeyIfConfigured();
		for (Signature.KeyPair key : new Signature.KeyPair[] { device, user }) {
			if (key != null && Id.of(key.publicKey().bytes()).equals(value.getRecipient())) {
				try {
					return value.decryptData(key.privateKey());
				} catch (CryptoException e) {
					context.output().warning("Value " + value.getId() + " is encrypted for you, but cannot be decrypted: " +
							e.getMessage());
					return null;
				}
			}
		}
		return null;
	}

	private static void checkSequence(Integer sequence) {
		if (sequence != null && sequence < 0)
			throw CliException.usage("--sequence must be 0 or more.", null);
	}

	private static void writeNew(Path file, byte[] data) throws IOException {
		try {
			Files.write(file, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (FileAlreadyExistsException e) {
			throw CliException.failed("The file " + file + " already exists; it was not changed.", "Choose another file.");
		}
	}

	private static CliException notKept(String what, Id id) {
		return CliException.notFound("The gateway keeps no " + what + " " + id + " for you.",
				"List what it keeps with 'boson-cli dht " + what + " list'.");
	}
}
