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

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.director.Views;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.DirectorClient;
import io.bosonnetwork.director.client.Profile;
import io.bosonnetwork.director.client.ProfileUpdate;
import io.bosonnetwork.director.client.UserRegistration;
import io.bosonnetwork.director.client.exceptions.ConflictException;
import io.bosonnetwork.director.client.exceptions.ForbiddenException;
import io.bosonnetwork.director.client.exceptions.PassphraseRequiredException;

/**
 * The {@code user} commands of {@code boson-cli}: the account of the user this tool acts as.
 */
@Command(name = "user", description = "Register with the super node, and manage your account.",
		subcommands = {UserCommand.RegisterCommand.class, UserCommand.ShowCommand.class, UserCommand.UpdateCommand.class,
				UserCommand.AvatarCommand.class, UserCommand.PassphraseCommand.class})
public class UserCommand extends CliGroup {

	@Command(name = "register", description = {"Register your user with the super node.",
			"The registration is proven with proof-of-work, which takes a few seconds. With --device-name, a device is "
					+ "registered along with the user: this machine, unless --device-key names another."})
	public static class RegisterCommand extends DirectorCommand {
		@Option(names = "--name", paramLabel = "<name>", description = "Your display name.")
		String name;

		@Option(names = "--email", paramLabel = "<email>", description = "Your email address.")
		String email;

		@Option(names = "--bio", paramLabel = "<text>", description = "A few words about you.")
		String bio;

		@Option(names = "--passphrase", description = "Protect the account with a passphrase, which you are asked for. "
				+ "It is then needed to register or remove devices and to update the profile, and cannot be recovered.")
		boolean passphrase;

		@ArgGroup(exclusive = false, heading = "%nFirst device, registered along with the user:%n")
		InitialDevice device;

		static class InitialDevice {
			@Option(names = "--device-name", paramLabel = "<name>", required = true,
					description = "A name for the device, such as \"Alice's laptop\".")
			String name;

			@Option(names = "--device-key", paramLabel = "<file>",
					description = "The identity file of the device. Default: this machine's device key, created if it "
							+ "does not exist yet.")
			Path key;

			@Option(names = "--app", paramLabel = "<name>", defaultValue = DeviceCommand.DEFAULT_APP,
					description = "The app the device runs. Default: ${DEFAULT-VALUE}.")
			String app;
		}

		@Override
		protected void run() throws Exception {
			// The connection and the user identity are checked before a device key is created.
			context().directorClient();
			DeviceCommand.DeviceKey deviceKey = device != null ? DeviceCommand.deviceKey(context(), device.key) : null;
			String secret = passphrase ? terminal().readNewSecret("Passphrase for the account", "passphrase") : null;

			DirectorClient client = context().directorClient(deviceKey != null ? deviceKey.key() : null);
			UserRegistration registration = new UserRegistration()
					.name(blankToNull(name))
					.email(blankToNull(email))
					.bio(blankToNull(bio))
					.passphrase(secret);
			if (device != null)
				registration.initialDevice(device.name, device.app);

			output().progress("Registering user " + client.getUserId() + " with " + client.getDirectorUrl() +
					" (solving the proof-of-work challenge)...");
			try {
				await(client.registerUser(registration));
			} catch (ConflictException e) {
				throw new CliException(ExitCode.CONFLICT,
						device != null ? "The user or the device is already registered with this node." :
								"This user is already registered with this node.",
						"Show the account with " + tool().command("user show") + ".");
			}

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("userId", client.getUserId());
				json.put("deviceId", client.getDeviceId());
				output().json(json);
				return;
			}

