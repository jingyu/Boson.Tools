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

package io.bosonnetwork.node.cli.commands;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.Vertx;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.cli.common.CliCommand;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.node.cli.NodeOptions;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;

/**
 * {@code boson-node config}: writing, showing and checking a node's configuration file.
 */
@Command(name = "config", description = {"Write, show and check a node's configuration.",
		"A node is configured by a node.yaml; without one named, the tools use the first of ./node.yaml, the "
				+ "user's boson/node.yaml and the system's boson/node.yaml that exists."},
		subcommands = {ConfigCommand.InitCommand.class, ConfigCommand.ShowCommand.class, ConfigCommand.CheckCommand.class})
public class ConfigCommand extends CliGroup {
	/** The template shipped with the tool, with a placeholder for each value only the operator has. */
	static final String TEMPLATE_RESOURCE = "/node.yaml";
	private static final String HOST_PLACEHOLDER = "LOCAL_IPV4_ADDRESS";
	private static final String KEY_PLACEHOLDER = "NODE_PRIVATE_KEY";

	/** What to do about an address a node cannot use, or can use only in developer mode. */
	public static final String ADDRESS_HINT = "A node listens on this host's LAN or public address, and announces it to the "
			+ "network: a loopback address is of no use to any other node. A node on a private address also needs "
			+ "developerMode: true.";

	@Command(name = "init", description = {"Write a node configuration, with a new node identity.",
			"The file explains every setting in comments. An existing file is never replaced."})
	public static class InitCommand extends CliCommand {
		@Option(names = {"-o", "--output"}, paramLabel = "<file>", defaultValue = NodeOptions.CONFIG_FILE_NAME,
				description = "Where to write it. Default: ${DEFAULT-VALUE} in the current directory.")
		Path file;

		@Option(names = {"-4", "--host4"}, paramLabel = "<address>",
				description = "The IPv4 address to listen on. Default: this host's address on the default route.")
		String host4;

		@Option(names = {"-d", "--data-dir"}, paramLabel = "<dir>",
				description = "The directory for the node's data. Default: the one the template names.")
		Path dataDir;

		@Option(names = "--developer-mode",
				description = "Write developerMode: true, which lets the node take part from a private address. "
						+ "For development only.")
		boolean developerMode;

		@Override
		protected void run() throws Exception {
			InetAddress address = address();
			// Checked here rather than left to the first command that reads the file: a configuration
			// written with an address no node can use is worse than no configuration at all.
			if (!AddressUtils.isAnyUnicast(address))
				throw CliException.usage("A node cannot listen on " + address.getHostAddress() + ".", ADDRESS_HINT);

			Signature.KeyPair key = Signature.KeyPair.random();
			Id nodeId = Id.of(key.publicKey().bytes());

			String content = template()
					.replace(HOST_PLACEHOLDER, address.getHostAddress())
					.replace(KEY_PLACEHOLDER, Base58.encode(key.privateKey().bytes()));
			if (dataDir != null)
				content = content.replaceAll("(?m)^dataDir: .*$", "dataDir: " + dataDir);
			if (developerMode)
				content = content.replaceAll("(?m)^(\\s*)developerMode: false$", "$1developerMode: true");

			try {
				Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			} catch (FileAlreadyExistsException e) {
				throw CliException.failed("The file " + file + " already exists; it was not changed.",
						"Choose another file with --output, or edit the existing one.");
			}

			boolean reachable = !address.isSiteLocalAddress() && !address.isLinkLocalAddress();
			if (!reachable && !developerMode)
				output().warning(address.getHostAddress() + " is a private address, which the network at large cannot "
						+ "reach. For a node on a private network, write the configuration with --developer-mode.");

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", file.toString());
				json.put("nodeId", nodeId);
				json.put("host4", address.getHostAddress());
				json.put("developerMode", developerMode);
				output().json(json);
				return;
			}

			output().message("Wrote " + file + ".");
			output().message("Node id: " + nodeId);
			output().blank();
			output().message("The file holds the node's private key: keep it readable by the node's user only, "
					+ "and back it up. A node that loses its key loses its identity on the network.");
		}

