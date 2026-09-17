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

package io.bosonnetwork.cli.testing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;

import io.bosonnetwork.Id;
import io.bosonnetwork.json.Json;

/**
 * A stand-in for a Director, and for the services a super node offers: answers each request with the
 * reply set for its method and path, and records the requests. Tokens are not checked; the Director's
 * and the services' own tests cover that.
 */
public final class StubDirector implements AutoCloseable {
	/**
	 * A request the stub received.
	 *
	 * @param method the method
	 * @param path   the path
	 * @param query  the query string, or {@code null}
	 * @param bytes  the body
	 */
	public record Request(String method, String path, String query, byte[] bytes) {
		/**
		 * Returns the body as text.
		 *
		 * @return the body
		 */
		public String body() {
			return new String(bytes, StandardCharsets.UTF_8);
		}

		/**
		 * Parses the body as a JSON object.
		 *
		 * @return the object
		 */
		public Map<String, Object> json() {
			return Json.parse(body());
		}
	}

	/**
	 * A reply.
	 *
	 * @param status  the status
	 * @param body    the body
	 * @param headers the headers
	 */
	public record Reply(int status, byte[] body, Map<String, String> headers) {
		/**
		 * Creates a JSON reply.
		 *
		 * @param status the status
		 * @param body   the body
		 * @return the reply
		 */
		public static Reply json(int status, String body) {
			return new Reply(status, body.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));
		}
	}

	private final Vertx vertx;
	private final HttpServer server;
	private final Map<String, Function<Request, Reply>> replies = new ConcurrentHashMap<>();
	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private StubDirector(Vertx vertx) throws Exception {
		this.vertx = vertx;
		this.server = vertx.createHttpServer()
				.requestHandler(req -> req.body().onSuccess(body -> {
					Request request = new Request(req.method().name(), req.path(), req.query(), body.getBytes());
					requests.add(request);
					Function<Request, Reply> handler = replies.get(req.method().name() + " " + req.path());
					if (handler == null) {
						req.response().setStatusCode(404).end("Not Found - no stub reply for " + req.method() + " " + req.path());
						return;
					}

					Reply reply = handler.apply(request);
					req.response().setStatusCode(reply.status());
					reply.headers().forEach(req.response()::putHeader);
					req.response().end(Buffer.buffer(reply.body()));
				}))
				.listen(0, "127.0.0.1")
				.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	/**
	 * Starts a stub.
	 *
	 * @return the stub
	 * @throws Exception if it cannot listen
	 */
	public static StubDirector start() throws Exception {
		return new StubDirector(Vertx.vertx());
	}

	/**
	 * Sets the reply to a request.
	 *
	 * @param method the method
	 * @param path   the path, without the query string
	 * @param status the status
	 * @param body   the body
	 * @return this stub
	 */
	public StubDirector reply(String method, String path, int status, String body) {
		Reply reply = Reply.json(status, body);
		return handle(method, path, request -> reply);
	}

	/**
	 * Sets what answers a request.
	 *
	 * @param method  the method
	 * @param path    the path, without the query string
	 * @param handler builds the reply from the request
	 * @return this stub
	 */
	public StubDirector handle(String method, String path, Function<Request, Reply> handler) {
		replies.put(method + " " + path, handler);
		return this;
	}

	/**
	 * Answers the node id lookups of both APIs.
	 *
	 * @param nodeId the node id
	 * @return this stub
	 */
	public StubDirector nodeId(Id nodeId) {
		String body = "{\"id\": \"" + nodeId.toBase58String() + "\"}";
		reply("GET", "/api/v1/client/id", 200, body);
		reply("GET", "/api/v1/admin/id", 200, body);
		return this;
	}

	/**
	 * Answers the node status lookup, naming services the stub itself answers for.
	 *
	 * @param nodeId   the node id
	 * @param services the services: id, peer id and endpoint, three strings each
	 * @return this stub
	 */
	public StubDirector nodeStatus(Id nodeId, String... services) {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < services.length; i += 3) {
			if (i > 0)
				list.append(", ");
			list.append("{\"serviceId\": \"").append(services[i]).append("\", \"peerId\": \"").append(services[i + 1])
					.append("\", \"endpoint\": \"").append(services[i + 2]).append("\"}");
		}
		return reply("GET", "/api/v1/client/node", 200, "{\"nodeId\": \"" + nodeId + "\", \"running\": true, " +
				"\"startedAt\": 1, \"services\": [" + list + "]}");
	}

	/**
	 * Forgets the replies and the requests.
	 */
	public void reset() {
		replies.clear();
		requests.clear();
	}

	/**
	 * Returns the stub's URL.
	 *
	 * @return the URL
	 */
	public String url() {
		return "http://127.0.0.1:" + port();
	}

	/**
	 * Returns the port the stub listens on.
	 *
	 * @return the port
	 */
	public int port() {
		return server.actualPort();
	}

	/**
	 * Returns the requests received, in order.
	 *
	 * @return the requests
	 */
	public List<Request> requests() {
		return requests;
	}

	/**
	 * Returns the requests with a method and path.
	 *
	 * @param method the method
	 * @param path   the path
	 * @return the requests, in order
	 */
	public List<Request> requests(String method, String path) {
		return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
	}

	@Override
	public void close() throws Exception {
		vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}
}
