package com.redhat.kb;

/**
 * Constants for Red Hat Knowledge Base MCP.
 */
public final class KnowledgeBaseConstants {

    private KnowledgeBaseConstants() {
        // Utility class
    }

    // Input limits
    public static final int MAX_QUERY_LENGTH = 1000;

    // Result limits
    public static final int DEFAULT_MAX_RESULTS = 10;
    public static final int MIN_RESULTS = 1;
    // Capped at 25: each rendered result costs roughly 110-150 tokens, so a larger page
    // would spend several thousand tokens of the client's context on a single call.
    public static final int MAX_RESULTS = 25;

    // Output limits, in characters, applied before content reaches the model.
    /** Per-section cap in the detailed article view. */
    public static final int MAX_SECTION_CHARS = 4000;
    /** Overall cap for a single article, roughly 4-5k tokens. */
    public static final int MAX_ARTICLE_CHARS = 15000;
    /**
     * Abstract length in search result summaries.
     *
     * <p>This is the budget the model spends deciding which article to open, so it pays for
     * itself: a Knowledge Base of failures has many near-identical titles ("Pods in
     * CrashLoopBackOff"), and at 150 characters the summary was cut before the symptom that
     * tells them apart.
     */
    public static final int MAX_SUMMARY_CHARS = 300;

    /** Prefix of the marker appended to truncated content; also used to detect it. */
    public static final String TRUNCATION_MARKER = "[truncated -";

    /** Base URL for article links; the full URL is derivable from the article ID. */
    public static final String ARTICLE_BASE_URL = "https://access.redhat.com/solutions/";

    /** Base URL for CVE pages; the full URL is derivable from the CVE name. */
    public static final String CVE_BASE_URL = "https://access.redhat.com/security/cve/";

    /**
     * The shape of a CVE identifier, as in CVE-2024-6387.
     *
     * <p>Shared so the client that validates an identifier and the formatter that decides
     * whether to link one cannot disagree about what counts as a CVE: two copies of this
     * pattern drift in silence, since neither side fails to compile when only one changes.
     */
    public static final String CVE_ID_REGEX = "CVE-\\d{4}-\\d{4,19}";
}
