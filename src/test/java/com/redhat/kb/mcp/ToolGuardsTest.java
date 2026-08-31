package com.redhat.kb.mcp;

import io.quarkiverse.mcp.server.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static com.redhat.kb.KnowledgeBaseConstants.MAX_QUERY_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the checks every tool runs before doing any work.
 *
 * <p>These were reached only through the two tool classes, which meant the boundaries went
 * untested: mutation testing found the length comparison and the refusal paths surviving
 * because no test distinguished "at the limit" from "over it". They are the first thing a
 * caller's input meets, and the specification lists input validation and rate limiting as
 * obligations on servers, so they are worth pinning directly rather than incidentally.
 */
class ToolGuardsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("refuses an argument that is absent or only whitespace")
    void refusesAbsentArgument(String value) {
        Optional<ToolResponse> refusal = ToolGuards.validate("query", value);

        assertThat(refusal).isPresent();
        assertThat(text(refusal.get())).isEqualTo("Error: query is required");
    }

    @Test
    @DisplayName("names the argument that was missing")
    void namesTheMissingArgument() {
        assertThat(text(ToolGuards.validate("articleId", null).orElseThrow()))
                .contains("articleId");
    }

    @Test
    @DisplayName("uses the caller-facing message a tool supplies for an absent argument")
    void usesSuppliedMissingMessage() {
        Optional<ToolResponse> refusal = ToolGuards.validate(
                "cveId", "", "Error: cveId is required, for example CVE-2024-6387");

        assertThat(text(refusal.orElseThrow()))
                .describedAs("an example is what turns a refusal into something actionable")
                .isEqualTo("Error: cveId is required, for example CVE-2024-6387");
    }

    @Test
    @DisplayName("accepts an argument exactly at the length cap")
    void acceptsArgumentAtTheCap() {
        assertThat(ToolGuards.validate("query", "x".repeat(MAX_QUERY_LENGTH)))
                .describedAs("the cap is the longest allowed value, not the first refused one")
                .isEmpty();
    }

    @Test
    @DisplayName("refuses an argument one character past the cap")
    void refusesArgumentPastTheCap() {
        Optional<ToolResponse> refusal = ToolGuards.validate("query", "x".repeat(MAX_QUERY_LENGTH + 1));

        assertThat(refusal).isPresent();
        assertThat(text(refusal.get()))
                .contains("too long")
                .contains(String.valueOf(MAX_QUERY_LENGTH));
    }

    @Test
    @DisplayName("lets an ordinary argument through")
    void acceptsOrdinaryArgument() {
        assertThat(ToolGuards.validate("query", "CrashLoopBackOff")).isEmpty();
    }

    @Test
    @DisplayName("lets a call proceed while the caller is within their quota")
    void allowsCallWithinQuota() {
        RateLimiter limiter = mock(RateLimiter.class);
        ToolAuditLog audit = mock(ToolAuditLog.class);
        when(limiter.tryAcquire("fingerprint")).thenReturn(true);

        Optional<ToolResponse> refusal = ToolGuards.enforceRateLimit(
                limiter, audit, "searchKnowledgeBase", Optional.of("fingerprint"));

        assertThat(refusal).isEmpty();
        verify(audit, never()).recordDenied(anyString(), anyString());
    }

    @Test
    @DisplayName("refuses and records a call that exceeds the quota")
    void refusesCallOverQuota() {
        RateLimiter limiter = mock(RateLimiter.class);
        ToolAuditLog audit = mock(ToolAuditLog.class);
        when(limiter.tryAcquire("fingerprint")).thenReturn(false);
        when(limiter.callsPerMinute()).thenReturn(60);

        Optional<ToolResponse> refusal = ToolGuards.enforceRateLimit(
                limiter, audit, "searchKnowledgeBase", Optional.of("fingerprint"));

        assertThat(refusal).isPresent();
        assertThat(text(refusal.get()))
                .contains("rate limit exceeded (60 calls/minute)")
                .contains("Wait a moment");
        // A refusal nobody records is a refusal nobody can investigate.
        verify(audit).recordDenied("searchKnowledgeBase", "rate limit exceeded (60 calls/minute)");
    }

    @Test
    @DisplayName("counts a credential-less tool against the caller, not against one shared bucket")
    void usesTheCallerBucketWithoutACredential() {
        RateLimiter limiter = mock(RateLimiter.class);
        ToolAuditLog audit = mock(ToolAuditLog.class);
        when(limiter.tryAcquire()).thenReturn(true);

        assertThat(ToolGuards.enforceRateLimit(limiter, audit, "lookupCve", Optional.empty()))
                .isEmpty();

        // The no-argument overload is the one that separates callers by identity or address;
        // passing a constant key here would give every caller one shared quota.
        verify(limiter).tryAcquire();
        verify(limiter, never()).tryAcquire(anyString());
    }

    private static String text(ToolResponse response) {
        return response.content().get(0).asText().text();
    }
}
