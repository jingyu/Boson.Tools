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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.FilePermissions;
import io.bosonnetwork.json.Json;

/**
 * The configuration file shared by the Boson command line tools: flat YAML, one {@code key: value}
 * setting per line.
 * <pre>
 * url: https://node.example.com:9000
 * nodeId: 5vVHp...        # optional
 * resolve: 127.0.0.1      # optional
 * identity: user.identity # optional, relative to this file
 * privateKey: 3Jx...      # optional, instead of an identity file
 * </pre>
 * {@code boson-cli} also takes the device it acts with ({@code deviceIdentity} or
 * {@code devicePrivateKey}), the user a device acts for when the user key is not at hand
 * ({@code userId}), and the defaults of {@code proxy start} ({@code proxyUpstream},
 * {@code proxyNameAccess}, {@code proxyAnnounce}). Which keys a file may hold is up to its tool
 * ({@link ToolSpec#configKeys()}).
 * Editing works on the lines of the file, not on a parsed and re-serialized document, so that the
 * comments explaining the settings survive every change.
 */
public final class ConfigFile {
	/** The Director URL. */
	public static final String URL = "url";
	/** The super node's id. */
	public static final String NODE_ID = "nodeId";
	/** The address to connect to instead of looking up the URL host. */
	public static final String RESOLVE = "resolve";
	/** The identity file. */
	public static final String IDENTITY = "identity";
	/** The private key, inline. */
	public static final String PRIVATE_KEY = "privateKey";
	/** The user a device acts for, when the user key is not configured. */
	public static final String USER_ID = "userId";
	/** The device identity file. */
	public static final String DEVICE_IDENTITY = "deviceIdentity";
	/** The device private key, inline. */
	public static final String DEVICE_PRIVATE_KEY = "devicePrivateKey";
	/** The local service {@code proxy start} exposes. */
	public static final String PROXY_UPSTREAM = "proxyUpstream";
	/** Whether {@code proxy start} asks for a named endpoint. */
	public static final String PROXY_NAME_ACCESS = "proxyNameAccess";
	/** Whether {@code proxy start} announces the endpoint on the DHT. */
	public static final String PROXY_ANNOUNCE = "proxyAnnounce";

	/** The settings of a tool that talks to the Director only, in the order they are documented. */
	public static final List<String> DIRECTOR_KEYS = List.of(URL, NODE_ID, RESOLVE, IDENTITY, PRIVATE_KEY);
	/** The settings of a tool that also uses the super node's services, in the order they are documented. */
	public static final List<String> CLIENT_KEYS = List.of(URL, NODE_ID, RESOLVE, IDENTITY, PRIVATE_KEY, USER_ID,
			DEVICE_IDENTITY, DEVICE_PRIVATE_KEY, PROXY_UPSTREAM, PROXY_NAME_ACCESS, PROXY_ANNOUNCE);
	/** Every setting there is. */
	public static final List<String> ALL_KEYS = CLIENT_KEYS;
	/** The settings holding a private key, which the command line never takes. */
	public static final List<String> SECRET_KEYS = List.of(PRIVATE_KEY, DEVICE_PRIVATE_KEY);
	/** The settings holding a flag. */
	public static final List<String> FLAG_KEYS = List.of(PROXY_NAME_ACCESS, PROXY_ANNOUNCE);

	// What the setup wizard of earlier Director versions called privateKey. The same literal is
	// io.bosonnetwork.director.Setup.RENAMED_ROOT_USER_KEY, whose --migrate mode performs the rename
	// this class only reports. Keep the two in step; neither module depends on the other, so they
	// cannot share one constant.
	private static final String RENAMED_ROOT_USER_KEY = "rootUserKey";

