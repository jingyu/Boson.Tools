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
import io.bosonnetwork.director.client.FederatedNodeHealth;
import io.bosonnetwork.director.client.FederationProposal;
import io.bosonnetwork.director.client.exceptions.ConflictException;
import io.bosonnetwork.director.client.exceptions.NotFoundException;
import io.bosonnetwork.service.FederatedNodeDescriptor;
import io.bosonnetwork.web.PaginatedResult;

/**
 * The {@code federation} commands of {@code boson-director-cli}.
 */
@Command(name = "federation", description = {"Inspect and manage how this node federates with other super nodes.",
		"In open mode every node is admitted unless blacklisted or suspended here; in mutual mode the nodes this node "
				+ "agreed with by proposal, and the ones admitted here; in allowlist mode only the nodes admitted here. "
				+ "A node that does not federate answers every command here with an error."},
		subcommands = {FederationCommand.NodeGroup.class, FederationCommand.ProposalGroup.class,
				FederationCommand.ServiceGroup.class})
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
					NodeGroup.SuspendCommand.class, NodeGroup.ProposeCommand.class, NodeGroup.RemoveCommand.class,
					NodeGroup.DescribeCommand.class, NodeGroup.HealthCommand.class})
	public static class NodeGroup extends CliGroup {
		@Command(name = "list", description = "List the nodes this node has a relationship with: admitted, suspended, pending or declined.")
		public static class ListCommand extends DirectorCommand {
			@Mixin
			PageOptions paging;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				PaginatedResult<FederatedNode> page = paging.fetch(this, admin::listFederatedNodes);
				Listing.page(output(), page, paging, "nodes", AdminViews.FEDERATED_NODE_HEADERS, AdminViews::federatedNodeRow,
						AdminViews::federatedNodeJson, "This node has no relationship with any node.");
			}
		}

		@Command(name = "health", description = {"List how working with other nodes has gone lately, the most recently seen first.",
				"Every node heard from within the last day is listed, whether this node has a relationship with it or not. "
						+ "The node keeps this in memory: it starts empty after a restart."})
		public static class HealthCommand extends DirectorCommand {
			@Mixin
			PageOptions paging;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				PaginatedResult<FederatedNodeHealth> page = paging.fetch(this, admin::listFederatedNodeHealth);
				Listing.page(output(), page, paging, "nodes", AdminViews.FEDERATED_NODE_HEALTH_HEADERS,
						AdminViews::federatedNodeHealthRow, AdminViews::federatedNodeHealthJson,
						"No node has been heard from within the last day.");
			}
		}

		@Command(name = "show", description = "Show whether a node is admitted, and why, and how working with it has gone lately.")
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

		@Command(name = "propose", description = {"Propose a relationship to a node, for nodes that federate in mutual mode.",
				"The node answers at once, or after its operator's review; the proposal is sent again until it is answered."})
		public static class ProposeCommand extends DirectorCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = "--note", paramLabel = "<text>", description = "A note for the node's operator, up to 256 characters.")
			String note;

			@Override
			protected void run() throws Exception {
				FederationProposal p;
				try {
					p = await(context().directorAdmin().proposeFederation(nodeId, Arguments.blankToNull(note)));
				} catch (ConflictException e) {
					throw CliException.failed("The relationship with node " + nodeId + " is already active.",
							"Show it with 'boson-director-cli federation node show " + nodeId + "'.");
				}

				if (output().isJson()) {
					output().json(AdminViews.federationProposalJson(p));
					return;
				}

				FederationProposal.Status status = p.getStatus().orElse(null);
				if (p.getRole().orElse(null) == FederationProposal.Role.ANSWER)
					output().message("Node " + nodeId + " had proposed: accepted its proposal. The relationship is active.");
				else if (status == FederationProposal.Status.ACCEPTED)
					output().message("Node " + nodeId + " accepted. The relationship is active.");
				else if (status == FederationProposal.Status.DECLINED)
					output().message("Node " + nodeId + " declined.");
				else
					output().message("Proposed to node " + nodeId + " (proposal " + p.getId() + "); waiting for its answer.");
			}
		}

		@Command(name = "remove", description = {"Remove the relationship with a node, so that the federation mode decides for it again.",
				"A relationship agreed by proposal is revoked with the other node; a pending proposal is withdrawn."})
		public static class RemoveCommand extends DirectorCommand {
			@Parameters(paramLabel = "<node-id>", description = "The node.")
			Id nodeId;

			@Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
			boolean yes;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				terminal().confirm("Remove the relationship with node " + nodeId + "? The federation mode decides for it again.", yes);

				try {
					await(admin.removeFederatedNode(nodeId));
				} catch (NotFoundException e) {
					throw CliException.notFound("This node has no relationship with node " + nodeId + ".",
							"List the relationships with 'boson-director-cli federation node list'.");
				}

				if (output().isJson())
					output().json(Map.of("nodeId", nodeId, "removed", true));
				else
					output().message("Removed the relationship with node " + nodeId + ".");
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

	@Command(name = "proposal", description = "Proposals: the ones this node made, and the ones other nodes made to it.",
			subcommands = {ProposalGroup.ListCommand.class, ProposalGroup.ShowCommand.class, ProposalGroup.AcceptCommand.class,
					ProposalGroup.DeclineCommand.class, ProposalGroup.RemoveCommand.class})
	public static class ProposalGroup extends CliGroup {
		// Answering a proposal: shared by accept and decline.
		abstract static class AnswerCommand extends DirectorCommand {
			@Parameters(paramLabel = "<proposal-id>", description = "The proposal.")
			long id;

			abstract boolean accept();

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				FederationProposal p;
				try {
					p = await(accept() ? admin.acceptFederationProposal(id) : admin.declineFederationProposal(id));
				} catch (NotFoundException e) {
					throw CliException.notFound("No proposal " + id + ".",
							"List the proposals waiting for an answer with 'boson-director-cli federation proposal list --role answer --status pending'.");
				} catch (ConflictException e) {
					throw CliException.failed("Proposal " + id + " is not one waiting for this node's answer.",
							"Show it with 'boson-director-cli federation proposal show " + id + "'.");
				}

				if (output().isJson())
					output().json(AdminViews.federationProposalJson(p));
				else
					output().message((accept() ? "Accepted" : "Declined") + " node " + p.getNodeId() + "'s proposal.");
			}
		}

		@Command(name = "list", description = "List the proposals, latest first.")
		public static class ListCommand extends DirectorCommand {
			@Option(names = "--role", paramLabel = "<role>", description = "Only these: offer (this node proposed) or answer (another node did).")
			FederationProposal.Role role;

			@Option(names = "--status", paramLabel = "<status>", description = "Only these: pending, accepted, declined, withdrawn or expired.")
			FederationProposal.Status status;

			@Mixin
			PageOptions paging;

			@Override
			protected void run() throws Exception {
				DirectorAdmin admin = context().directorAdmin();
				PaginatedResult<FederationProposal> page = paging.fetch(this,
						(p, size) -> admin.listFederationProposals(role, status, p, size));
				Listing.page(output(), page, paging, "proposals", AdminViews.FEDERATION_PROPOSAL_HEADERS,
						AdminViews::federationProposalRow, AdminViews::federationProposalJson, "No proposals.");
			}
		}

		@Command(name = "show", description = "Show a proposal.")
		public static class ShowCommand extends DirectorCommand {
			@Parameters(paramLabel = "<proposal-id>", description = "The proposal.")
			long id;

			@Override
			protected void run() throws Exception {
				FederationProposal p = await(context().directorAdmin().getFederationProposal(id)).orElseThrow(() ->
						CliException.notFound("No proposal " + id + ".", "List the proposals with 'boson-director-cli federation proposal list'."));
				if (output().isJson())
					output().json(AdminViews.federationProposalJson(p));
				else
					output().details(AdminViews.federationProposal(p));
			}
		}

		@Command(name = "accept", description = "Accept a node's proposal, and tell it: the relationship becomes active.")
		public static class AcceptCommand extends AnswerCommand {
			@Override
			boolean accept() {
				return true;
			}
		}

		@Command(name = "decline", description = "Decline a node's proposal, and tell it.")
		public static class DeclineCommand extends AnswerCommand {
			@Override
			boolean accept() {
				return false;
			}
		}

		@Command(name = "remove", aliases = "withdraw",
				description = "Withdraw a pending proposal this node made, or remove a finished proposal from the record.")
		public static class RemoveCommand extends DirectorCommand {
			@Parameters(paramLabel = "<proposal-id>", description = "The proposal.")
			long id;

			@Override
			protected void run() throws Exception {
				try {
					await(context().directorAdmin().removeFederationProposal(id));
				} catch (NotFoundException e) {
					throw CliException.notFound("No proposal " + id + ".", "List the proposals with 'boson-director-cli federation proposal list'.");
				} catch (ConflictException e) {
					throw CliException.failed("Proposal " + id + " waits for this node's answer.",
							"Accept or decline it with 'boson-director-cli federation proposal accept|decline " + id + "'.");
				}

				if (output().isJson())
					output().json(Map.of("id", id, "removed", true));
				else
					output().message("Removed proposal " + id + ".");
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
