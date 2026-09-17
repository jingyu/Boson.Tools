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

package io.bosonnetwork.cli.services;

import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.ErrorReporter;
import io.bosonnetwork.cli.common.ErrorTranslator;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.director.CliContext;
import io.bosonnetwork.cli.director.ConnectionFailures;
import io.bosonnetwork.cli.director.DirectorErrors;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.higgs.exceptions.ForbiddenException;
import io.bosonnetwork.higgs.exceptions.GatewayServerException;
import io.bosonnetwork.higgs.exceptions.GatewayTimeoutException;
import io.bosonnetwork.higgs.exceptions.HiggsException;
import io.bosonnetwork.higgs.exceptions.NotFoundException;
import io.bosonnetwork.higgs.exceptions.PreconditionFailedException;
import io.bosonnetwork.ionstore.exceptions.DecryptionException;
import io.bosonnetwork.ionstore.exceptions.IonStoreException;
import io.bosonnetwork.ionstore.exceptions.IonStoreIOException;
import io.bosonnetwork.ionstore.exceptions.IonStoreServerException;
import io.bosonnetwork.ionstore.exceptions.MetabaseException;
import io.bosonnetwork.ionstore.exceptions.ObjectIntegrityException;
import io.bosonnetwork.ionstore.exceptions.ObjectNotFoundException;
import io.bosonnetwork.ionstore.exceptions.ObjectTooLargeException;
import io.bosonnetwork.ionstore.exceptions.PeerNotFoundException;
import io.bosonnetwork.ionstore.exceptions.PeerRequestException;
import io.bosonnetwork.ionstore.exceptions.PeerResponseException;
import io.bosonnetwork.ionstore.exceptions.QuotaExceededException;
import io.bosonnetwork.ionstore.exceptions.TtlExceededException;

/**
 * What the refusals of the super node's services, and a service that cannot be reached, mean to the
 * user. The Director's failures are left to {@link DirectorErrors}.
 */
public class ServiceErrors implements ErrorTranslator {
	/** What messages call the Ion Store. */
	public static final String ION_STORE = "Ion Store";
	/** What messages call the web gateway. */
	public static final String WEB_GATEWAY = "Web Gateway";
	/** What messages call the Active Proxy. */
	public static final String ACTIVE_PROXY = "Active Proxy";

	private final CliContext context;
	private final DirectorErrors director;

	/**
	 * Creates the translator of a run.
	 *
	 * @param context the context of the run
	 */
	public ServiceErrors(CliContext context) {
		this.context = context;
		this.director = new DirectorErrors(context);
	}

	@Override
	public CliException translate(Throwable error) {
		if (error instanceof IonStoreException e)
			return ionStore(e);
		if (error instanceof HiggsException e)
			return gateway(e);
		return director.translate(error);
	}

	// Why a service did not accept the device: almost always a device that is not registered, or one
	// registered to another user.
	private String deviceHint() {
		String device = context.deviceId() != null ? "device " + context.deviceId() : "this device";
		String user = context.identityId() != null ? "user " + context.identityId() : "your user";
		return "Check that " + device + " is registered to " + user + " with " + context.tool().command("device list") +
				", and register it with " + context.tool().command("device add --name <name>") + ".";
	}

	private CliException ionStore(IonStoreException e) {
		String detail = detail(e.getMessage());

		if (e instanceof io.bosonnetwork.ionstore.exceptions.UnauthorizedException)
			return new CliException(ExitCode.NOT_AUTHORIZED, "The Ion Store did not accept this device" + suffix(detail),
					deviceHint());
		if (e instanceof TtlExceededException)
			return CliException.usage("The lifetime asked for is longer than the node allows" + suffix(detail),
					"Use a shorter --ttl, or leave it out for the node's default.");
		if (e instanceof io.bosonnetwork.ionstore.exceptions.ForbiddenException)
			return new CliException(ExitCode.NOT_AUTHORIZED, "The Ion Store refused the request" + suffix(detail),
					"Your plan may not include storage; " + context.tool().command("user show") + " names it.");
		if (e instanceof QuotaExceededException)
			return CliException.failed("Your storage quota is used up" + suffix(detail),
					"Remove objects you no longer need: " + context.tool().command("ionstore list") + ", then " +
							context.tool().command("ionstore remove <id>") + ".");
		if (e instanceof ObjectTooLargeException)
			return CliException.failed("The object is larger than the node accepts" + suffix(detail), null);
		if (e instanceof ObjectNotFoundException)
			return CliException.notFound(detail != null ? ErrorReporter.sentence(detail) : "No such object.", null);
		if (e instanceof ObjectIntegrityException)
			return CliException.failed("The object failed its integrity check" + suffix(detail),
					"Try again; if it keeps failing, the stored copy is damaged.");
		if (e instanceof DecryptionException)
			return CliException.failed(ErrorReporter.sentence(e.getMessage()), null);
		if (e instanceof PeerNotFoundException)
			return CliException.notFound("The Ion Store node holding the object cannot be found on the DHT" + suffix(detail),
					"Check the peer id in the object's address.");
		if (e instanceof PeerRequestException || e instanceof PeerResponseException)
			return new CliException(ExitCode.UNAVAILABLE, "Your super node could not fetch the object from the node " +
					"holding it" + suffix(detail), "Try again later.");
		if (e instanceof io.bosonnetwork.ionstore.exceptions.RateLimitException limit)
			return new CliException(ExitCode.UNAVAILABLE, "The Ion Store is limiting the rate of requests" + suffix(detail),
					retryHint(limit.getRetryAfter()));
		if (e instanceof io.bosonnetwork.ionstore.exceptions.ServiceBusyException busy)
			return new CliException(ExitCode.UNAVAILABLE, "The Ion Store is too busy to handle the request" + suffix(detail),
					retryHint(busy.getRetryAfter()));
		if (e instanceof io.bosonnetwork.ionstore.exceptions.InvalidRequestException)
			return CliException.failed("The Ion Store rejected the request as invalid" + suffix(detail), null);
		if (e instanceof IonStoreServerException || e instanceof IonStoreIOException || e instanceof MetabaseException)
			return new CliException(ExitCode.UNAVAILABLE, "The Ion Store failed to handle the request" + suffix(detail),
					"The super node's log has the details.");

		if (e.getStatus() == IonStoreException.NO_HTTP_STATUS)
			return e.getCause() != null ?
					ConnectionFailures.service(e, ION_STORE, context.serviceUrl(NodeStatus.Service.ION_STORE)) :
					CliException.failed(ErrorReporter.sentence(e.getMessage()), null);

		return CliException.failed("The Ion Store refused the request (HTTP " + e.getStatus() + ")" + suffix(detail), null);
	}