		// The address given, or this host's on the default route.
		private InetAddress address() {
			if (host4 == null) {
				InetAddress detected = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
				if (detected == null)
					throw CliException.usage("This host has no IPv4 address on its default route.",
							"Name the address to listen on with --host4.");
				return detected;
			}

			try {
				return InetAddress.getByName(host4);
			} catch (UnknownHostException e) {
				throw CliException.usage("'" + host4 + "' is not an IPv4 address.",
						"Give the address itself, such as 203.0.113.5.");
			}
		}

		private static String template() {
			try (InputStream in = ConfigCommand.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
				if (in == null)
					throw new IOException("the template is missing from the tool");
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw CliException.failed("Cannot read the configuration template: " + e.getMessage(), null);
			}
		}
	}

	@Command(name = "show", description = "Show which configuration file is in effect, and what it sets.")
	public static class ShowCommand extends CliCommand {
		@Option(names = {"-c", "--config"}, paramLabel = "<file>", description = "The configuration file to show.")
		Path configFile;

		@Override
		protected void run() {
			Path file = resolve(configFile);
			Map<String, Object> config = NodeOptions.load(file);

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", file.toString());
				json.put("settings", config);
				output().json(json);
				return;
			}

			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("Configuration file", file.toString());
			rows.put("Node id", nodeId(config));
			rows.put("IPv4", Formats.text(text(config, "host4")));
			rows.put("IPv6", Formats.text(text(config, "host6")));
			rows.put("Port", Formats.text(text(config, "port")));
			rows.put("Data directory", Formats.text(text(config, "dataDir")));
			rows.put("Bootstraps", Formats.value(config.get("bootstraps") instanceof List<?> list ? list.size() : 0));
			output().details(rows);
		}

		private static String nodeId(Map<String, Object> config) {
			Object privateKey = config.get("privateKey");
			if (privateKey == null || privateKey.toString().isBlank())
				return "not set";

			try {
				return Id.of(Keys.privateKey(privateKey.toString(), "privateKey").publicKey().bytes()).toBase58String();
			} catch (CliException e) {
				return "invalid";
			}
		}

		private static String text(Map<String, Object> config, String key) {
			Object value = config.get(key);
			return value == null ? null : value.toString();
		}
	}

	@Command(name = "check", description = {"Check that a configuration file is usable.",
			"Reads it the way a node does, and says what is wrong with it. Exits with 0 when it is usable."})
	public static class CheckCommand extends CliCommand {
		@Option(names = {"-c", "--config"}, paramLabel = "<file>", description = "The configuration file to check.")
		Path configFile;

		@Override
		protected void run() {
			Path file = resolve(configFile);
			Map<String, Object> config = NodeOptions.load(file);

			Vertx vertx = Vertx.vertx();
			try {
				NodeConfiguration node = NodeConfiguration.builder().vertx(vertx).fromMap(config).build();

				if (output().isJson()) {
					Map<String, Object> json = new LinkedHashMap<>();
					json.put("configFile", file.toString());
					json.put("usable", true);
					json.put("nodeId", Id.of(node.keyPair().publicKey().bytes()));
					output().json(json);
					return;
				}

				output().message(file + " is usable.");
				output().message("Node id: " + Id.of(node.keyPair().publicKey().bytes()));
			} catch (RuntimeException e) {
				String problem = e.getMessage();

				if (output().isJson()) {
					Map<String, Object> json = new LinkedHashMap<>();
					json.put("configFile", file.toString());
					json.put("usable", false);
					json.put("problem", problem);
					output().json(json);
					setExitCode(ExitCode.FAILED);
					return;
				}

				throw CliException.config(file + " is not usable: " + problem,
						problem != null && problem.contains("unicast") ? ADDRESS_HINT
								: "Compare it with a fresh one from 'boson-node config init'.");
			} finally {
				vertx.close();
			}
		}
	}

	// The file named, or the first of the default locations that exists.
	static Path resolve(Path named) {
		if (named != null) {
			if (!Files.isRegularFile(named))
				throw CliException.config("The configuration file " + named + " does not exist.", null);
			return named;
		}

		for (Path candidate : NodeOptions.defaultConfigFiles())
			if (Files.isRegularFile(candidate))
				return candidate;

		throw CliException.config("No node configuration found.",
				"Name one with --config, or create one with 'boson-node config init'.");
	}
}
