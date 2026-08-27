package com.redhat.kb.mcp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.redhat.kb.infrastructure.client.SearchPage;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;

import static com.redhat.kb.KnowledgeBaseConstants.ARTICLE_BASE_URL;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_ARTICLE_CHARS;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_SECTION_CHARS;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_SUMMARY_CHARS;
import static com.redhat.kb.KnowledgeBaseConstants.TRUNCATION_MARKER;

/**
 * Renders Knowledge Base articles for the model.
 *
 * <p>Two concerns drive the format. Token cost: article bodies are unbounded upstream, so
 * every section is sanitized and capped. Trust: article content is third-party text, so it
 * is fenced with nonce-carrying markers the content cannot predict (see
 * {@link UntrustedFence} and {@link ContentSanitizer}).
 *
 * <p>The structured record is built first and the prose is rendered from it, so both
 * channels carry identical content by construction rather than by convention.
 */
final class ArticleFormatter {

    /**
     * Stated as fact rather than possibility: Red Hat withholds every {@code solution_*}
     * field without an entitled token, so "may require a subscription" understated it and
     * left the model guessing. Emitted outside the untrusted fence — it is our text, not
     * upstream content.
     */
    private static final String WITHHELD_NOTICE =
            "[Red Hat withheld the root cause, resolution and diagnostic steps: this content "
            + "requires an entitled subscription. Set a valid REDHAT_TOKEN (or send the "
            + "X-Red-Hat-Token header) to read it, or open the URL above.]\n";

    private ArticleFormatter() {
        // Utility class
    }

    /**
     * Builds the structured payload for a search.
     */
    static SearchResult toSearchResult(SearchPage page) {
        List<SearchResult.Article> articles = page.articles().stream()
                .map(a -> new SearchResult.Article(
                        a.getId(),
                        ContentSanitizer.clean(a.getTitle()),
                        defaultIfBlank(ContentSanitizer.clean(a.getDocumentKind()), "Article"),
                        safeUrl(a),
                        ContentSanitizer.truncate(ContentSanitizer.clean(a.getAbstractText()), MAX_SUMMARY_CHARS),
                        calendarDay(a.getLastModifiedDate())))
                .toList();
        return new SearchResult(articles.size(), page.totalFound(), articles);
    }

    /**
     * Renders a compact result list. One line per article keeps a full page of results in
     * the low thousands of tokens; per-article URLs are omitted because they derive from
     * the ID, which is stated once in the header.
     */
    static String formatSearchResults(SearchResult result, String query) {
        StringBuilder sb = new StringBuilder();

        // Stating the total is what lets the model tell a precise search from a vague one:
        // ten of ten is an answer, ten of several thousand is a hint to narrow the query.
        if (result.totalFound() > result.count()) {
            sb.append("Showing ").append(result.count())
              .append(" of ").append(result.totalFound()).append(" matches for: ");
        } else {
            sb.append(result.count()).append(" result(s) for: ");
        }
        sb.append(ContentSanitizer.clean(query)).append('\n');

        if (result.totalFound() > result.count() * 5) {
            sb.append("The query is broad; add a product, version or error text to narrow it.\n");
        }

        sb.append("Article URLs are ").append(ARTICLE_BASE_URL).append("<id>\n\n");

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append(fence.open()).append('\n');

        for (SearchResult.Article article : result.articles()) {
            sb.append(article.id()).append(" [").append(article.documentKind()).append("] ");
            if (!article.lastModified().isEmpty()) {
                // Age is a tie-breaker the title cannot carry: two articles can describe the
                // same failure while only the recent one matches a supported version.
                sb.append('(').append(article.lastModified()).append(") ");
            }
            sb.append(article.title()).append('\n');

            if (!article.summary().isEmpty()) {
                sb.append("    ").append(article.summary()).append('\n');
            }
        }

        sb.append(fence.close()).append('\n');
        sb.append("\nCall getArticle with an article ID for the full content.");
        return sb.toString();
    }

    /**
     * Builds the structured payload for a single article. Sections are capped individually
     * and the article as a whole, so an oversized upstream body cannot flood the context.
     */
    static ArticleDetail toArticleDetail(KnowledgeBaseArticleDto article) {
        // Ordered so the whole-article budget is spent on the most useful sections first:
        // a reader needs the resolution far more often than the diagnostic transcript.
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Environment", cappedSection(article.getSolutionEnvironment()));
        sections.put("Issue", cappedSection(article.getIssue()));
        sections.put("Root Cause", cappedSection(article.getSolutionRootcause()));
        sections.put("Resolution", cappedSection(article.getSolutionResolution()));
        sections.put("Diagnostic Steps", cappedSection(article.getSolutionDiagnosticsteps()));

        boolean truncated = applyArticleBudget(sections);

        return new ArticleDetail(
                article.getId(),
                ContentSanitizer.clean(article.getTitle()),
                defaultIfBlank(ContentSanitizer.clean(article.getDocumentKind()), "Article"),
                safeUrl(article),
                ContentSanitizer.clean(article.getProduct()),
                sections.get("Environment"),
                sections.get("Issue"),
                sections.get("Root Cause"),
                sections.get("Diagnostic Steps"),
                sections.get("Resolution"),
                truncated,
                article.isSubscriberOnly());
    }

