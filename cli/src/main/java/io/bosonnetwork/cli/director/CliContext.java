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
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.common.Awaiter;
import io.bosonnetwork.cli.common.CliEnvironment;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.Futures;
import io.bosonnetwork.cli.common.IdentityFile;
import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.cli.common.Terminal;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.DirectorAdmin;
import io.bosonnetwork.director.client.DirectorClient;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.higgs.HiggsNode;
import io.bosonnetwork.higgs.exceptions.HiggsException;
import io.bosonnetwork.ionstore.IonStore;

/**
 * What one run of a Director tool works with: its settings, identity, output and terminal, and the
 * clients built from them - the Director's, and those of the services the super node offers.
 * <p>
 * Everything is created when first needed, so that a command that never talks to a Director - or never
 * needs an identity - does not fail for the lack of one. Closing the context closes the clients and the
 * Vert.x instance they run on.
 * <p>
 * Services are found through the Director: its node status names each service's peer id and
 * endpoint, so nothing about a service is configured. The status is fetched once per run.
 */
public final class CliContext implements Awaiter, AutoCloseable {
	private static final long CLOSE_TIMEOUT_SECONDS = 5;

	private final ToolSpec tool;
	private final CliEnvironment environment;
	private final ConnectionOptions options;
	private final DeviceOptions deviceOptions;
	private final Output output;
	private final Terminal terminal;

	private Settings settings;
	private Vertx vertx;
	private boolean exposureChecked;
	private boolean deviceExposureChecked;
	private Id identityId;
	private Id deviceId;
	private URL directorUrl;
	private NodeStatus nodeStatus;
	private final Map<String, URL> serviceUrls = new HashMap<>();
	private final List<Supplier<CompletableFuture<Void>>> clients = new ArrayList<>();

	/**
	 * Creates the context of a run.
	 *
	 * @param tool          the tool
	 * @param environment   the environment
	 * @param options       the connection options given on the command line
	 * @param deviceOptions the device option given on the command line, or {@code null} for a tool that
	 *                      acts with no device
	 * @param output        where results are written
	 * @param terminal      where the user is asked
	 */
	public CliContext(ToolSpec tool, CliEnvironment environment, ConnectionOptions options, DeviceOptions deviceOptions,
			Output output, Terminal terminal) {
		this.tool = tool;
		this.environment = environment;
		this.options = options;
		this.deviceOptions = deviceOptions;
		this.output = output;
		this.terminal = terminal;
	}

	/**
	 * Returns the tool.
	 *
	 * @return the tool
	 */
	public ToolSpec tool() {
		return tool;
	}

	/**
	 * Returns the environment.
	 *
	 * @return the environment
	 */
	public CliEnvironment environment() {
		return environment;
	}

	/**
	 * Returns the connection options given on the command line.
	 *
	 * @return the options
	 */
	public ConnectionOptions options() {
		return options;
	}

	/**
	 * Returns the output.
	 *
	 * @return the output
	 */
	public Output output() {
		return output;
	}

	/**
	 * Returns the terminal.
	 *
	 * @return the terminal
	 */
	public Terminal terminal() {
		return terminal;
	}

	/**
	 * Returns the settings, reading the configuration file the first time.
	 *
	 * @return the settings
	 * @throws io.bosonnetwork.cli.common.CliException if the configuration file cannot be read
	 */
	public Settings settings() {
		if (settings == null)
			settings = Settings.resolve(tool, environment, options, deviceOptions);
		return settings;
	}

	/**
	 * Returns the key the tool acts with. Warns once if its identity file is open to other users.
	 *
	 * @return the key pair
	 * @throws io.bosonnetwork.cli.common.CliException if there is no identity, or it is not valid
	 */
	public Signature.KeyPair identity() {
		Signature.KeyPair key = settings().identityKey();
		identityId = Id.of(key.publicKey().bytes());

		if (!exposureChecked) {
			exposureChecked = true;
			warnIfExposed(settings().identityFile(), "identity file");
		}

		return key;
	}

