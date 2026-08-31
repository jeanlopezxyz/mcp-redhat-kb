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

    @Test
    @DisplayName("accepts an advisory id, which search offers alongside article ids")
    void acceptsAdvisoryIds() {
        // Errata are returned by search with ids like RHSA-2026:6565. Rejecting them left
        // the model holding a result it had no way to open.
        assertTrue(SolrQuery.isValidArticleId("RHSA-2026:6565"));
        assertTrue(SolrQuery.isValidArticleId("rhba-2025:1234"));
    }

    @Test
    @DisplayName("still rejects anything that is neither an article nor an advisory")
    void rejectsOtherIdentifiers() {
        assertFalse(SolrQuery.isValidArticleId("1 OR 1=1"));
        assertFalse(SolrQuery.isValidArticleId("https://docs.redhat.com/en/x"));
        assertFalse(SolrQuery.isValidArticleId("helm-3.0.0-1.el8.x86_64.rpm"));
        assertFalse(SolrQuery.isValidArticleId("RHSA-2026:6565 OR id:*"));
    }
}