	// A value that YAML reads as the same string without quotes: no leading indicator character, no
	// ": " or " #", and not a word YAML reads as a boolean or null.
	private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9._/~+=-][A-Za-z0-9._/~+=:@\\[\\]-]*");
	private static final Set<String> RESERVED_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n");

	private final Path path;
	private final boolean exists;
	private final Map<String, String> values;

	private ConfigFile(Path path, boolean exists, Map<String, String> values) {
		this.path = path;
		this.exists = exists;
		this.values = Collections.unmodifiableMap(values);
	}

	/**
	 * Reads a configuration file. A file that does not exist is an empty configuration.
	 *
	 * @param path the file
	 * @param keys the settings the file may hold
	 * @return the configuration
	 * @throws CliException if the file cannot be read, or holds anything but those settings
	 */
	public static ConfigFile load(Path path, List<String> keys) {
		if (!Files.exists(path))
			return new ConfigFile(path, false, Map.of());

		JsonNode root;
		try {
			root = Json.yamlMapper().readTree(path.toFile());
		} catch (JsonProcessingException e) {
			throw CliException.config("Cannot read the configuration file " + path + ": " + e.getOriginalMessage(),
					"It should hold settings as 'key: value' lines.");
		} catch (IOException e) {
			throw CliException.config("Cannot read the configuration file " + path + ": " + describe(e), null);
		}

		Map<String, String> values = new LinkedHashMap<>();
		if (root == null || root.isMissingNode() || root.isNull())
			return new ConfigFile(path, true, values);

		if (!root.isObject())
			throw CliException.config("The configuration file " + path + " is not a list of settings.",
					"It should hold settings as 'key: value' lines.");

		for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
			Map.Entry<String, JsonNode> field = it.next();
			String key = field.getKey();
			JsonNode value = field.getValue();

			if (key.equals(RENAMED_ROOT_USER_KEY))
				// Reported rather than fixed: rewriting a file the user owns on what is only a read would be a
				// surprise. The deb package migrates root's copy on upgrade, so this is for everyone else.
				throw CliException.config("The configuration file " + path + " sets rootUserKey, which is now called privateKey.",
						"Run 'boson.sh --setup --migrate --file " + path + "' from the Boson distribution, " +
								"or rename rootUserKey to privateKey in that file yourself.");
			if (!keys.contains(key))
				throw CliException.config("Unknown setting '" + key + "' in " + path + ".",
						"The settings are: " + String.join(", ", keys) + ".");
			if (value.isContainerNode())
				throw CliException.config("The setting '" + key + "' in " + path + " must be a single value.", null);

			if (!value.isNull() && !value.asText().isBlank())
				values.put(key, value.asText().strip());
		}

		return new ConfigFile(path, true, values);
	}

	/**
	 * Returns the file this configuration was read from.
	 *
	 * @return the path
	 */
	public Path path() {
		return path;
	}

	/**
	 * Tells whether the file exists.
	 *
	 * @return {@code true} if it was read from a file
	 */
	public boolean exists() {
		return exists;
	}

	/**
	 * Returns a setting.
	 *
	 * @param key the setting, one of {@link #ALL_KEYS}
	 * @return the value, or {@code null} if it is not set
	 */
	public String get(String key) {
		return values.get(key);
	}

	/**
	 * Creates a configuration file, readable by its owner only.
	 *
	 * @param path    the file; it must not exist
	 * @param content the content
	 * @throws CliException if the file exists, which is left unchanged, or cannot be written
	 */
	public static void create(Path path, String content) {
		try {
			FilePermissions.writeNewPrivateFile(path, content);
		} catch (FileAlreadyExistsException e) {
			throw CliException.failed("The configuration file " + path + " already exists; it was not changed.",
					"Change a setting in it with 'config set <key> <value>'.");
		} catch (IOException e) {
			throw CliException.failed("Cannot write the configuration file " + path + ": " + describe(e), null);
		}
	}

	/**
	 * Sets a setting in a configuration file, creating the file if it does not exist.
	 * <p>
	 * The line setting the key is replaced; failing that, a commented-out {@code # key:} line, as the
	 * templates carry; failing that, the setting is appended. Every other line is kept as it is.
	 *
	 * @param path   the file
	 * @param key    the setting, one of {@link #ALL_KEYS}
	 * @param value  the value
	 * @param header the comment starting a new file
	 * @throws CliException if the file cannot be read or written
	 */
	public static void set(Path path, String key, String value, String header) {
		String setting = key + ": " + quote(value);
		if (!Files.exists(path)) {
			create(path, header + setting + System.lineSeparator());
			return;
		}

		List<String> lines = readLines(path);
		Pattern active = activeLine(key);
		Pattern placeholder = Pattern.compile("^#\\s*" + Pattern.quote(key) + "\\s*:.*$");

		int index = -1;
		for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
			String line = it.next();
			if (active.matcher(line).matches()) {
				String current = line.substring(line.indexOf(':') + 1).strip();
				if (current.startsWith("|") || current.startsWith(">"))
					throw CliException.failed("The setting '" + key + "' in " + path + " spans several lines, which 'config set' does not edit.",
							"Edit the file yourself.");
				if (index < 0)
					index = lines.indexOf(line);
			}
		}

		if (index >= 0) {
			lines.set(index, setting);
			// A key set twice would leave YAML to pick one; keep only the line just written.
			for (int i = lines.size() - 1; i > index; i--)
				if (active.matcher(lines.get(i)).matches())
					lines.remove(i);
		} else {
			for (int i = 0; i < lines.size() && index < 0; i++)
				if (placeholder.matcher(lines.get(i)).matches())
					index = i;

			if (index >= 0)
				lines.set(index, setting);
			else
				lines.add(setting);
		}

		writeLines(path, lines);
	}

	/**
	 * Removes a setting from a configuration file.
	 *
	 * @param path the file
	 * @param key  the setting, one of {@link #ALL_KEYS}
	 * @return {@code true} if the file set it
	 * @throws CliException if the file cannot be read or written
	 */
	public static boolean unset(Path path, String key) {
		if (!Files.exists(path))
			return false;

		List<String> lines = readLines(path);
		Pattern active = activeLine(key);
		boolean removed = lines.removeIf(line -> active.matcher(line).matches());
		if (removed)
			writeLines(path, lines);
		return removed;
	}

	/**
	 * Quotes a value for YAML, if it needs quoting to be read back as the same string.
	 *
	 * @param value the value
	 * @return the value, plain or single-quoted
	 */
	static String quote(String value) {
		if (PLAIN_SCALAR.matcher(value).matches() && !RESERVED_WORDS.contains(value.toLowerCase()))
			return value;
		return "'" + value.replace("'", "''") + "'";
	}

	/**
	 * Builds the content of a new configuration file, with a comment explaining each setting.
	 *
	 * @param tool     the tool the file is for
	 * @param url      the Director URL, or {@code null} to leave it commented out
	 * @param nodeId   the node id, or {@code null} to leave it commented out
	 * @param resolve  the address to connect to, or {@code null} to leave it commented out
	 * @return the content
	 */
	public static String template(ToolSpec tool, String url, String nodeId, String resolve) {
		String n = System.lineSeparator();
		return "# " + tool.name() + " configuration." + n +
				"#" + n +
				"# Change a setting with '" + tool.name() + " config set <key> <value>', or edit this file." + n +
				"# Command-line options and BOSON_* environment variables override it." + n +
				n +
				"# The Director of the super node: scheme, host and port. Use https:// for" + n +
				"# any Director that is not on this machine." + n +
				setting(URL, url, "https://node.example.com:9000") + n +
				n +
				"# The super node's id. Optional, but set it whenever you know it: requests" + n +
				"# are bound to it, and a self-signed Director certificate is accepted only" + n +
				"# when it is pinned to this id." + n +
				setting(NODE_ID, nodeId, "") + n +
				n +
				"# Optional: connect to this IP address, with an optional port, instead of" + n +
				"# looking up the URL's host name. TLS is still verified against the name." + n +
				setting(RESOLVE, resolve, "127.0.0.1") + n +
				n +
				"# The identity file requests are signed with, relative to this file." + n +
				"# Default: " + tool.defaultIdentityFileName() + n +
				"# " + IDENTITY + ": " + tool.defaultIdentityFileName() + n +
				(tool.hasDevice() ? clientTemplate(tool) : "");
	}

	private static String clientTemplate(ToolSpec tool) {
		String n = System.lineSeparator();
		return n +
				"# The key of this device, relative to this file. The node's services -" + n +
				"# objects, the DHT, the proxy - are used as a device of your account:" + n +
				"# register it with '" + tool.name() + " device add --name <name>'." + n +
				"# Default: " + tool.defaultDeviceIdentityFileName() + n +
				"# " + DEVICE_IDENTITY + ": " + tool.defaultDeviceIdentityFileName() + n +
				n +
				"# Optional: the user this device acts for, when the user identity is not" + n +
				"# on this machine. With a user identity, it is that identity's id." + n +
				"# " + USER_ID + ":" + n +
				n +
				"# The defaults of '" + tool.name() + " proxy start': the local service to" + n +
				"# expose, as host:port or scheme://host:port, whether to ask for a named" + n +
				"# https endpoint, and whether to announce the endpoint on the DHT." + n +
				"# " + PROXY_UPSTREAM + ": localhost:8080" + n +
				"# " + PROXY_NAME_ACCESS + ": false" + n +
				"# " + PROXY_ANNOUNCE + ": false" + n;
	}

	/**
	 * The comment that starts a configuration file created by setting a value in it.
	 *
	 * @param tool the tool the file is for
	 * @return the header
	 */
	public static String header(ToolSpec tool) {
		String n = System.lineSeparator();
		return "# " + tool.name() + " configuration. See '" + tool.name() + " config --help'." + n + n;
	}

	private static String setting(String key, String value, String example) {
		if (value != null)
			return key + ": " + quote(value);
		return example.isEmpty() ? "# " + key + ":" : "# " + key + ": " + example;
	}

	private static Pattern activeLine(String key) {
		return Pattern.compile("^" + Pattern.quote(key) + "\\s*:.*$");
	}

	private static List<String> readLines(Path path) {
		try {
			return new ArrayList<>(Files.readAllLines(path));
		} catch (IOException e) {
			throw CliException.failed("Cannot read the configuration file " + path + ": " + describe(e), null);
		}
	}

	private static void writeLines(Path path, List<String> lines) {
		try {
			// Rewritten in place, so the file keeps its permissions.
			Files.writeString(path, String.join(System.lineSeparator(), lines) + System.lineSeparator());
		} catch (IOException e) {
			throw CliException.failed("Cannot write the configuration file " + path + ": " + describe(e), null);
		}
	}

	private static String describe(IOException e) {
		if (e instanceof AccessDeniedException)
			return "permission denied";
		if (e instanceof NoSuchFileException)
			return "no such file";
		return e.getMessage();
	}
}