	/**
	 * Returns the key of the device the tool acts as. Warns once if its identity file is open to other
	 * users.
	 *
	 * @return the key pair
	 * @throws CliException if there is no device identity, or it is not valid
	 */
	public Signature.KeyPair deviceKey() {
		Signature.KeyPair key = settings().deviceKey();
		deviceId = Id.of(key.publicKey().bytes());

		if (!deviceExposureChecked) {
			deviceExposureChecked = true;
			warnIfExposed(settings().deviceIdentityFile(), "device identity file");
		}

		return key;
	}

	private void warnIfExposed(Path file, String what) {
		if (file != null && IdentityFile.isExposed(file))
			output.warning("The " + what + " " + file + " can be read by other users. Restrict it with: chmod 600 " + file);
	}

	/**
	 * Returns the id of the device the tool last acted as, for messages.
	 *
	 * @return the id, or {@code null} if no device was used
	 */
	public Id deviceId() {
		return deviceId;
	}

	/**
	 * Returns the user a device acts for: the user identity's id, or, without a user identity, the
	 * configured user id.
	 *
	 * @return the user id
	 * @throws CliException if neither is configured, or they disagree
	 */
	public Id userId() {
		Id configured = settings().userIdValue();
		if (settings().identityKeyIfConfigured() != null) {
			Id id = Id.of(identity().publicKey().bytes());
			if (configured != null && !configured.equals(id))
				throw CliException.config("The user id " + configured + " (from " + settings().userId().origin() +
						") is not the id of the user identity, " + id + ".",
						"Remove the user id setting, which the user identity makes unnecessary.");
			return id;
		}

		if (configured != null) {
			identityId = configured;
			return configured;
		}

		throw CliException.config("No user is configured: there is no user identity, and no user id.",
				"Create a user identity with " + tool.command("identity create") + " and register it with " +
						tool.command("user register --device-name <name>") + ". On a device of a user whose key is kept " +
						"elsewhere, set the user id instead: " + tool.command("config set userId <id>") + ".");
	}

	/**
	 * Returns the id of the identity the tool last acted with, for messages.
	 *
	 * @return the id, or {@code null} if no identity was used
	 */
	public Id identityId() {
		return identityId;
	}

	/**
	 * Returns the URL of the Director the tool last connected to, for messages.
	 *
	 * @return the URL, or {@code null} if no client was built
	 */
	public URL directorUrl() {
		return directorUrl;
	}

