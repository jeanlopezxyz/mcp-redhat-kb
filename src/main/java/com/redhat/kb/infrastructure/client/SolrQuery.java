package com.redhat.kb.infrastructure.client;

import java.util.regex.Pattern;

/**
 * Helpers for building safe Solr/Lucene queries against the Hydra API.
 *
 * <p>URL encoding alone is not enough: it prevents HTTP parameter injection but the value
 * still reaches Solr as valid Lucene syntax, so an unescaped input can alter the query
 * semantics (widen the result set, or build a deliberately expensive query).
 */
final class SolrQuery {

    /** Lucene syntax metacharacters, plus the boolean operators {@code &&} and {@code ||}. */
    private static final Pattern SPECIAL_CHARS =
            Pattern.compile("([+\\-!(){}\\[\\]^\"~*?:\\\\/]|&&|\\|\\|)");

    /** Article IDs in the Red Hat Knowledge Base are numeric. */
    private static final Pattern ARTICLE_ID = Pattern.compile("\\d{1,12}");

    private SolrQuery() {
        // Utility class
    }

    /**
     * Escapes Lucene metacharacters so the value is treated as literal search text.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return SPECIAL_CHARS.matcher(value).replaceAll("\\\\$1");
    }

    /**
     * Checks that an article ID is strictly numeric before it is interpolated into a query.
     */
    static boolean isValidArticleId(String articleId) {
        return articleId != null && ARTICLE_ID.matcher(articleId).matches();
    }
}
