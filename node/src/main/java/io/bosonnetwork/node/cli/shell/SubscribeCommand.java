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

package io.bosonnetwork.node.cli.shell;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Subscription;
import io.bosonnetwork.SubscriptionListener;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.Output;

/**
 * {@code subscribe}: asks the nodes storing a value or peer records to report changes, and prints each one as
 * it arrives, until {@code unsubscribe}.
 */
@Command(name = "subscribe", description = "Watch a value or peer records for changes.",
		subcommands = {SubscribeCommand.ValueCommand.class, SubscribeCommand.PeerCommand.class,
				SubscribeCommand.ListCommand.class})
public class SubscribeCommand extends ShellGroup {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	/**
	 * Prefixes an event with the time it happened, to the millisecond: how long an update took to arrive is
	 * the first thing anyone watching one wants to know.
	 */
	static String stamped(String text) {
		return "[" + LocalTime.now().format(TIME) + "] " + text;
	}

	private static <T> SubscriptionListener<T> printer(Output output, String what,
			Function<T, String> describe) {
		return new SubscriptionListener<>() {
			@Override
			public void onUpdate(T record) {
				output.event(stamped(what + " updated: " + describe.apply(record)));
			}

			@Override
			public void onStatusChange(int subscribedNodes) {
				output.event(stamped(what + " watched by " + subscribedNodes + " node(s)"));
			}
		};
	}

	private static String describe(Value value) {
		return "seq " + value.getSequenceNumber() + ", " + (value.isEncrypted() ? "encrypted" :
				"\"" + new String(value.getData(), StandardCharsets.UTF_8) + "\"");
	}

	private static String describe(PeerInfo peer) {
		return "fingerprint " + peer.getFingerprint() + ", seq " + peer.getSequenceNumber() + ", " + peer.getEndpoint();
	}

	@Command(name = "value", description = "Watch a value for newer versions.")
	public static class ValueCommand extends ShellCommandBase {
		@Option(names = {"-s", "--seq"}, paramLabel = "<n>",
				description = "The version already held; only newer ones are reported. Default: report the current one too.")
		int knownSeq = -1;

		@Parameters(paramLabel = "<value-id>", description = "The value to watch.")
		Id id;

		@Override
		protected void run() throws Exception {
			String key = "value " + id;
			if (session().subscriptions().containsKey(key))
				throw CliException.usage("Already watching value " + id + ".", "'subscribe list' shows what is watched.");

			Subscription subscription = await(node().subscribeValue(id, knownSeq,
					printer(output(), "Value " + id, SubscribeCommand::describe)));
			session().subscriptions().put(key, subscription);
			output().event(stamped("Watching value " + id + " on " + subscription.getSubscribedNodes() + " node(s)."));
		}
	}

	@Command(name = "peer", description = "Watch the peer records under an id.")
	public static class PeerCommand extends ShellCommandBase {
		@Parameters(paramLabel = "<peer-id>", description = "The peer id to watch.")
		Id id;

		@Override
		protected void run() throws Exception {
			String key = "peer " + id;
			if (session().subscriptions().containsKey(key))
				throw CliException.usage("Already watching peer " + id + ".", "'subscribe list' shows what is watched.");

			Subscription subscription = await(node().subscribePeer(id,
					printer(output(), "Peer " + id, SubscribeCommand::describe)));
			session().subscriptions().put(key, subscription);
			output().event(stamped("Watching peer " + id + " on " + subscription.getSubscribedNodes() + " node(s)."));
		}
	}

	@Command(name = "list", description = "List what is watched.")
	public static class ListCommand extends ShellCommandBase {
		@Override
		protected void run() {
			Map<String, Subscription> subscriptions = session().subscriptions();
			if (subscriptions.isEmpty()) {
				output().message("Nothing is watched.");
				return;
			}

			List<List<String>> rows = new ArrayList<>();
			subscriptions.forEach((key, sub) -> rows.add(List.of(key.substring(0, key.indexOf(' ')),
					sub.getTarget().toString(), String.valueOf(sub.getSubscribedNodes()))));
			output().table(List.of("KIND", "ID", "NODES"), rows);
		}
	}
}
