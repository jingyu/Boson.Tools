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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.bosonnetwork.Id;
import io.bosonnetwork.activeproxy.ActiveProxyClient;
import io.bosonnetwork.activeproxy.Configuration;
import io.bosonnetwork.activeproxy.ConnectionStatusListener;
import io.bosonnetwork.cli.common.Causes;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.ErrorReporter;
import io.bosonnetwork.cli.common.ExitCode;
import io.bosonnetwork.cli.director.ConfigFile;
import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.cli.director.Settings;
import io.bosonnetwork.cli.director.Settings.Setting;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.director.client.NodeStatus;
import io.bosonnetwork.higgs.HiggsNode;
import io.bosonnetwork.utils.ApplicationLock;

/**
 * The {@code proxy} commands of {@code boson-cli}: the Active Proxy client, which makes a service on
 * this machine reachable from the internet through the super node.
 */
@Command(name = "proxy", description = {"Expose a service on this machine through the super node's Active Proxy.",
		"The service becomes reachable from the internet at an address of the super node, with no port forwarding "
				+ "and no public IP address. The proxy runs as this machine's device."},
		subcommands = {ProxyCommand.StartCommand.class})
public class ProxyCommand extends CliGroup {

	@Command(name = "start", description = {"Start the proxy, and keep it running until Ctrl+C.",
			"The service is reached at a port of the super node, and, with --name-access, at an https name as well. "
					+ "The options override the proxy settings in the configuration (proxyUpstream, proxyNameAccess and "
					+ "proxyAnnounce), so that a configured proxy starts with no options at all. The tunnel reconnects "
					+ "by itself when the network drops. One proxy runs per device."})
	public static class StartCommand extends DirectorCommand {
		// How long to wait for the first connection before saying that it is taking long.
		private static final long CONNECT_GRACE_SECONDS = 30;
		private static final long STOP_TIMEOUT_SECONDS = 30;

		@Option(names = "--upstream", paramLabel = "<uri>",
				description = "The service to expose: host:port for an http service, or scheme://host:port for any "
						+ "other, as in tcp://localhost:22. Default: proxyUpstream in the configuration.")
		String upstream;

		@Option(names = "--name-access", negatable = true,
				description = "Ask for an https name as well; whether the node grants one depends on it and on your plan. "
						+ "Needs an http service. Default: proxyNameAccess in the configuration, or no.")
		Boolean nameAccess;

		@Option(names = "--announce", negatable = true,
				description = "Announce the address on the DHT, as this device's peer, through the web gateway. "
						+ "Default: proxyAnnounce in the configuration, or no.")
		Boolean announce;

