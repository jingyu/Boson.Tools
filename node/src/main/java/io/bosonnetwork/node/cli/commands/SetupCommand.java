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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliCommand;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;

/**
 * {@code boson-node setup}: the configuration of a packaged bootstrap node - its identity, its
 * directories, and the configuration and logging files rendered from the packaged templates.
 * <p>
 * This is what a package's post-install step runs, which is why it takes {@code --batch}: an install
 * that runs again must leave a configured node exactly as it is.
 */
@Command(name = "setup", description = {"Configure a packaged bootstrap node.",
		"Generates the node's identity, creates its directories, and renders the packaged templates. Run by the "
				+ "package after installation; an operator rarely runs it by hand."})
public class SetupCommand extends CliCommand {
	/**
	 * Mode for the generated configuration: readable by the owner and the group the node runs as, and
	 * by nobody else. It carries the node's private key in clear text, so the default mode would
	 * publish the node's identity to every account on the host.
	 */
	private static final Set<PosixFilePermission> CONFIG_PERMISSIONS = PosixFilePermissions.fromString("rw-r-----");

	private static final String NODE_PUBLIC_KEY = "NODE_PUBLIC_KEY";
	private static final String NODE_PRIVATE_KEY = "NODE_PRIVATE_KEY";
	private static final String LOCAL_IPV4_ADDRESS = "LOCAL_IPV4_ADDRESS";
	private static final String LOG_DIR = "LOG_DIR";
	private static final String DATA_DIR = "DATA_DIR";

	@Option(names = "--home", paramLabel = "<dir>",
			description = "The installation directory holding config/templates/bootstrap. "
					+ "Without it, the packaged templates in /usr/share/boson/config/bootstrap.")
	Path home;

	@Option(names = "--batch",
			description = "Leave an existing configuration alone instead of asking. For a package's post-install step.")
	boolean batch;

	@Option(names = {"-y", "--yes"}, description = "Do not ask before replacing an existing configuration.")
	boolean yes;

	@Option(names = "--config-dir", paramLabel = "<dir>", defaultValue = "/etc/boson/bootstrap",
			description = "Where the configuration is written. Default: ${DEFAULT-VALUE}.")
	Path configDir;

	@Option(names = "--data-dir", paramLabel = "<dir>", defaultValue = "/var/lib/boson/bootstrap",
			description = "The node's data directory. Default: ${DEFAULT-VALUE}.")
	Path dataDir;

	@Option(names = "--log-dir", paramLabel = "<dir>", defaultValue = "/var/log/boson/bootstrap",
			description = "Where the node logs. Default: ${DEFAULT-VALUE}.")
	Path logDir;

	@Override
	protected void run() throws Exception {
		Path configFile = configDir.resolve("node.yaml");
		if (Files.exists(configFile)) {
			if (batch) {
				output().message("The node is already configured: " + configFile + ". Nothing was changed.");
				return;
			}

			// A bootstrap node's value is that other nodes carry its id in their configuration, so its
			// identity is the one thing here that cannot be regenerated: replacing the key does not
			// reconfigure this node, it removes it from the network as far as every node that knows it
			// is concerned.
			output().message("This bootstrap node is already configured: " + configFile);
			output().message("Continuing generates a new identity. The current private key is lost, and every node");
			output().message("configured with this node's current id will no longer reach it.");
			terminal().confirm("Replace the configuration of " + configFile + "?", yes);
		}

		Path templates = templateDir();
		Signature.KeyPair key = Signature.KeyPair.random();
		Id nodeId = Id.of(key.publicKey().bytes());

		// The address of this host's own interface on the default route, which is what the node can
		// bind. On a host whose public address is mapped to it by the network - an elastic or floating
		// address - that is the private address the public one maps to, never the public one itself.
		InetAddress detected = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
		String address = detected != null ? detected.getHostAddress() : "127.0.0.1";

		Map<String, String> values = new HashMap<>();
		values.put(NODE_PUBLIC_KEY, nodeId.toBase58String());
		values.put(NODE_PRIVATE_KEY, Base58.encode(key.privateKey().bytes()));
		values.put(LOCAL_IPV4_ADDRESS, address);
		values.put(LOG_DIR, logDir.toAbsolutePath().toString());
		values.put(DATA_DIR, dataDir.toAbsolutePath().toString());

		try {
			Files.createDirectories(configDir);
			Files.createDirectories(dataDir);
			Files.createDirectories(logDir);

			render(templates.resolve("node.yaml"), configFile, values);
			render(templates.resolve("logback.xml"), configDir.resolve("logback.xml"), values);
		} catch (IOException e) {
			throw CliException.failed("Cannot write the configuration: " + e.getMessage(),
					"The setup writes to " + configDir + "; run it as a user that may.");
		}

		if (output().isJson()) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("nodeId", nodeId);
			json.put("configFile", configFile.toString());
			json.put("dataDir", dataDir.toString());
			json.put("logDir", logDir.toString());
			output().json(json);
			return;
		}

		output().message("Configured the bootstrap node.");
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Node id", nodeId.toBase58String());
		rows.put("Configuration", configFile.toString());
		rows.put("Data directory", dataDir.toString());
		rows.put("Log directory", logDir.toString());
		output().details(rows);
	}

	private Path templateDir() {
		if (home != null) {
			Path templates = home.resolve("config/templates/bootstrap");
			if (Files.isDirectory(templates))
				return templates;
		}

		Path packaged = Path.of("/usr/share/boson/config/bootstrap");
		if (Files.isDirectory(packaged))
			return packaged;

		throw CliException.config("The configuration templates were not found.",
				"Name the installation directory with --home, which holds config/templates/bootstrap.");
	}

	private static void render(Path source, Path target, Map<String, String> values) throws IOException {
		String content = Files.readString(source);
		for (Map.Entry<String, String> value : values.entrySet())
			content = content.replace("${" + value.getKey() + "}", value.getValue());

		// Created already restricted: setting the mode after the write leaves the private key on disk
		// under the default umask - world-readable on a stock system - for the moment in between.
		if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.deleteIfExists(target);
			Files.createFile(target, PosixFilePermissions.asFileAttribute(CONFIG_PERMISSIONS));
		}

		Files.writeString(target, content);
	}
}
