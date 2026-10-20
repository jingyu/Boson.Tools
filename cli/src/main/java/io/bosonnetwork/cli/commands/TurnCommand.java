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

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.TrustOptions;
import picocli.CommandLine.Command;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.cli.director.CliContext;
import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.HybridTrustManager;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.service.AccessScope;
import io.bosonnetwork.web.client.SelfIssuedAccessTokens;

/**
 * The {@code turn} commands of {@code boson-cli}: the super node's TURN relay.
 * <p>
 * There is no TURN client library: getting credentials is one authenticated GET, so the command
 * makes it itself, with the same self-issued device token and the same pinned TLS the other
 * service clients use.
 */
@Command(name = "turn",
		description = {"Get credentials for the super node's TURN relay.",
				"Calls between devices that cannot reach each other directly are relayed through it. Uses this "
						+ "machine's device."},
		subcommands = {TurnCommand.CredentialsCommand.class})
public class TurnCommand extends CliGroup {
	static final String CREDENTIALS_PATH = "/v1/credentials";

	@Command(name = "credentials", aliases = "creds", description = {"Get time-limited TURN credentials.",
			"Prints the username, password and TURN/STUN URIs a WebRTC client - or a TURN test tool such as "
					+ "turnutils_uclient - is configured with. They work until they expire, and only on this "
					+ "super node."})
	public static class CredentialsCommand extends DirectorCommand {
		@Override
		protected void run() throws Exception {
			CliContext context = context();
			context.checkConnection();
			Id userId = context.userId();
			Signature.KeyPair deviceKey = context.deviceKey();
			CliContext.HttpService turn = context.httpService(NodeStatus.Service.TURN, "TURN");
			Id peerId = turn.service().getPeerId();

			SelfIssuedAccessTokens tokens = SelfIssuedAccessTokens.builder(new CryptoIdentity(deviceKey))
					.subject(userId)
					.clientId(Id.of(deviceKey.publicKey().bytes()))
					.scope(AccessScope.CLIENT)
					.audience(peerId)
					.build();
			String token = await(tokens.token().toCompletionStage().toCompletableFuture());

			output().progress("Asking the TURN service at " + turn.url() + " for credentials...");
			JsonObject body = fetch(context, turn.url(), peerId, token);

			if (output().isJson()) {
				output().json(body.getMap());
				return;
			}

			long ttl = body.getLong("ttl", 0L);
			Map<String, String> rows = new LinkedHashMap<>();
			rows.put("Username", body.getString("username"));
			rows.put("Password", body.getString("password"));
			rows.put("Expires", ttl > 0 ? Formats.time(System.currentTimeMillis() + ttl * 1000) : Formats.NONE);
			JsonArray uris = body.getJsonArray("uris", new JsonArray());
			rows.put("URIs", uris.isEmpty() ? Formats.NONE : String.join("\n", uris.stream().map(String::valueOf).toList()));
			output().details(rows);
		}

		private JsonObject fetch(CliContext context, URL url, Id peerId, String token) throws Exception {
			boolean ssl = url.getProtocol().equals("https");
			int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
			// The service is reached at its endpoint's path: behind a proxy that is a prefix, such as /turn.
			String path = url.getPath().replaceAll("/+$", "") + CREDENTIALS_PATH;

			HttpClientOptions options = new HttpClientOptions()
					.setSsl(ssl)
					.setConnectTimeout(10_000)
					.setIdleTimeout(30)
					.setIdleTimeoutUnit(TimeUnit.SECONDS);
			if (ssl)
				options.setEnabledSecureTransportProtocols(Set.of("TLSv1.2", "TLSv1.3"))
						.setTrustOptions(TrustOptions.wrap(new HybridTrustManager(peerId.toString(), peerId.bytesUnsafe())));

			HttpClient client = context.vertx().createHttpClient(options);
			try {
				RequestOptions request = new RequestOptions()
						.setMethod(HttpMethod.GET)
						.setHost(url.getHost())
						.setPort(port)
						.setURI(path)
						.putHeader("Authorization", "Bearer " + token)
						.putHeader("Accept", "application/json");

				Response response = await(client.request(request)
						.compose(r -> r.send())
						.compose(r -> r.body().map(b -> new Response(r, b)))
						.toCompletionStage().toCompletableFuture());

				int status = response.http().statusCode();
				if (status == 200)
					return response.body().toJsonObject();
				throw failure(status, response, url);
			} finally {
				client.close();
			}
		}

		private record Response(HttpClientResponse http, Buffer body) {
		}

		private CliException failure(int status, Response response, URL url) {
			String message;
			try {
				message = response.body().toJsonObject().getString("message", "");
			} catch (Exception e) {
				message = "";
			}
			String detail = message.isBlank() ? "" : ": " + message;
			String retryAfter = response.http().getHeader("Retry-After");

			return switch (status) {
				case 401 -> new CliException(ExitCode.NOT_AUTHORIZED, "The TURN service did not accept this device" + detail + ".",
						"Check that this device is registered with the super node: " + tool().command("device list") + ".");
				case 403 -> new CliException(ExitCode.NOT_AUTHORIZED, "The TURN service refused credentials" + detail + ".",
						"Your plan may not include the call relay; ask the node's operator.");
				case 429 -> new CliException(ExitCode.UNAVAILABLE, "Too many credential requests" + detail + ".",
						retryAfter != null ? "Try again in " + retryAfter + " seconds." : "Try again later.");
				case 503 -> new CliException(ExitCode.UNAVAILABLE, "The TURN relay is not available right now" + detail + ".",
						"Tell the node's operator if it persists.");
				default -> CliException.failed("The TURN service at " + url + " answered HTTP " + status + detail + ".", null);
			};
		}
	}

}
