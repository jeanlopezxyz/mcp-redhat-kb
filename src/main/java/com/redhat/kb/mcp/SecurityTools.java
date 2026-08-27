package com.redhat.kb.mcp;

import com.redhat.kb.mcp.model.CveDetail;
import com.redhat.kb.mcp.model.LifecycleDetail;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;
import com.redhat.kb.infrastructure.client.LifecycleClient;
import com.redhat.kb.infrastructure.client.SecurityDataClient;

import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static com.redhat.kb.KnowledgeBaseConstants.MAX_QUERY_LENGTH;

/**
 * MCP tools backed by Red Hat's public product APIs.
 *
 * <p>Separate from {@link KnowledgeBaseTools} because these need no credential: the
 * Security Data and Product Life Cycle APIs are open, so no subscription is spent and the
 * caller's entitlements do not apply. They answer questions the Knowledge Base cannot,
 * since severity ratings, fix states and support dates are structured fields rather than
 * article prose.
 */
@ApplicationScoped
public class SecurityTools {

    private static final Logger LOG = Logger.getLogger(SecurityTools.class);

    @Inject
    SecurityDataClient securityData;

    @Inject
    LifecycleClient lifecycle;

    @Inject
    ToolAuditLog audit;

    @Inject
    RateLimiter rateLimiter;

    @Tool(
            name = "lookupCve",
            title = "Look up a CVE",
            description = """
                    Look up a CVE in Red Hat's security database. Returns the Red Hat severity \
                    rating, CVSS v3 score, which products have a released fix and which are \
                    still affected, plus any mitigation.
                    Use this instead of searchKnowledgeBase whenever a CVE identifier is \
                    involved: it answers "does this affect my product and is there a fix?" \
                    with structured data rather than prose.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = CveDetail.class))
    @Blocking
    public ToolResponse lookupCve(
            @ToolArg(description = "CVE identifier, e.g. 'CVE-2024-6387'") String cveId) {

        Optional<ToolResponse> rejection = validate("cveId", cveId,
                "Error: cveId is required, for example CVE-2024-6387");
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Optional<ToolResponse> throttled = enforceRateLimit("lookupCve");
        if (throttled.isPresent()) {
            return throttled.get();
        }
        audit.record("lookupCve", cveId, "none-public-api");

        try {
            Optional<JsonNode> record = securityData.lookupCve(cveId);
            if (record.isEmpty()) {
                // A declared output schema obliges every successful response to carry
                // structured content, so "not found" ships an empty record rather than
                // text alone.
                return new ToolResponse(
                        false,
                        List.of(new TextContent("No Red Hat record for " + cveId.strip()
                                + ". Red Hat only tracks CVEs affecting its products.")),
                        CveDetail.notFound(cveId.strip()),
                        Map.<MetaKey, Object>of());
            }

            CveDetail detail = SecurityFormatter.toCveDetail(record.get());
            return new ToolResponse(
                    false,
                    List.of(new TextContent(SecurityFormatter.formatCve(detail))),
                    detail,
                    Map.<MetaKey, Object>of());
        } catch (Exception e) {
            return toErrorResponse("CVE lookup failed", e);
        }
    }

    @Tool(
            name = "getProductLifecycle",
            title = "Get product support life cycle",
            description = """
                    Get the support life cycle of a Red Hat product: every released version \
                    with its current support phase, general availability date and end of \
                    maintenance.
                    Use this for questions about supported versions, end of life or upgrade \
                    deadlines. The product name must be the full official one, for example \
                    'Red Hat Enterprise Linux' or 'Red Hat OpenShift Container Platform'.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = LifecycleDetail.class))
    @Blocking
    public ToolResponse getProductLifecycle(
            @ToolArg(description = "Full product name, e.g. 'Red Hat OpenShift Container Platform'")
            String product) {

        Optional<ToolResponse> rejection = validate("product", product,
                "Error: product is required");
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Optional<ToolResponse> throttled = enforceRateLimit("getProductLifecycle");
        if (throttled.isPresent()) {
            return throttled.get();
        }
        audit.record("getProductLifecycle", product, "none-public-api");

        try {
            Optional<JsonNode> record = lifecycle.lookupProduct(product);
            if (record.isEmpty()) {
                return new ToolResponse(
                        false,
                        List.of(new TextContent("No life cycle published for \"" + product.strip() + "\"."
                                + " The name must match Red Hat's official product name, for example"
                                + " 'Red Hat Enterprise Linux'.")),
                        new LifecycleDetail(product.strip(), List.of()),
                        Map.<MetaKey, Object>of());
            }

            LifecycleDetail detail = SecurityFormatter.toLifecycleDetail(record.get());
            return new ToolResponse(
                    false,
                    List.of(new TextContent(SecurityFormatter.formatLifecycle(detail))),
                    detail,
                    Map.<MetaKey, Object>of());
        } catch (Exception e) {
            return toErrorResponse("Life cycle lookup failed", e);
        }
    }

    /**
     * These tools take no credential, so the limiter separates callers by identity or
     * remote address instead. A constant key here would give every caller one shared
     * bucket — the very starvation the limiter exists to prevent.
     */
    /**
     * Rejects an argument that is absent or implausibly long.
     *
     * <p>The length cap matters as much here as on the Knowledge Base tools: without it a
     * megabyte-long product name would consume this caller's rate-limit budget and be
     * URL-encoded into an upstream request before anything noticed.
     */
    private Optional<ToolResponse> validate(String argName, String value, String missingMessage) {
        if (value == null || value.isBlank()) {
            return Optional.of(ToolResponse.error(missingMessage));
        }
        if (value.length() > MAX_QUERY_LENGTH) {
            return Optional.of(ToolResponse.error(
                    "Error: " + argName + " too long (max " + MAX_QUERY_LENGTH + " chars)"));
        }
        return Optional.empty();
    }

    private Optional<ToolResponse> enforceRateLimit(String tool) {
        if (rateLimiter.tryAcquire()) {
            return Optional.empty();
        }
        String reason = "rate limit exceeded (" + rateLimiter.callsPerMinute() + " calls/minute)";
        audit.recordDenied(tool, reason);
        return Optional.of(ToolResponse.error("Error: " + reason + ". Wait a moment before retrying."));
    }

    /**
     * Only messages from our own typed exceptions are relayed; anything else could carry
     * response bodies, so the detail goes to the log instead.
     */
    private ToolResponse toErrorResponse(String context, Exception e) {
        LOG.errorf(e, "%s", context);
        if (e instanceof KnowledgeBaseException) {
            return ToolResponse.error("Error: " + e.getMessage());
        }
        return ToolResponse.error("Error: " + context + ". Check the server logs for details.");
    }
}
