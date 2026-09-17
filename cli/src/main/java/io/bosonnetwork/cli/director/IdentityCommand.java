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

package io.bosonnetwork.cli.director;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import picocli.CommandLine.Command;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.director.CliContext;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.director.ConfigFile;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.cli.director.Settings;
import io.bosonnetwork.cli.director.Settings.Setting;
import io.bosonnetwork.crypto.Signature;

/**
 * The {@code identity} commands, shared by both tools.
 */
@Command(name = "identity", description = {"Create, import or show the identity this tool acts with.",
		"The identity is a private key in an identity file, readable by you only. Its public key is your id."},
		subcommands = {IdentityCommand.CreateCommand.class, IdentityCommand.ImportCommand.class,
				IdentityCommand.ShowCommand.class})
public class IdentityCommand extends CliGroup {

	@Command(name = "create", description = {"Create a new identity file with a new key.",
			"Writes the identity file in effect (see 'config show'). An existing file is never replaced."})
	public static class CreateCommand extends DirectorCommand {
		@Override
		protected void run() {
			Path file = identityFile(context());
			Signature.KeyPair key = Signature.KeyPair.random();
			IdentityFile.create(file, key);
			report(context(), "Created a new", file, key);
		}
	}

	@Command(name = "import", description = {"Create the identity file from an existing private key.",
			"The key - Base58, or hex with 0x - is read from the terminal without showing it, or from standard input. "
					+ "An existing file is never replaced."})
	public static class ImportCommand extends DirectorCommand {
		@Override
		protected void run() {
			Path file = identityFile(context());
			// Checked before asking for the key, which would otherwise be typed in for nothing.
			if (Files.exists(file))
				throw CliException.failed("The identity file " + file + " already exists; it was not changed.",
						"Import into another file with --identity <file>.");

			String text = terminal().readSecret("Private key (Base58, or hex with 0x): ", "private key");
			Signature.KeyPair key = Keys.privateKey(text, "private key");
			IdentityFile.create(file, key);
			report(context(), "Imported the", file, key);
		}
	}

	@Command(name = "show", description = {"Show the id of the identity in effect, and where it comes from.",
			"For boson-cli, also the device it acts as."})
	public static class ShowCommand extends DirectorCommand {
		@Override
		protected void run() {
			Settings settings = context().settings();
			if (!tool().hasDevice()) {
				Signature.KeyPair key = context().identity();
				show(key, settings.identity(), settings.identityFile());
				return;
			}

			// A device may act for a user whose key is elsewhere: then the user is its configured id.
			Signature.KeyPair userKey = settings.identityKeyIfConfigured();
			Signature.KeyPair deviceKey = settings.deviceKeyIfConfigured();
			if (userKey == null && settings.userId() == null)
				context().identity();	// fails, explaining how to create one

			Map<String, Object> user = userKey != null ?
					entry(Id.of(context().identity().publicKey().bytes()), settings.identity(), settings.identityFile()) :
					entry(context().userId(), settings.userId(), null);
			Map<String, Object> device = deviceKey != null ?
					entry(Id.of(context().deviceKey().publicKey().bytes()), settings.deviceIdentity(), settings.deviceIdentityFile()) :
					null;

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>(user);
				json.put("device", device);
				output().json(json);
				return;
			}

			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("User id", user.get("id").toString());
			rows.put("User key", userKey != null ? describe(user) : "not on this machine; the user id is from " + user.get("from"));
			if (device != null) {
				rows.put("Device id", device.get("id").toString());
				rows.put("Device key", describe(device));
			} else {
				rows.put("Device", "none: register this machine with " + tool().command("device add --name <name>"));
			}
			output().details(rows);
		}

		private void show(Signature.KeyPair key, Setting identity, Path file) {
			Id id = Id.of(key.publicKey().bytes());
			if (output().isJson()) {
				output().json(entry(id, identity, file));
				return;
			}

			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("Id", id.toBase58String());
			rows.put("File", file != null ? file.toString() : "none: the private key is given in " + identity.origin());
			if (file != null)
				rows.put("From", identity.origin());
			output().details(rows);
		}

		private static Map<String, Object> entry(Id id, Setting setting, Path file) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", id);
			json.put("file", file != null ? file.toString() : null);
			json.put("from", setting.origin());
			return json;
		}

		private static String describe(Map<String, Object> entry) {
			return entry.get("file") != null ? entry.get("file") + " (from " + entry.get("from") + ")" :
					"the private key given in " + entry.get("from");
		}
	}

	// The identity file the commands write: the one in effect, which must be a file.
	private static Path identityFile(CliContext context) {
		Settings settings = context.settings();
		Setting identity = settings.identity();
		if (identity.key().equals(ConfigFile.PRIVATE_KEY))
			throw CliException.config("The identity in effect is a private key given in " + identity.origin() + ", not a file.",
					"Pass --identity <file> to write an identity file.");
		return settings.identityFile();
	}

	private static void report(CliContext context, String what, Path file, Signature.KeyPair key) {
		Id id = Id.of(key.publicKey().bytes());
		Output output = context.output();
		String role = context.tool().identityRole();

		if (output.isJson()) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", id);
			json.put("file", file.toString());
			output.json(json);
			return;
		}

		output.message(what + " " + role + " identity in " + file + ".");
		output.message("Id: " + id);
		output.blank();
		output.message("Keep this file private, and back it up: its key cannot be recovered, and anyone who has it can act as this " + role + ".");
	}
}
