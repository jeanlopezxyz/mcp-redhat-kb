package com.redhat.kb.mcp;

import com.redhat.kb.application.service.KnowledgeBaseService;
import com.redhat.kb.infrastructure.client.AuthenticationException;
import com.redhat.kb.infrastructure.client.KnowledgeBaseException;
import com.redhat.kb.infrastructure.client.MissingCredentialException;
import com.redhat.kb.infrastructure.client.RedHatCredential;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;

import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_MAX_RESULTS;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_QUERY_LENGTH;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_RESULTS;
import static com.redhat.kb.KnowledgeBaseConstants.MIN_RESULTS;

/**
 * MCP Tools for Red Hat Knowledge Base.
 *
 * <p>Both tools are marked {@link Blocking}: they perform synchronous HTTP calls, so they
 * must run on a worker thread rather than on the event loop.
 */
@ApplicationScoped
public class KnowledgeBaseTools {

    private static final Logger LOG = Logger.getLogger(KnowledgeBaseTools.class);

    @Inject
    KnowledgeBaseService kbService;

    @Inject
    ToolAuditLog audit;

    @Inject
    RateLimiter rateLimiter;

    @Tool(
            name = "searchKnowledgeBase",
            title = "Search Red Hat Knowledge Base",
            description = """
                    Search the Red Hat Knowledge Base for solutions, documentation and articles.
                    Works with error messages, log excerpts, Prometheus/OpenShift alert names \
                    (e.g. 'KubePodCrashLooping') and general technical topics.
                    Set documentType to 'Solution' when troubleshooting a failure, or \
                    'Documentation' when looking for how-to guides and best practices.
                    Returns a compact list; call getSolution with an ID for the full article.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = SearchResult.class))
    @Blocking
    public ToolResponse searchKnowledgeBase(
            @ToolArg(description = "Search keywords, error message, or alert name") String query,
            @ToolArg(description = "Max results, 1-25", defaultValue = "10", required = false) Integer maxResults,
            @ToolArg(description = "Product filter, e.g. 'Red Hat OpenShift Container Platform' or "
                    + "'Red Hat Enterprise Linux'. Omit to search all products.",
                    defaultValue = "", required = false) String product,
            @ToolArg(description = "Filter by type: 'Solution', 'Documentation' or 'Article'",
                    defaultValue = "", required = false) String documentType) {

        Optional<ToolResponse> rejection = validate("query", query);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        try {
            RedHatCredential credential = kbService.currentCredential();
            Optional<ToolResponse> throttled = enforceRateLimit("searchKnowledgeBase", credential);
            if (throttled.isPresent()) {
                return throttled.get();
            }
            audit.record("searchKnowledgeBase", query, credential.fingerprint());

            List<KnowledgeBaseArticleDto> results = kbService.search(
                    credential,
                    query.strip(),
                    clampMaxResults(maxResults),
                    normalize(product),
                    normalize(documentType));

            if (results.isEmpty()) {
                return ToolResponse.success(new TextContent(
                        "No results found for: " + query.strip()
                                + "\nTry broader keywords, or omit the product/documentType filters."));
            }
            // Both channels: prose for the model to read, structured data for the client
            // to parse without scraping it back out of the text. The prose is rendered
            // from the record, so the two cannot drift apart.
            SearchResult structured = ArticleFormatter.toSearchResult(results);
            return new ToolResponse(
                    false,
                    List.of(new TextContent(ArticleFormatter.formatSearchResults(structured, query.strip()))),
                    structured,
                    Map.of());
        } catch (Exception e) {
            return toErrorResponse("Search failed", e);
        }
    }

    @Tool(
            name = "getSolution",
            title = "Get Knowledge Base article",
            description = "Retrieve the full content of a Knowledge Base article: environment, issue, "
                    + "root cause, diagnostic steps and resolution. Use a numeric article ID returned "
                    + "by searchKnowledgeBase. Long articles are truncated.",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = ArticleDetail.class))
    @Blocking
    public ToolResponse getSolution(
            @ToolArg(description = "Numeric article ID from search results, e.g. '5049001'") String solutionId) {

        Optional<ToolResponse> rejection = validate("solutionId", solutionId);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        try {
            RedHatCredential credential = kbService.currentCredential();
            Optional<ToolResponse> throttled = enforceRateLimit("getSolution", credential);
            if (throttled.isPresent()) {
                return throttled.get();
            }
            audit.record("getSolution", solutionId, credential.fingerprint());

            return kbService.getArticle(credential, solutionId.strip())
                    .map(article -> {
                        ArticleDetail structured = ArticleFormatter.toArticleDetail(article);
                        return new ToolResponse(
                                false,
                                List.of(new TextContent(ArticleFormatter.formatArticle(structured))),
                                structured,
                                Map.<MetaKey, Object>of());
                    })
                    .orElseGet(() -> ToolResponse.error(
                            "Error: no article found with ID " + solutionId.strip()));
        } catch (Exception e) {
            return toErrorResponse("Could not retrieve the article", e);
        }
    }

    /**
     * Applies the argument checks shared by every tool.
     *
     * <p>Credentials are deliberately not checked here: which token serves a request is
     * resolved per call, and a caller supplying their own is valid even when the server
     * holds no shared token.
     */
    private Optional<ToolResponse> validate(String argName, String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(ToolResponse.error("Error: " + argName + " is required"));
        }
        if (value.length() > MAX_QUERY_LENGTH) {
            return Optional.of(ToolResponse.error(
                    "Error: " + argName + " too long (max " + MAX_QUERY_LENGTH + " chars)"));
        }
        return Optional.empty();
    }

    /**
     * Refuses the call when the caller has exceeded their share of the Red Hat quota.
     *
     * @return the refusal to return, or empty when the call may proceed
     */
    private Optional<ToolResponse> enforceRateLimit(String tool, RedHatCredential credential) {
        if (rateLimiter.tryAcquire(credential.fingerprint())) {
            return Optional.empty();
        }
        String reason = "rate limit exceeded (" + rateLimiter.callsPerMinute() + " calls/minute)";
        audit.recordDenied(tool, reason);
        return Optional.of(ToolResponse.error(
                "Error: " + reason + ". Wait a moment before retrying."));
    }

    /**
     * Builds the client-facing error. Only messages from our own typed exceptions are
     * relayed, since arbitrary exception messages may carry response bodies or credentials;
     * the full detail goes to the log.
     */
    private ToolResponse toErrorResponse(String context, Exception e) {
        if (e instanceof MissingCredentialException) {
            // Actionable configuration problem, not a server fault: no stack trace.
            LOG.debugf("Request without a usable Red Hat credential: %s", e.getMessage());
            return ToolResponse.error("Error: " + e.getMessage());
        }
        LOG.errorf(e, "%s", context);
        if (e instanceof KnowledgeBaseException || e instanceof AuthenticationException) {
            return ToolResponse.error("Error: " + e.getMessage());
        }
        return ToolResponse.error("Error: " + context + ". Check the server logs for details.");
    }

    private static int clampMaxResults(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.max(MIN_RESULTS, Math.min(MAX_RESULTS, requested));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
