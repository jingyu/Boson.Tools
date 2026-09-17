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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.testing.CliRunner;
import io.bosonnetwork.cli.common.testing.CliRunner.Result;
import io.bosonnetwork.cli.testing.StubDirector;
import io.bosonnetwork.cli.testing.StubDirector.Reply;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.json.Json;

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
	private final Id proxyPeerId = Id.random();
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
}
