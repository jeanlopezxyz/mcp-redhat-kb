package com.redhat.kb.mcp;

import com.redhat.kb.mcp.model.CveDetail;
import com.redhat.kb.mcp.model.LifecycleDetail;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** Stands in for the credential fingerprint in the audit trail: these APIs take none. */
    private static final String NO_CREDENTIAL = "none-public-api";

    private final SecurityDataClient securityData;
    private final LifecycleClient lifecycle;
    private final ToolAuditLog audit;
    private final RateLimiter rateLimiter;

    @Inject
    public SecurityTools(SecurityDataClient securityData, LifecycleClient lifecycle,
            ToolAuditLog audit, RateLimiter rateLimiter) {
        this.securityData = securityData;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

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

        Optional<ToolResponse> rejection = ToolGuards.validate("cveId", cveId,
                "Error: cveId is required, for example CVE-2024-6387");
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Optional<ToolResponse> throttled = enforceRateLimit("lookupCve");
        if (throttled.isPresent()) {
            return throttled.get();
        }
        audit.record("lookupCve", cveId, NO_CREDENTIAL);

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
            return ToolErrors.toResponse("CVE lookup failed", e);
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

        Optional<ToolResponse> rejection = ToolGuards.validate("product", product,
                "Error: product is required");
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Optional<ToolResponse> throttled = enforceRateLimit("getProductLifecycle");
        if (throttled.isPresent()) {
            return throttled.get();
        }
        audit.record("getProductLifecycle", product, NO_CREDENTIAL);

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
            return ToolErrors.toResponse("Life cycle lookup failed", e);
        }
    }

    /**
     * These tools take no credential, so the limiter separates callers by identity or
     * remote address instead. A constant key here would give every caller one shared
     * bucket — the very starvation the limiter exists to prevent.
     */
    private Optional<ToolResponse> enforceRateLimit(String tool) {
        return ToolGuards.enforceRateLimit(rateLimiter, audit, tool, Optional.empty());
    }
}
