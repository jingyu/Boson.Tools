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
import java.net.ConnectException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.bosonnetwork.cli.common.Causes;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.ExitCode;

/**
 * What a request that got no answer means to the user: a refused connection, a name that does not
 * resolve, an untrusted certificate, a timeout. The Director is reached at the URL the user configured,
 * and a service at the endpoint the super node advertises, so the hints differ: one is the user's to
 * fix, the other the node operator's.
 */
public final class ConnectionFailures {
	private static final String OPERATOR_HINT = "If it persists, tell the node's operator.";

	private ConnectionFailures() {
	}

	/**
	 * Describes the failure of a request to the Director.
	 *
	 * @param error the failure
	 * @param url   the Director URL, or {@code null} if unknown
	 * @param tool  the tool
	 * @return what to tell the user
	 */
	public static CliException director(Throwable error, URL url, ToolSpec tool) {
		String where = url != null ? url.toString() : "the Director";
		String host = url != null ? url.getHost() : "the Director";
		String chain = Causes.messages(error);
		String reason = Causes.describe(Causes.rootCause(error));

		if (isPlainHttp(error, chain))
			return new CliException(ExitCode.UNAVAILABLE,
					host + " did not answer the TLS handshake with TLS: it serves plain HTTP on this port.",
					"Use an http:// URL for a Director with ssl: false or behind a TLS-terminating proxy, and https:// for one serving TLS itself.");

		if (isUntrusted(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "The TLS certificate of " + host + " is not trusted: " + reason,
					"For a self-signed Director certificate, set the node id (" + tool.command("config set nodeId <id>") +
					"); for a certificate from a CA, use the host name it was issued for.");

		if (Causes.hasCause(error, SSLException.class))
			return new CliException(ExitCode.UNAVAILABLE, "The TLS connection to " + where + " failed: " + reason,
					"Check the URL scheme: https:// needs a Director that serves TLS.");

		if (Causes.hasCause(error, UnknownHostException.class))
			return new CliException(ExitCode.UNAVAILABLE, "Cannot find the host " + host + ".",
					"Check the host name in the URL, or connect to an address with --resolve.");

		if (Causes.hasCause(error, ConnectException.class))
			return new CliException(ExitCode.UNAVAILABLE,
					"Cannot connect to " + where + ": " + (chain.contains("refused") ? "connection refused." : reason),
					"Check the URL, and that the super node is running and reachable from here.");

		if (isTimeout(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "Timed out connecting to " + where + ".",
					"Check the URL, and that no firewall blocks the port.");

		if (isClosed(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "The connection to " + where + " closed before the Director answered.",
					"Check the URL scheme and port: an https:// URL for a plain HTTP port, or the reverse, ends this way.");

		return new CliException(ExitCode.UNAVAILABLE, "Cannot reach the Director at " + where + ": " + reason,
				"Run the command again with --verbose for the details.");
	}

	/**
	 * Describes the failure of a request to a service of the super node.
	 *
	 * @param error the failure
	 * @param name  the service, as messages call it, such as {@code "Ion Store"}
	 * @param url   the service endpoint, or {@code null} if unknown
	 * @return what to tell the user
	 */
	public static CliException service(Throwable error, String name, URL url) {
		String where = url != null ? " at " + url : "";
		String host = url != null ? url.getHost() : "its host";
		String chain = Causes.messages(error);
		String reason = Causes.describe(Causes.rootCause(error));
		String advertised = "The super node advertises this address for its " + name + " service.";

		if (isPlainHttp(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "The " + name + where +
					" does not serve TLS, though its advertised address is https.", OPERATOR_HINT);

		if (isUntrusted(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "The TLS certificate of the " + name + where +
					" is not trusted: " + reason, "The certificate has to match the service's peer id, which the super node reports. " +
					OPERATOR_HINT);

		if (Causes.hasCause(error, SSLException.class))
			return new CliException(ExitCode.UNAVAILABLE, "The TLS connection to the " + name + where + " failed: " + reason,
					OPERATOR_HINT);

		if (Causes.hasCause(error, UnknownHostException.class))
			return new CliException(ExitCode.UNAVAILABLE, "Cannot find the host " + host + " of the " + name + ".",
					advertised + " Check that this machine can look up the name.");

		if (Causes.hasCause(error, ConnectException.class))
			return new CliException(ExitCode.UNAVAILABLE, "Cannot connect to the " + name + where + ": " +
					(chain.contains("refused") ? "connection refused." : reason),
					advertised + " Check that it is reachable from here. " + OPERATOR_HINT);

		if (isTimeout(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "Timed out waiting for the " + name + where + ".",
					advertised + " Check that no firewall blocks it, and try again.");

		if (isClosed(error, chain))
			return new CliException(ExitCode.UNAVAILABLE, "The connection to the " + name + where +
					" closed before it answered.", "Try again. " + OPERATOR_HINT);

		return new CliException(ExitCode.UNAVAILABLE, "Cannot reach the " + name + where + ": " + reason,
				"Run the command again with --verbose for the details.");
	}

	/**
	 * Tells whether a failure is the transport's - no connection, no answer, a broken TLS session -
	 * rather than something that went wrong on this side of it.
	 *
	 * @param error the failure
	 * @return {@code true} for a transport failure
	 */
	public static boolean isTransport(Throwable error) {
		String chain = Causes.messages(error);
		return Causes.hasCause(error, IOException.class) || isTimeout(error, chain) || isPlainHttp(error, chain) ||
				Causes.hasCause(error, "ConnectTimeoutException") || chain.contains("connection");
	}

	private static boolean isPlainHttp(Throwable error, String chain) {
		return Causes.hasCause(error, "NotSslRecordException") || chain.contains("not an ssl/tls record");
	}

	private static boolean isUntrusted(Throwable error, String chain) {
		return Causes.hasCause(error, CertificateException.class) ||
				(Causes.hasCause(error, SSLHandshakeException.class) &&
						(chain.contains("certificate") || chain.contains("pkix") || chain.contains("trust")));
	}

	private static boolean isTimeout(Throwable error, String chain) {
		return Causes.hasCause(error, TimeoutException.class) || Causes.hasCause(error, "TimeoutException") ||
				chain.contains("timed out");
	}

	private static boolean isClosed(Throwable error, String chain) {
		return Causes.hasCause(error, ClosedChannelException.class) || chain.contains("connection reset") ||
				chain.contains("closed");
	}
}
