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

package io.bosonnetwork.director.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.bosonnetwork.cli.common.Formats;
import io.bosonnetwork.director.client.BlacklistedNode;
import io.bosonnetwork.director.client.Feature;
import io.bosonnetwork.director.client.FederatedNode;
import io.bosonnetwork.director.client.FederatedNodeDescriptor;
import io.bosonnetwork.director.client.FederatedNodeHealth;
import io.bosonnetwork.director.client.FederatedService;
import io.bosonnetwork.director.client.FederationProposal;
import io.bosonnetwork.director.client.Plan;
import io.bosonnetwork.director.client.Profile;
import io.bosonnetwork.director.client.Subscription;
import io.bosonnetwork.json.Json;

/**
 * How {@code boson-director-cli} shows the admin API's objects: table rows, details and JSON. The JSON
 * forms are built field by field, so that the output scripts rely on is stated here.
 */
public final class AdminViews {
	/** The table headers of a user list. */
	public static final List<String> USER_HEADERS = List.of("USER ID", "NAME", "EMAIL", "ADMIN", "PLAN", "CREATED");
	/** The table headers of a plan list. */
	public static final List<String> PLAN_HEADERS = List.of("ID", "NAME", "PRICE", "ANNUAL DISCOUNT", "ACTIVE", "DESCRIPTION");
	/** The table headers of a feature list. */
	public static final List<String> FEATURE_HEADERS = List.of("ID", "PLAN", "SERVICE", "UPDATED", "FEATURE");
	/** The table headers of a subscription list. */
	public static final List<String> SUBSCRIPTION_HEADERS = List.of("ID", "PLAN", "STATUS", "START", "END");
	/** The table headers of a blacklist. */
	public static final List<String> BLACKLIST_HEADERS = List.of("ID", "NODE ID", "HOST", "AUTO", "REASON", "CREATED");
	/** The table headers of a federated node list. */
	public static final List<String> FEDERATED_NODE_HEADERS = List.of("NODE ID", "STATE", "ADMISSION", "NOTE", "UPDATED");
	/** The table headers of a federated service list. */
	public static final List<String> FEDERATED_SERVICE_HEADERS = List.of("SERVICE TYPE", "NAME", "PEER ID", "ENDPOINT", "FEDERATION");
	/** The table headers of a list of nodes' health. */
	public static final List<String> FEDERATED_NODE_HEALTH_HEADERS = List.of("NODE ID", "LAST SEEN", "LAST SUCCESS", "FAILURES IN A ROW", "LAST FAILURE", "LAST HOUR", "WANTED");
	/** The table headers of a federation proposal list. */
	public static final List<String> FEDERATION_PROPOSAL_HEADERS = List.of("ID", "NODE ID", "ROLE", "STATUS", "NOTE", "CREATED");

	private static final int BRIEF = 40;

	private AdminViews() {
	}

	// ---- Users ---------------------------------------------------------------------------------

	/**
	 * Returns the table row of a user.
	 *
	 * @param user the user
	 * @return the row
	 */
	public static List<String> userRow(Profile user) {
		return List.of(user.getId().toBase58String(),
				Formats.text(user.getName()),
				Formats.text(user.getEmail()),
				Formats.yesNo(user.isAdmin()),
				Formats.text(user.getPlanName()),
				Formats.time(user.getCreatedAt()));
	}

	// ---- Plans ---------------------------------------------------------------------------------

	/**
	 * Returns the table row of a plan.
	 *
	 * @param plan the plan
	 * @return the row
	 */
	public static List<String> planRow(Plan plan) {
		return List.of(Integer.toString(plan.getId()),
				plan.getName(),
				price(plan),
				plan.getAnnuallyDiscount().toPlainString(),
				Formats.yesNo(plan.isActive()),
				Formats.brief(plan.getDescription().orElse(null), BRIEF));
	}

