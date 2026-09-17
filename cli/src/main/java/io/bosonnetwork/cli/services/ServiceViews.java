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

package io.bosonnetwork.cli.services;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.ionstore.IonObject;
import io.bosonnetwork.utils.Hex;

/**
 * How {@code boson-cli} shows what the node's services hold: objects, values, peers and nodes. As with
 * the Director's views, the JSON forms are built field by field, so that what scripts rely on is
 * stated here.
 */
public final class ServiceViews {
	/** The table headers of an object list. */
	public static final List<String> OBJECT_HEADERS = List.of("OBJECT ID", "NAME", "SIZE", "TYPE", "ENCRYPTED", "EXPIRES");
	/** The table headers of a value list. */
	public static final List<String> VALUE_HEADERS = List.of("VALUE ID", "KIND", "SEQUENCE", "SIZE", "RECIPIENT");
	/** The table headers of a peer list. */
	public static final List<String> PEER_HEADERS = List.of("PEER ID", "FINGERPRINT", "SEQUENCE", "ENDPOINT", "SIGNED BY NODE");

	private ServiceViews() {
	}

	// ---- Objects -------------------------------------------------------------------------------

	/**
	 * Returns the table row of an object.
	 *
	 * @param object the object
	 * @return the row, matching {@link #OBJECT_HEADERS}
	 */
	public static List<String> objectRow(IonObject object) {
		return List.of(object.getId().toBase58String(),
				Formats.brief(object.getName(), 40),
				Formats.bytes(plainSize(object)),
				Formats.text(object.getContentType()),
				Formats.yesNo(object.isEncrypted()),
				expiry(object.getExpireAt()));
	}