	/**
	 * Returns the Vert.x instance the clients run on, creating it the first time.
	 *
	 * @return the Vert.x instance
	 */
	public Vertx vertx() {
		if (vertx == null) {
			vertx = Vertx.vertx(new VertxOptions()
					// Two, so that the proxy relays connections on one while the other serves its tunnel.
				.setEventLoopPoolSize(2)
					// Proof-of-work runs on a worker for seconds at a time; that is expected, not blocked.
					.setWorkerPoolSize(2)
					.setMaxWorkerExecuteTime(10)
					.setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES));
		}
		return vertx;
	}

	/**
	 * Builds a client acting as the configured user.
	 *
	 * @return the client
	 * @throws io.bosonnetwork.cli.common.CliException if the settings or the identity are missing or invalid
	 */
	public DirectorClient directorClient() {
		return directorClient(null);
	}

	/**
	 * Builds a client acting as the configured user, with a device key.
	 *
	 * @param deviceKey the device key, or {@code null}
	 * @return the client
	 * @throws io.bosonnetwork.cli.common.CliException if the settings or the identity are missing or invalid
	 */
	public DirectorClient directorClient(Signature.KeyPair deviceKey) {
		// Where to connect is reported before who connects: without a Director, no identity helps.
		checkConnection();
		return buildClient(identity(), deviceKey);
	}

	/**
	 * Builds a client for the calls the Director answers without authentication, which therefore work
	 * without an identity: the node id and status.
	 *
	 * @return the client
	 * @throws io.bosonnetwork.cli.common.CliException if the connection settings are missing or invalid
	 */
	public DirectorClient anonymousDirectorClient() {
		// The client insists on a key, but these calls carry no token, so a throwaway one signs nothing.
		return buildClient(Signature.KeyPair.random(), null);
	}

	private DirectorClient buildClient(Signature.KeyPair userKey, Signature.KeyPair deviceKey) {
		URL url = settings().directorUrl();
		Id nodeId = settings().nodeIdValue();
		InetSocketAddress address = settings().resolveToAddress(url);

		DirectorClient.Builder builder = DirectorClient.builder()
				.vertx(vertx())
				.directorUrl(url)
				.userKey(userKey);
		if (nodeId != null)
			builder.nodeId(nodeId);
		if (address != null)
			builder.resolveToAddress(address);
		if (deviceKey != null)
			builder.deviceKey(deviceKey);

		DirectorClient client = builder.build();
		directorUrl = url;
		clients.add(client::close);
		return client;
	}

	/**
	 * Builds an admin client acting as the configured administrator.
	 *
	 * @return the client
	 * @throws io.bosonnetwork.cli.common.CliException if the settings or the identity are missing or invalid
	 */
	public DirectorAdmin directorAdmin() {
		checkConnection();
		return buildAdmin(identity());
	}

	/**
	 * Builds an admin client for the calls the Director answers without authentication: the node id.
	 *
	 * @return the client
	 * @throws io.bosonnetwork.cli.common.CliException if the connection settings are missing or invalid
	 */
	public DirectorAdmin anonymousDirectorAdmin() {
		return buildAdmin(Signature.KeyPair.random());
	}

	private DirectorAdmin buildAdmin(Signature.KeyPair userKey) {
		URL url = settings().directorUrl();
		Id nodeId = settings().nodeIdValue();
		InetSocketAddress address = settings().resolveToAddress(url);

		DirectorAdmin.Builder builder = DirectorAdmin.builder()
				.vertx(vertx())
				.directorUrl(url)
				.userKey(userKey);
		if (nodeId != null)
			builder.nodeId(nodeId);
		if (address != null)
			builder.resolveToAddress(address);

		DirectorAdmin admin = builder.build();
		directorUrl = url;
		clients.add(admin::close);
		return admin;
	}

	/**
	 * Returns the status of the super node, fetched from the Director once per run: what it runs, and
	 * where its services are.
	 *
	 * @return the status
	 * @throws Exception if the Director cannot be asked
	 */
	public NodeStatus nodeStatus() throws Exception {
		if (nodeStatus == null)
			nodeStatus = await(anonymousDirectorClient().getNodeStatus());
		return nodeStatus;
	}

	/**
	 * Finds a service of the super node, with the endpoint clients connect to.
	 *
	 * @param serviceId the service id, such as {@link NodeStatus.Service#ION_STORE}
	 * @param name      what the service is called in messages, such as {@code "Ion Store"}
	 * @return the service
	 * @throws CliException if the node does not offer it, or advertises no endpoint for it
	 * @throws Exception    if the Director cannot be asked
	 */
	public NodeStatus.Service service(String serviceId, String name) throws Exception {
		NodeStatus status = nodeStatus();
		NodeStatus.Service service = status.getService(serviceId).orElseThrow(() ->
				CliException.failed("The super node at " + directorUrl + " does not offer the " + name + " service (" +
						serviceId + ").", "See the services it offers with " + tool.command("node status") +
						", or use a super node that offers this one."));

		if (service.getEndpoint().isEmpty() || service.getEndpoint().get().isBlank())
			throw CliException.failed("The super node at " + directorUrl + " offers the " + name +
					" service without saying where it is reached.", "Tell the node's operator: its " + name +
					" service announces no endpoint.");

		return service;
	}

	/**
	 * Returns the URL of a service this run connected to, for messages.
	 *
	 * @param serviceId the service id
	 * @return the URL, or {@code null} if no client of the service was built
	 */
	public URL serviceUrl(String serviceId) {
		return serviceUrls.get(serviceId);
	}

	/**
	 * Builds an Ion Store client acting as the configured device of the configured user.
	 *
	 * @return the client
	 * @throws CliException if the user or the device is not configured, or the node offers no Ion Store
	 * @throws Exception    if the Director cannot be asked
	 */
	public IonStore ionStore() throws Exception {
		// Where to connect, then who connects: what is missing locally is reported before anything is
		// asked of the super node.
		checkConnection();
		Id userId = userId();
		Signature.KeyPair deviceKey = deviceKey();
		return buildIonStore(userId, deviceKey);
	}

	/**
	 * Builds an Ion Store client for retrieving objects, which needs no account, so that anyone who has
	 * a Director URL can retrieve.
	 *
	 * @return the client
	 * @throws CliException if the node offers no Ion Store
	 * @throws Exception    if the Director cannot be asked
	 */
	public IonStore ionStoreForRetrieval() throws Exception {
		checkConnection();
		// Retrieval sends no token, so the client's mandatory identity signs nothing.
		return buildIonStore(Id.random(), Signature.KeyPair.random());
	}

	private IonStore buildIonStore(Id userId, Signature.KeyPair deviceKey) throws Exception {
		NodeStatus.Service service = service(NodeStatus.Service.ION_STORE, "Ion Store");
		URL url = httpUrl(service, "Ion Store");

		IonStore store = IonStore.builder()
				.vertx(vertx())
				.userId(userId)
				.deviceKey(deviceKey)
				.servicePeerId(service.getPeerId())
				.serviceUrl(url)
				.build();
		serviceUrls.put(NodeStatus.Service.ION_STORE, url);
		clients.add(store::close);
		return store;
	}

	/**
	 * Builds and starts a Higgs node - a DHT client that works through the super node's web gateway -
	 * acting as the configured device of the configured user.
	 *
	 * @return the started node
	 * @throws CliException if the user or the device is not configured, or the node offers no web gateway
	 * @throws Exception    if the Director or the gateway cannot be reached
	 */
	public HiggsNode higgsNode() throws Exception {
		checkConnection();
		Id userId = userId();
		Signature.KeyPair deviceKey = deviceKey();
		NodeStatus.Service service = service(NodeStatus.Service.WEB_GATEWAY, "Web Gateway");
		URL url = httpUrl(service, "Web Gateway");

		HiggsNode node = HiggsNode.builder()
				.vertx(vertx())
				.userId(userId)
				.deviceKey(deviceKey)
				.gatewayPeerId(service.getPeerId())
				.gatewayUrl(url)
				.build();
		serviceUrls.put(NodeStatus.Service.WEB_GATEWAY, url);
		try {
			await(node.start());
		} catch (HiggsException e) {
			throw e;
		} catch (Exception e) {
			// Starting fetches the gateway's info, and passes a transport failure on unwrapped.
			throw ConnectionFailures.service(e, "Web Gateway", url);
		}
		clients.add(() -> node.isRunning() ? node.stop() : CompletableFuture.completedFuture(null));
		return node;
	}

	private URL httpUrl(NodeStatus.Service service, String name) {
		String endpoint = service.getEndpoint().orElseThrow();
		try {
			URL url = URI.create(endpoint).toURL();
			if (url.getProtocol().equals("http") || url.getProtocol().equals("https"))
				return url;
		} catch (IllegalArgumentException | MalformedURLException e) {
			// reported below
		}

		throw CliException.failed("The super node advertises its " + name + " service at '" + endpoint +
				"', which is not an http or https URL.", "Tell the node's operator: its " + name +
				" service announces an endpoint clients cannot use.");
	}

	/**
	 * Checks the settings that say where to connect, so that a command reports a missing or invalid
	 * Director URL before anything else.
	 *
	 * @throws CliException if the connection settings are missing or invalid
	 */
	public void checkConnection() {
		URL url = settings().directorUrl();
		settings().nodeIdValue();
		settings().resolveToAddress(url);
	}

	@Override
	public <T> T await(CompletableFuture<T> future) throws Exception {
		return Futures.await(future);
	}

	/**
	 * Closes the clients and the Vert.x instance.
	 */
	@Override
	public void close() {
		for (Supplier<CompletableFuture<Void>> client : clients) {
			try {
				client.get().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// Closing on the way out: nothing left to report it to.
			}
		}
		clients.clear();

		if (vertx != null) {
			try {
				vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// As above.
			}
			vertx = null;
		}

		output.flush();
	}
}
