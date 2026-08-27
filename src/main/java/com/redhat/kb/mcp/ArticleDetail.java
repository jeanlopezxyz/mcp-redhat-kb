package com.redhat.kb.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured payload for a single article, returned alongside the text rendering.
 *
 * <p>Every text field here has already been sanitized and truncated by
 * {@link ContentSanitizer}: the structured channel carries the same bounded content as the
 * prose, not the raw upstream body.
 */
public record ArticleDetail(
        @JsonPropertyDescription("Numeric article ID") String id,
        @JsonPropertyDescription("Article title") String title,
        @JsonPropertyDescription("Document kind: Solution, Documentation or Article") String documentKind,
        @JsonPropertyDescription("Canonical article URL") String url,
        @JsonPropertyDescription("Red Hat products this article applies to") List<String> products,
        @JsonPropertyDescription("Affected environment") List<String> environment,
        @JsonPropertyDescription("Problem description") List<String> issue,
        @JsonPropertyDescription("Underlying cause") List<String> rootCause,
        @JsonPropertyDescription("Steps to confirm the diagnosis") List<String> diagnosticSteps,
        @JsonPropertyDescription("Steps that resolve the problem") List<String> resolution,
        @JsonPropertyDescription("Whether content was omitted to stay within size limits") boolean truncated,
        @JsonPropertyDescription("Whether Red Hat withheld the solution sections because the "
                + "credential is not entitled to them; when true, root cause, resolution and "
                + "diagnostic steps are empty for lack of a subscription, not because the "
                + "article omits them") boolean subscriberOnly) {
}