		@Override
		protected void run() throws Exception {
			Configuration config = configuration();
			HiggsNode node = config.isAnnouncePeer() ? context().higgsNode() : null;

			Id deviceId = Id.of(config.getDeviceKey().publicKey().bytes());
			ApplicationLock lock = lock(deviceId);

			AtomicBoolean stopping = new AtomicBoolean();
			AtomicBoolean connected = new AtomicBoolean();
			CountDownLatch stopped = new CountDownLatch(1);
			CountDownLatch finished = new CountDownLatch(1);
			ActiveProxyClient client = null;
			Thread hook = null;

			try {
				ActiveProxyClient proxy = new ActiveProxyClient(context().vertx(), node, config);
				client = proxy;
				proxy.addConnectionListener(listener(proxy, config, connected, stopping));

				// Ctrl+C ends the process with the hooks: this one stops the tunnel, then gives the command
				// the time to close the rest before the process goes.
				hook = new Thread(() -> {
					stopping.set(true);
					stop(proxy);
					stopped.countDown();
					try {
						finished.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}, "boson-cli-proxy-stop");
				Runtime.getRuntime().addShutdownHook(hook);

				output().progress("Exposing " + upstreamText(config) + " through the Active Proxy at " +
						config.getServiceHost() + ":" + config.getServicePort() + ", as device " + deviceId + "...");
				try {
					await(proxy.start());
				} catch (CliException e) {
					throw e;
				} catch (Exception e) {
					throw new CliException(ExitCode.FAILED, "The proxy did not start: " +
							ErrorReporter.sentence(Causes.describe(ErrorReporter.unwrap(e))),
							"Run the command again with --verbose for the details.");
				}
				output().progress("Started; press Ctrl+C to stop.");

				if (!stopped.await(CONNECT_GRACE_SECONDS, TimeUnit.SECONDS) && !connected.get())
					output().warning("The proxy has not connected after " + CONNECT_GRACE_SECONDS + " seconds; it keeps " +
							"trying. Check that device " + deviceId + " is registered to your account (" +
							tool().command("device list") + "), that your plan includes the proxy (" +
							tool().command("user show") + "), and that " + config.getServiceHost() + ":" +
							config.getServicePort() + " is reachable from here. --verbose shows each attempt.");
				stopped.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				// An interrupted command still stops the proxy, which waits: clear the flag for that, and set
				// it again once done.
				boolean interrupted = Thread.interrupted();
				if (!stopping.get()) {
					if (hook != null) {
						try {
							Runtime.getRuntime().removeShutdownHook(hook);
						} catch (IllegalStateException shuttingDown) {
							// The hook runs, and stops the proxy itself.
						}
					}
					if (client != null)
						stop(client);
				}

				context().close();
				lock.close();
				finished.countDown();
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}

		// The client configuration: the options, then the configuration file, then the service the
		// super node reports.
		private Configuration configuration() throws Exception {
			Settings settings = context().settings();
			Setting upstreamSetting = settings.proxy(ConfigFile.PROXY_UPSTREAM);
			String service = upstream != null ? upstream : upstreamSetting != null ? upstreamSetting.value() : null;
			if (service == null)
				throw CliException.usage("No service to expose is given.",
						"Pass --upstream host:port, or set it once with " +
								tool().command("config set proxyUpstream host:port") + ".");

			boolean name = nameAccess != null ? nameAccess :
					Boolean.TRUE.equals(Settings.flag(settings.proxy(ConfigFile.PROXY_NAME_ACCESS)));
			boolean publish = announce != null ? announce :
					Boolean.TRUE.equals(Settings.flag(settings.proxy(ConfigFile.PROXY_ANNOUNCE)));

			Configuration.Builder builder = Configuration.builder();
			try {
				builder.upstream(service);
			} catch (IllegalArgumentException e) {
				String origin = upstream != null ? "--upstream" : upstreamSetting.origin();
				throw new CliException(upstream != null ? ExitCode.USAGE : ExitCode.CONFIG,
						ErrorReporter.sentence(e.getMessage()).replaceAll("\\.$", "") + " (from " + origin + ").",
						"Use host:port for an http service, or scheme://host:port, such as tcp://127.0.0.1:22.");
			}
			// The builder has checked the value, so its scheme is what comes before ://, or http.
			int separator = service.indexOf("://");
			String scheme = separator < 0 ? "http" : service.substring(0, separator).strip().toLowerCase(Locale.ROOT);
			if (name && !scheme.equals("http"))
				throw CliException.usage("An https name needs an http service, but " + service + " is not one.",
						"The node serves the name over https itself; expose the service's plain http port, or leave out " +
								"--name-access.");

			// Where to connect, who connects, then what the super node says.
			context().checkConnection();
			Id userId = context().userId();
			Signature.KeyPair deviceKey = context().deviceKey();
			NodeStatus.Service proxy = context().service(NodeStatus.Service.ACTIVE_PROXY, "Active Proxy");
			try {
				builder.service(proxy.getPeerId()).serviceEndpoint(proxy.getEndpoint().orElseThrow());
			} catch (IllegalArgumentException e) {
				throw CliException.failed("The super node advertises its Active Proxy at '" + proxy.getEndpoint().orElseThrow() +
						"', which is not a tcp://host:port address.", "Tell the node's operator.");
			}

			try {
				return builder.userId(userId)
						.deviceKey(deviceKey)
						.nameAccess(name)
						.announcePeer(publish)
						.build();
			} catch (IllegalStateException e) {
				throw CliException.usage(ErrorReporter.sentence(Causes.describe(Causes.rootCause(e))), null);
			}
		}

		private ConnectionStatusListener listener(ActiveProxyClient proxy, Configuration config, AtomicBoolean connected,
				AtomicBoolean stopping) {
			return new ConnectionStatusListener() {
				@Override
				public void connected() {
					String endpoint;
					String named;
					try {
						endpoint = proxy.getEndpoint();
						named = proxy.isNameAccessEnabled() ? proxy.getNamedEndpoint().orElse(null) : null;
					} catch (IllegalStateException e) {
						// Dropped again before the endpoint could be read; the reconnect reports it.
						return;
					}
					connected.set(true);

					if (output().isJson()) {
						Map<String, Object> json = new LinkedHashMap<>();
						json.put("event", "connected");
						json.put("endpoint", endpoint);
						json.put("namedEndpoint", named);
						json.put("upstream", upstreamText(config));
						output().jsonLine(json);
						return;
					}

					output().event("Connected. " + upstreamText(config) + " is reachable at:");
					output().event("  " + endpoint);
					if (named != null)
						output().event("  " + named);
					else if (config.isNameAccessEnabled())
						output().warning("The super node did not grant an https name; the address above still works.");
				}

				@Override
				public void disconnected() {
					if (stopping.get())
						return;
					if (output().isJson())
						output().jsonLine(Map.of("event", "disconnected"));
					else
						output().progress("Disconnected; reconnecting...");
				}
			};
		}

		private void stop(ActiveProxyClient proxy) {
			if (!proxy.isRunning())
				return;

			output().progress("Stopping the proxy...");
			try {
				proxy.stop().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				output().progress("Stopped.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				output().warning("The proxy did not stop cleanly: " + Causes.describe(ErrorReporter.unwrap(e)));
			}
		}

		// One proxy per device: two would take turns tearing down each other's session on the super node.
		private ApplicationLock lock(Id deviceId) {
			Path file = context().environment().userStateDir().resolve("boson").resolve("client")
					.resolve("active-proxy-" + deviceId + ".lock");
			try {
				Files.createDirectories(file.getParent());
				return new ApplicationLock(file);
			} catch (IllegalStateException e) {
				throw new CliException(ExitCode.CONFLICT, "A proxy is already running as device " + deviceId + ".",
						"Stop it first, or run this one as another device with --device-identity <file>.");
			} catch (IOException e) {
				throw CliException.failed("Cannot create the lock file " + file + ": " + e.getMessage(), null);
			}
		}

		private static String upstreamText(Configuration config) {
			String host = config.getUpstreamHost().contains(":") ? "[" + config.getUpstreamHost() + "]" : config.getUpstreamHost();
			return config.getUpstreamScheme() + "://" + host + ":" + config.getUpstreamPort();
		}
	}
}
