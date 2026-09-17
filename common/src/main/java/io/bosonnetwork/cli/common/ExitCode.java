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

package io.bosonnetwork.cli.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exit codes of the Boson command line tools. Both tools use the same codes, so a script can
 * tell the common failures apart without parsing messages.
 */
public final class ExitCode {
	/** The command did what it was asked. */
	public static final int OK = 0;
	/** The command failed: the super node refused the request, or a check failed. */
	public static final int FAILED = 1;
	/** The command line is wrong: an unknown command or option, or an invalid value. */
	public static final int USAGE = 2;
	/** The configuration or identity is missing or invalid. */
	public static final int CONFIG = 3;
	/** What the command names does not exist. */
	public static final int NOT_FOUND = 4;
	/** The Director did not accept the identity, the passphrase, or the request's permissions. */
	public static final int NOT_AUTHORIZED = 5;
	/** What the command would create already exists. */
	public static final int CONFLICT = 6;
	/** The Director or a service could not be reached, is busy or rate limiting, or failed internally. */
	public static final int UNAVAILABLE = 7;

	private ExitCode() {
	}

	/**
	 * Describes every exit code, for the help of a tool's root command.
	 *
	 * @return the descriptions, keyed by exit code, in order
	 */
	public static Map<String, String> descriptions() {
		Map<String, String> codes = new LinkedHashMap<>();
		codes.put(Integer.toString(OK), "Success");
		codes.put(Integer.toString(FAILED), "The command failed");
		codes.put(Integer.toString(USAGE), "Invalid command line");
		codes.put(Integer.toString(CONFIG), "Missing or invalid configuration or identity");
		codes.put(Integer.toString(NOT_FOUND), "Not found");
		codes.put(Integer.toString(NOT_AUTHORIZED), "Not authorized: identity, passphrase or permission");
		codes.put(Integer.toString(CONFLICT), "Already exists");
		codes.put(Integer.toString(UNAVAILABLE), "Super node or service unreachable, busy or failing");
		return codes;
	}
}
