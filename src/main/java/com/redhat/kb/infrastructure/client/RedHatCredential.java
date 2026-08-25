package com.redhat.kb.infrastructure.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A Red Hat offline token together with a stable, non-reversible identifier for it.
 *
 * <p>The identifier is the SHA-256 of the token, so caches can be partitioned per
 * credential without the key itself being a secret: it appears in cache keys and log lines,
 * where the token must never appear. Hashing also works when there is no Keycloak identity
 * to key on, which is the case for the stdio transport.
 *
 * <p>{@link #toString()} is overridden so an accidental interpolation of this object into a
 * message or log cannot leak the token.
 */
public final class RedHatCredential {

    /** Length of the hash prefix used as an identifier; ample to avoid collisions. */
    private static final int FINGERPRINT_LENGTH = 16;

    private final String token;
    private final String fingerprint;

    private RedHatCredential(String token) {
        this.token = token;
        this.fingerprint = fingerprint(token);
    }

    /**
     * @throws IllegalArgumentException when the token is null or blank
     */
    public static RedHatCredential of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Red Hat token must not be blank");
        }
        return new RedHatCredential(token.strip());
    }

    public String token() {
        return token;
    }

    /**
     * Stable identifier for this credential, safe to log and to use as a cache key.
     */
    public String fingerprint() {
        return fingerprint;
    }

    private static String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_LENGTH);
        } catch (Exception e) {
            // SHA-256 is mandated by the platform; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RedHatCredential other && token.equals(other.token);
    }

    @Override
    public int hashCode() {
        return token.hashCode();
    }

    @Override
    public String toString() {
        // Never render the token itself.
        return "RedHatCredential[" + fingerprint + "]";
    }
}