    /**
     * Renders a single article from its structured form.
     */
    static String formatArticle(ArticleDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(detail.documentKind()).append(" ===\n\n");
        sb.append("ID: ").append(detail.id()).append('\n');
        sb.append("Title: ").append(detail.title()).append('\n');
        sb.append("URL: ").append(detail.url()).append('\n');

        if (!detail.products().isEmpty()) {
            sb.append("Products: ").append(String.join(", ", detail.products())).append('\n');
        }

        StringBuilder body = new StringBuilder();
        appendSection(body, "Environment", detail.environment());
        appendSection(body, "Issue", detail.issue());
        appendSection(body, "Root Cause", detail.rootCause());
        appendSection(body, "Diagnostic Steps", detail.diagnosticSteps());
        appendSection(body, "Resolution", detail.resolution());

        if (body.isEmpty()) {
            sb.append('\n').append(detail.subscriberOnly()
                    ? WITHHELD_NOTICE
                    : "No detailed content available for this article; open the URL above to read it.\n");
            return sb.toString();
        }

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append('\n').append(fence.open()).append('\n');
        sb.append(body);
        if (detail.truncated()) {
            sb.append("\n[Content was truncated to stay within size limits; open the URL for the full article]\n");
        }
        sb.append(fence.close()).append('\n');

        // The public fields (issue, abstract) are substantial, so a paywalled article still
        // renders a body. Without this the model would read a detailed problem statement,
        // find no resolution, and conclude the article has none.
        if (detail.subscriberOnly()) {
            sb.append('\n').append(WITHHELD_NOTICE);
        }
        return sb.toString();
    }

    /**
     * Enforces the whole-article budget across sections, trimming from the end so earlier
     * (more useful) sections survive intact.
     *
     * @return whether anything was dropped, including per-section truncation
     */
    private static boolean applyArticleBudget(Map<String, List<String>> sections) {
        boolean truncated = sections.values().stream()
                .flatMap(List::stream)
                .anyMatch(s -> s.contains(TRUNCATION_MARKER));

        int remaining = MAX_ARTICLE_CHARS;
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            List<String> content = entry.getValue();
            if (content.isEmpty()) {
                continue;
            }
            String text = content.get(0);
            if (text.length() <= remaining) {
                remaining -= text.length();
                continue;
            }
            // Budget exhausted: keep what fits, drop the rest of this and later sections.
            entry.setValue(remaining > 0 ? List.of(ContentSanitizer.truncate(text, remaining)) : List.of());
            remaining = 0;
            truncated = true;
        }
        return truncated;
    }

    private static List<String> cappedSection(List<String> raw) {
        List<String> cleaned = ContentSanitizer.clean(raw);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.of(ContentSanitizer.truncate(String.join("\n", cleaned), MAX_SECTION_CHARS));
    }

    private static void appendSection(StringBuilder sb, String heading, List<String> content) {
        if (content.isEmpty()) {
            return;
        }
        sb.append("\n--- ").append(heading).append(" ---\n");
        sb.append(String.join("\n", content)).append('\n');
    }

    /**
     * Prefers the canonical URL derived from the article ID. A {@code view_uri} supplied by
     * the API is only echoed when it is an HTTPS redhat.com address, so a tampered record
     * cannot present an arbitrary link to the model as official.
     */
    private static String safeUrl(KnowledgeBaseArticleDto article) {
        String viewUri = article.getViewUri();
        if (viewUri != null && !viewUri.isBlank()) {
            try {
                URI uri = URI.create(viewUri.strip());
                String host = uri.getHost();
                if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                        && (host.equals("redhat.com") || host.endsWith(".redhat.com"))) {
                    return uri.toString();
                }
            } catch (IllegalArgumentException e) {
                // Fall through to the derived URL.
            }
        }
        return article.getId() != null ? ARTICLE_BASE_URL + article.getId() : "N/A";
    }

    /**
     * Keeps the calendar day of an upstream timestamp ({@code 2024-06-13T23:37:19Z}). The
     * time of day says nothing about how current an article is and costs tokens per result.
     */
    private static String calendarDay(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return "";
        }
        String day = timestamp.substring(0, 10);
        return day.matches("\\d{4}-\\d{2}-\\d{2}") ? day : "";
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
