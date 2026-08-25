package com.redhat.kb.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured payload for a search, returned alongside the text rendering.
 *
 * <p>The specification (2025-06-18 onward) lets a tool declare an output schema and return
 * {@code structuredContent}. That gives the client parseable data instead of forcing it to
 * scrape the prose, and it makes the article IDs unambiguous for the follow-up
 * {@code getSolution} call.
 */
public record SearchResult(
        @JsonPropertyDescription("Number of articles returned") int count,
        @JsonPropertyDescription("Matching articles, most relevant first") List<Article> articles) {

    /**
     * One search hit. Deliberately narrow: the full body is fetched via getSolution, so
     * repeating it here would double the token cost of a result page.
     */
    public record Article(
            @JsonPropertyDescription("Numeric article ID; pass this to getSolution") String id,
            @JsonPropertyDescription("Article title") String title,
            @JsonPropertyDescription("Document kind: Solution, Documentation or Article") String documentKind,
            @JsonPropertyDescription("Canonical article URL") String url,
            @JsonPropertyDescription("Short abstract, truncated") String summary) {
    }
}
