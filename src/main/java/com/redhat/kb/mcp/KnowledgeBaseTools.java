package com.redhat.kb.mcp;

import com.redhat.kb.mcp.model.ArticleDetail;
import com.redhat.kb.mcp.model.SearchResult;

import com.redhat.kb.infrastructure.credential.AuthenticationException;
import com.redhat.kb.infrastructure.credential.CredentialResolver;
import com.redhat.kb.infrastructure.client.KnowledgeBaseClient;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;
import com.redhat.kb.infrastructure.credential.MissingCredentialException;
import com.redhat.kb.infrastructure.credential.RedHatCredential;
import com.redhat.kb.infrastructure.model.SearchPage;

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
import java.util.Locale;
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
    KnowledgeBaseClient knowledgeBase;

    @Inject
    CredentialResolver credentials;

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
                    Query with the distinctive words of the problem - the failing component \
                    plus the error text - rather than one broad term: 'etcd' matches over \
                    20000 articles, 'etcd members are unhealthy after upgrade' about 300. \
                    Plain keywords only; boolean operators and field:value syntax are \
                    escaped, not interpreted.
                    Set documentType to 'Solution' when troubleshooting a failure, or \
                    'Documentation' when looking for how-to guides and best practices.
                    Reports how many articles matched in total, so a much larger total than \
                    the page returned means the query should be narrowed.
                    Returns a compact list of ID, kind, date and abstract - not the article \
                    bodies. Read it, pick the entry that matches the symptom, then call \
                    getArticle with that ID. When several look alike, prefer the more \
                    specific title and the more recent date.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = SearchResult.class))
    @Blocking
    public ToolResponse searchKnowledgeBase(
            @ToolArg(description = "The distinctive words of the problem: failing component and "
                    + "error text, e.g. 'etcd members unhealthy after upgrade'") String query,
            @ToolArg(description = "Max results, 1-25", defaultValue = "10", required = false) Integer maxResults,
            @ToolArg(description = "Product filter. Matched exactly, so it must be Red Hat's full "
                    + "official name: 'Red Hat OpenShift Container Platform' works, 'OpenShift' "
                    + "returns nothing. When unsure, omit it and put the product in the query "
                    + "instead - an empty result page is indistinguishable from a wrong filter.",
                    defaultValue = "", required = false) String product,
            @ToolArg(description = "Filter by type: 'Solution' (a fix for a failure, the only kind "
                    + "with root cause and resolution), 'Article' (background) or 'Documentation' "
                    + "(product manuals, identified by URL rather than a numeric ID)",
                    defaultValue = "", required = false) String documentType) {

        Optional<ToolResponse> rejection = validate("query", query);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        try {
            RedHatCredential credential = credentials.resolve();
            Optional<ToolResponse> throttled = enforceRateLimit("searchKnowledgeBase", credential);
            if (throttled.isPresent()) {
                return throttled.get();
            }
            audit.record("searchKnowledgeBase", query, credential.fingerprint());

            SearchPage page = knowledgeBase.search(
                    credential,
                    query.strip(),
                    clampMaxResults(maxResults),
                    normalize(product),
                    normalizeDocumentType(documentType));

            if (page.isEmpty()) {
                // A declared output schema obliges every successful response to carry
                // structured content, so an empty result set ships an empty record rather
                // than text alone.
                return new ToolResponse(
                        false,
                        List.of(new TextContent("No results found for: " + query.strip()
                                + "\nTry broader keywords, or omit the product/documentType filters."
                                + "\nNote that product is matched exactly, so it must be the full name,"
                                + " for example 'Red Hat OpenShift Container Platform'.")),
                        new SearchResult(0, 0, List.of()),
                        Map.<MetaKey, Object>of());
            }
            // Both channels: prose for the model to read, structured data for the client
            // to parse without scraping it back out of the text. The prose is rendered
            // from the record, so the two cannot drift apart.
            SearchResult structured = ArticleFormatter.toSearchResult(page);
            return new ToolResponse(
                    false,
                    List.of(new TextContent(ArticleFormatter.formatSearchResults(structured, query.strip()))),
                    structured,
                    Map.of());
        } catch (Exception e) {
            return ToolErrors.toResponse("Search failed", e);
        }
    }

    @Tool(
            name = "getArticle",
            title = "Get Knowledge Base article",
            description = "Retrieve the content of a Knowledge Base article: environment, issue, "
                    + "root cause, diagnostic steps and resolution. Use an ID returned by "
                    + "searchKnowledgeBase. Long articles are truncated. Without an entitled Red Hat "
                    + "subscription the problem description is returned but the root cause, resolution "
                    + "and diagnostic steps are withheld; the response says so explicitly when that "
                    + "happens, so report it rather than concluding the article has no fix.",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = ArticleDetail.class))
    @Blocking
    public ToolResponse getArticle(
            @ToolArg(description = "An ID from search results: an article number such as '5049001', "
                    + "or an advisory such as 'RHSA-2026:6565'. Documentation entries are identified "
                    + "by URL and are read from that link instead.") String articleId,
            @ToolArg(description = "The kind shown in brackets next to that ID in the search results "
                    + "('Solution', 'Vulnerability', 'Errata'...). Ids are reused across kinds, so "
                    + "passing it is what stops an unrelated document being returned.",
                    defaultValue = "", required = false) String documentKind) {

        Optional<ToolResponse> rejection = validate("articleId", articleId);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        try {
            RedHatCredential credential = credentials.resolve();
            Optional<ToolResponse> throttled = enforceRateLimit("getArticle", credential);
            if (throttled.isPresent()) {
                return throttled.get();
            }
            audit.record("getArticle", articleId, credential.fingerprint());

            return knowledgeBase.getArticle(credential, articleId.strip(),
                    normalizeDocumentType(documentKind))
                    .map(article -> {
                        ArticleDetail structured = ArticleFormatter.toArticleDetail(article);
                        return new ToolResponse(
                                false,
                                List.of(new TextContent(ArticleFormatter.formatArticle(structured))),
                                structured,
                                Map.<MetaKey, Object>of());
                    })
                    .orElseGet(() -> ToolResponse.error(
                            "Error: no article found with ID " + articleId.strip()));
        } catch (Exception e) {
            return ToolErrors.toResponse("Could not retrieve the article", e);
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


    private static int clampMaxResults(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.max(MIN_RESULTS, Math.min(MAX_RESULTS, requested));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * Canonicalizes the document kind's capitalization.
     *
     * <p>Hydra matches {@code documentKind} exactly, so "solution" returns nothing while
     * "Solution" returns thousands — an empty page that reads as "nothing is documented"
     * rather than as a mistyped filter.
     */
    private static String normalizeDocumentType(String value) {
        String kind = normalize(value);
        if (kind.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1).toLowerCase(Locale.ROOT);
    }
}
