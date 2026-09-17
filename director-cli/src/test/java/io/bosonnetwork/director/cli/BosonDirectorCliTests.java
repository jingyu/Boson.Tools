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

package io.bosonnetwork.director.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.TypeConversionException;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.cli.common.testing.CliRunner;
import io.bosonnetwork.cli.common.testing.CliRunner.Result;
import io.bosonnetwork.cli.testing.StubDirector;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;

/**
 * Tests of {@code boson-director-cli}, run in process against a stub Director.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BosonDirectorCliTests {
	private final Id nodeId = Id.random();
	private final Signature.KeyPair adminKey = Signature.KeyPair.random();
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

	// An administrator configured through the environment, as a script would be.
	private CliRunner admin(Path configDir) {
		return new CliRunner(BosonDirectorCli::new, configDir)
				.env("BOSON_DIRECTOR_URL", director.url())
				.env("BOSON_NODE_ID", nodeId.toBase58String())
				.env("BOSON_PRIVATE_KEY", Base58.encode(adminKey.privateKey().bytes()));
	}

	private static String profileJson(Id userId, String name) {
		return "{\"id\": \"" + userId + "\", \"name\": \"" + name + "\", \"planName\": \"Free\", \"admin\": false, " +
				"\"createdAt\": 1700000000000, \"updatedAt\": 1700000000000, \"passphraseProtected\": false}";
	}

	@Test
	void everyCommandHasHelp(@TempDir Path dir) {
		CliEnvironment environment = new CliEnvironment(Map.of(), dir, System.in, null,
				new PrintWriter(Writer.nullWriter()), new PrintWriter(Writer.nullWriter()));
		List<String[]> commands = new ArrayList<>();
		collect(new CommandLine(new BosonDirectorCli(environment)), new ArrayList<>(), commands);
		assertTrue(commands.size() > 40, "commands: " + commands.size());

		for (String[] command : commands) {
			List<String> args = new ArrayList<>(List.of(command));
			args.add("--help");
			Result result = new CliRunner(BosonDirectorCli::new, dir).run(args.toArray(new String[0]));
			assertEquals(0, result.exitCode(), () -> String.join(" ", command) + ": " + result);
		}
	}

	private static void collect(CommandLine commandLine, List<String> path, List<String[]> commands) {
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
	void theClientSettingsAreNotTheAdminToolsOwn(@TempDir Path dir) throws Exception {
		CliRunner cli = new CliRunner(BosonDirectorCli::new, dir);
		Result set = cli.run("config", "set", "proxyUpstream", "localhost:8080");
		assertEquals(2, set.exitCode(), set::toString);
		assertTrue(set.err().contains("Unknown setting 'proxyUpstream'"), set::toString);

		Path config = dir.resolve("boson").resolve("director-cli.yaml");
		Files.createDirectories(config.getParent());
		Files.writeString(config, "url: " + director.url() + "\ndeviceIdentity: device.identity\n");
		Result read = cli.run("node", "id");
		assertEquals(3, read.exitCode(), read::toString);
		assertTrue(read.err().contains("Unknown setting 'deviceIdentity'"), read::toString);

		Result option = cli.run("--device-identity", "x", "node", "id");
		assertEquals(2, option.exitCode(), option::toString);
	}

	@Test
	void theSetupWizardsConfigurationIsRead(@TempDir Path dir) throws Exception {
		Path config = dir.resolve("boson").resolve("director-cli.yaml");
		Files.createDirectories(config.getParent());
		Files.writeString(config, "nodeId: " + nodeId + "\nurl: " + director.url() + "\n# resolve:\nprivateKey: " +
				Base58.encode(adminKey.privateKey().bytes()) + "\n");
		director.reply("GET", "/api/v1/admin/users/" + nodeId, 200, profileJson(nodeId, "Root"));

		Result result = new CliRunner(BosonDirectorCli::new, dir).run("--json", "identity", "show");
		assertEquals(0, result.exitCode(), result::toString);
		assertEquals(Id.of(adminKey.publicKey().bytes()).toBase58String(), result.json().get("id"));
	}

	@Test
	void usersAreListedAPageAtATime(@TempDir Path dir) {
		Id userId = Id.random();
		director.reply("GET", "/api/v1/admin/users", 200, "{\"page\": 1, \"pageSize\": 20, \"totalPages\": 3, " +
				"\"totalItems\": 45, \"items\": [" + profileJson(userId, "Bob") + "]}");

		Result page = admin(dir).run("user", "list", "--sort", "createdAt:desc");
		assertEquals(0, page.exitCode(), page::toString);
		assertTrue(page.out().contains("USER ID"), page::toString);
		assertTrue(page.out().contains(userId.toBase58String()), page::toString);
		assertTrue(page.out().contains("Page 1 of 3, 45 users in all. Next page: --page 2."), page::toString);
		String query = director.requests("GET", "/api/v1/admin/users").get(0).query();
		assertTrue(query.contains("page=1") && query.contains("pageSize=20") && query.contains("orderBy="), query);

		director.requests().clear();
		Result all = admin(dir).run("user", "list", "--all", "--json");
		assertEquals(0, all.exitCode(), all::toString);
		assertEquals(3, director.requests("GET", "/api/v1/admin/users").size());
		assertEquals(3, ((List<?>) all.json().get("items")).size());

		Result conflicting = admin(dir).run("user", "list", "--all", "--page", "2");
		assertEquals(2, conflicting.exitCode(), conflicting::toString);
	}

	@Test
	void aUserIsAddedWithThePassphraseFromStandardInput(@TempDir Path dir) {
		Id userId = Id.random();
		director.reply("POST", "/api/v1/admin/users", 200, "");

		Result result = admin(dir).runWithInput("initial secret\n", "user", "add", userId.toBase58String(), "--name", "Bob");
		assertEquals(0, result.exitCode(), result::toString);
		Map<String, Object> body = director.requests("POST", "/api/v1/admin/users").get(0).json();
		assertEquals(userId.toBase58String(), body.get("userId"));
		assertEquals("initial secret", body.get("passphrase"));
		assertEquals("Bob", body.get("userName"));
	}

	@Test
	void grantingAdminNeedsConfirmation(@TempDir Path dir) {
		Id userId = Id.random();
		director.reply("PUT", "/api/v1/admin/users/" + userId, 200, "");

		Result unconfirmed = admin(dir).run("user", "grant-admin", userId.toBase58String());
		assertEquals(2, unconfirmed.exitCode(), unconfirmed::toString);
		assertTrue(director.requests("PUT", "/api/v1/admin/users/" + userId).isEmpty());

		Result granted = admin(dir).run("user", "grant-admin", userId.toBase58String(), "--yes");
		assertEquals(0, granted.exitCode(), granted::toString);
		assertEquals(Boolean.TRUE, director.requests("PUT", "/api/v1/admin/users/" + userId).get(0).json().get("admin"));
	}

	@Test
	void anUnknownUserIsNotFound(@TempDir Path dir) {
		Id userId = Id.random();
		Result result = admin(dir).run("user", "show", userId.toBase58String());
		assertEquals(4, result.exitCode(), result::toString);
		assertTrue(result.err().contains("There is no user " + userId), result::toString);
	}

	@Test
	void theBlacklistTellsNodesFromHosts(@TempDir Path dir) {
		Id blocked = Id.random();
		director.reply("POST", "/api/v1/admin/blacklist", 200, "{\"id\": 7, \"nodeHost\": \"bad.example.com\", \"auto\": false}");

		Result node = admin(dir).run("blacklist", "add", blocked.toBase58String(), "--reason", "spam");
		assertEquals(0, node.exitCode(), node::toString);
		Map<String, Object> nodeBody = director.requests("POST", "/api/v1/admin/blacklist").get(0).json();
		assertEquals(blocked.toBase58String(), nodeBody.get("nodeId"));
		assertEquals("spam", nodeBody.get("reason"));

		Result host = admin(dir).run("blacklist", "add", "bad.example.com");
		assertEquals(0, host.exitCode(), host::toString);
		assertEquals("bad.example.com", director.requests("POST", "/api/v1/admin/blacklist").get(1).json().get("nodeHost"));

		Result digits = admin(dir).run("blacklist", "add", "12345");
		assertEquals(2, digits.exitCode(), digits::toString);
		assertEquals(2, director.requests("POST", "/api/v1/admin/blacklist").size());
	}

	@Test
	void plansAreNamedByIdOrName(@TempDir Path dir) {
		String plan = "{\"id\": 3, \"name\": \"Pro\", \"price\": 9.99, \"currency\": \"USD\", \"cycle\": \"monthly\", \"active\": true}";
		director.reply("GET", "/api/v1/admin/plans/3", 200, plan);
		director.reply("GET", "/api/v1/admin/plans/Pro", 200, plan);

		Result byId = admin(dir).run("plan", "show", "3");
		assertEquals(0, byId.exitCode(), byId::toString);
		assertTrue(byId.out().contains("9.99 USD monthly"), byId::toString);

		Result byName = admin(dir).run("--json", "plan", "show", "Pro");
		assertEquals(0, byName.exitCode(), byName::toString);
		assertEquals("9.99", byName.json().get("price"));

		Result missing = admin(dir).run("plan", "show", "Missing");
		assertEquals(4, missing.exitCode(), missing::toString);
		assertTrue(missing.err().contains("There is no plan Missing"), missing::toString);
	}

	@Test
	void aSubscriptionEndsAtARelativeTime(@TempDir Path dir) {
		Id userId = Id.random();
		director.reply("POST", "/api/v1/admin/subscriptions", 200, "{\"id\": 11, \"userId\": \"" + userId + "\", \"planId\": 3, " +
				"\"planName\": \"Pro\", \"status\": \"active\", \"startDate\": 1, \"endDate\": 4102444800000}");

		Result result = admin(dir).run("subscription", "add", userId.toBase58String(), "--plan", "Pro", "--end", "+30d");
		assertEquals(0, result.exitCode(), result::toString);
		Map<String, Object> body = director.requests("POST", "/api/v1/admin/subscriptions").get(0).json();
		assertEquals("Pro", body.get("plan"));
		assertEquals("active", body.get("status"));
		long end = ((Number) body.get("endDate")).longValue();
		long expected = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30);
		assertTrue(Math.abs(end - expected) < TimeUnit.DAYS.toMillis(1), "endDate " + end);

		Result backwards = admin(dir).run("subscription", "add", userId.toBase58String(), "--plan", "Pro",
				"--start", "2030-01-02", "--end", "2030-01-01");
		assertEquals(2, backwards.exitCode(), backwards::toString);
	}

	@Test
	void aProposalThatDoesNotFederateFails(@TempDir Path dir) {
		Id peer = Id.random();
		director.reply("POST", "/api/v1/admin/federation/proposals", 200, "null");

		Result result = admin(dir).run("federation", "propose", peer.toBase58String());
		assertEquals(1, result.exitCode(), result::toString);
		assertTrue(result.out().contains("did not federate"), result::toString);
	}

	@Test
	void aNodeThatDoesNotFederateSaysSo(@TempDir Path dir) {
		director.reply("GET", "/api/v1/admin/federation/nodes", 501, "Not Implemented - Federation is not enabled");

		Result result = admin(dir).run("federation", "node", "list");
		assertEquals(1, result.exitCode(), result::toString);
		assertTrue(result.err().contains("Federation is not enabled"), result::toString);
		assertFalse(result.err().contains("Exception"), result::toString);
	}

	@Test
	void timesAreReadInEveryForm() {
		Arguments.TimeConverter converter = new Arguments.TimeConverter();
		assertEquals(LocalDate.of(2027, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
				converter.convert("2027-01-31"));
		assertEquals(1893456000000L, converter.convert("2030-01-01T00:00:00Z"));
		assertEquals(1893456000000L, converter.convert("1893456000000"));
		assertTrue(converter.convert("+1y") > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(360));
		assertThrows(TypeConversionException.class, () -> converter.convert("next tuesday"));
	}
}
