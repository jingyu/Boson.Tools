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

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.ErrorReporter;
import io.bosonnetwork.cli.common.ErrorTranslator;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.director.client.exceptions.ConflictException;
import io.bosonnetwork.director.client.exceptions.DirectorException;
import io.bosonnetwork.director.client.exceptions.DirectorServerException;
import io.bosonnetwork.director.client.exceptions.ForbiddenException;
import io.bosonnetwork.director.client.exceptions.InvalidRequestException;
import io.bosonnetwork.director.client.exceptions.NotEnabledException;
import io.bosonnetwork.director.client.exceptions.NotFoundException;
import io.bosonnetwork.director.client.exceptions.PassphraseRequiredException;
import io.bosonnetwork.director.client.exceptions.RateLimitException;
import io.bosonnetwork.director.client.exceptions.RegistrationDisabledException;
import io.bosonnetwork.director.client.exceptions.ServiceBusyException;
import io.bosonnetwork.director.client.exceptions.UnauthorizedException;

/**
 * What a Director's refusals and an unreachable Director mean to the user: why the request failed,
 * what to do about it, and the exit code. HTTP statuses and TLS internals stay out of the message.
 */
public class DirectorErrors implements ErrorTranslator {
	private final CliContext context;

	/**
	 * Creates the translator of a run.
	 *
	 * @param context the context of the run
	 */
	public DirectorErrors(CliContext context) {
		this.context = context;
	}

	@Override
	public CliException translate(Throwable error) {
		if (!(error instanceof DirectorException e))
			return null;

		String detail = detail(e);

		if (e instanceof PassphraseRequiredException)
			return new CliException(ExitCode.NOT_AUTHORIZED, "This account is protected by a passphrase.",
					"Pass --passphrase to be asked for it.");

		if (e instanceof UnauthorizedException) {
			Id id = context.identityId();
			String role = context.tool().identityRole();
			String who = id != null ? "the " + role + " identity " + id : "this " + role + " identity";
			return new CliException(ExitCode.NOT_AUTHORIZED, "The Director did not accept " + who + suffix(detail),
					context.tool().unauthorizedHint());
		}

		if (e instanceof ForbiddenException)
			return new CliException(ExitCode.NOT_AUTHORIZED, "The Director refused the request" + suffix(detail), null);
		if (e instanceof NotFoundException)
			return CliException.notFound(detail != null ? ErrorReporter.sentence(detail) : "Not found.", null);
		if (e instanceof ConflictException)
			return new CliException(ExitCode.CONFLICT,
					detail != null ? ErrorReporter.sentence(detail) : "It already exists.", null);
		if (e instanceof RateLimitException limit)
			return new CliException(ExitCode.UNAVAILABLE, "The Director is limiting the rate of requests" + suffix(detail),
					retryHint(limit.getRetryAfter()));
		if (e instanceof ServiceBusyException busy)
			return new CliException(ExitCode.UNAVAILABLE, "The Director is too busy to handle the request" + suffix(detail),
					retryHint(busy.getRetryAfter()));
		if (e instanceof RegistrationDisabledException)
			return CliException.failed(ErrorReporter.sentence(detail != null ? detail : e.getMessage()), null);
		if (e instanceof NotEnabledException)
			return CliException.failed("The super node does not offer this" + suffix(detail), null);
		if (e instanceof InvalidRequestException)
			return CliException.failed("The Director rejected the request as invalid" + suffix(detail), null);
		if (e instanceof DirectorServerException)
			return new CliException(ExitCode.UNAVAILABLE,
					"The Director failed to handle the request (HTTP " + e.getStatus() + ")" + suffix(detail),
					"The super node's log has the details.");

		int status = e.getStatus();
		if (status == DirectorException.NO_HTTP_STATUS)
			return ConnectionFailures.director(e, context.directorUrl(), context.tool());
		if (status >= 200 && status < 300)
			return CliException.failed("The Director answered with something this tool cannot read.",
					"Check that the URL is the Director of a Boson super node, of a version this tool supports.");
		if (status >= 300 && status < 400)
			return CliException.failed("The Director URL redirects elsewhere (HTTP " + status + "), which this tool does not follow.",
					"Use the URL it redirects to; often that is the https:// one.");

		return CliException.failed("The Director refused the request (HTTP " + status + ")" + suffix(detail), null);
	}

	// The Director explains a refusal as "<reason> - <detail>"; the reason only restates the status.
	private static String detail(DirectorException e) {
		String message = e.getMessage();
		if (message == null || message.isBlank() || message.equals("HTTP " + e.getStatus()))
			return null;

		// An HTML error page from a proxy in front of the Director says nothing useful here.
		if (message.startsWith("<"))
			return null;

		int separator = message.indexOf(" - ");
		String detail = separator >= 0 ? message.substring(separator + 3) : message;
		return detail.isBlank() ? null : detail.strip();
	}

	private static String suffix(String detail) {
		return detail == null ? "." : ": " + ErrorReporter.sentence(detail);
	}

	private static String retryHint(long seconds) {
		return seconds > 0 ? "Try again in " + seconds + (seconds == 1 ? " second." : " seconds.") : "Try again later.";
	}
}
