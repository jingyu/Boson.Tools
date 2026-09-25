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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.testing.CliRunner;
import io.bosonnetwork.cli.common.testing.CliRunner.Result;
import io.bosonnetwork.cli.testing.StubDirector;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;

/**
 * Tests of {@code boson-cli}, run in process against a stub Director.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BosonCliTests {
	private final Id nodeId = Id.random();
	private StubDirector director;

	@BeforeAll
	void startDirector() throws Exception {
		director = StubDirector.start();
	}

	@AfterAll
	void stopDirector() throws Exception {
		director.close();
	}

	@BeforeEach
	void resetDirector() {
		director.reset();
		director.nodeId(nodeId);
	}

	private static CliRunner cli(Path configDir) {
		return new CliRunner(BosonCli::new, configDir);
	}

	private static Path configFile(Path configDir) {
		return configDir.resolve("boson").resolve("client").resolve("boson.yaml");
	}

	private static Path identityFile(Path configDir) {
		return configDir.resolve("boson").resolve("client").resolve("user.identity");
	}

	// A user with a configuration pointing at the stub and an identity.
	private CliRunner user(Path configDir) {
		CliRunner cli = cli(configDir);
		assertEquals(0, cli.run("--url", director.url(), "--node-id", nodeId.toBase58String(), "config", "init").exitCode());
		assertEquals(0, cli.run("identity", "create").exitCode());
		return cli;
	}

	private static String profileJson(Id userId, String name) {
		return "{\"id\": \"" + userId + "\", \"name\": \"" + name + "\", \"planName\": \"Free\", \"admin\": false, " +
				"\"createdAt\": 1700000000000, \"updatedAt\": 1700000000000, \"passphraseProtected\": false}";
	}

	// ---- Help ----------------------------------------------------------------------------------

	@Test
	void helpListsTheCommandsAndTheGlobalOptions(@TempDir Path dir) {
		Result root = cli(dir).run("--help");
		assertEquals(0, root.exitCode(), root::toString);
		assertTrue(root.out().contains("Commands:"), root::toString);
		assertTrue(root.out().contains("register"), root::toString);
		assertTrue(root.out().contains("Global options, taken by every command:"), root::toString);

		Result group = cli(dir).run("user", "--help");
		assertEquals(0, group.exitCode(), group::toString);
		assertTrue(group.out().contains("passphrase"), group::toString);
		assertTrue(group.out().contains("Global options: --config"), group::toString);

		Result missing = cli(dir).run("user");
		assertEquals(2, missing.exitCode(), missing::toString);
		assertTrue(missing.err().contains("needs one of the commands"), missing::toString);

		Result typo = cli(dir).run("usr");
		assertEquals(2, typo.exitCode(), typo::toString);
		assertTrue(typo.err().contains("user"), typo::toString);
	}

	@Test
	void everyCommandHasHelp(@TempDir Path dir) {
		CliEnvironment environment = new CliEnvironment(Map.of(), dir, System.in, null,
				new java.io.PrintWriter(java.io.Writer.nullWriter()), new java.io.PrintWriter(java.io.Writer.nullWriter()));
		List<String[]> commands = new ArrayList<>();
		collect(new CommandLine(new BosonCli(environment)), new ArrayList<>(), commands);
		assertTrue(commands.size() > 20, "commands: " + commands.size());

		for (String[] command : commands) {
			List<String> args = new ArrayList<>(List.of(command));
			args.add("--help");
			Result result = cli(dir).run(args.toArray(new String[0]));
			assertEquals(0, result.exitCode(), () -> String.join(" ", command) + ": " + result);
			assertFalse(result.out().isBlank(), () -> String.join(" ", command) + " has no help");
		}
	}

	static void collect(CommandLine commandLine, List<String> path, List<String[]> commands) {
		for (var entry : commandLine.getSubcommands().entrySet()) {
			if (entry.getKey().equals("help"))
				continue;
			List<String> sub = new ArrayList<>(path);
			sub.add(entry.getKey());
			assertTrue(entry.getValue().getCommandSpec().usageMessage().description().length > 0,
					() -> String.join(" ", sub) + " has no description");
			commands.add(sub.toArray(new String[0]));
			collect(entry.getValue(), sub, commands);
		}
	}

	@Test
	void globalOptionsWorkBeforeAndAfterTheCommand(@TempDir Path dir) {
		Result before = cli(dir).run("--url", director.url(), "--json", "node", "id");
		assertEquals(0, before.exitCode(), before::toString);
		assertEquals(nodeId.toBase58String(), before.json().get("nodeId"));

		Result after = cli(dir).run("node", "id", "--url", director.url(), "--json");
		assertEquals(0, after.exitCode(), after::toString);
		assertEquals(nodeId.toBase58String(), after.json().get("nodeId"));
	}

	// ---- Configuration -------------------------------------------------------------------------

	@Test
	void configEditsKeepTheComments(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		Result init = cli.run("--url", director.url(), "config", "init");
		assertEquals(0, init.exitCode(), init::toString);

		String created = Files.readString(configFile(dir));
		assertTrue(created.contains("url: " + director.url()), created);
		assertTrue(created.contains("# nodeId:"), created);

		assertEquals(0, cli.run("config", "set", "nodeId", nodeId.toBase58String()).exitCode());
		assertEquals(0, cli.run("config", "set", "resolve", "127.0.0.1").exitCode());
		String edited = Files.readString(configFile(dir));
		assertTrue(edited.contains("nodeId: " + nodeId.toBase58String()), edited);
		assertFalse(edited.contains("# nodeId:"), edited);
		assertTrue(edited.contains("resolve: 127.0.0.1"), edited);
		assertTrue(edited.contains("# The super node's id."), edited);

		Result unset = cli.run("config", "unset", "resolve");
		assertEquals(0, unset.exitCode(), unset::toString);
		assertFalse(Files.readString(configFile(dir)).lines().anyMatch(line -> line.startsWith("resolve:")));

		Result show = cli.run("--json", "config", "show");
		assertEquals(0, show.exitCode(), show::toString);
		assertEquals(director.url(), ((Map<?, ?>) show.json().get("url")).get("value"));
		assertEquals("configuration file", ((Map<?, ?>) show.json().get("url")).get("from"));

		Result again = cli.run("config", "init");
		assertEquals(1, again.exitCode(), again::toString);
		assertTrue(again.err().contains("already exists"), again::toString);
		assertEquals(edited.replace("resolve: 127.0.0.1" + System.lineSeparator(), ""), Files.readString(configFile(dir)));
	}

	@Test
	void configRefusesBadSettings(@TempDir Path dir) {
		CliRunner cli = cli(dir);

		Result privateKey = cli.run("config", "set", "privateKey", "3Jx");
		assertEquals(2, privateKey.exitCode(), privateKey::toString);
		assertTrue(privateKey.err().contains("identity import"), privateKey::toString);

		Result typo = cli.run("config", "set", "nodeid", "x");
		assertEquals(2, typo.exitCode(), typo::toString);
		assertTrue(typo.err().contains("Did you mean nodeId?"), typo::toString);

		Result url = cli.run("config", "set", "url", "ftp://node.example.com");
		assertEquals(2, url.exitCode(), url::toString);

		Result resolve = cli.run("config", "set", "resolve", "node.example.com");
		assertEquals(2, resolve.exitCode(), resolve::toString);
		assertFalse(Files.exists(configFile(dir)));
	}

	@Test
	void theRenamedRootUserKeyIsNamed(@TempDir Path dir) throws Exception {
		Files.createDirectories(configFile(dir).getParent());
		Files.writeString(configFile(dir), "url: " + director.url() + "\nrootUserKey: 3Jx\n");

		Result result = cli(dir).run("user", "show");
		assertEquals(3, result.exitCode(), result::toString);
		assertTrue(result.err().contains("now called privateKey"), result::toString);
		// Names the migration, pointed at this very file rather than the wizard's default path.
		assertTrue(result.err().contains("boson.sh --setup --migrate --file " + configFile(dir)), result::toString);
	}

	@Test
	void aMissingUrlIsAConfigurationError(@TempDir Path dir) {
		Result result = cli(dir).run("user", "show");
		assertEquals(3, result.exitCode(), result::toString);
		assertTrue(result.err().contains("No Director URL is configured."), result::toString);
		assertTrue(result.err().contains("Hint: Set it with 'boson-cli config set url"), result::toString);
	}

	// ---- Identity ------------------------------------------------------------------------------

	@Test
	void identityCreateNeverOverwrites(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		Result created = cli.run("identity", "create");
		assertEquals(0, created.exitCode(), created::toString);
		Path file = identityFile(dir);
		assertTrue(Files.exists(file));
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

		String key = Files.readString(file);
		Result again = cli.run("identity", "create");
		assertEquals(1, again.exitCode(), again::toString);
		assertTrue(again.err().contains("already exists"), again::toString);
		assertEquals(key, Files.readString(file));

		Result show = cli.run("--json", "identity", "show");
		assertEquals(0, show.exitCode(), show::toString);
		Signature.KeyPair keyPair = IdentityFile.read(file, "identity file");
		assertEquals(Id.of(keyPair.publicKey().bytes()).toBase58String(), show.json().get("id"));
	}

	@Test
	void identityImportReadsTheKeyFromStandardInput(@TempDir Path dir) {
		Signature.KeyPair key = Signature.KeyPair.random();
		Result imported = cli(dir).runWithInput(Base58.encode(key.privateKey().bytes()) + "\n", "identity", "import");
		assertEquals(0, imported.exitCode(), imported::toString);
		assertEquals(key.publicKey(), IdentityFile.read(identityFile(dir), "identity file").publicKey());

		Result invalid = cli(dir).runWithInput("not a key\n", "--identity", dir.resolve("other.identity").toString(),
				"identity", "import");
		assertEquals(2, invalid.exitCode(), invalid::toString);
		assertFalse(Files.exists(dir.resolve("other.identity")));
	}

	@Test
	void aMissingIdentityIsAConfigurationError(@TempDir Path dir) {
		Result result = cli(dir).run("--url", director.url(), "user", "show");
		assertEquals(3, result.exitCode(), result::toString);
		assertTrue(result.err().contains("'boson-cli identity create'"), result::toString);
	}

	// ---- Util ----------------------------------------------------------------------------------

	@Test
	void keysAreGeneratedCheckedAndDerived(@TempDir Path dir) {
		CliRunner cli = cli(dir);

		Result keygen = cli.run("--json", "util", "keygen");
		assertEquals(0, keygen.exitCode(), keygen::toString);
		Map<?, ?> signature = (Map<?, ?>) keygen.json().get("signature");
		String privateKey = (String) signature.get("privateKey");
		assertEquals(64, Base58.decode(privateKey).length);

		Result valid = cli.run("util", "check-key", "--private", privateKey);
		assertEquals(0, valid.exitCode(), valid::toString);
		assertEquals("Valid Ed25519 private key.", valid.out().strip());

		Result invalid = cli.run("util", "check-key", "--public", Base58.encode(new byte[31]));
		assertEquals(1, invalid.exitCode(), invalid::toString);
		assertEquals("Invalid Ed25519 public key: expected 32 bytes, got 31.", invalid.out().strip());

		Result publicKey = cli.run("util", "public-key", privateKey);
		assertEquals(0, publicKey.exitCode(), publicKey::toString);
		assertEquals(signature.get("publicKey"), publicKey.out().strip());

		Result seed = cli.run("util", "public-key", Base58.encode(new byte[32]));
		assertEquals(2, seed.exitCode(), seed::toString);
		assertTrue(seed.err().contains("32-byte seed"), seed::toString);

		Result hex = cli.run("util", "base58-to-hex", Base58.encode(new byte[] { 1, 2 }));
		assertEquals("0x0102", hex.out().strip());
		Result base58 = cli.run("util", "hex-to-base58", "0x0102");
		assertEquals(Base58.encode(new byte[] { 1, 2 }), base58.out().strip());
	}

	@Test
	void signSignsOnlyNonces(@TempDir Path dir) throws Exception {
		CliRunner cli = cli(dir);
		assertEquals(0, cli.run("identity", "create").exitCode());
		Signature.KeyPair key = IdentityFile.read(identityFile(dir), "identity file");

		byte[] nonce = Random.randomBytes(32);
		Result signed = cli.run("--json", "util", "sign", Base58.encode(nonce));
		assertEquals(0, signed.exitCode(), signed::toString);
		assertEquals(Base58.encode(key.publicKey().bytes()), signed.json().get("publicKey"));
		assertTrue(key.publicKey().verify(nonce, Base58.decode((String) signed.json().get("signature"))));

		Result tooLong = cli.run("util", "sign", Base58.encode(Random.randomBytes(64)));
		assertEquals(2, tooLong.exitCode(), tooLong::toString);
	}

	// ---- Talking to the Director ---------------------------------------------------------------

	@Test
	void userShowPrintsTheProfile(@TempDir Path dir) throws Exception {
		CliRunner cli = user(dir);
		Id userId = Id.of(IdentityFile.read(identityFile(dir), "identity file").publicKey().bytes());
		director.reply("GET", "/api/v1/client/profile", 200, profileJson(userId, "Alice"));

		Result human = cli.run("user", "show");
		assertEquals(0, human.exitCode(), human::toString);
		assertTrue(human.out().contains("Name:"), human::toString);
		assertTrue(human.out().contains("Alice"), human::toString);

		Result json = cli.run("user", "show", "--json");
		assertEquals(0, json.exitCode(), json::toString);
		assertEquals("Alice", json.json().get("name"));
		assertEquals(userId.toBase58String(), json.json().get("id"));
	}

	@Test
	void anUnacceptedIdentityIsExplained(@TempDir Path dir) {
		CliRunner cli = user(dir);
		director.reply("GET", "/api/v1/client/profile", 401, "Unauthorized - unknown user");

		Result result = cli.run("user", "show");
		assertEquals(5, result.exitCode(), result::toString);
		assertTrue(result.err().contains("The Director did not accept the user identity"), result::toString);
		assertTrue(result.err().contains("Hint: Check that this user is registered"), result::toString);
	}

	@Test
	void anUnreachableDirectorIsExplained(@TempDir Path dir) {
		Result result = cli(dir).run("--url", "http://127.0.0.1:1", "node", "id");
		assertEquals(7, result.exitCode(), result::toString);
		assertTrue(result.err().contains("Cannot connect to http://127.0.0.1:1"), result::toString);
		assertFalse(result.err().contains("Exception"), result::toString);
	}

	@Test
	void devicesAreListedAndRemovedWithConfirmation(@TempDir Path dir) throws Exception {
		CliRunner cli = user(dir);
		Id userId = Id.of(IdentityFile.read(identityFile(dir), "identity file").publicKey().bytes());
		Id deviceId = Id.random();
		director.reply("GET", "/api/v1/client/devices", 200, "[{\"id\": \"" + deviceId + "\", \"userId\": \"" + userId +
				"\", \"name\": \"Laptop\", \"app\": \"MyApp\", \"createdAt\": 1, \"updatedAt\": 1, \"lastSeen\": 0}]");
		director.reply("POST", "/api/v1/client/devices/" + deviceId + "/remove", 200, "");

		Result list = cli.run("device", "list");
		assertEquals(0, list.exitCode(), list::toString);
		assertTrue(list.out().contains("DEVICE ID"), list::toString);
		assertTrue(list.out().contains(deviceId.toBase58String()), list::toString);

		Result unconfirmed = cli.run("device", "remove", deviceId.toBase58String());
		assertEquals(2, unconfirmed.exitCode(), unconfirmed::toString);
		assertTrue(unconfirmed.err().contains("--yes"), unconfirmed::toString);
		assertTrue(director.requests("POST", "/api/v1/client/devices/" + deviceId + "/remove").isEmpty());

		Result removed = cli.run("device", "remove", deviceId.toBase58String(), "--yes");
		assertEquals(0, removed.exitCode(), removed::toString);
		assertEquals(1, director.requests("POST", "/api/v1/client/devices/" + deviceId + "/remove").size());
	}

	@Test
	void thePassphraseIsReadFromStandardInput(@TempDir Path dir) {
		CliRunner cli = user(dir);
		director.reply("PUT", "/api/v1/client/passphrase", 200, "");

		Result result = cli.runWithInput("correct horse\n", "user", "passphrase", "set");
		assertEquals(0, result.exitCode(), result::toString);
		List<StubDirector.Request> requests = director.requests("PUT", "/api/v1/client/passphrase");
		assertEquals(1, requests.size());
		assertEquals("correct horse", requests.get(0).json().get("passphrase"));
	}

	@Test
	void resolveConnectsToTheAddress(@TempDir Path dir) {
		Result result = cli(dir).run("--url", "http://director.invalid:" + director.port(), "--resolve", "127.0.0.1", "node", "id");
		assertEquals(0, result.exitCode(), result::toString);
		assertEquals(nodeId.toBase58String(), result.out().strip());
	}

	@Test
	void aDeviceKeyMustNotBeTheUserKey(@TempDir Path dir) {
		CliRunner cli = user(dir);
		Result result = cli.run("device", "add", "--key", identityFile(dir).toString(), "--name", "Laptop", "--app", "MyApp");
		assertEquals(2, result.exitCode(), result::toString);
		assertTrue(result.err().contains("The device key is your user key."), result::toString);
	}

	@Test
	void keygenWritesAnIdentityFileOnce(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("device.identity");
		Result written = cli(dir).run("util", "keygen", "--output", file.toString());
		assertEquals(0, written.exitCode(), written::toString);
		byte[] key = IdentityFile.read(file, "identity file").privateKey().bytes();

		Result again = cli(dir).run("util", "keygen", "--output", file.toString());
		assertEquals(1, again.exitCode(), again::toString);
		assertArrayEquals(key, IdentityFile.read(file, "identity file").privateKey().bytes());
		assumeTrue(Files.exists(file));
	}
}
