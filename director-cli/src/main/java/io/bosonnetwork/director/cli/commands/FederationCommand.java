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


package io.bosonnetwork.director.cli.commands;

import java.util.Map;
import java.util.Optional;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.cli.director.DirectorCommand;
import io.bosonnetwork.cli.common.CliException;
import io.bosonnetwork.cli.common.CliGroup;
import io.bosonnetwork.cli.common.Listing;
import io.bosonnetwork.cli.common.PageOptions;
import io.bosonnetwork.director.cli.AdminViews;
import io.bosonnetwork.director.cli.Arguments;
import io.bosonnetwork.director.client.DirectorAdmin;
import io.bosonnetwork.director.client.FederatedNode;
import io.bosonnetwork.director.client.FederatedNodeDescriptor;
import io.bosonnetwork.director.client.exceptions.NotFoundException;
import io.bosonnetwork.web.PaginatedResult;

/**
 * The {@code federation} commands of {@code boson-director-cli}.
 */
@Command(name = "federation", description = {"Inspect and manage how this node federates with other super nodes.",
		"In open mode every node is admitted unless blacklisted or suspended here; in allowlist mode only the nodes "
				+ "admitted here are. A node that does not federate answers every command here with an error."},
		subcommands = {FederationCommand.NodeGroup.class, FederationCommand.ServiceGroup.class})
public class FederationCommand extends CliGroup {
	// What the federation commands share: showing a node, and fetching a node's descriptor.
	abstract static class FederationNodeCommand extends DirectorCommand {
		void show(FederatedNode node, String message) {
			if (output().isJson())
				output().json(AdminViews.federatedNodeJson(node));
			else
				output().message(message);
		}

		FederatedNodeDescriptor descriptor(Id nodeId) throws Exception {
			Optional<FederatedNodeDescriptor> d = await(context().directorAdmin().getFederatedNodeDescriptor(nodeId));
			return d.orElseThrow(() -> CliException.notFound("Node " + nodeId + "'s descriptor could not be reached or verified.",
					"Check the node id, and that the node is online and federates."));
		}
	}

	@Command(name = "node", description = "Other nodes: the decisions made about them here, and whether they are admitted.",
			subcommands = {NodeGroup.ListCommand.class, NodeGroup.ShowCommand.class, NodeGroup.AdmitCommand.class,
					NodeGroup.SuspendCommand.class, NodeGroup.RemoveCommand.class, NodeGroup.DescribeCommand.class})
	public static class NodeGroup extends CliGroup {
		@Command(name = "list", description = "List the nodes admitted or suspended here.")
		public static class ListCommand extends DirectorCommand {
			@Mixin
			PageOptions paging;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				PaginatedResult<FederatedNode> page = paging.fetch(this, admin::listFederatedNodes);
				Listing.page(output(), page, paging, "nodes", AdminViews.FEDERATED_NODE_HEADERS, AdminViews::federatedNodeRow,
						AdminViews::federatedNodeJson, "No node has been admitted or suspended here.");
			}
		}

		@Command(name = "show", description = "Show whether a node is admitted, and why.")
		public static class ShowCommand extends DirectorCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Override
			protected void run() throws Exception {
				FederatedNode node = await(context().directorAdmin().getFederatedNode(nodeId));
				if (output().isJson())
					output().json(AdminViews.federatedNodeJson(node));
				else
					output().details(AdminViews.federatedNode(node));
			}
		}

		@Command(name = "admit", description = "Admit a node whatever the federation mode. The blacklist still refuses it.")
		public static class AdmitCommand extends FederationNodeCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = "--note", paramLabel = "<text>", description = "A note about the node, up to 256 characters.")
			String note;

			@Override
			protected void run() throws Exception {
				FederatedNode node = await(context().directorAdmin().admitFederatedNode(nodeId, Arguments.blankToNull(note)));
				show(node, "Admitted node " + nodeId + ".");
			}
		}

		@Command(name = "suspend", description = "Refuse a node whatever the federation mode.")
		public static class SuspendCommand extends FederationNodeCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = "--note", paramLabel = "<text>", description = "A note about the node, up to 256 characters.")
			String note;

			@Override
			protected void run() throws Exception {
				FederatedNode node = await(context().directorAdmin().suspendFederatedNode(nodeId, Arguments.blankToNull(note)));
				show(node, "Suspended node " + nodeId + ".");
			}
		}

		@Command(name = "remove", description = "Remove the decision made about a node here, so that the federation mode decides for it again.")
		public static class RemoveCommand extends DirectorCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
			boolean yes;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				terminal().confirm("Remove the decision about node " + nodeId + "? The federation mode decides for it again.", yes);

				try {
					await(admin.removeFederatedNode(nodeId));
				} catch (NotFoundException e) {
					throw CliException.notFound("No decision has been made about node " + nodeId + " here.",
							"List the nodes admitted or suspended with 'boson-director-cli federation node list'.");
				}

				if (output().isJson())
					output().json(Map.of("nodeId", nodeId, "removed", true));
				else
					output().message("Removed the decision about node " + nodeId + ".");
			}
		}

		@Command(name = "describe", description = "Show what a node says about itself, from its signed descriptor.")
		public static class DescribeCommand extends FederationNodeCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Override
			protected void run() throws Exception {
				FederatedNodeDescriptor d = descriptor(nodeId);
				if (output().isJson()) {
					output().json(AdminViews.federatedNodeDescriptorJson(d));
				} else {
					output().details(AdminViews.federatedNodeDescriptor(d));
					output().message("");
					Listing.list(output(), d.getServices(), AdminViews.FEDERATED_SERVICE_HEADERS,
							AdminViews::federatedServiceRow, AdminViews::federatedServiceJson, "The node lists no services.");
				}
			}
		}
	}

	@Command(name = "service", description = "The services other nodes run.",
			subcommands = {ServiceGroup.ListCommand.class})
	public static class ServiceGroup extends CliGroup {
		@Command(name = "list", description = "List the services a node says it runs, from its signed descriptor.")
		public static class ListCommand extends FederationNodeCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Override
			protected void run() throws Exception {
				FederatedNodeDescriptor d = descriptor(nodeId);
				Listing.list(output(), d.getServices(), AdminViews.FEDERATED_SERVICE_HEADERS, AdminViews::federatedServiceRow,
						AdminViews::federatedServiceJson, "Node " + nodeId + " lists no services.");
			}
		}
	}
}