			output().message("Registered user " + client.getUserId() + " with " + client.getDirectorUrl() + ".");
			if (device != null) {
				output().message("Registered device " + client.getDeviceId() + " (" + device.name + ", " + device.app +
						") as its first device.");
				deviceKey.report(context());
			} else {
				output().message("To use the node's services from this machine, register it as a device: " +
						tool().command("device add --name <name>") + ".");
			}
		}
	}

	@Command(name = "show", description = "Show your profile.")
	public static class ShowCommand extends DirectorCommand {
		@Override
		protected void run() throws Exception {
			Profile profile = await(context().directorClient().getProfile());
			if (output().isJson())
				output().json(Views.profileJson(profile));
			else
				output().details(Views.profile(profile));
		}
	}

	@Command(name = "update", description = {"Change your profile.",
			"Only the fields given change. An empty value clears a field, as in --bio \"\"."})
	public static class UpdateCommand extends DirectorCommand {
		@Option(names = "--name", paramLabel = "<name>", description = "Your display name.")
		String name;

		@Option(names = "--email", paramLabel = "<email>", description = "Your email address.")
		String email;

		@Option(names = "--bio", paramLabel = "<text>", description = "A few words about you.")
		String bio;

		@Option(names = "--passphrase", description = "Ask for the account passphrase up front. "
				+ "Without it, you are asked only if the account has one.")
		boolean passphrase;

		@Override
		protected void run() throws Exception {
			ProfileUpdate update = new ProfileUpdate();
			List<String> changed = new ArrayList<>();
			if (name != null) {
				update.name(blankToNull(name));
				changed.add("name");
			}
			if (email != null) {
				update.email(blankToNull(email));
				changed.add("email");
			}
			if (bio != null) {
				update.bio(blankToNull(bio));
				changed.add("bio");
			}
			if (update.isEmpty())
				throw CliException.usage("Nothing to update.",
						"Pass --name, --email or --bio. An empty value clears a field, as in --bio \"\".");

			DirectorClient client = context().directorClient();
			withPassphrase(passphrase, p -> client.updateProfile(update, p));

			if (output().isJson())
				output().json(Views.profileJson(await(client.getProfile())));
			else
				output().message("Updated your profile: " + String.join(", ", changed) + ".");
		}
	}

	@Command(name = "avatar", description = "Save or change your avatar.",
			subcommands = {AvatarCommand.GetCommand.class, AvatarCommand.SetCommand.class})
	public static class AvatarCommand extends CliGroup {
		@Command(name = "get", description = "Save your avatar to a file.")
		public static class GetCommand extends DirectorCommand {
			@Option(names = {"-o", "--output"}, paramLabel = "<file>", required = true,
					description = "The file to save the image to.")
			Path file;

			@Option(names = "--overwrite", description = "Replace the file if it exists.")
			boolean overwrite;

			@Override
			protected void run() throws Exception {
				if (!overwrite && Files.exists(file))
					throw existing(file);

				io.bosonnetwork.director.client.Avatar avatar = await(context().directorClient().getAvatar());
				if (avatar == null)
					throw CliException.notFound("You have no avatar.",
							"Upload one with " + tool().command("user avatar set <file>") + ".");

				try {
					if (overwrite)
						Files.write(file, avatar.getData());
					else
						Files.write(file, avatar.getData(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				} catch (FileAlreadyExistsException e) {
					throw existing(file);
				}

				if (output().isJson()) {
					Map<String, Object> json = new LinkedHashMap<>();
					json.put("file", file.toString());
					json.put("contentType", avatar.getContentType());
					json.put("size", avatar.getData().length);
					output().json(json);
					return;
				}

				output().message("Saved your avatar (" + avatar.getContentType() + ", " + avatar.getData().length +
						" bytes) to " + file + ".");
			}

			private static CliException existing(Path file) {
				return CliException.failed("The file " + file + " already exists; it was not changed.",
						"Choose another file, or pass --overwrite.");
			}
		}

		@Command(name = "set", description = {"Upload an image as your avatar.",
				"PNG or JPEG, within the size limit the node sets."})
		public static class SetCommand extends DirectorCommand {
			@Parameters(paramLabel = "<file>", description = "The image: a .png, .jpg or .jpeg file.")
			Path file;

			@Override
			protected void run() throws Exception {
				if (!Files.isRegularFile(file))
					throw CliException.usage("No such file: " + file + ".", null);

				String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
				if (!fileName.endsWith(".png") && !fileName.endsWith(".jpg") && !fileName.endsWith(".jpeg"))
					throw CliException.usage("Only PNG and JPEG images can be avatars.",
							"The file name has to end in .png, .jpg or .jpeg.");

				String uri = await(context().directorClient().updateAvatar(file));

				if (output().isJson())
					output().json(Map.of("uri", uri));
				else
					output().message("Uploaded " + file.getFileName() + " as your avatar; it is published at " + uri + ".");
			}
		}
	}

	@Command(name = "passphrase", description = {"Set, change or remove the account passphrase.",
			"Once set, the passphrase is needed to register or remove devices and to update the profile. "
					+ "It cannot be recovered."},
			subcommands = {PassphraseCommand.SetCommand.class, PassphraseCommand.ChangeCommand.class,
					PassphraseCommand.ClearCommand.class})
	public static class PassphraseCommand extends CliGroup {
		@Command(name = "set", description = "Set a passphrase on an account that has none. You are asked for it.")
		public static class SetCommand extends DirectorCommand {
			@Override
			protected void run() throws Exception {
				DirectorClient client = context().directorClient();
				String passphrase = terminal().readNewSecret("New passphrase", "passphrase");
				try {
					await(client.setPassphrase(passphrase));
				} catch (PassphraseRequiredException e) {
					throw new CliException(ExitCode.CONFLICT, "The account already has a passphrase; it was not changed.",
							"Change it with " + tool().command("user passphrase change") + ".");
				}
				output().message("Set the account passphrase. Keep it safe: it cannot be recovered.");
			}
		}

		@Command(name = "change", description = "Change the account passphrase. You are asked for the current one and the new one.")
		public static class ChangeCommand extends DirectorCommand {
			@Override
			protected void run() throws Exception {
				DirectorClient client = context().directorClient();
				String current = terminal().readSecret("Current passphrase: ", "current passphrase");
				String replacement = terminal().readNewSecret("New passphrase", "new passphrase");
				try {
					await(client.updatePassphrase(current, replacement));
				} catch (ForbiddenException e) {
					throw wrongPassphrase();
				}
				output().message("Changed the account passphrase.");
			}
		}

		@Command(name = "clear", description = "Remove the passphrase from the account. You are asked for it.")
		public static class ClearCommand extends DirectorCommand {
			@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
			boolean yes;

			@Override
			protected void run() throws Exception {
				DirectorClient client = context().directorClient();
				terminal().confirm("Remove the passphrase from your account?", yes);
				String current = terminal().readSecret("Current passphrase: ", "current passphrase");
				try {
					await(client.clearPassphrase(current));
				} catch (ForbiddenException e) {
					throw wrongPassphrase();
				}
				output().message("Removed the account passphrase.");
			}
		}

		private static CliException wrongPassphrase() {
			return new CliException(ExitCode.NOT_AUTHORIZED, "The current passphrase is not correct; nothing was changed.");
		}
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
