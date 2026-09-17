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

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.activeproxy.Configuration;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.ErrorReporter;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.cli.director.Settings.Setting;

/**
 * The {@code config} commands, shared by both tools.
 */
@Command(name = "config", description = {"Show and change the configuration.",
		"The configuration names the Director to talk to and the identities to act with. Command-line options "
				+ "and BOSON_* environment variables override it."},
		subcommands = {ConfigCommand.InitCommand.class, ConfigCommand.ShowCommand.class,
				ConfigCommand.SetCommand.class, ConfigCommand.UnsetCommand.class})
public class ConfigCommand extends CliGroup {

	@Command(name = "init", description = {"Create the configuration file.",
			"Writes the settings given with --url, --node-id and --resolve, with a comment explaining each setting. "
					+ "An existing file is never replaced."})
	public static class InitCommand extends DirectorCommand {
		@Override
		protected void run() {
			ConnectionOptions options = context().options();
			Path path = Settings.configFileLocation(tool(), context().environment(), options);
			if (Files.exists(path))
				throw CliException.failed("The configuration file " + path + " already exists; it was not changed.",
						"Change a setting in it with " + tool().command("config set <key> <value>") + ".");

			String url = options.url() != null ? checkValue(tool(), ConfigFile.URL, options.url()) : null;
			String nodeId = options.nodeId() != null ? checkValue(tool(), ConfigFile.NODE_ID, options.nodeId()) : null;
			String resolve = options.resolve() != null ? checkValue(tool(), ConfigFile.RESOLVE, options.resolve()) : null;

			ConfigFile.create(path, ConfigFile.template(tool(), url, nodeId, resolve));

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", path.toString());
				json.put("url", url);
				json.put("nodeId", nodeId);
				json.put("resolve", resolve);
				output().json(json);
				return;
			}

