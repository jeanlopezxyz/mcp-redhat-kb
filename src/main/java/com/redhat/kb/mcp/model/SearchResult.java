package com.redhat.kb.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured payload for a search, returned alongside the text rendering.
 *
 * <p>The specification (2025-06-18 onward) lets a tool declare an output schema and return
 * {@code structuredContent}. That gives the client parseable data instead of forcing it to
 * scrape the prose, and it makes the article IDs unambiguous for the follow-up
 * {@code getArticle} call.
 */
public record SearchResult(
        @JsonPropertyDescription("Number of articles returned in this response") int count,
        @JsonPropertyDescription("Total articles matching the query across the whole Knowledge Base; "
                + "when this greatly exceeds count, narrow the query rather than trusting these to be "
                + "the only matches") int totalFound,
        @JsonPropertyDescription("Matching articles, most relevant first") List<Article> articles) {

    /**
     * One search hit. Deliberately narrow: the full body is fetched via getArticle, so
     * repeating it here would double the token cost of a result page.
     */
    public record Article(
            @JsonPropertyDescription("Numeric article ID; pass this to getArticle") String id,
            @JsonPropertyDescription("Article title") String title,
            @JsonPropertyDescription("Document kind: Solution, Documentation or Article") String documentKind,
            @JsonPropertyDescription("Canonical article URL") String url,
            @JsonPropertyDescription("Short abstract, truncated") String summary,
            @JsonPropertyDescription("Date the article was last modified (YYYY-MM-DD), empty when "
                    + "upstream does not report one; prefer the more recent of two articles that "
                    + "otherwise apply equally") String lastModified) {
    }
}
