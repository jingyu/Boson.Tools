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

package io.bosonnetwork.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.testing.CliRunner;
import io.bosonnetwork.cli.common.testing.CliRunner.Result;
import io.bosonnetwork.cli.testing.StubDirector;
import io.bosonnetwork.cli.testing.StubDirector.Reply;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Base58;

/**
 * Tests of the {@code boson-cli} commands that use the super node's services, run in process against a
 * stub that answers for the Director and for the services it reports.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BosonCliServiceTests {
	private static final String ION = "/ion/v1";
	private static final String GATEWAY = "/gw/v1";

	private final Id nodeId = Id.random();
	private final Id ionStorePeerId = Id.random();
	private final Id gatewayPeerId = Id.random();
	// The proxy client sets up encryption with its service peer, so this id has to be a real public key.
	private final Id proxyPeerId = Id.of(Signature.KeyPair.random().publicKey().bytes());
	private StubDirector stub;

	@BeforeAll
	void startStub() throws Exception {
		stub = StubDirector.start();
	}

	@AfterAll
	void stopStub() throws Exception {
		stub.close();
	}

	@BeforeEach
	void resetStub() {
		stub.reset();
		stub.nodeId(nodeId);
		stub.nodeStatus(nodeId,
				NodeStatus.Service.ION_STORE, ionStorePeerId.toBase58String(), stub.url() + "/ion",
				NodeStatus.Service.WEB_GATEWAY, gatewayPeerId.toBase58String(), stub.url() + "/gw",
				NodeStatus.Service.ACTIVE_PROXY, proxyPeerId.toBase58String(), "tcp://127.0.0.1:1");
		stub.reply("GET", GATEWAY + "/info", 200, "{\"nodeId\": \"" + nodeId + "\", \"peerId\": \"" + gatewayPeerId +
				"\", \"version\": \"Stub/1\"}");
	}

	private static CliRunner cli(Path dir) {
		return new CliRunner(BosonCli::new, dir);
	}

	private static Path clientDir(Path dir) {
		return dir.resolve("boson").resolve("client");
	}

	// A configuration pointing at the stub, with a user identity and no device.
	private CliRunner user(Path dir) {
		CliRunner cli = cli(dir);
		assertEquals(0, cli.run("--url", stub.url(), "--node-id", nodeId.toBase58String(), "config", "init").exitCode());
		assertEquals(0, cli.run("identity", "create").exitCode());
		return cli;
	}

	// A user whose machine is registered as a device.
	private CliRunner device(Path dir) {
		CliRunner cli = user(dir);
		stub.reply("POST", "/api/v1/client/devices", 200, "");
		Result added = cli.run("device", "add", "--name", "Laptop");
		assertEquals(0, added.exitCode(), added::toString);
		return cli;
	}

	private static Id id(Path identityFile) {
		return Id.of(IdentityFile.read(identityFile, "identity file").publicKey().bytes());
	}

	private static String objectJson(Id id, Id peerId, byte[] content, String name) {
		return "{\"id\": \"" + id + "\", \"contentId\": \"" + Id.of(Hash.sha256(content)) + "\", \"name\": \"" + name +
				"\", \"size\": " + content.length + ", \"contentType\": \"text/plain\", \"encrypted\": false, " +
				"\"expireAt\": 0, \"uri\": \"ions://" + peerId + "/" + id + "\"}";
	}

	// ---- Configuration of the user and the device ----------------------------------------------

	@Test
	void theTemplateExplainsTheDeviceAndTheProxy(@TempDir Path dir) throws Exception {
		user(dir);
		String config = Files.readString(clientDir(dir).resolve("boson.yaml"));
		assertTrue(config.contains("# deviceIdentity: device.identity"), config);
		assertTrue(config.contains("# proxyUpstream: localhost:8080"), config);
	}

	@Test
	void clientSettingsAreChecked(@TempDir Path dir) throws Exception {
		CliRunner cli = user(dir);
		Id userId = Id.random();

		assertEquals(0, cli.run("config", "set", "proxyUpstream", "tcp://127.0.0.1:22").exitCode());
		assertEquals(0, cli.run("config", "set", "proxyNameAccess", "TRUE").exitCode());
		assertTrue(Files.readString(clientDir(dir).resolve("boson.yaml")).contains("proxyNameAccess: 'true'") ||
				Files.readString(clientDir(dir).resolve("boson.yaml")).contains("proxyNameAccess: true"));

		Result upstream = cli.run("config", "set", "proxyUpstream", "localhost");
		assertEquals(2, upstream.exitCode(), upstream::toString);
		Result flag = cli.run("config", "set", "proxyAnnounce", "maybe");
		assertEquals(2, flag.exitCode(), flag::toString);
		Result secret = cli.run("config", "set", "devicePrivateKey", "3Jx");
		assertEquals(2, secret.exitCode(), secret::toString);
		assertTrue(secret.err().contains("deviceIdentity"), secret::toString);

		// A user id that is not the user identity's is refused where it is used.
		assertEquals(0, cli.run("config", "set", "userId", userId.toBase58String()).exitCode());
		Result mismatch = cli.run("ionstore", "list");
		assertEquals(3, mismatch.exitCode(), mismatch::toString);
		assertTrue(mismatch.err().contains("is not the id of the user identity"), mismatch::toString);

		Result show = cli.run("--json", "config", "show");
		assertEquals(0, show.exitCode(), show::toString);
		assertEquals(userId.toBase58String(), ((Map<?, ?>) show.json().get("userId")).get("value"));
		assertEquals(false, ((Map<?, ?>) show.json().get("deviceIdentity")).get("exists"));
	}

	@Test
	void aMissingUserIsExplained(@TempDir Path dir) {
		CliRunner cli = cli(dir);
		assertEquals(0, cli.run("--url", stub.url(), "config", "init").exitCode());

		Result result = cli.run("ionstore", "list");
		assertEquals(3, result.exitCode(), result::toString);
		assertTrue(result.err().contains("No user is configured"), result::toString);
		assertTrue(result.err().contains("'boson-cli identity create'"), result::toString);
		assertTrue(result.err().contains("config set userId"), result::toString);
		assertTrue(stub.requests().isEmpty(), "nothing is asked of the node before the user is known");
	}

	@Test
	void aMissingDeviceIsExplained(@TempDir Path dir) {
		CliRunner cli = user(dir);
		for (String[] command : List.of(new String[] { "ionstore", "list" }, new String[] { "dht", "value", "list" },
				new String[] { "proxy", "start", "--upstream", "localhost:8080" })) {
			Result result = cli.run(command);
			assertEquals(3, result.exitCode(), () -> String.join(" ", command) + ": " + result);
			assertTrue(result.err().contains("No device is configured"), result::toString);
			assertTrue(result.err().contains("'boson-cli device add --name <name>'"), result::toString);
		}
		assertTrue(stub.requests().isEmpty(), "nothing is asked of the node before the device is known");
	}

	@Test
	void deviceAddCreatesAndRegistersThisMachine(@TempDir Path dir) throws Exception {
		CliRunner cli = user(dir);
		stub.reply("POST", "/api/v1/client/devices", 200, "");

		Result added = cli.run("device", "add", "--name", "Laptop");
		assertEquals(0, added.exitCode(), added::toString);
		Path deviceFile = clientDir(dir).resolve("device.identity");
		assertTrue(Files.exists(deviceFile));
		assertTrue(added.out().contains("Created the device key in " + deviceFile), added::toString);
		assertTrue(added.out().contains("This machine now uses the node's services as this device."), added::toString);

		Map<String, Object> body = stub.requests("POST", "/api/v1/client/devices").get(0).json();
		assertEquals(id(deviceFile).toBase58String(), body.get("deviceId"));
		assertEquals("boson-cli", body.get("appName"));

		// Registering again uses the same key.
		String key = Files.readString(deviceFile);
		assertEquals(0, cli.run("device", "add", "--name", "Laptop").exitCode());
		assertEquals(key, Files.readString(deviceFile));

		Result show = cli.run("--json", "identity", "show");
		assertEquals(0, show.exitCode(), show::toString);
		assertEquals(id(deviceFile).toBase58String(), ((Map<?, ?>) show.json().get("device")).get("id"));
		assertEquals(id(clientDir(dir).resolve("user.identity")).toBase58String(), show.json().get("id"));
	}

	@Test
	void aDeviceCanActForAUserWhoseKeyIsElsewhere(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		Id userId = Id.random();
		assertEquals(0, cli.run("--url", stub.url(), "config", "init").exitCode());
		assertEquals(0, cli.run("config", "set", "userId", userId.toBase58String()).exitCode());
		assertEquals(0, cli.run("util", "keygen", "--output", clientDir(dir).resolve("device.identity").toString()).exitCode());
		stub.reply("GET", ION + "/objects", 200, "{\"page\": 1, \"pageSize\": 20, \"totalItems\": 0, \"items\": []}");

		Result list = cli.run("ionstore", "list");
		assertEquals(0, list.exitCode(), list::toString);
		assertTrue(list.out().contains("You have no objects."), list::toString);

		Result show = cli.run("identity", "show");
		assertEquals(0, show.exitCode(), show::toString);
		assertTrue(show.out().contains(userId.toBase58String()), show::toString);
		assertTrue(show.out().contains("not on this machine"), show::toString);
	}

	@Test
	void aServiceTheNodeDoesNotOfferIsNamed(@TempDir Path dir) {
		CliRunner cli = device(dir);
		stub.nodeStatus(nodeId);

		Result result = cli.run("ionstore", "list");
		assertEquals(1, result.exitCode(), result::toString);
		assertTrue(result.err().contains("does not offer the Ion Store service"), result::toString);
		assertTrue(result.err().contains("'boson-cli node status'"), result::toString);

		Result proxy = cli.run("proxy", "start", "--upstream", "localhost:8080");
		assertEquals(1, proxy.exitCode(), proxy::toString);
		assertTrue(proxy.err().contains("does not offer the Active Proxy service"), proxy::toString);
	}

	@Test
	void anUnreachableServiceIsExplained(@TempDir Path dir) {
		CliRunner cli = device(dir);
		stub.nodeStatus(nodeId, NodeStatus.Service.ION_STORE, ionStorePeerId.toBase58String(), "http://127.0.0.1:1/ion");

		Result result = cli.run("ionstore", "list");
		assertEquals(7, result.exitCode(), result::toString);
		assertTrue(result.err().contains("Cannot connect to the Ion Store at http://127.0.0.1:1/ion"), result::toString);
		assertFalse(result.err().contains("Exception"), result::toString);
	}

	// ---- Objects -------------------------------------------------------------------------------

	@Test
	void objectsAreListedAndShown(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id objectId = Id.random();
		byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
		stub.reply("GET", ION + "/objects", 200, "{\"page\": 1, \"pageSize\": 20, \"totalItems\": 1, \"items\": [" +
				objectJson(objectId, ionStorePeerId, content, "hello.txt") + "]}");
		stub.reply("GET", ION + "/objects/" + objectId, 200, objectJson(objectId, ionStorePeerId, content, "hello.txt"));

		Result list = cli.run("ionstore", "list");
		assertEquals(0, list.exitCode(), list::toString);
		assertTrue(list.out().contains("OBJECT ID"), list::toString);
		assertTrue(list.out().contains(objectId.toBase58String()), list::toString);
		assertTrue(list.out().contains("hello.txt"), list::toString);

		Result json = cli.run("--json", "ionstore", "list");
		assertEquals(0, json.exitCode(), json::toString);
		assertEquals(1, ((List<?>) json.json().get("items")).size());

		Result show = cli.run("ionstore", "show", "ions://" + ionStorePeerId + "/" + objectId);
		assertEquals(0, show.exitCode(), show::toString);
		assertTrue(show.out().contains("hello.txt"), show::toString);

		Result remote = cli.run("ionstore", "show", "ions://" + Id.random() + "/" + objectId);
		assertEquals(2, remote.exitCode(), remote::toString);
		assertTrue(remote.err().contains("on another node"), remote::toString);

		Result bad = cli.run("ionstore", "show", "ions://x/y");
		assertEquals(2, bad.exitCode(), bad::toString);
		assertTrue(bad.err().contains("not an object address"), bad::toString);
	}

	@Test
	void objectsAreRetrievedFromThisNodeAndOthers(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		assertEquals(0, cli.run("--url", stub.url(), "config", "init").exitCode());
		Id objectId = Id.random();
		Id otherPeer = Id.random();
		byte[] content = "retrieved without an account".getBytes(StandardCharsets.UTF_8);
		Reply payload = new Reply(200, content, Map.of("Content-Type", "text/plain",
				"Ion-Content-Id", Id.of(Hash.sha256(content)).toBase58String(),
				"Content-Disposition", "attachment; filename=\"note.txt\""));
		stub.handle("GET", ION + "/objects/" + objectId, request -> payload);
		stub.handle("GET", ION + "/objects/" + otherPeer + "/" + objectId, request -> payload);

		// Named after the object, in the working directory: here, --output names the file instead.
		Path file = dir.resolve("note.txt");
		Result saved = cli.run("ionstore", "get", objectId.toBase58String(), "--output", file.toString());
		assertEquals(0, saved.exitCode(), saved::toString);
		assertArrayEquals(content, Files.readAllBytes(file));
		assertTrue(saved.out().contains("Saved object " + objectId), saved::toString);

		Result again = cli.run("ionstore", "get", objectId.toBase58String(), "--output", file.toString());
		assertEquals(1, again.exitCode(), again::toString);
		assertTrue(again.err().contains("--overwrite"), again::toString);
		assertTrue(Files.list(dir).noneMatch(p -> p.getFileName().toString().endsWith(".part")));

		Result stdout = cli.run("ionstore", "get", "ions://" + otherPeer + "/" + objectId, "-o", "-");
		assertEquals(0, stdout.exitCode(), stdout::toString);
		assertArrayEquals(content, stdout.outBytes());
		assertEquals(1, stub.requests("GET", ION + "/objects/" + otherPeer + "/" + objectId).size());

		Result missing = cli.run("ionstore", "get", Id.random().toBase58String(), "-o", dir.resolve("x").toString());
		assertEquals(4, missing.exitCode(), missing::toString);
		assertTrue(missing.err().contains("ions://<peer-id>/<object-id>"), missing::toString);
		assertFalse(Files.exists(dir.resolve("x")));

		Result key = cli.run("ionstore", "get", objectId.toBase58String(), "-o", "-", "--key", "0x0102");
		assertEquals(2, key.exitCode(), key::toString);
	}

	@Test
	void objectsAreStoredAndEncrypted(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		Id objectId = Id.random();
		// The service answers with the content id of what it received, as the real one does.
		stub.handle("POST", ION + "/objects", request -> Reply.json(201,
				"{\"id\": \"" + objectId + "\", \"contentId\": \"" + Id.of(Hash.sha256(request.bytes())) +
						"\", \"name\": \"data.bin\", \"size\": " + request.bytes().length + ", \"encrypted\": false, " +
						"\"expireAt\": 0, \"uri\": \"ions://" + ionStorePeerId + "/" + objectId + "\"}"));

		Path file = dir.resolve("data.bin");
		Files.write(file, new byte[] { 1, 2, 3 });
		Result put = cli.run("ionstore", "put", file.toString(), "--ttl", "7d", "--meta", "owner=alice");
		assertEquals(0, put.exitCode(), put::toString);
		assertTrue(put.out().contains("as object " + objectId), put::toString);
		assertTrue(put.out().contains("ions://" + ionStorePeerId + "/" + objectId), put::toString);
		StubDirector.Request request = stub.requests("POST", ION + "/objects").get(0);
		assertArrayEquals(new byte[] { 1, 2, 3 }, request.bytes());

		Result encrypted = cli.run("--json", "ionstore", "put", file.toString(), "--encrypt");
		assertEquals(0, encrypted.exitCode(), encrypted::toString);
		assertNotNull(encrypted.json().get("key"));
		assertFalse(Arrays.equals(new byte[] { 1, 2, 3 }, stub.requests("POST", ION + "/objects").get(1).bytes()));

		Result stdin = cli.runWithInput("from standard input", "ionstore", "put", "-", "--name", "note.txt");
		assertEquals(0, stdin.exitCode(), stdin::toString);
		assertEquals("from standard input", stub.requests("POST", ION + "/objects").get(2).body());

		Result ttl = cli.run("ionstore", "put", file.toString(), "--ttl", "soon");
		assertEquals(2, ttl.exitCode(), ttl::toString);
		Result missing = cli.run("ionstore", "put", dir.resolve("none").toString());
		assertEquals(2, missing.exitCode(), missing::toString);
	}

	@Test
	void objectsAreRemovedWithConfirmation(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id objectId = Id.random();
		Id missingId = Id.random();
		stub.handle("DELETE", ION + "/objects/" + objectId, request -> new Reply(204, new byte[0], Map.of()));
		stub.handle("DELETE", ION + "/objects/" + missingId, request -> new Reply(404, new byte[0], Map.of()));

		Result unconfirmed = cli.run("ionstore", "remove", objectId.toBase58String());
		assertEquals(2, unconfirmed.exitCode(), unconfirmed::toString);
		assertTrue(stub.requests("DELETE", ION + "/objects/" + objectId).isEmpty());

		Result removed = cli.run("ionstore", "delete", objectId.toBase58String(), missingId.toBase58String(), "--yes");
		assertEquals(4, removed.exitCode(), removed::toString);
		assertTrue(removed.out().contains("Removed object " + objectId), removed::toString);
		assertTrue(removed.err().contains("You have no object " + missingId), removed::toString);
	}

	@Test
	void anUnregisteredDeviceIsExplained(@TempDir Path dir) {
		CliRunner cli = device(dir);
		stub.reply("GET", ION + "/objects", 401, "{\"type\": \"UNAUTHORIZED\", \"code\": 11, \"message\": \"Unknown device\"}");
		stub.reply("GET", GATEWAY + "/user/values", 401, "Unauthorized");

		for (String[] command : List.of(new String[] { "ionstore", "list" }, new String[] { "dht", "value", "list" })) {
			Result result = cli.run(command);
			assertEquals(5, result.exitCode(), () -> String.join(" ", command) + ": " + result);
			assertTrue(result.err().contains("did not accept this device"), result::toString);
			assertTrue(result.err().contains("'boson-cli device list'"), result::toString);
		}
	}

	// ---- DHT -----------------------------------------------------------------------------------

	@Test
	void dhtIdNamesTheDeviceAndTheGateway(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Result id = cli.run("--json", "dht", "id");
		assertEquals(0, id.exitCode(), id::toString);
		assertEquals(id(clientDir(dir).resolve("device.identity")).toBase58String(), id.json().get("nodeId"));
		assertEquals(gatewayPeerId.toBase58String(), id.json().get("gatewayPeerId"));

		stub.reply("GET", GATEWAY + "/info", 200, "{\"nodeId\": \"" + nodeId + "\", \"peerId\": \"" + Id.random() +
				"\", \"version\": \"Stub/1\"}");
		Result mismatch = cli.run("dht", "id");
		assertEquals(7, mismatch.exitCode(), mismatch::toString);
		assertTrue(mismatch.err().contains("not the one the super node reports"), mismatch::toString);
	}

	@Test
	void valuesAreStoredAndUpdatedWithAKeyFile(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		String nothing = Json.toString(AnnounceResult.of(List.of()));
		stub.reply("POST", GATEWAY + "/values", 201, nothing);

		Result immutable = cli.run("dht", "store", "hello");
		assertEquals(0, immutable.exitCode(), immutable::toString);
		Id immutableId = Id.of(Hash.sha256("hello".getBytes(StandardCharsets.UTF_8)));
		assertTrue(immutable.out().contains("Stored immutable value " + immutableId), immutable::toString);

		Path keyFile = dir.resolve("greeting.key");
		Result created = cli.run("dht", "store", "--key", keyFile.toString(), "--persistent", "hi");
		assertEquals(0, created.exitCode(), created::toString);
		Id valueId = id(keyFile);
		assertTrue(created.out().contains("Stored mutable value " + valueId + ", sequence 0"), created::toString);
		Map<String, Object> first = stub.requests("POST", GATEWAY + "/values").get(1).json();
		assertEquals(true, first.get("persistent"));
		assertNull(first.get("expectedSequenceNumber"));

		// The gateway keeps sequence 0; storing again with the key file makes sequence 1.
		Signature.KeyPair key = IdentityFile.read(keyFile, "key");
		Value stored = Value.signedBuilder().key(key).data("hi").build();
		stub.reply("GET", GATEWAY + "/user/values/" + valueId, 200, Json.toString(stored));
		Result updated = cli.run("--json", "dht", "store", "--key", keyFile.toString(), "hello again");
		assertEquals(0, updated.exitCode(), updated::toString);
		assertEquals(1, updated.json().get("sequenceNumber"));
		assertEquals(0, stub.requests("POST", GATEWAY + "/values").get(2).json().get("expectedSequenceNumber"));

		Result recipient = cli.run("dht", "store", "--key", keyFile.toString(), "--recipient", Id.random().toBase58String(), "x");
		assertEquals(2, recipient.exitCode(), recipient::toString);
		assertTrue(recipient.err().contains("is not encrypted"), recipient::toString);

		Result noKey = cli.run("dht", "store", "--recipient", Id.random().toBase58String(), "x");
		assertEquals(2, noKey.exitCode(), noKey::toString);
	}

	@Test
	void peersAreAnnouncedAsTheDevice(@TempDir Path dir) {
		CliRunner cli = device(dir);
		stub.reply("POST", GATEWAY + "/peers", 201, Json.toString(AnnounceResult.of(List.of())));
		Id deviceId = id(clientDir(dir).resolve("device.identity"));

		Result announced = cli.run("--json", "dht", "announce", "https://example.com:8443", "--extra", "{\"name\": \"web\"}");
		assertEquals(0, announced.exitCode(), announced::toString);
		assertEquals(deviceId.toBase58String(), announced.json().get("id"));
		assertEquals("web", ((Map<?, ?>) announced.json().get("extra")).get("name"));

		Result extra = cli.run("dht", "announce", "https://example.com:8443", "--extra", "not json");
		assertEquals(2, extra.exitCode(), extra::toString);
	}

	@Test
	void keptValuesAndPeersAreListedAndRemoved(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Value value = Value.immutableBuilder().data("kept").build();
		Id peerId = Id.random();
		stub.reply("GET", GATEWAY + "/user/values", 200, "{\"page\": 1, \"pageSize\": 20, \"totalPages\": 1, " +
				"\"totalItems\": 1, \"items\": [" + Json.toString(value) + "]}");
		stub.reply("GET", GATEWAY + "/user/peers", 200, "{\"page\": 1, \"pageSize\": 20, \"totalPages\": 0, " +
				"\"totalItems\": 0, \"items\": []}");
		stub.handle("DELETE", GATEWAY + "/user/values/" + value.getId(), request -> new Reply(204, new byte[0], Map.of()));
		stub.handle("DELETE", GATEWAY + "/user/peers/" + peerId, request -> new Reply(404, new byte[0], Map.of()));

		Result values = cli.run("dht", "value", "list");
		assertEquals(0, values.exitCode(), values::toString);
		assertTrue(values.out().contains(value.getId().toBase58String()), values::toString);
		assertTrue(values.out().contains("immutable"), values::toString);

		Result peers = cli.run("dht", "peer", "list");
		assertEquals(0, peers.exitCode(), peers::toString);
		assertTrue(peers.out().contains("The gateway keeps no peers for you."), peers::toString);

		Result removed = cli.run("dht", "value", "remove", value.getId().toBase58String(), "--yes");
		assertEquals(0, removed.exitCode(), removed::toString);

		Result missing = cli.run("dht", "peer", "remove", peerId.toBase58String(), "--yes");
		assertEquals(4, missing.exitCode(), missing::toString);
		assertTrue(missing.err().contains("'boson-cli dht peer list'"), missing::toString);
	}

	// ---- Proxy ---------------------------------------------------------------------------------

	@Test
	void theProxyNeedsAnUpstreamItCanUse(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);

		Result none = cli.run("proxy", "start");
		assertEquals(2, none.exitCode(), none::toString);
		assertTrue(none.err().contains("config set proxyUpstream"), none::toString);

		Result invalid = cli.run("proxy", "start", "--upstream", "localhost");
		assertEquals(2, invalid.exitCode(), invalid::toString);
		assertTrue(invalid.err().contains("(from --upstream)"), invalid::toString);

		Result named = cli.run("proxy", "start", "--upstream", "tcp://127.0.0.1:22", "--name-access");
		assertEquals(2, named.exitCode(), named::toString);
		assertTrue(named.err().contains("needs an http service"), named::toString);

		// A bad value in the file is a configuration error, and the option overrides the file.
		Files.writeString(clientDir(dir).resolve("boson.yaml"), "proxyUpstream: localhost\nproxyNameAccess: true\n",
				StandardOpenOption.APPEND);
		Result file = cli.run("proxy", "start");
		assertEquals(3, file.exitCode(), file::toString);
		assertTrue(file.err().contains("proxyUpstream in"), file::toString);

		// Without a proxy service on the node, a good configuration gets as far as looking for one, and no
		// further: nothing here starts a tunnel.
		stub.nodeStatus(nodeId);
		Result overridden = cli.run("proxy", "start", "--upstream", "tcp://127.0.0.1:22", "--no-name-access");
		assertEquals(1, overridden.exitCode(), overridden::toString);
		assertTrue(overridden.err().contains("does not offer the Active Proxy service"), overridden::toString);
	}

	// ---- Account commands and this machine's device -----------------------------------------------

	@Test
	void registrationRegistersThisMachineAsTheFirstDevice(@TempDir Path dir) throws Exception {
		CliRunner cli = user(dir);
		// Tiny proof-of-work parameters, so that the test solves the challenge at once.
		stub.reply("GET", "/api/v1/client/users/challenge", 200, "{\"challenge\": \"" + base64(Random.randomBytes(16)) +
				"\", \"challengeSig\": \"" + base64(Random.randomBytes(64)) + "\", \"nonce\": \"" +
				base64(Random.randomBytes(32)) + "\", \"n\": 48, \"k\": 3, \"effort\": 0}");
		stub.reply("POST", "/api/v1/client/usersAndInitialDevice", 200, "{}");

		Result registered = cli.run("user", "register", "--name", "Alice", "--device-name", "Laptop");
		assertEquals(0, registered.exitCode(), registered::toString);
		Path deviceFile = clientDir(dir).resolve("device.identity");
		assertTrue(Files.exists(deviceFile));
		assertTrue(registered.out().contains("as its first device"), registered::toString);
		assertTrue(registered.out().contains("This machine now uses the node's services as this device."), registered::toString);

		Map<String, Object> body = stub.requests("POST", "/api/v1/client/usersAndInitialDevice").get(0).json();
		assertEquals(id(deviceFile).toBase58String(), body.get("deviceId"));
		assertEquals("Laptop", body.get("deviceName"));
		assertEquals("boson-cli", body.get("appName"));
		assertEquals("Alice", body.get("userName"));
	}

	@Test
	void registrationWithoutADeviceSaysHowToAddOne(@TempDir Path dir) {
		CliRunner cli = user(dir);
		stub.reply("GET", "/api/v1/client/users/challenge", 200, "{\"challenge\": \"" + base64(Random.randomBytes(16)) +
				"\", \"challengeSig\": \"" + base64(Random.randomBytes(64)) + "\", \"nonce\": \"" +
				base64(Random.randomBytes(32)) + "\", \"n\": 48, \"k\": 3, \"effort\": 0}");
		stub.reply("POST", "/api/v1/client/users", 200, "{}");

		Result registered = cli.run("user", "register");
		assertEquals(0, registered.exitCode(), registered::toString);
		assertTrue(registered.out().contains("'boson-cli device add --name <name>'"), registered::toString);
		assertFalse(Files.exists(clientDir(dir).resolve("device.identity")));
	}

	@Test
	void removingThisMachinesDeviceIsPointedOut(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id deviceId = id(clientDir(dir).resolve("device.identity"));
		Id otherId = Id.random();
		stub.reply("POST", "/api/v1/client/devices/" + deviceId + "/remove", 200, "");
		stub.reply("POST", "/api/v1/client/devices/" + otherId + "/remove", 200, "");

		Result other = cli.run("device", "remove", otherId.toBase58String(), "--yes");
		assertEquals(0, other.exitCode(), other::toString);
		assertFalse(other.out().contains("That was this machine's device"), other::toString);

		Result mine = cli.run("device", "remove", deviceId.toBase58String(), "--yes");
		assertEquals(0, mine.exitCode(), mine::toString);
		assertTrue(mine.out().contains("That was this machine's device"), mine::toString);
	}

	// ---- More of the Ion Store -----------------------------------------------------------------

	@Test
	void aRetrievedObjectIsNamedAfterItself(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		assertEquals(0, cli.run("--url", stub.url(), "config", "init").exitCode());
		Id objectId = Id.random();
		byte[] content = "named".getBytes(StandardCharsets.UTF_8);
		String name = "boson-cli-test-" + objectId + ".txt";
		// One path serves the metadata, asked for as JSON, and the content.
		stub.handle("GET", ION + "/objects/" + objectId, request -> "application/json".equals(request.header("Accept")) ?
				Reply.json(200, objectJson(objectId, ionStorePeerId, content, name)) :
				new Reply(200, content, Map.of("Ion-Content-Id", Id.of(Hash.sha256(content)).toBase58String())));

		// The working directory is the test's own, so the file is removed whatever happens.
		Path local = Path.of(name);
		Path remote = Path.of(objectId.toBase58String());
		try {
			Result named = cli.run("ionstore", "get", objectId.toBase58String());
			assertEquals(0, named.exitCode(), named::toString);
			assertArrayEquals(content, Files.readAllBytes(local));

			// An object on another node has no metadata to ask for: it is named by its id.
			Id otherPeer = Id.random();
			stub.handle("GET", ION + "/objects/" + otherPeer + "/" + objectId, request ->
					new Reply(200, content, Map.of("Ion-Content-Id", Id.of(Hash.sha256(content)).toBase58String())));
			Result byId = cli.run("ionstore", "get", "ions://" + otherPeer + "/" + objectId);
			assertEquals(0, byId.exitCode(), byId::toString);
			assertArrayEquals(content, Files.readAllBytes(remote));
		} finally {
			Files.deleteIfExists(local);
			Files.deleteIfExists(remote);
		}
	}

	@Test
	void anEncryptedObjectIsReadWithItsKeyOnly(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		Id objectId = Id.random();
		byte[] plain = "a secret".getBytes(StandardCharsets.UTF_8);
		stub.handle("POST", ION + "/objects", request -> Reply.json(201,
				"{\"id\": \"" + objectId + "\", \"contentId\": \"" + Id.of(Hash.sha256(request.bytes())) +
						"\", \"size\": " + request.bytes().length + ", \"encrypted\": true, \"expireAt\": 0, " +
						"\"uri\": \"ions://" + ionStorePeerId + "/" + objectId + "\"}"));

		Path file = dir.resolve("secret.txt");
		Files.write(file, plain);
		Result put = cli.run("--json", "ionstore", "put", file.toString(), "--encrypt");
		assertEquals(0, put.exitCode(), put::toString);
		String key = (String) put.json().get("key");

		// The service keeps the ciphertext and its descriptor, and serves them back.
		StubDirector.Request stored = stub.requests("POST", ION + "/objects").get(0);
		byte[] cipher = stored.bytes();
		assertFalse(Arrays.equals(plain, cipher));
		stub.handle("GET", ION + "/objects/" + objectId, request -> new Reply(200, cipher, Map.of(
				"Ion-Content-Id", Id.of(Hash.sha256(cipher)).toBase58String(),
				"Ion-Encrypted", "true",
				"Ion-Encryption", stored.header("Ion-Encryption"))));

		Result decrypted = cli.run("ionstore", "get", objectId.toBase58String(), "-o", "-", "--key", key);
		assertEquals(0, decrypted.exitCode(), decrypted::toString);
		assertArrayEquals(plain, decrypted.outBytes());

		Result raw = cli.run("ionstore", "get", objectId.toBase58String(), "-o", "-", "--raw");
		assertEquals(0, raw.exitCode(), raw::toString);
		assertArrayEquals(cipher, raw.outBytes());

		Result noKey = cli.run("ionstore", "get", objectId.toBase58String(), "-o", dir.resolve("x").toString());
		assertEquals(1, noKey.exitCode(), noKey::toString);
		assertTrue(noKey.err().contains("is encrypted"), noKey::toString);
		assertTrue(noKey.err().contains("--key <key>"), noKey::toString);
		assertFalse(Files.exists(dir.resolve("x")));

		Result wrongKey = cli.run("ionstore", "get", objectId.toBase58String(), "-o", dir.resolve("y").toString(),
				"--key", Base58.encode(Random.randomBytes(32)));
		assertEquals(1, wrongKey.exitCode(), wrongKey::toString);
		assertTrue(wrongKey.err().contains("cannot be decrypted with this key"), wrongKey::toString);
		assertFalse(Files.exists(dir.resolve("y")));

		Result both = cli.run("ionstore", "get", objectId.toBase58String(), "-o", "-", "--raw", "--key", key);
		assertEquals(2, both.exitCode(), both::toString);

		// A key for an object that is not encrypted is refused too.
		byte[] open = "open".getBytes(StandardCharsets.UTF_8);
		stub.handle("GET", ION + "/objects/" + objectId, request ->
				new Reply(200, open, Map.of("Ion-Content-Id", Id.of(Hash.sha256(open)).toBase58String())));
		Result notEncrypted = cli.run("ionstore", "get", objectId.toBase58String(), "-o", "-", "--key", key);
		assertEquals(2, notEncrypted.exitCode(), notEncrypted::toString);
		assertTrue(notEncrypted.err().contains("is not encrypted"), notEncrypted::toString);
	}

	@Test
	void everyObjectIsListedPageByPage(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id first = Id.random();
		Id second = Id.random();
		byte[] content = "x".getBytes(StandardCharsets.UTF_8);
		stub.handle("GET", ION + "/objects", request -> {
			boolean one = request.query().contains("page=1&");
			return Reply.json(200, "{\"page\": " + (one ? 1 : 2) + ", \"pageSize\": 1, \"totalItems\": 2, \"items\": [" +
					objectJson(one ? first : second, ionStorePeerId, content, one ? "first" : "second") + "]}");
		});

		Result all = cli.run("ionstore", "list", "--all");
		assertEquals(0, all.exitCode(), all::toString);
		assertTrue(all.out().contains(first.toBase58String()) && all.out().contains(second.toBase58String()), all::toString);
		assertEquals(2, stub.requests("GET", ION + "/objects").size());

		Result page = cli.run("ionstore", "list", "--page", "2", "--page-size", "1");
		assertEquals(0, page.exitCode(), page::toString);
		assertTrue(page.out().contains(second.toBase58String()), page::toString);
		assertTrue(page.out().contains("Page 2 of 2, 2 objects in all."), page::toString);

		Result mixed = cli.run("ionstore", "list", "--all", "--page", "2");
		assertEquals(2, mixed.exitCode(), mixed::toString);
	}

	// ---- Finding on the DHT --------------------------------------------------------------------

	@Test
	void nodesAreFound(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id found = Id.random();
		stub.reply("GET", GATEWAY + "/nodes/" + found, 200, Json.toString(NodeInfo.of(found, "192.0.2.7", 39001)));

		Result node = cli.run("dht", "find", "node", found.toBase58String(), "--mode", "arbitrary");
		assertEquals(0, node.exitCode(), node::toString);
		assertTrue(node.out().contains("192.0.2.7:39001"), node::toString);
		assertEquals("mode=arbitrary", stub.requests("GET", GATEWAY + "/nodes/" + found).get(0).query());

		Result json = cli.run("--json", "dht", "find", "node", found.toBase58String());
		assertEquals(0, json.exitCode(), json::toString);
		assertEquals(List.of("192.0.2.7:39001"), json.json().get("addresses"));

		Result missing = cli.run("dht", "find", "node", Id.random().toBase58String());
		assertEquals(4, missing.exitCode(), missing::toString);

		Result mode = cli.run("dht", "find", "node", found.toBase58String(), "--mode", "eventually");
		assertEquals(2, mode.exitCode(), mode::toString);
	}

	@Test
	void valuesAreFoundAndDecryptedForThisDevice(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		Id deviceId = id(clientDir(dir).resolve("device.identity"));
		Value plain = Value.signedBuilder().data("in the open").build();
		Value secret = Value.encryptedBuilder().recipient(deviceId).data("for this device").build();
		Value other = Value.encryptedBuilder().recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
				.data("for someone else").build();
		for (Value value : List.of(plain, secret, other))
			stub.reply("GET", GATEWAY + "/values/" + value.getId(), 200, Json.toString(value));

		Result found = cli.run("dht", "find", "value", plain.getId().toBase58String(), "--sequence", "0",
				"-o", dir.resolve("data").toString());
		assertEquals(0, found.exitCode(), found::toString);
		assertTrue(found.out().contains("in the open"), found::toString);
		assertEquals("in the open", Files.readString(dir.resolve("data")));
		assertTrue(stub.requests("GET", GATEWAY + "/values/" + plain.getId()).get(0).query().contains("seq=0"));

		Result decrypted = cli.run("--json", "dht", "find", "value", secret.getId().toBase58String());
		assertEquals(0, decrypted.exitCode(), decrypted::toString);
		assertEquals("for this device", decrypted.json().get("decryptedText"));

		Result sealed = cli.run("dht", "find", "value", other.getId().toBase58String());
		assertEquals(0, sealed.exitCode(), sealed::toString);
		assertTrue(sealed.out().contains("encrypted for the recipient"), sealed::toString);
		assertFalse(sealed.out().contains("someone else"), sealed::toString);

		Result exists = cli.run("dht", "find", "value", plain.getId().toBase58String(), "-o", dir.resolve("data").toString());
		assertEquals(1, exists.exitCode(), exists::toString);

		Result missing = cli.run("dht", "find", "value", Id.random().toBase58String());
		assertEquals(4, missing.exitCode(), missing::toString);

		Result sequence = cli.run("dht", "find", "value", plain.getId().toBase58String(), "--sequence", "-2");
		assertEquals(2, sequence.exitCode(), sequence::toString);
	}

	@Test
	void peersAreFound(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Signature.KeyPair key = Signature.KeyPair.random();
		PeerInfo first = PeerInfo.builder().key(key).endpoint("https://a.example.com").build();
		PeerInfo second = PeerInfo.builder().key(key).fingerprint(2).endpoint("https://b.example.com").build();
		Id peerId = first.getId();
		stub.reply("GET", GATEWAY + "/peers/" + peerId, 200, "[" + Json.toString(first) + ", " + Json.toString(second) + "]");

		Result found = cli.run("dht", "find", "peer", peerId.toBase58String(), "--count", "2");
		assertEquals(0, found.exitCode(), found::toString);
		assertTrue(found.out().contains("https://a.example.com") && found.out().contains("https://b.example.com"), found::toString);
		assertTrue(stub.requests("GET", GATEWAY + "/peers/" + peerId).get(0).query().contains("count=2"));

		Result json = cli.run("--json", "dht", "find", "peer", peerId.toBase58String());
		assertEquals(0, json.exitCode(), json::toString);
		assertEquals(2, json.jsonArray().size());

		Result missing = cli.run("dht", "find", "peer", Id.random().toBase58String());
		assertEquals(4, missing.exitCode(), missing::toString);

		Result count = cli.run("dht", "find", "peer", peerId.toBase58String(), "--count", "0");
		assertEquals(2, count.exitCode(), count::toString);
	}

	// ---- More of storing and announcing --------------------------------------------------------

	@Test
	void valuesAreStoredFromAFileAndForARecipient(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		stub.reply("POST", GATEWAY + "/values", 201, Json.toString(AnnounceResult.of(List.of())));
		Path data = dir.resolve("data.bin");
		Files.write(data, new byte[] { 0, 1, 2 });

		Result fromFile = cli.run("dht", "store", "--file", data.toString());
		assertEquals(0, fromFile.exitCode(), fromFile::toString);
		Value immutable = requestedValue(0);
		assertArrayEquals(new byte[] { 0, 1, 2 }, immutable.getData());

		Id recipient = Id.of(Signature.KeyPair.random().publicKey().bytes());
		Path keyFile = dir.resolve("secret.key");
		Result forRecipient = cli.run("dht", "store", "--key", keyFile.toString(), "--recipient",
				recipient.toBase58String(), "only for you");
		assertEquals(0, forRecipient.exitCode(), forRecipient::toString);
		assertTrue(forRecipient.out().contains("Stored encrypted value " + id(keyFile)), forRecipient::toString);
		Value encrypted = requestedValue(1);
		assertTrue(encrypted.isEncrypted());
		assertEquals(recipient, encrypted.getRecipient());
		assertFalse(Arrays.equals("only for you".getBytes(StandardCharsets.UTF_8), encrypted.getData()));

		// An update keeps the recipient without naming it again, and refuses another one.
		stub.reply("GET", GATEWAY + "/user/values/" + id(keyFile), 200, Json.toString(encrypted));
		Result update = cli.run("dht", "store", "--key", keyFile.toString(), "still only for you");
		assertEquals(0, update.exitCode(), update::toString);
		assertEquals(recipient, requestedValue(2).getRecipient());
		assertEquals(1, requestedValue(2).getSequenceNumber());

		Result another = cli.run("dht", "store", "--key", keyFile.toString(), "--recipient",
				Id.of(Signature.KeyPair.random().publicKey().bytes()).toBase58String(), "x");
		assertEquals(2, another.exitCode(), another::toString);
		assertTrue(another.err().contains("keeps its recipient"), another::toString);

		Result both = cli.run("dht", "store", "--file", data.toString(), "text");
		assertEquals(2, both.exitCode(), both::toString);
		Result neither = cli.run("dht", "store");
		assertEquals(2, neither.exitCode(), neither::toString);
		Result empty = cli.run("dht", "store", "");
		assertEquals(2, empty.exitCode(), empty::toString);
	}

	private Value requestedValue(int index) {
		Map<String, Object> body = stub.requests("POST", GATEWAY + "/values").get(index).json();
		return Json.objectMapper().convertValue(body.get("value"), Value.class);
	}

	private PeerInfo requestedPeer(int index) {
		Map<String, Object> body = stub.requests("POST", GATEWAY + "/peers").get(index).json();
		return Json.objectMapper().convertValue(body.get("peer"), PeerInfo.class);
	}

	@Test
	void peersAreUpdatedSignedAndKept(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		stub.reply("POST", GATEWAY + "/peers", 201, Json.toString(AnnounceResult.of(List.of())));
		Signature.KeyPair deviceKey = IdentityFile.read(clientDir(dir).resolve("device.identity"), "key");
		Id deviceId = Id.of(deviceKey.publicKey().bytes());

		// The gateway keeps sequence 0 of this fingerprint; announcing again makes sequence 1.
		PeerInfo kept = PeerInfo.builder().key(deviceKey).fingerprint(7).endpoint("https://old.example.com").build();
		stub.reply("GET", GATEWAY + "/user/peers/" + deviceId + "/7", 200, Json.toString(kept));
		Result updated = cli.run("dht", "announce", "https://new.example.com", "--fingerprint", "7", "--persistent",
				"--authenticated");
		assertEquals(0, updated.exitCode(), updated::toString);
		assertTrue(updated.out().contains("fingerprint 7"), updated::toString);
		assertTrue(updated.out().contains("'boson-cli dht peer remove " + deviceId + " --fingerprint 7'"), updated::toString);
		Map<String, Object> body = stub.requests("POST", GATEWAY + "/peers").get(0).json();
		assertEquals(true, body.get("persistent"));
		assertEquals(0, body.get("expectedSequenceNumber"));
		PeerInfo peer = requestedPeer(0);
		assertEquals(1, peer.getSequenceNumber());
		assertEquals(7, peer.getFingerprint());
		assertEquals(deviceId, peer.getNodeId());
		assertTrue(peer.isValid());

		// Once signed by this device, an update stays signed without being asked.
		stub.reply("GET", GATEWAY + "/user/peers/" + deviceId + "/7", 200, Json.toString(peer));
		assertEquals(0, cli.run("dht", "announce", "https://newer.example.com", "--fingerprint", "7").exitCode());
		assertEquals(deviceId, requestedPeer(1).getNodeId());

		// A peer another node signed cannot be updated from here.
		Signature.KeyPair peerKey = Signature.KeyPair.random();
		Path keyFile = dir.resolve("web.key");
		IdentityFile.create(keyFile, peerKey);
		PeerInfo signedElsewhere = PeerInfo.builder().key(peerKey).endpoint("https://web.example.com")
				.node(new CryptoIdentity(Signature.KeyPair.random())).build();
		stub.reply("GET", GATEWAY + "/user/peers/" + signedElsewhere.getId() + "/0", 200, Json.toString(signedElsewhere));
		Result refused = cli.run("dht", "announce", "https://web.example.com", "--key", keyFile.toString());
		assertEquals(2, refused.exitCode(), refused::toString);
		assertTrue(refused.err().contains("which this device is not"), refused::toString);

		// A new key file is created, and names the peer.
		Path newKey = dir.resolve("new.key");
		Result created = cli.run("dht", "announce", "https://example.org", "--key", newKey.toString());
		assertEquals(0, created.exitCode(), created::toString);
		assertEquals(id(newKey), requestedPeer(2).getId());
		assertEquals(0, requestedPeer(2).getSequenceNumber());
		assertTrue(created.out().contains("Its key is in " + newKey), created::toString);
	}

	// ---- What the gateway keeps ----------------------------------------------------------------

	@Test
	void keptValuesAndPeersAreShown(@TempDir Path dir) {
		CliRunner cli = device(dir);
		Id deviceId = id(clientDir(dir).resolve("device.identity"));
		Value value = Value.encryptedBuilder().recipient(deviceId).data("kept for this device").build();
		Signature.KeyPair key = Signature.KeyPair.random();
		PeerInfo first = PeerInfo.builder().key(key).endpoint("https://a.example.com").build();
		PeerInfo second = PeerInfo.builder().key(key).fingerprint(3).endpoint("https://b.example.com")
				.extra(Map.of("name", "web")).build();
		Id peerId = first.getId();
		stub.reply("GET", GATEWAY + "/user/values/" + value.getId(), 200, Json.toString(value));
		stub.reply("GET", GATEWAY + "/user/peers/" + peerId, 200, "[" + Json.toString(first) + ", " + Json.toString(second) + "]");
		stub.reply("GET", GATEWAY + "/user/peers/" + peerId + "/3", 200, Json.toString(second));
		stub.handle("DELETE", GATEWAY + "/user/peers/" + peerId + "/3", request -> new Reply(204, new byte[0], Map.of()));

		Result shown = cli.run("dht", "value", "show", value.getId().toBase58String());
		assertEquals(0, shown.exitCode(), shown::toString);
		assertTrue(shown.out().contains("kept for this device"), shown::toString);

		Result notKept = cli.run("dht", "value", "show", Id.random().toBase58String());
		assertEquals(4, notKept.exitCode(), notKept::toString);
		assertTrue(notKept.err().contains("'boson-cli dht value list'"), notKept::toString);

		Result peers = cli.run("dht", "peer", "show", peerId.toBase58String());
		assertEquals(0, peers.exitCode(), peers::toString);
		assertTrue(peers.out().contains("https://a.example.com") && peers.out().contains("https://b.example.com"), peers::toString);
		assertTrue(peers.out().contains("name=web"), peers::toString);

		Result one = cli.run("--json", "dht", "peer", "show", peerId.toBase58String(), "--fingerprint", "3");
		assertEquals(0, one.exitCode(), one::toString);
		assertEquals(1, one.jsonArray().size());

		Result removed = cli.run("dht", "peer", "remove", peerId.toBase58String(), "--fingerprint", "3", "--yes");
		assertEquals(0, removed.exitCode(), removed::toString);
		assertTrue(removed.out().contains("(fingerprint 3)"), removed::toString);

		Result unconfirmed = cli.run("dht", "value", "remove", value.getId().toBase58String());
		assertEquals(2, unconfirmed.exitCode(), unconfirmed::toString);
		assertTrue(stub.requests("DELETE", GATEWAY + "/user/values/" + value.getId()).isEmpty());
	}

	// ---- The running proxy ---------------------------------------------------------------------

	@Test
	void aRunningProxyHoldsItsDeviceAndStops(@TempDir Path dir) throws Exception {
		CliRunner cli = device(dir);
		// Nothing listens at the proxy's port, so the proxy starts and keeps trying to connect.
		AtomicReference<Result> first = new AtomicReference<>();
		Thread running = new Thread(() -> first.set(cli.run("proxy", "start", "--upstream", "localhost:8080")));
		running.start();
		try {
			Path lock = dir.resolve("state").resolve("boson").resolve("client")
					.resolve("active-proxy-" + id(clientDir(dir).resolve("device.identity")) + ".lock");
			long deadline = System.currentTimeMillis() + 30_000;
			while (!Files.exists(lock) && running.isAlive() && System.currentTimeMillis() < deadline)
				Thread.sleep(50);
			assertTrue(Files.exists(lock), () -> "the running proxy holds its lock: " + first.get());

			Result second = cli.run("proxy", "start", "--upstream", "localhost:8080");
			assertEquals(6, second.exitCode(), second::toString);
			assertTrue(second.err().contains("A proxy is already running as device"), second::toString);
		} finally {
			// Interrupted, the command stops the proxy as Ctrl+C would, and returns.
			running.interrupt();
			running.join(60_000);
		}

		assertFalse(running.isAlive(), "the proxy stopped");
		Result result = first.get();
		assertEquals(0, result.exitCode(), result::toString);
		assertTrue(result.err().contains("Exposing http://localhost:8080 through the Active Proxy at 127.0.0.1:1"), result::toString);
		assertTrue(result.err().contains("Stopped."), result::toString);
	}

	private static String base64(byte[] bytes) {
		return Json.BASE64_ENCODER.encodeToString(bytes);
	}
}