	/**
	 * Returns the details of a plan.
	 *
	 * @param plan the plan
	 * @return the details, keyed by label
	 */
	public static Map<String, String> plan(Plan plan) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Plan id", Integer.toString(plan.getId()));
		rows.put("Name", plan.getName());
		rows.put("Price", price(plan));
		rows.put("Annual discount", plan.getAnnuallyDiscount().toPlainString());
		rows.put("Active", Formats.yesNo(plan.isActive()));
		rows.put("Description", Formats.text(plan.getDescription()));
		rows.put("Detail", Formats.text(plan.getDetail()));
		rows.put("Created", Formats.time(plan.getCreatedAt()));
		rows.put("Updated", Formats.time(plan.getUpdatedAt()));
		return rows;
	}

	/**
	 * Returns the JSON of a plan.
	 *
	 * @param plan the plan
	 * @return the JSON object
	 */
	public static Map<String, Object> planJson(Plan plan) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", plan.getId());
		json.put("name", plan.getName());
		json.put("price", plan.getPrice().toPlainString());
		json.put("currency", plan.getCurrency());
		json.put("cycle", plan.getCycle().toString());
		json.put("annuallyDiscount", plan.getAnnuallyDiscount().toPlainString());
		json.put("active", plan.isActive());
		json.put("description", plan.getDescription().orElse(null));
		json.put("detail", plan.getDetail().orElse(null));
		json.put("createdAt", plan.getCreatedAt());
		json.put("updatedAt", plan.getUpdatedAt());
		return json;
	}

	private static String price(Plan plan) {
		return plan.getPrice().toPlainString() + " " + plan.getCurrency() + " " + plan.getCycle();
	}

	// ---- Features ------------------------------------------------------------------------------

	/**
	 * Returns the table row of a feature.
	 *
	 * @param feature the feature
	 * @return the row
	 */
	public static List<String> featureRow(Feature feature) {
		return List.of(Integer.toString(feature.getId()),
				feature.getPlanName().orElse(Integer.toString(feature.getPlanId())),
				feature.getServiceId(),
				Formats.time(feature.getUpdatedAt()),
				Formats.brief(Json.toString(feature.getFeature()), BRIEF));
	}

	/**
	 * Returns the details of a feature.
	 *
	 * @param feature the feature
	 * @return the details, keyed by label
	 */
	public static Map<String, String> feature(Feature feature) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Feature id", Integer.toString(feature.getId()));
		rows.put("Plan", feature.getPlanName().map(name -> name + " (id " + feature.getPlanId() + ")")
				.orElse("id " + feature.getPlanId()));
		rows.put("Service", feature.getServiceId());
		rows.put("Created", Formats.time(feature.getCreatedAt()));
		rows.put("Updated", Formats.time(feature.getUpdatedAt()));
		rows.put("Feature", Json.toPrettyString(feature.getFeature()));
		return rows;
	}

	/**
	 * Returns the JSON of a feature.
	 *
	 * @param feature the feature
	 * @return the JSON object
	 */
	public static Map<String, Object> featureJson(Feature feature) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", feature.getId());
		json.put("planId", feature.getPlanId());
		json.put("planName", feature.getPlanName().orElse(null));
		json.put("serviceId", feature.getServiceId());
		json.put("feature", feature.getFeature());
		json.put("createdAt", feature.getCreatedAt());
		json.put("updatedAt", feature.getUpdatedAt());
		return json;
	}

	// ---- Subscriptions -------------------------------------------------------------------------

	/**
	 * Returns the table row of a subscription.
	 *
	 * @param subscription the subscription
	 * @return the row
	 */
	public static List<String> subscriptionRow(Subscription subscription) {
		return List.of(Long.toString(subscription.getId()),
				subscription.getPlanName().orElse(Integer.toString(subscription.getPlanId())),
				subscription.getStatus().toString(),
				Formats.time(subscription.getStartDate()),
				Formats.time(subscription.getEndDate()));
	}

	/**
	 * Returns the details of a subscription.
	 *
	 * @param subscription the subscription
	 * @return the details, keyed by label
	 */
	public static Map<String, String> subscription(Subscription subscription) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Subscription id", Long.toString(subscription.getId()));
		rows.put("User id", subscription.getUserId().toBase58String());
		rows.put("Plan", subscription.getPlanName().map(name -> name + " (id " + subscription.getPlanId() + ")")
				.orElse("id " + subscription.getPlanId()));
		rows.put("Status", subscription.getStatus().toString());
		rows.put("Start", Formats.time(subscription.getStartDate()));
		rows.put("End", Formats.time(subscription.getEndDate()));
		rows.put("Created", Formats.time(subscription.getCreatedAt()));
		rows.put("Updated", Formats.time(subscription.getUpdatedAt()));
		return rows;
	}

	/**
	 * Returns the JSON of a subscription.
	 *
	 * @param subscription the subscription
	 * @return the JSON object
	 */
	public static Map<String, Object> subscriptionJson(Subscription subscription) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", subscription.getId());
		json.put("userId", subscription.getUserId());
		json.put("planId", subscription.getPlanId());
		json.put("planName", subscription.getPlanName().orElse(null));
		json.put("status", subscription.getStatus().toString());
		json.put("startDate", subscription.getStartDate());
		json.put("endDate", subscription.getEndDate());
		json.put("createdAt", subscription.getCreatedAt());
		json.put("updatedAt", subscription.getUpdatedAt());
		return json;
	}

	// ---- Blacklist -----------------------------------------------------------------------------

	/**
	 * Returns the table row of a blacklist entry.
	 *
	 * @param entry the entry
	 * @return the row
	 */
	public static List<String> blacklistRow(BlacklistedNode entry) {
		return List.of(Long.toString(entry.getId()),
				entry.getNodeId().map(id -> id.toBase58String()).orElse(Formats.NONE),
				Formats.text(entry.getNodeHost()),
				Formats.yesNo(entry.isAuto()),
				Formats.brief(entry.getReason().orElse(null), BRIEF),
				Formats.time(entry.getCreatedAt()));
	}

	/**
	 * Returns the details of a blacklist entry.
	 *
	 * @param entry the entry
	 * @return the details, keyed by label
	 */
	public static Map<String, String> blacklist(BlacklistedNode entry) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Entry id", Long.toString(entry.getId()));
		rows.put("Node id", entry.getNodeId().map(id -> id.toBase58String()).orElse(Formats.NONE));
		rows.put("Host", Formats.text(entry.getNodeHost()));
		rows.put("Automatic", Formats.yesNo(entry.isAuto()));
		rows.put("Reason", Formats.text(entry.getReason()));
		rows.put("Created", Formats.time(entry.getCreatedAt()));
		rows.put("Updated", Formats.time(entry.getUpdatedAt()));
		return rows;
	}

	/**
	 * Returns the JSON of a blacklist entry.
	 *
	 * @param entry the entry
	 * @return the JSON object
	 */
	public static Map<String, Object> blacklistJson(BlacklistedNode entry) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", entry.getId());
		json.put("nodeId", entry.getNodeId().orElse(null));
		json.put("nodeHost", entry.getNodeHost().orElse(null));
		json.put("auto", entry.isAuto());
		json.put("reason", entry.getReason().orElse(null));
		json.put("createdAt", entry.getCreatedAt());
		json.put("updatedAt", entry.getUpdatedAt());
		return json;
	}

	// ---- Federation ----------------------------------------------------------------------------

	private static String admission(FederatedNode node) {
		return node.getAdmission().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("-");
	}

	private static String state(FederatedNode node) {
		return node.getState().map(st -> st.name().toLowerCase(Locale.ROOT)).orElse("-");
	}

	/**
	 * Returns the table row of a federated node.
	 *
	 * @param node the node
	 * @return the row
	 */
	public static List<String> federatedNodeRow(FederatedNode node) {
		return List.of(node.getId().toBase58String(),
				state(node),
				admission(node),
				Formats.text(node.getNote()),
				node.getUpdatedAt() > 0 ? Formats.time(node.getUpdatedAt()) : "-");
	}

	/**
	 * Returns the details of a federated node.
	 *
	 * @param node the node
	 * @return the details, keyed by label
	 */
	public static Map<String, String> federatedNode(FederatedNode node) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Node id", node.getId().toBase58String());
		rows.put("Relationship", node.getState().isPresent() ? state(node) : "none (the federation mode decides)");
		rows.put("Admission", admission(node) + (node.isAdmitted() ? " (admitted)" : " (refused)"));
		rows.put("Note", Formats.text(node.getNote()));
		if (node.getCreatedAt() > 0) {
			rows.put("Created", Formats.time(node.getCreatedAt()));
			rows.put("Updated", Formats.time(node.getUpdatedAt()));
		}
		node.getHealth().ifPresentOrElse(h -> {
			rows.put("Last seen", h.getLastSeenAt() > 0 ? Formats.time(h.getLastSeenAt()) : "-");
			rows.put("Last success", h.getLastSuccessAt() > 0 ? Formats.time(h.getLastSuccessAt()) : "-");
			rows.put("Last failure", lastFailure(h));
			rows.put("Failures in a row", Integer.toString(h.getConsecutiveFailures()));
			rows.put("Last hour", lastHour(h));
			rows.put("Wanted", wanted(h));
		}, () -> rows.put("Health", "nothing observed within the last day"));
		return rows;
	}

	private static String lastFailure(FederatedNodeHealth h) {
		if (h.getLastFailureAt() == 0)
			return "-";
		String how = h.getLastFailure().map(o -> o.name().toLowerCase(Locale.ROOT)).orElse("failed");
		return Formats.time(h.getLastFailureAt()) + " (" + how + h.getLastFailureDetail().map(d -> ": " + d).orElse("") + ")";
	}

	// How often local services wanted the node and couldn't reach it for want of a relationship.
	private static String wanted(FederatedNodeHealth h) {
		if (h.getDemand() == 0)
			return "-";
		return h.getDemand() + " (last " + Formats.time(h.getLastDemandAt()) +
				h.getLastDemandReason().map(r -> ", " + r.name().toLowerCase(Locale.ROOT)).orElse("") + ")";
	}

	private static String lastHour(FederatedNodeHealth h) {
		if (h.getLastHour().isEmpty())
			return "-";
		StringBuilder sb = new StringBuilder();
		h.getLastHour().forEach((outcome, count) -> {
			if (!sb.isEmpty())
				sb.append(", ");
			sb.append(outcome.name().toLowerCase(Locale.ROOT)).append(' ').append(count);
		});
		return sb.toString();
	}

	/**
	 * Returns the table row of a node's health.
	 *
	 * @param h the health
	 * @return the row
	 */
	public static List<String> federatedNodeHealthRow(FederatedNodeHealth h) {
		return List.of(h.getNodeId().toBase58String(),
				h.getLastSeenAt() > 0 ? Formats.time(h.getLastSeenAt()) : "-",
				h.getLastSuccessAt() > 0 ? Formats.time(h.getLastSuccessAt()) : "-",
				Integer.toString(h.getConsecutiveFailures()),
				h.getLastFailure().map(o -> o.name().toLowerCase(Locale.ROOT)).orElse("-"),
				lastHour(h),
				h.getDemand() > 0 ? Integer.toString(h.getDemand()) : "-");
	}

	/**
	 * Returns the JSON of a node's health.
	 *
	 * @param h the health
	 * @return the JSON object
	 */
	public static Map<String, Object> federatedNodeHealthJson(FederatedNodeHealth h) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("nodeId", h.getNodeId());
		json.put("lastSeenAt", h.getLastSeenAt());
		json.put("lastSuccessAt", h.getLastSuccessAt() > 0 ? h.getLastSuccessAt() : null);
		json.put("lastFailureAt", h.getLastFailureAt() > 0 ? h.getLastFailureAt() : null);
		json.put("lastFailure", h.getLastFailure().map(Enum::name).orElse(null));
		json.put("lastFailureDetail", h.getLastFailureDetail().orElse(null));
		json.put("consecutiveFailures", h.getConsecutiveFailures());
		Map<String, Integer> lastHour = new LinkedHashMap<>();
		h.getLastHour().forEach((outcome, count) -> lastHour.put(outcome.name(), count));
		json.put("lastHour", lastHour);
		json.put("demand", h.getDemand());
		json.put("lastDemandAt", h.getLastDemandAt() > 0 ? h.getLastDemandAt() : null);
		json.put("lastDemandReason", h.getLastDemandReason().map(Enum::name).orElse(null));
		return json;
	}

	/**
	 * Returns the JSON of a federated node.
	 *
	 * @param node the node
	 * @return the JSON object
	 */
	public static Map<String, Object> federatedNodeJson(FederatedNode node) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("nodeId", node.getId());
		json.put("state", node.getState().map(st -> st.name().toLowerCase(Locale.ROOT)).orElse(null));
		json.put("admission", node.getAdmission().map(Enum::name).orElse(null));
		json.put("admitted", node.isAdmitted());
		json.put("note", node.getNote().orElse(null));
		json.put("createdAt", node.getCreatedAt());
		json.put("updatedAt", node.getUpdatedAt());
		json.put("health", node.getHealth().map(AdminViews::federatedNodeHealthJson).orElse(null));
		return json;
	}

	/**
	 * Returns the details of a node's descriptor.
	 *
	 * @param d the descriptor
	 * @return the details, keyed by label
	 */
	public static Map<String, String> federatedNodeDescriptor(FederatedNodeDescriptor d) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Node id", d.getNodeId().toBase58String());
		rows.put("Name", Formats.text(d.getName()));
		rows.put("Software", Formats.text((d.getSoftware().orElse("") + " " + d.getVersion().orElse("")).strip()));
		rows.put("Website", Formats.text(d.getWebsite()));
		rows.put("Contact", Formats.text(d.getContact()));
		rows.put("Federation mode", d.getMode().map(m -> m.wireName()).orElse("-"));
		rows.put("Protocols", Formats.list(d.getProtocols()));
		rows.put("Services", Integer.toString(d.getServices().size()));
		rows.put("Signed", Formats.time(d.getIssuedAt()));
		rows.put("Expires", Formats.time(d.getExpiresAt()));
		return rows;
	}

	/**
	 * Returns the JSON of a node's descriptor.
	 *
	 * @param d the descriptor
	 * @return the JSON object
	 */
	public static Map<String, Object> federatedNodeDescriptorJson(FederatedNodeDescriptor d) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("nodeId", d.getNodeId());
		json.put("name", d.getName().orElse(null));
		json.put("software", d.getSoftware().orElse(null));
		json.put("version", d.getVersion().orElse(null));
		json.put("logo", d.getLogo().orElse(null));
		json.put("website", d.getWebsite().orElse(null));
		json.put("contact", d.getContact().orElse(null));
		json.put("mode", d.getMode().map(m -> m.wireName()).orElse(null));
		json.put("protocols", d.getProtocols());
		json.put("capabilities", d.getCapabilities());
		json.put("services", d.getServices().stream().map(AdminViews::federatedServiceJson).toList());
		json.put("issuedAt", d.getIssuedAt());
		json.put("expiresAt", d.getExpiresAt());
		return json;
	}

	/**
	 * Returns the table row of a federated service.
	 *
	 * @param service the service
	 * @return the row
	 */
	public static List<String> federatedServiceRow(FederatedService service) {
		return List.of(service.getType(),
				Formats.text(service.getName()),
				service.getPeerId().toBase58String(),
				service.getEndpoint(),
				service.isFederation() ? "yes" : "no");
	}

	/**
	 * Returns the JSON of a federated service.
	 *
	 * @param service the service
	 * @return the JSON object
	 */
	public static Map<String, Object> federatedServiceJson(FederatedService service) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("type", service.getType());
		json.put("name", service.getName().orElse(null));
		json.put("peerId", service.getPeerId());
		json.put("endpoint", service.getEndpoint());
		json.put("federation", service.isFederation());
		json.put("extra", service.getExtra().isEmpty() ? null : service.getExtra());
		return json;
	}

	private static String role(FederationProposal p) {
		return p.getRole().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("-");
	}

	private static String status(FederationProposal p) {
		return p.getStatus().map(st -> st.name().toLowerCase(Locale.ROOT)).orElse("-");
	}

	/**
	 * Returns the table row of a federation proposal.
	 *
	 * @param p the proposal
	 * @return the row
	 */
	public static List<String> federationProposalRow(FederationProposal p) {
		return List.of(Long.toString(p.getId()),
				p.getNodeId().toBase58String(),
				role(p),
				status(p),
				Formats.brief(p.getNote().orElse(null), BRIEF),
				Formats.time(p.getCreatedAt()));
	}

	/**
	 * Returns the details of a federation proposal.
	 *
	 * @param p the proposal
	 * @return the details, keyed by label
	 */
	public static Map<String, String> federationProposal(FederationProposal p) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Id", Long.toString(p.getId()));
		rows.put("Node id", p.getNodeId().toBase58String());
		rows.put("Role", p.getRole().map(r -> r == FederationProposal.Role.OFFER ?
				"offer (this node proposed)" : "answer (the node proposed)").orElse("-"));
		rows.put("Status", status(p));
		rows.put("Note", Formats.text(p.getNote()));
		rows.put("Created", Formats.time(p.getCreatedAt()));
		rows.put("Updated", Formats.time(p.getUpdatedAt()));
		if (p.getAnsweredAt() > 0)
			rows.put("Answered", Formats.time(p.getAnsweredAt()));
		return rows;
	}

	/**
	 * Returns the JSON of a federation proposal.
	 *
	 * @param p the proposal
	 * @return the JSON object
	 */
	public static Map<String, Object> federationProposalJson(FederationProposal p) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", p.getId());
		json.put("nodeId", p.getNodeId());
		json.put("role", p.getRole().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse(null));
		json.put("status", p.getStatus().map(st -> st.name().toLowerCase(Locale.ROOT)).orElse(null));
		json.put("note", p.getNote().orElse(null));
		json.put("createdAt", p.getCreatedAt());
		json.put("updatedAt", p.getUpdatedAt());
		json.put("answeredAt", p.getAnsweredAt() > 0 ? p.getAnsweredAt() : null);
		return json;
	}
}
