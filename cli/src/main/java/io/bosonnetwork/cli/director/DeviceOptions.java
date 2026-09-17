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

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The option of a tool that acts as a device as well as a user: the device identity, which overrides
 * the configuration.
 */
public class DeviceOptions {
	@Option(names = {"-d", "--device-identity"}, paramLabel = "<file>", scope = ScopeType.INHERIT,
			description = "The device identity file to act with (env: BOSON_DEVICE_IDENTITY, or BOSON_DEVICE_PRIVATE_KEY "
					+ "for the key itself).")
	Path deviceIdentity;

	/**
	 * Returns the device identity file named on the command line.
	 *
	 * @return the file, or {@code null}
	 */
	public Path deviceIdentity() {
		return deviceIdentity;
	}
}
