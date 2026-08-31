package com.redhat.kb.mcp;

import io.quarkiverse.mcp.server.ToolResponse;

import java.util.Optional;

import static com.redhat.kb.KnowledgeBaseConstants.MAX_QUERY_LENGTH;

/**
 * The argument and quota checks every tool runs before doing any work.
 *
 * <p>Shared for the same reason as {@link ToolErrors}: both tool classes grew their own
 * copy of these checks, down to the wording of the refusals. Two copies of a rule the
 * caller sees is two chances for them to drift, and the message is the interface — a
 * caller who is told a different limit by two tools cannot tell which one to believe.
 */
final class ToolGuards {

    private ToolGuards() {
        // Utility class
    }

    /**
     * Rejects an argument that is absent or implausibly long.
     *
     * <p>The length cap is not cosmetic: without it a megabyte-long argument would spend
     * the caller's rate-limit budget and be URL-encoded into an upstream request before
     * anything noticed.
     *
     * @param missingMessage what to say when the value is absent, so a tool can name an
     *                       example of the identifier it expects
     * @return the refusal to return, or empty when the argument may be used
     */
    static Optional<ToolResponse> validate(String argName, String value, String missingMessage) {
        if (value == null || value.isBlank()) {
            return Optional.of(ToolResponse.error(missingMessage));
        }
        if (value.length() > MAX_QUERY_LENGTH) {
            return Optional.of(ToolResponse.error(
                    "Error: " + argName + " too long (max " + MAX_QUERY_LENGTH + " chars)"));
        }
        return Optional.empty();
    }

    /**
     * Rejects an absent or implausibly long argument, reporting it by name.
     *
     * @return the refusal to return, or empty when the argument may be used
     */
    static Optional<ToolResponse> validate(String argName, String value) {
        return validate(argName, value, "Error: " + argName + " is required");
    }

    /**
     * Refuses the call when the caller has exceeded their share of the Red Hat quota.
     *
     * @param fingerprint the credential whose quota is spent, or empty for the public
     *                    APIs, where the limiter tells callers apart by identity or
     *                    remote address instead — a constant key would give every caller
     *                    one shared bucket, the very starvation the limiter prevents
     * @return the refusal to return, or empty when the call may proceed
     */
    static Optional<ToolResponse> enforceRateLimit(
            RateLimiter rateLimiter, ToolAuditLog audit, String tool, Optional<String> fingerprint) {

        boolean allowed = fingerprint.map(rateLimiter::tryAcquire).orElseGet(rateLimiter::tryAcquire);
        if (allowed) {
            return Optional.empty();
        }
        String reason = "rate limit exceeded (" + rateLimiter.callsPerMinute() + " calls/minute)";
        audit.recordDenied(tool, reason);
        return Optional.of(ToolResponse.error(
                "Error: " + reason + ". Wait a moment before retrying."));
    }
}
