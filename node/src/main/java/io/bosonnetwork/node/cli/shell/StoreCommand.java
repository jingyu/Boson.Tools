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
import java.util.Optional;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.Output;
import io.bosonnetwork.vertx.ContextualFuture;

/**
 * {@code store}: puts a value on the DHT.
 */
@Command(name = "store", description = "Store a value on the DHT.",
		subcommands = {StoreCommand.ValueCommand.class})
public class StoreCommand extends ShellGroup {

	@Command(name = "value", description = {"Store a value on the DHT.",
			"Immutable by default: its id is the hash of its data. A mutable value is signed, and can be updated; "
					+ "an encrypted one can be read only by its recipient."})
	public static class ValueCommand extends ShellCommandBase {
		@Option(names = {"-m", "--mutable"}, description = "Store a mutable value, which can be updated later.")
		boolean mutable;

		@Option(names = {"-r", "--recipient"}, paramLabel = "<id>",
				description = "Encrypt the value for this recipient. Implies --mutable.")
		Id recipient;

		@Option(names = {"-u", "--update"}, paramLabel = "<value-id>",
				description = "Update this existing mutable value instead of storing a new one.")
		Id update;

		@Option(names = {"-p", "--persistent"}, description = "Keep the value announced, rather than letting it expire.")
		boolean persistent;

		@Option(names = {"-l", "--local-only"}, description = "Store it on this node only, without announcing it.")
		boolean localOnly;

		@Parameters(paramLabel = "<text>", description = "The value's data, as text.")
		String text;

		@Override
		protected void run() throws Exception {
			Value value = update != null ? updated() : created();

			if (localOnly) {
				await(ContextualFuture.of(StorageCommand.storage(node()).putValue(value, persistent)));
				output().message("Stored value " + value.getId() + " on this node only.");
				return;
			}

			AnnounceResult result = await(node().storeValue(value, persistent));
			report(output(), "Value " + value.getId(), result);
		}

		private Value created() {
			Value.Builder builder;
			if (recipient != null)
				builder = Value.encryptedBuilder().recipient(recipient);
			else if (mutable)
				builder = Value.signedBuilder();
			else
				builder = Value.immutableBuilder();

			return builder.data(text.getBytes(StandardCharsets.UTF_8)).build();
		}

		private Value updated() throws Exception {
			Optional<Value> existing = await(node().getValue(update));
			Value value = existing.orElseThrow(() -> CliException.notFound("This node has no value " + update + ".",
					"Only a value this node stores can be updated here; 'storage values' lists them."));

			if (!value.isMutable())
				throw CliException.usage("Value " + update + " is immutable, so it cannot be updated.",
						"Store a new value instead.");

			return value.update().data(text.getBytes(StandardCharsets.UTF_8)).build();
		}
	}

	/**
	 * Reports what came of an announcement, naming the nodes that did not take it: a node that took a
	 * token and then refused is worth looking at, and what it said is the only clue to why.
	 *
	 * @param output what to write to
	 * @param what   what was announced
	 * @param result what the DHT answered
	 */
	static void report(Output output, String what, AnnounceResult result) {
		output.message(what + " reached " + result.acknowledged() + " of " + result.targets().size() +
				" nodes (" + result.status() + ").");

		for (AnnounceResult.Target target : result.targets())
			if (!target.isAcknowledged())
				output.message("  " + target.nodeId() + ": " + target.outcome() +
						(target.cause() != null ? " - " + target.cause() : ""));
	}
}
