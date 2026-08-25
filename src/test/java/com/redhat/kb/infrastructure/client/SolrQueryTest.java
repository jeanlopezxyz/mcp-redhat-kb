package com.redhat.kb.infrastructure.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrQueryTest {

    @Test
    @DisplayName("leaves ordinary search text untouched")
    void escapesNothingInPlainText() {
        assertEquals("CrashLoopBackOff OpenShift", SolrQuery.escape("CrashLoopBackOff OpenShift"));
    }

    @Test
    @DisplayName("escapes the field separator so a value cannot become a field query")
    void escapesColon() {
        assertEquals("id\\:5049001", SolrQuery.escape("id:5049001"));
    }

    @Test
    @DisplayName("escapes quotes so a value cannot break out of a phrase filter")
    void escapesQuotesInFilterValue() {
        // Without escaping this would close the fq phrase and inject a second clause.
        assertEquals("x\\\" OR product\\:\\\"y", SolrQuery.escape("x\" OR product:\"y"));
    }

    @Test
    @DisplayName("escapes boolean operators")
    void escapesBooleanOperators() {
        assertEquals("a \\&& b \\|| c", SolrQuery.escape("a && b || c"));
    }

    @Test
    @DisplayName("escapes wildcards used to build deliberately expensive queries")
    void escapesWildcards() {
        assertEquals("\\*\\:\\*", SolrQuery.escape("*:*"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("returns an empty string for null or empty input")
    void escapeHandlesNullAndEmpty(String input) {
        assertEquals("", SolrQuery.escape(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5049001", "123456789012"})
    @DisplayName("accepts numeric article IDs")
    void acceptsNumericIds(String id) {
        assertTrue(SolrQuery.isValidArticleId(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1 OR documentKind:Solution",
            "*:*",
            "5049001 ",
            "abc",
            "",
            "1234567890123"
    })
    @DisplayName("rejects IDs that are not strictly numeric or exceed the length bound")
    void rejectsNonNumericIds(String id) {
        assertFalse(SolrQuery.isValidArticleId(id));
    }

    @Test
    @DisplayName("rejects a null article ID")
    void rejectsNullId() {
        assertFalse(SolrQuery.isValidArticleId(null));
    }
}