	private CliException gateway(HiggsException e) {
		String detail = detail(e.getMessage());

		if (e instanceof io.bosonnetwork.higgs.exceptions.UnauthorizedException)
			return new CliException(ExitCode.NOT_AUTHORIZED, "The web gateway did not accept this device" + suffix(detail),
					deviceHint());
		if (e instanceof ForbiddenException) {
			String hint = detail != null && detail.toLowerCase().contains("quota") ?
					"Remove what you no longer need kept: " + context.tool().command("dht value list") + " and " +
							context.tool().command("dht peer list") + " show it." : null;
			return new CliException(ExitCode.NOT_AUTHORIZED, "The web gateway refused the request" + suffix(detail), hint);
		}
		if (e instanceof NotFoundException)
			return CliException.notFound(detail != null ? ErrorReporter.sentence(detail) : "Not found.", null);
		if (e instanceof PreconditionFailedException)
			return new CliException(ExitCode.CONFLICT, "A newer version is already stored" + suffix(detail),
					"Run the command again: it continues from the newest version.");
		if (e instanceof io.bosonnetwork.higgs.exceptions.RateLimitException limit)
			return new CliException(ExitCode.UNAVAILABLE, "The web gateway is limiting the rate of requests" + suffix(detail),
					retryHint(limit.getRetryAfter()));
		if (e instanceof io.bosonnetwork.higgs.exceptions.ServiceBusyException busy)
			return new CliException(ExitCode.UNAVAILABLE, "The web gateway is too busy to handle the request" + suffix(detail),
					retryHint(busy.getRetryAfter()));
		if (e instanceof GatewayTimeoutException)
			return new CliException(ExitCode.UNAVAILABLE, "The DHT did not answer in time" + suffix(detail),
					"Try again; a DHT operation can take a while when the network is busy.");
		if (e instanceof io.bosonnetwork.higgs.exceptions.InvalidRequestException)
			return CliException.failed("The web gateway rejected the request as invalid" + suffix(detail), null);
		if (e instanceof GatewayServerException)
			return new CliException(ExitCode.UNAVAILABLE, "The web gateway failed to handle the request" + suffix(detail),
					"The super node's log has the details.");

		if (e.getStatus() == HiggsException.NO_HTTP_STATUS)
			return e.getCause() != null ?
					ConnectionFailures.service(e, WEB_GATEWAY, context.serviceUrl(NodeStatus.Service.WEB_GATEWAY)) :
					new CliException(ExitCode.UNAVAILABLE, "The web gateway is not the one the super node reports" +
							suffix(detail), "Tell the node's operator.");

		return CliException.failed("The web gateway refused the request (HTTP " + e.getStatus() + ")" + suffix(detail), null);
	}

	// A service error message, unless it only restates the HTTP status or is a proxy's HTML page.
	private static String detail(String message) {
		if (message == null || message.isBlank() || message.startsWith("<") || message.matches("HTTP \\d+"))
			return null;
		return message.strip();
	}

	private static String suffix(String detail) {
		return detail == null ? "." : ": " + ErrorReporter.sentence(detail);
	}

	static String retryHint(long seconds) {
		return seconds > 0 ? "Try again in " + seconds + (seconds == 1 ? " second." : " seconds.") : "Try again later.";
	}
}