	/**
	 * Returns the details of an object.
	 *
	 * @param object the object
	 * @return the details, keyed by label
	 */
	public static Map<String, String> object(IonObject object) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Object id", object.getId().toBase58String());
		rows.put("Address", Formats.text(object.getUri()));
		rows.put("Name", Formats.text(object.getName()));
		rows.put("Type", Formats.text(object.getContentType()));
		long plain = plainSize(object);
		rows.put("Size", sizeText(plain) + (object.isEncrypted() && plain != object.getSize() ?
				", " + sizeText(object.getSize()) + " stored" : ""));
		rows.put("Encrypted", Formats.yesNo(object.isEncrypted()));
		rows.put("Content id", object.getContentId().toBase58String());
		rows.put("Expires", expiry(object.getExpireAt()));
		object.getMetadata().forEach((key, value) -> {
			if (!key.equalsIgnoreCase("Ion-Encryption"))
				rows.put(key, String.valueOf(value));
		});
		return rows;
	}

	/**
	 * Returns the JSON of an object.
	 *
	 * @param object the object
	 * @return the JSON object
	 */
	public static Map<String, Object> objectJson(IonObject object) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", object.getId());
		json.put("uri", object.getUri());
		json.put("name", object.getName());
		json.put("contentType", object.getContentType());
		json.put("size", object.getSize());
		json.put("plainTextSize", plainSize(object));
		json.put("encrypted", object.isEncrypted());
		json.put("contentId", object.getContentId());
		json.put("expireAt", object.getExpireAt());
		json.put("metadata", object.getMetadata());
		return json;
	}

	// The size a reader receives; an object whose encryption cannot be framed shows what is stored.
	private static long plainSize(IonObject object) {
		try {
			return object.getPlainTextSize();
		} catch (IllegalStateException e) {
			return object.getSize();
		}
	}

	private static String sizeText(long bytes) {
		return bytes < 1024 ? Formats.bytes(bytes) : Formats.bytes(bytes) + " (" + bytes + " bytes)";
	}

	// Expiry times are epoch seconds; 0 is never.
	private static String expiry(long epochSeconds) {
		return epochSeconds <= 0 ? "never" : Formats.time(epochSeconds * 1000);
	}

	// ---- Values --------------------------------------------------------------------------------

	/**
	 * Names the kind of a value.
	 *
	 * @param value the value
	 * @return {@code immutable}, {@code mutable} or {@code encrypted}
	 */
	public static String kind(Value value) {
		return value.isEncrypted() ? "encrypted" : value.isMutable() ? "mutable" : "immutable";
	}

	/**
	 * Returns the table row of a value.
	 *
	 * @param value the value
	 * @return the row, matching {@link #VALUE_HEADERS}
	 */
	public static List<String> valueRow(Value value) {
		return List.of(value.getId().toBase58String(),
				kind(value),
				value.isMutable() ? Integer.toString(value.getSequenceNumber()) : Formats.NONE,
				Formats.bytes(value.getData().length),
				value.getRecipient() != null ? value.getRecipient().toBase58String() : Formats.NONE);
	}

	/**
	 * Returns the details of a value.
	 *
	 * @param value     the value
	 * @param plainText the decrypted data of an encrypted value, or {@code null}
	 * @return the details, keyed by label
	 */
	public static Map<String, String> value(Value value, byte[] plainText) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Value id", value.getId().toBase58String());
		rows.put("Kind", kind(value));
		if (value.isMutable())
			rows.put("Sequence", Integer.toString(value.getSequenceNumber()));
		if (value.getRecipient() != null)
			rows.put("Recipient", value.getRecipient().toBase58String());
		if (value.isEncrypted()) {
			rows.put("Data", plainText != null ? data(plainText) : "encrypted for the recipient (" +
					Formats.bytes(value.getData().length) + ")");
		} else {
			rows.put("Data", data(value.getData()));
		}
		return rows;
	}

	/**
	 * Returns the JSON of a value. Data is Base64URL, as the Boson JSON encoding writes bytes.
	 *
	 * @param value     the value
	 * @param plainText the decrypted data of an encrypted value, or {@code null}
	 * @return the JSON object
	 */
	public static Map<String, Object> valueJson(Value value, byte[] plainText) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", value.getId());
		json.put("kind", kind(value));
		json.put("sequenceNumber", value.isMutable() ? value.getSequenceNumber() : null);
		json.put("recipient", value.getRecipient());
		json.put("data", value.getData());
		json.put("text", value.isEncrypted() ? null : text(value.getData()));
		if (plainText != null) {
			json.put("decrypted", plainText);
			json.put("decryptedText", text(plainText));
		}
		return json;
	}

	/**
	 * Shows bytes as text when they are printable UTF-8, and as hex otherwise.
	 *
	 * @param data the bytes
	 * @return the text, or {@code 0x}-prefixed hex
	 */
	public static String data(byte[] data) {
		String text = text(data);
		return text != null ? text : "0x" + Hex.encode(data);
	}

	// The bytes as text, if they are printable UTF-8.
	private static String text(byte[] data) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(data)).toString();
		} catch (CharacterCodingException e) {
			return null;
		}

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
				return null;
		}
		return text;
	}

	// ---- Peers ---------------------------------------------------------------------------------

	/**
	 * Returns the table row of a peer.
	 *
	 * @param peer the peer
	 * @return the row, matching {@link #PEER_HEADERS}
	 */
	public static List<String> peerRow(PeerInfo peer) {
		return List.of(peer.getId().toBase58String(),
				Long.toString(peer.getFingerprint()),
				Integer.toString(peer.getSequenceNumber()),
				peer.getEndpoint(),
				peer.getNodeId() != null ? peer.getNodeId().toBase58String() : Formats.NONE);
	}

	/**
	 * Returns the details of a peer.
	 *
	 * @param peer the peer
	 * @return the details, keyed by label
	 */
	public static Map<String, String> peer(PeerInfo peer) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Peer id", peer.getId().toBase58String());
		rows.put("Endpoint", peer.getEndpoint());
		rows.put("Fingerprint", Long.toString(peer.getFingerprint()));
		rows.put("Sequence", Integer.toString(peer.getSequenceNumber()));
		rows.put("Signed by node", peer.getNodeId() != null ? peer.getNodeId().toBase58String() : "no");
		if (peer.hasExtra())
			rows.put("Extra", extra(peer));
		return rows;
	}

	/**
	 * Returns the JSON of a peer.
	 *
	 * @param peer the peer
	 * @return the JSON object
	 */
	public static Map<String, Object> peerJson(PeerInfo peer) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", peer.getId());
		json.put("endpoint", peer.getEndpoint());
		json.put("fingerprint", peer.getFingerprint());
		json.put("sequenceNumber", peer.getSequenceNumber());
		json.put("nodeId", peer.getNodeId());
		json.put("extra", peer.hasExtra() ? extraJson(peer) : null);
		return json;
	}

	private static String extra(PeerInfo peer) {
		Object extra = extraJson(peer);
		return extra instanceof byte[] bytes ? data(bytes) : String.valueOf(extra);
	}

	// The extra data as the JSON object it usually is, or its bytes when it is not one.
	private static Object extraJson(PeerInfo peer) {
		try {
			return peer.getExtra();
		} catch (RuntimeException e) {
			return peer.getExtraData();
		}
	}

	// ---- Nodes ---------------------------------------------------------------------------------

	/**
	 * Returns the details of a node.
	 *
	 * @param node the node
	 * @return the details, keyed by label
	 */
	public static Map<String, String> node(NodeInfo node) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Node id", node.getId().toBase58String());
		rows.put("Addresses", String.join("\n", node.getAddresses().stream().map(ServiceViews::address).toList()));
		return rows;
	}

	/**
	 * Returns the JSON of a node.
	 *
	 * @param node the node
	 * @return the JSON object
	 */
	public static Map<String, Object> nodeJson(NodeInfo node) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", node.getId());
		json.put("addresses", node.getAddresses().stream().map(ServiceViews::address).toList());
		return json;
	}

	private static String address(InetSocketAddress address) {
		String host = address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
	}

	// ---- Publishing ----------------------------------------------------------------------------

	/**
	 * Writes what came of storing a value or announcing a peer: how many nodes took it, and what the
	 * others said. What a refusing node says is its own claim, so it is shown as such.
	 *
	 * @param output the output
	 * @param result the result
	 */
	public static void announced(Output output, AnnounceResult result) {
		output.message("Reached " + result.acknowledged() + " of " +
				Formats.count(result.targets().size(), "node", "nodes") + " (" + status(result.status()) + ").");
		for (AnnounceResult.Target target : result.targets())
			if (!target.isAcknowledged())
				output.message("  " + target.nodeId() + ": " + target.outcome().name().toLowerCase().replace('_', ' ') +
						(target.cause() != null ? " - it said: " + target.cause() : ""));
		if (result.status() == AnnounceResult.Status.NO_TARGETS)
			output.message("The gateway found no nodes to publish to; the network may still be starting.");
	}

	/**
	 * Returns the JSON of what came of a publish.
	 *
	 * @param result the result
	 * @return the JSON object
	 */
	public static Map<String, Object> announcedJson(AnnounceResult result) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("status", result.status().name());
		json.put("acknowledged", result.acknowledged());
		json.put("targets", result.targets().stream().map(target -> {
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("nodeId", target.nodeId());
			t.put("outcome", target.outcome().name());
			t.put("cause", target.cause() != null ? target.cause().toString() : null);
			return t;
		}).toList());
		return json;
	}

	private static String status(AnnounceResult.Status status) {
		return switch (status) {
			case SUCCESS -> "all took it";
			case PARTIAL_SUCCESS -> "some did not take it";
			case NO_TARGETS -> "no nodes to publish to";
			case FAILED -> "none took it";
		};
	}
}
