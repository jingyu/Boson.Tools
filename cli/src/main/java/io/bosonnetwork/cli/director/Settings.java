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

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.Keys;
import io.bosonnetwork.crypto.Signature;

/**
 * The settings a command runs with, each taken from the first place that has it: the command line,
 * then the environment, then the configuration file, then the default.
 * <p>
 * Nothing is checked until it is used, so that a command that does not need a setting is never
 * failed by it, and {@code config show} can display a configuration that does not work.
 */
public final class Settings {
	/** The environment variable naming the configuration file. */
	public static final String ENV_CONFIG = "BOSON_CONFIG";
	/** The environment variable holding the Director URL. */
	public static final String ENV_URL = "BOSON_DIRECTOR_URL";
	/** The environment variable holding the node id. */
	public static final String ENV_NODE_ID = "BOSON_NODE_ID";
	/** The environment variable holding the address to connect to. */
	public static final String ENV_RESOLVE = "BOSON_DIRECTOR_RESOLVE";
	/** The environment variable naming the identity file. */
	public static final String ENV_IDENTITY = "BOSON_IDENTITY";
	/** The environment variable holding the private key itself. */
	public static final String ENV_PRIVATE_KEY = "BOSON_PRIVATE_KEY";
	/** The environment variable holding the user id a device acts for. */
	public static final String ENV_USER_ID = "BOSON_USER_ID";
	/** The environment variable naming the device identity file. */
	public static final String ENV_DEVICE_IDENTITY = "BOSON_DEVICE_IDENTITY";
	/** The environment variable holding the device private key itself. */
	public static final String ENV_DEVICE_PRIVATE_KEY = "BOSON_DEVICE_PRIVATE_KEY";

	/**
	 * Where a setting comes from.
	 */
	public enum Source {
		/** A command-line option. */
		OPTION("command line"),
		/** An environment variable. */
		ENVIRONMENT("environment"),
		/** The configuration file. */
		FILE("configuration file"),
		/** The built-in default. */
		DEFAULT("default");

		private final String label;

