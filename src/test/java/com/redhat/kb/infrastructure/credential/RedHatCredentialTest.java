package com.redhat.kb.infrastructure.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedHatCredentialTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.secret-offline-token.signature";

    @Test
    @DisplayName("keeps the token available to the caller that needs it")
    void exposesToken() {
        assertEquals(TOKEN, RedHatCredential.of(TOKEN).token());
    }

    @Test
    @DisplayName("never renders the token in toString")
    void toStringHidesToken() {
        // toString lands in log lines and exception messages by accident; it must not be
        // the path by which a credential leaks.
        String rendered = RedHatCredential.of(TOKEN).toString();

        assertFalse(rendered.contains(TOKEN));
        assertFalse(rendered.contains("secret-offline-token"));
        assertTrue(rendered.contains(RedHatCredential.of(TOKEN).fingerprint()));
    }

    @Test
    @DisplayName("derives a fingerprint that does not contain the token")
    void fingerprintDoesNotRevealToken() {
        String fingerprint = RedHatCredential.of(TOKEN).fingerprint();

        assertFalse(fingerprint.contains(TOKEN));
        assertFalse(fingerprint.contains("secret-offline-token"));
        assertEquals(16, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]+"), "fingerprint should be hex: " + fingerprint);
    }

    @Test
    @DisplayName("gives the same fingerprint for the same token")
    void fingerprintIsStable() {
        assertEquals(RedHatCredential.of(TOKEN).fingerprint(),
                RedHatCredential.of(TOKEN).fingerprint());
    }

    @Test
    @DisplayName("gives different fingerprints to different tokens")
    void fingerprintSeparatesCredentials() {
        // This is what keeps one user's cached access token from being served to another.
        assertNotEquals(RedHatCredential.of("token-user-a").fingerprint(),
                RedHatCredential.of("token-user-b").fingerprint());
    }

    @Test
    @DisplayName("treats surrounding whitespace as the same credential")
    void trimsWhitespace() {
        // A header value copied by hand often carries stray spaces; it is the same token.
        assertEquals(RedHatCredential.of(TOKEN).fingerprint(),
                RedHatCredential.of("  " + TOKEN + "  ").fingerprint());
        assertEquals(TOKEN, RedHatCredential.of("  " + TOKEN + "  ").token());
    }

    @Test
    @DisplayName("compares by token value")
    void equalityIsByToken() {
        assertEquals(RedHatCredential.of(TOKEN), RedHatCredential.of(TOKEN));
        assertNotEquals(RedHatCredential.of(TOKEN), RedHatCredential.of("other-token"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("rejects a blank token instead of building an unusable credential")
    void rejectsBlankToken(String token) {
        assertThrows(IllegalArgumentException.class, () -> RedHatCredential.of(token));
    }

    @Test
    @DisplayName("states the problem without echoing the rejected value")
    void rejectionMessageIsSafe() {
        // A tab-only value is blank, so it is rejected; the message should describe the
        // rule rather than quote back whatever the caller sent.
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> RedHatCredential.of("\t\t"));

        assertFalse(e.getMessage().contains("\t"));
        assertTrue(e.getMessage().contains("must not be blank"));
    }
}
