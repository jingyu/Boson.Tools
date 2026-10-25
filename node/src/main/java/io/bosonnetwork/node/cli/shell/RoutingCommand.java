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

import java.net.StandardProtocolFamily;

import picocli.CommandLine.Command;

import io.bosonnetwork.kademlia.KadNode;

/**
 * {@code routing}: the routing table the node has now, as opposed to the one it saved ({@code cache}).
 */
@Command(name = "routing", description = "Show the node's routing tables as they are now.")
public class RoutingCommand extends ShellCommandBase {
	@Override
	protected void run() throws Exception {
		KadNode node = node();

		if (node.isIPv4Enabled()) {
			output().message("IPv4 routing table:");
			await(node.dumpRoutingTable(StandardProtocolFamily.INET, System.out));
			output().blank();
		}

		if (node.isIPv6Enabled()) {
			output().message("IPv6 routing table:");
			await(node.dumpRoutingTable(StandardProtocolFamily.INET6, System.out));
			output().blank();
		}
	}
}
