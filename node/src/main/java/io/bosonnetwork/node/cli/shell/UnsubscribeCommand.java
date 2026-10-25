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

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.Subscription;
import io.bosonnetwork.cli.common.CliException;

/**
 * {@code unsubscribe}: stops watching what {@code subscribe} started watching.
 */
@Command(name = "unsubscribe", description = "Stop watching a value or peer records.")
public class UnsubscribeCommand extends ShellCommandBase {
	@Parameters(index = "0", paramLabel = "<kind>", description = "value or peer.")
	String kind;

	@Parameters(index = "1", paramLabel = "<id>", description = "The id being watched.")
	Id id;

	@Override
	protected void run() throws Exception {
		if (!kind.equals("value") && !kind.equals("peer"))
			throw CliException.usage("Unknown kind '" + kind + "'.", "Use 'value' or 'peer'.");

		Subscription subscription = session().subscriptions().remove(kind + " " + id);
		if (subscription == null)
			throw CliException.notFound("Not watching " + kind + " " + id + ".", "'subscribe list' shows what is watched.");

		await(subscription.cancel());
		output().event(SubscribeCommand.stamped("Stopped watching " + kind + " " + id + "."));
	}
}