		Source(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/**
	 * One setting: its value, and where it comes from.
	 */
	public static final class Setting {
		private final String key;
		private final String value;
		private final Source source;
		private final String origin;

		Setting(String key, String value, Source source, String origin) {
			this.key = key;
			this.value = value;
			this.source = source;
			this.origin = origin;
		}

		/**
		 * Returns the setting's key: one of {@link ConfigFile#ALL_KEYS}, or {@code config} for the
		 * configuration file itself.
		 *
		 * @return the key
		 */
		public String key() {
			return key;
		}

		/**
		 * Returns the value.
		 *
		 * @return the value
		 */
		public String value() {
			return value;
		}

		/**
		 * Returns where the setting comes from.
		 *
		 * @return the source
		 */
		public Source source() {
			return source;
		}

		/**
		 * Names exactly where the setting comes from, for messages: the option, the variable, or the
		 * setting in the file.
		 *
		 * @return the origin, such as {@code --url}, {@code BOSON_DIRECTOR_URL} or
		 *         {@code url in /home/alice/.config/boson/client/boson.yaml}
		 */
		public String origin() {
			return origin;
		}
	}

	private final ToolSpec tool;
	private final CliEnvironment environment;
	private final ConnectionOptions options;
	private final DeviceOptions deviceOptions;
	private final Setting configFile;
	private final ConfigFile config;
	private final Setting url;
	private final Setting nodeId;
	private final Setting resolve;

	private Settings(ToolSpec tool, CliEnvironment environment, ConnectionOptions options, DeviceOptions deviceOptions,
			Setting configFile, ConfigFile config) {
		this.tool = tool;
		this.environment = environment;
		this.options = options;
		this.deviceOptions = deviceOptions;
		this.configFile = configFile;
		this.config = config;
		this.url = pick(ConfigFile.URL, options.url(), "--url", ENV_URL);
		this.nodeId = pick(ConfigFile.NODE_ID, options.nodeId(), "--node-id", ENV_NODE_ID);
		this.resolve = pick(ConfigFile.RESOLVE, options.resolve(), "--resolve", ENV_RESOLVE);
	}

	/**
	 * Resolves the settings of a command.
	 *
	 * @param tool          the tool
	 * @param environment   the environment
	 * @param options       the global options given on the command line
	 * @param deviceOptions the device option given on the command line, or {@code null} for a tool that
	 *                      acts with no device
	 * @return the settings
	 * @throws CliException if the configuration file cannot be read
	 */
	public static Settings resolve(ToolSpec tool, CliEnvironment environment, ConnectionOptions options,
			DeviceOptions deviceOptions) {
		Setting configFile = configFileSetting(tool, environment, options);
		Path path = expandHome(configFile.value(), null);
		return new Settings(tool, environment, options, deviceOptions, configFile, ConfigFile.load(path, tool.configKeys()));
	}

	/**
	 * Returns where the configuration file is, without reading it: for the commands that create or
	 * edit it, which must work whatever it holds.
	 *
	 * @param tool        the tool
	 * @param environment the environment
	 * @param options     the global options given on the command line
	 * @return the file, which may not exist
	 */
	public static Path configFileLocation(ToolSpec tool, CliEnvironment environment, ConnectionOptions options) {
		return expandHome(configFileSetting(tool, environment, options).value(), null);
	}

	private static Setting configFileSetting(ToolSpec tool, CliEnvironment environment, ConnectionOptions options) {
		if (options.configFile() != null)
			return new Setting("config", options.configFile().toString(), Source.OPTION, "--config");
		if (environment.variable(ENV_CONFIG) != null)
			return new Setting("config", environment.variable(ENV_CONFIG), Source.ENVIRONMENT, ENV_CONFIG);
		return new Setting("config", tool.defaultConfigFile().toString(), Source.DEFAULT, "default");
	}

	// A setting from the option, the variable or the file; either of the first two may be null for a
	// setting that has no such form.
	private Setting pick(String key, String option, String optionName, String variable) {
		if (option != null && !option.isBlank())
			return new Setting(key, option.strip(), Source.OPTION, optionName);
		if (variable != null && environment.variable(variable) != null)
			return new Setting(key, environment.variable(variable), Source.ENVIRONMENT, variable);
		if (config.get(key) != null)
			return new Setting(key, config.get(key), Source.FILE, key + " in " + config.path());
		return null;
	}

	/**
	 * Returns the configuration file setting.
	 *
	 * @return the setting
	 */
	public Setting configFileSetting() {
		return configFile;
	}

	/**
	 * Returns the configuration file.
	 *
	 * @return the file, which may not exist
	 */
	public Path configFile() {
		return config.path();
	}

	/**
	 * Tells whether the configuration file exists.
	 *
	 * @return {@code true} if it exists
	 */
	public boolean configFileExists() {
		return config.exists();
	}

	/**
	 * Returns the Director URL setting.
	 *
	 * @return the setting, or {@code null} if it is not set
	 */
	public Setting url() {
		return url;
	}

	/**
	 * Returns the node id setting.
	 *
	 * @return the setting, or {@code null} if it is not set
	 */
	public Setting nodeId() {
		return nodeId;
	}

	/**
	 * Returns the setting of the address to connect to.
	 *
	 * @return the setting, or {@code null} if it is not set
	 */
	public Setting resolve() {
		return resolve;
	}

	/**
	 * Returns the setting of the user id a device acts for.
	 *
	 * @return the setting, or {@code null} if it is not set
	 */
	public Setting userId() {
		return pick(ConfigFile.USER_ID, null, null, ENV_USER_ID);
	}

	/**
	 * Returns the configured user id.
	 *
	 * @return the user id, or {@code null} if it is not set
	 * @throws CliException if it is not a valid id
	 */
	public Id userIdValue() {
		Setting userId = userId();
		if (userId == null)
			return null;

		try {
			return Id.of(userId.value());
		} catch (IllegalArgumentException e) {
			throw CliException.config("The user id '" + userId.value() + "' (from " + userId.origin() + ") is not a valid id.",
					"A user id is Base58, such as the one '" + tool.name() + " identity show' prints.");
		}
	}

	/**
	 * Returns a setting of {@code proxy start}, which the command line overrides.
	 *
	 * @param key one of {@link ConfigFile#PROXY_UPSTREAM}, {@link ConfigFile#PROXY_NAME_ACCESS} and
	 *            {@link ConfigFile#PROXY_ANNOUNCE}
	 * @return the setting, or {@code null} if it is not set
	 */
	public Setting proxy(String key) {
		return pick(key, null, null, null);
	}

	/**
	 * Returns the value of a flag setting.
	 *
	 * @param setting the setting, or {@code null}
	 * @return the flag, or {@code null} if it is not set
	 * @throws CliException if it is neither true nor false
	 */
	public static Boolean flag(Setting setting) {
		if (setting == null)
			return null;
		if (setting.value().equalsIgnoreCase("true"))
			return true;
		if (setting.value().equalsIgnoreCase("false"))
			return false;
		throw CliException.config("The setting " + setting.key() + " (from " + setting.origin() + ") is '" +
				setting.value() + "', but it has to be true or false.", null);
	}

	/**
	 * Returns the identity setting: an identity file ({@link ConfigFile#IDENTITY}), or the private key
	 * itself ({@link ConfigFile#PRIVATE_KEY}). Without either, the default identity file beside the
	 * configuration file.
	 *
	 * @return the setting
	 * @throws CliException if one place sets both an identity file and a private key
	 */
	public Setting identity() {
		return identity(ConfigFile.IDENTITY, ConfigFile.PRIVATE_KEY, options.identity(), "--identity",
				ENV_IDENTITY, ENV_PRIVATE_KEY, tool.defaultIdentityFileName());
	}

	/**
	 * Returns the device identity setting: a device identity file ({@link ConfigFile#DEVICE_IDENTITY}),
	 * or the device private key itself ({@link ConfigFile#DEVICE_PRIVATE_KEY}). Without either, the
	 * default device identity file beside the configuration file.
	 *
	 * @return the setting
	 * @throws CliException if one place sets both a device identity file and a device private key
	 * @throws IllegalStateException if the tool acts with no device
	 */
	public Setting deviceIdentity() {
		return identity(ConfigFile.DEVICE_IDENTITY, ConfigFile.DEVICE_PRIVATE_KEY,
				deviceOptions != null ? deviceOptions.deviceIdentity() : null, "--device-identity",
				ENV_DEVICE_IDENTITY, ENV_DEVICE_PRIVATE_KEY, tool.defaultDeviceIdentityFileName());
	}

	private Setting identity(String fileKey, String privateKeyKey, Path option, String optionName,
			String fileVariable, String keyVariable, String defaultFileName) {
		if (option != null)
			return new Setting(fileKey, option.toString(), Source.OPTION, optionName);

		String file = environment.variable(fileVariable);
		String key = environment.variable(keyVariable);
		if (file != null && key != null)
			throw CliException.config("Both " + fileVariable + " and " + keyVariable + " are set.",
					"Unset one of them.");
		if (key != null)
			return new Setting(privateKeyKey, key, Source.ENVIRONMENT, keyVariable);
		if (file != null)
			return new Setting(fileKey, file, Source.ENVIRONMENT, fileVariable);

		file = config.get(fileKey);
		key = config.get(privateKeyKey);
		if (file != null && key != null)
			throw CliException.config("The configuration file " + config.path() + " sets both " + fileKey + " and " +
					privateKeyKey + ".", "Remove one of them.");
		if (key != null)
			return new Setting(privateKeyKey, key, Source.FILE, privateKeyKey + " in " + config.path());
		if (file != null)
			return new Setting(fileKey, file, Source.FILE, fileKey + " in " + config.path());

		return new Setting(fileKey, configDirectory().resolve(defaultFileName).toString(), Source.DEFAULT, "default");
	}

	/**
	 * Returns the identity file.
	 *
	 * @return the file, or {@code null} if the identity is a private key given inline
	 * @throws CliException if one place sets both an identity file and a private key
	 */
	public Path identityFile() {
		return file(identity());
	}

	/**
	 * Returns the device identity file.
	 *
	 * @return the file, or {@code null} if the device identity is a private key given inline
	 * @throws CliException if one place sets both a device identity file and a device private key
	 */
	public Path deviceIdentityFile() {
		return file(deviceIdentity());
	}

	// The file an identity setting names, or null for a private key given inline.
	private Path file(Setting identity) {
		if (ConfigFile.SECRET_KEYS.contains(identity.key()))
			return null;

		// A file named in the configuration is relative to it; one named on the command line or in the
		// environment, to the working directory.
		return expandHome(identity.value(), identity.source() == Source.FILE ? configDirectory() : null);
	}

	/**
	 * Returns the Director URL.
	 *
	 * @return the URL
	 * @throws CliException if none is configured, or it is not a valid http(s) URL
	 */
	public URL directorUrl() {
		if (url == null) {
			String hint = "Set it with " + tool.command("config set url https://node.example.com:9000") + ", or pass --url.";
			if (configFile.source() != Source.DEFAULT && !config.exists())
				throw CliException.config("No Director URL is configured: the configuration file " + config.path() +
						" (from " + configFile.origin() + ") does not exist.", hint);
			throw CliException.config("No Director URL is configured.", hint);
		}

		String hint = "Use a URL such as https://node.example.com:9000.";
		try {
			URL parsed = new URL(url.value());
			if (!parsed.getProtocol().equals("http") && !parsed.getProtocol().equals("https"))
				throw CliException.config("The Director URL '" + url.value() + "' (from " + url.origin() +
						") is not an http or https URL.", hint);
			if (parsed.getHost() == null || parsed.getHost().isEmpty())
				throw CliException.config("The Director URL '" + url.value() + "' (from " + url.origin() +
						") has no host.", hint);
			return parsed;
		} catch (MalformedURLException e) {
			throw CliException.config("The Director URL '" + url.value() + "' (from " + url.origin() +
					") is not a valid URL.", hint);
		}
	}

	/**
	 * Returns the node id.
	 *
	 * @return the node id, or {@code null} if it is not set
	 * @throws CliException if it is not a valid id
	 */
	public Id nodeIdValue() {
		if (nodeId == null)
			return null;

		try {
			return Id.of(nodeId.value());
		} catch (IllegalArgumentException e) {
			throw CliException.config("The node id '" + nodeId.value() + "' (from " + nodeId.origin() + ") is not a valid id.",
					"A node id is Base58, such as the one '" + tool.name() + " node id' prints.");
		}
	}

	/**
	 * Returns the address to connect to instead of looking up the host of a URL.
	 *
	 * @param directorUrl the Director URL
	 * @return the address, or {@code null} if it is not set
	 * @throws CliException if it is not an IP address, or the URL host is already an address
	 */
	public InetSocketAddress resolveToAddress(URL directorUrl) {
		if (resolve == null)
			return null;

		if (ResolveAddress.isAddress(directorUrl.getHost()))
			throw CliException.config("The resolve address (from " + resolve.origin() + ") needs a host name in the " +
					"Director URL, but " + directorUrl.getHost() + " is already an address.",
					"Remove the resolve setting, or use the Director's host name in the URL.");

		int port = directorUrl.getPort() > 0 ? directorUrl.getPort() : directorUrl.getDefaultPort();
		return ResolveAddress.parse(resolve.value(), port, resolve.origin());
	}

	/**
	 * Returns the key the tool acts with.
	 *
	 * @return the key pair
	 * @throws CliException if there is no identity, or it is not valid
	 */
	public Signature.KeyPair identityKey() {
		String hint = "Create one with " + tool.command("identity create") + ", or import an existing private key with " +
				tool.command("identity import") + ".";
		return key(identity(), "No " + tool.identityRole() + " identity", "private key", "identity file", hint);
	}

	/**
	 * Returns the key the tool acts with, if there is one: the default identity file may be missing.
	 *
	 * @return the key pair, or {@code null} if no identity is configured and the default file does not
	 *         exist
	 * @throws CliException if a configured identity is missing or not valid
	 */
	public Signature.KeyPair identityKeyIfConfigured() {
		Setting identity = identity();
		if (identity.source() == Source.DEFAULT && !Files.exists(Objects.requireNonNull(file(identity))))
			return null;
		return identityKey();
	}

	/**
	 * Returns the key of the device the tool acts as.
	 *
	 * @return the key pair
	 * @throws CliException if there is no device identity, or it is not valid
	 */
	public Signature.KeyPair deviceKey() {
		String hint = "The node's services are used as a device of your account. Register this machine with " +
				tool.command("device add --name <name>") + ", which creates the device key; a new user registers " +
				"with its first device in one step with " + tool.command("user register --device-name <name>") + ".";
		return key(deviceIdentity(), "No device is configured", "device private key", "device identity file", hint);
	}

	/**
	 * Returns the key of the device the tool acts as, if there is one: the default device identity file
	 * may be missing.
	 *
	 * @return the key pair, or {@code null} if no device identity is configured and the default file
	 *         does not exist
	 * @throws CliException if a configured device identity is missing or not valid
	 */
	public Signature.KeyPair deviceKeyIfConfigured() {
		Setting identity = deviceIdentity();
		if (identity.source() == Source.DEFAULT && !Files.exists(Objects.requireNonNull(file(identity))))
			return null;
		return deviceKey();
	}

	private Signature.KeyPair key(Setting identity, String missing, String keyName, String fileName, String hint) {
		if (ConfigFile.SECRET_KEYS.contains(identity.key())) {
			try {
				return Keys.privateKey(identity.value(), keyName + " (from " + identity.origin() + ")");
			} catch (CliException e) {
				throw CliException.config(e.getMessage(), null);
			}
		}

		Path file = Objects.requireNonNull(file(identity));
		if (!Files.exists(file)) {
			if (identity.source() == Source.DEFAULT)
				throw CliException.config(missing + ": " + file + " does not exist.", hint);
			throw CliException.config("The " + fileName + " " + file + " (from " + identity.origin() + ") does not exist.", hint);
		}

		return IdentityFile.read(file, fileName);
	}

	private Path configDirectory() {
		Path parent = config.path().toAbsolutePath().getParent();
		return parent != null ? parent : Path.of("").toAbsolutePath();
	}

	// Expands a leading ~ to the home directory, which a shell does not do inside a configuration
	// file or a quoted option, and resolves a relative path against a base directory.
	static Path expandHome(String value, Path base) {
		String home = System.getProperty("user.home");
		Path path;
		if (value.equals("~"))
			path = Path.of(home);
		else if (value.startsWith("~/") || value.startsWith("~\\"))
			path = Path.of(home, value.substring(2));
		else
			path = Path.of(value);

		if (base != null && !path.isAbsolute())
			path = base.resolve(path);
		return path.normalize();
	}
}