			output().message("Created " + path + ".");
			if (url == null)
				output().message("Next, set the Director URL with " + tool().command("config set url <url>") + ".");
		}
	}

	@Command(name = "show", description = "Show the settings in effect, and where each one comes from.")
	public static class ShowCommand extends DirectorCommand {
		@Override
		protected void run() {
			Settings settings = context().settings();
			boolean device = tool().hasDevice();

			List<String> problems = new ArrayList<>();
			Identity identity = Identity.of(settings::identity, settings::identityFile, problems);
			Identity deviceIdentity = device ? Identity.of(settings::deviceIdentity, settings::deviceIdentityFile, problems) : null;

			List<Setting> plain = new ArrayList<>();
			plain.add(settings.url());
			plain.add(settings.nodeId());
			plain.add(settings.resolve());
			List<String> plainKeys = new ArrayList<>(List.of(ConfigFile.URL, ConfigFile.NODE_ID, ConfigFile.RESOLVE));
			List<Setting> clientSettings = new ArrayList<>();
			List<String> clientKeys = new ArrayList<>();
			if (device) {
				clientSettings.add(settings.userId());
				clientKeys.add(ConfigFile.USER_ID);
				for (String key : List.of(ConfigFile.PROXY_UPSTREAM, ConfigFile.PROXY_NAME_ACCESS, ConfigFile.PROXY_ANNOUNCE)) {
					clientSettings.add(settings.proxy(key));
					clientKeys.add(key);
				}
			}

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", settings.configFile().toString());
				json.put("configFileExists", settings.configFileExists());
				for (int i = 0; i < plain.size(); i++)
					json.put(plainKeys.get(i), settingJson(plain.get(i)));
				json.put("identity", identity.json());
				if (device) {
					json.put("deviceIdentity", deviceIdentity.json());
					for (int i = 0; i < clientSettings.size(); i++)
						json.put(clientKeys.get(i), settingJson(clientSettings.get(i)));
				}
				output().json(json);
				return;
			}

			Map<String, String> file = new LinkedHashMap<>();
			file.put("Configuration file", settings.configFile() + (settings.configFileExists() ? "" : " (does not exist)"));
			output().details(file);
			output().blank();

			List<List<String>> rows = new ArrayList<>();
			for (int i = 0; i < plain.size(); i++)
				rows.add(row(plainKeys.get(i), plain.get(i)));
			rows.add(identity.row("identity"));
			if (device) {
				rows.add(deviceIdentity.row("deviceIdentity"));
				for (int i = 0; i < clientSettings.size(); i++)
					rows.add(row(clientKeys.get(i), clientSettings.get(i)));
			}
			output().table(List.of("SETTING", "VALUE", "FROM"), rows);

			problems.forEach(output()::warning);
		}

		// An identity setting as shown: a file, which may not exist yet, or a key that is not shown.
		private record Identity(Setting setting, Path file) {
			static Identity of(Supplier<Setting> setting, Supplier<Path> file,
					List<String> problems) {
				try {
					return new Identity(setting.get(), file.get());
				} catch (CliException e) {
					problems.add(e.getMessage());
					return new Identity(null, null);
				}
			}

			List<String> row(String key) {
				if (setting == null)
					return List.of(key, Formats.NONE, Formats.NONE);
				if (file != null)
					return List.of(key, file + (Files.exists(file) ? "" : " (does not exist)"), from(setting));
				return List.of(key, "private key (not shown)", from(setting));
			}

			Object json() {
				if (setting == null)
					return Map.of("error", "invalid");
				Map<String, Object> id = new LinkedHashMap<>();
				if (file != null) {
					id.put("file", file.toString());
					id.put("exists", Files.exists(file));
				} else {
					id.put("privateKey", "(hidden)");
				}
				id.put("from", from(setting));
				return id;
			}
		}

		private static List<String> row(String key, Setting setting) {
			return setting == null ? List.of(key, Formats.NONE, Formats.NONE) : List.of(key, setting.value(), from(setting));
		}

		private static Object settingJson(Setting setting) {
			if (setting == null)
				return null;
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("value", setting.value());
			json.put("from", from(setting));
			return json;
		}

		private static String from(Setting setting) {
			return switch (setting.source()) {
				case OPTION, ENVIRONMENT -> setting.origin();
				case FILE -> "configuration file";
				case DEFAULT -> "default";
			};
		}
	}

	@Command(name = "set", description = {"Change a setting in the configuration file.",
			"Creates the file if it does not exist. Comments and the other settings are kept."})
	public static class SetCommand extends DirectorCommand {
		@Parameters(index = "0", paramLabel = "<key>",
				description = "The setting, such as url or nodeId. 'config show' lists them, and a wrong one is named.")
		String key;

		@Parameters(index = "1", paramLabel = "<value>", description = "The new value.")
		String value;

		@Override
		protected void run() {
			checkKey(tool(), key, true);
			String normalized = checkValue(tool(), key, value);
			Path path = Settings.configFileLocation(tool(), context().environment(), context().options());

			ConfigFile.set(path, key, normalized, ConfigFile.header(tool()));

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", path.toString());
				json.put("key", key);
				json.put("value", normalized);
				output().json(json);
				return;
			}

			output().message("Set " + key + " to " + normalized + " in " + path + ".");
			if ((key.equals(ConfigFile.IDENTITY) || key.equals(ConfigFile.DEVICE_IDENTITY)) &&
					!Path.of(normalized).isAbsolute() && !normalized.startsWith("~"))
				output().message("A relative identity file is relative to the configuration file's directory.");
		}
	}

	@Command(name = "unset", description = "Remove a setting from the configuration file.")
	public static class UnsetCommand extends DirectorCommand {
		@Parameters(index = "0", paramLabel = "<key>", description = "The setting, such as resolve or privateKey.")
		String key;

		@Override
		protected void run() {
			checkKey(tool(), key, false);
			Path path = Settings.configFileLocation(tool(), context().environment(), context().options());
			boolean removed = ConfigFile.unset(path, key);

			if (output().isJson()) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("configFile", path.toString());
				json.put("key", key);
				json.put("removed", removed);
				output().json(json);
				return;
			}

			output().message(removed ? "Removed " + key + " from " + path + "." : key + " is not set in " + path + ".");
		}
	}

	// The settings 'config set' changes: the private keys are left to 'identity import', off the command line.
	private static List<String> settable(ToolSpec tool) {
		return tool.configKeys().stream().filter(key -> !ConfigFile.SECRET_KEYS.contains(key)).toList();
	}

	private static void checkKey(ToolSpec tool, String key, boolean settable) {
		if (settable && key.equals(ConfigFile.PRIVATE_KEY))
			throw CliException.usage("privateKey cannot be set from the command line, where your shell would keep it in its history.",
					"Use 'identity import', which reads the key without showing it, or edit the file yourself.");
		if (settable && key.equals(ConfigFile.DEVICE_PRIVATE_KEY))
			throw CliException.usage("devicePrivateKey cannot be set from the command line, where your shell would keep it in its history.",
					"Put the key in a device identity file and set deviceIdentity, or edit the configuration file yourself.");

		List<String> keys = tool.configKeys();
		if (keys.contains(key))
			return;

		for (String known : keys)
			if (known.equalsIgnoreCase(key))
				throw CliException.usage("Unknown setting '" + key + "'. Did you mean " + known + "?", null);

		throw CliException.usage("Unknown setting '" + key + "'.",
				"The settings are: " + String.join(", ", settable ? settable(tool) : keys) + ".");
	}

	// Checks a value before it is written, so that a mistake is reported now rather than by every
	// command that reads it later. Returns the value to write.
	static String checkValue(ToolSpec tool, String key, String value) {
		String v = value.strip();
		switch (key) {
			case ConfigFile.URL -> {
				try {
					URL url = new URL(v);
					if ((!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) || url.getHost().isEmpty())
						throw new MalformedURLException();
				} catch (MalformedURLException e) {
					throw CliException.usage("'" + value + "' is not an http or https URL.",
							"Use a URL such as https://node.example.com:9000.");
				}
				return v;
			}
			case ConfigFile.NODE_ID -> {
				try {
					return Id.of(v).toBase58String();
				} catch (IllegalArgumentException e) {
					throw CliException.usage("'" + value + "' is not a valid node id.",
							"Get the node's id with " + tool.command("node id") + ", and check it with the node's operator.");
				}
			}
			case ConfigFile.RESOLVE -> {
				try {
					ResolveAddress.parse(v, 1, "the command line");
				} catch (CliException e) {
					throw CliException.usage(e.getMessage(), e.getHint());
				}
				return v;
			}
			case ConfigFile.IDENTITY, ConfigFile.DEVICE_IDENTITY -> {
				if (v.isEmpty())
					throw CliException.usage("The identity file name is empty.", null);
				return v;
			}
			case ConfigFile.USER_ID -> {
				try {
					return Id.of(v).toBase58String();
				} catch (IllegalArgumentException e) {
					throw CliException.usage("'" + value + "' is not a valid user id.",
							"A user id is Base58, such as the one '" + tool.name() + " identity show' prints.");
				}
			}
			case ConfigFile.PROXY_UPSTREAM -> {
				try {
					Configuration.builder().upstream(v);
				} catch (IllegalArgumentException e) {
					throw CliException.usage(ErrorReporter.sentence(e.getMessage()),
							"Use host:port for an http service, or scheme://host:port, such as tcp://127.0.0.1:22.");
				}
				return v;
			}
			case ConfigFile.PROXY_NAME_ACCESS, ConfigFile.PROXY_ANNOUNCE -> {
				String flag = v.toLowerCase(Locale.ROOT);
				if (flag.equals("true") || flag.equals("false"))
					return flag;
				throw CliException.usage(key + " is true or false, not '" + value + "'.", null);
			}
			default -> throw new IllegalStateException("Unchecked setting " + key);
		}
	}
}
