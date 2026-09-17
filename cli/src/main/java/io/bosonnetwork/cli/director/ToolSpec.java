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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What sets one command line tool apart from the other where they share code: its name, where its
 * configuration and identity live by default, whose identity it acts with, and which settings its
 * configuration file takes.
 */
public final class ToolSpec {
	private final String name;
	private final Path defaultConfigFile;
	private final String defaultIdentityFileName;
	private final String identityRole;
	private final String unauthorizedHint;
	private final List<String> configKeys;
	private final String defaultDeviceIdentityFileName;

	/**
	 * Describes a tool whose configuration file takes the Director settings only
	 * ({@link ConfigFile#DIRECTOR_KEYS}).
	 *
	 * @param name                    the command name, such as {@code boson-cli}
	 * @param defaultConfigFile       the configuration file used unless another is named
	 * @param defaultIdentityFileName the identity file used unless another is named, beside the
	 *                                configuration file
	 * @param identityRole            whose identity the tool acts with, such as {@code "user"}
	 * @param unauthorizedHint        how to fix an identity the Director does not accept
	 */
	public ToolSpec(String name, Path defaultConfigFile, String defaultIdentityFileName, String identityRole,
			String unauthorizedHint) {
		this(name, defaultConfigFile, defaultIdentityFileName, identityRole, unauthorizedHint, ConfigFile.DIRECTOR_KEYS,
				null);
	}

	/**
	 * Describes a tool.
	 *
	 * @param name                          the command name, such as {@code boson-cli}
	 * @param defaultConfigFile             the configuration file used unless another is named
	 * @param defaultIdentityFileName       the identity file used unless another is named, beside the
	 *                                      configuration file
	 * @param identityRole                  whose identity the tool acts with, such as {@code "user"}
	 * @param unauthorizedHint              how to fix an identity the Director does not accept
	 * @param configKeys                    the settings its configuration file takes, from
	 *                                      {@link ConfigFile#ALL_KEYS}, in the order they are shown
	 * @param defaultDeviceIdentityFileName the device identity file used unless another is named, beside
	 *                                      the configuration file, or {@code null} for a tool that acts
	 *                                      with no device
	 */
	public ToolSpec(String name, Path defaultConfigFile, String defaultIdentityFileName, String identityRole,
			String unauthorizedHint, List<String> configKeys, String defaultDeviceIdentityFileName) {
		if (!ConfigFile.ALL_KEYS.containsAll(configKeys))
			throw new IllegalArgumentException("Unknown configuration keys in " + configKeys);
		if (configKeys.contains(ConfigFile.DEVICE_IDENTITY) != (defaultDeviceIdentityFileName != null))
			throw new IllegalArgumentException("A tool with a device identity names its default file, and only then");

		this.configKeys = List.copyOf(configKeys);
		this.defaultDeviceIdentityFileName = defaultDeviceIdentityFileName;
		this.name = Objects.requireNonNull(name, "name");
		this.defaultConfigFile = Objects.requireNonNull(defaultConfigFile, "defaultConfigFile");
		this.defaultIdentityFileName = Objects.requireNonNull(defaultIdentityFileName, "defaultIdentityFileName");
		this.identityRole = Objects.requireNonNull(identityRole, "identityRole");
		this.unauthorizedHint = Objects.requireNonNull(unauthorizedHint, "unauthorizedHint");
	}

	/**
	 * Returns the command name.
	 *
	 * @return the name, such as {@code boson-cli}
	 */
	public String name() {
		return name;
	}

	/**
	 * Returns the configuration file used unless another is named.
	 *
	 * @return the file
	 */
	public Path defaultConfigFile() {
		return defaultConfigFile;
	}

	/**
	 * Returns the name of the identity file used unless another is named, beside the configuration
	 * file.
	 *
	 * @return the file name, such as {@code user.identity}
	 */
	public String defaultIdentityFileName() {
		return defaultIdentityFileName;
	}

	/**
	 * Returns the settings the tool's configuration file takes.
	 *
	 * @return the keys, in the order they are shown
	 */
	public List<String> configKeys() {
		return configKeys;
	}

	/**
	 * Tells whether the tool acts with a device identity, as well as its own.
	 *
	 * @return {@code true} if the configuration takes a device identity
	 */
	public boolean hasDevice() {
		return defaultDeviceIdentityFileName != null;
	}

	/**
	 * Returns the name of the device identity file used unless another is named, beside the
	 * configuration file.
	 *
	 * @return the file name, such as {@code device.identity}
	 * @throws IllegalStateException if the tool acts with no device
	 */
	public String defaultDeviceIdentityFileName() {
		if (defaultDeviceIdentityFileName == null)
			throw new IllegalStateException(name + " acts with no device");
		return defaultDeviceIdentityFileName;
	}

	/**
	 * Returns whose identity the tool acts with.
	 *
	 * @return the role, such as {@code "user"} or {@code "administrator"}
	 */
	public String identityRole() {
		return identityRole;
	}

	/**
	 * Returns how to fix an identity the Director does not accept.
	 *
	 * @return the hint
	 */
	public String unauthorizedHint() {
		return unauthorizedHint;
	}

	/**
	 * Returns a command line of this tool, for hints.
	 *
	 * @param arguments the arguments, such as {@code "identity create"}
	 * @return the command, quoted, such as {@code 'boson-cli identity create'}
	 */
	public String command(String arguments) {
		return "'" + name + " " + arguments + "'";
	}
}
