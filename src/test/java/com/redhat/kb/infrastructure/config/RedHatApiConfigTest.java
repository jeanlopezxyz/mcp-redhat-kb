package com.redhat.kb.infrastructure.config;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the "is a usable token configured?" decision, which gates the startup warning
 * and the shared-credential fallback.
 */
class RedHatApiConfigTest {

    /** Minimal stand-in: only the token matters here. */
    private static RedHatApiConfig withToken(String token) {
        return new RedHatApiConfig() {
            @Override
            public Optional<String> offlineToken() {
                return Optional.ofNullable(token);
            }

            @Override
            public boolean requireUserToken() {
                return false;
            }

            @Override
            public Sso sso() {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public Timeouts timeouts() {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public Urls urls() {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };
    }

    @Test
    @DisplayName("accepts a real token")
    void acceptsRealToken() {
        assertTrue(withToken("sha256~AbCdEf0123456789").isConfigured());
    }

    @Test
    @DisplayName("treats an absent or blank token as unconfigured")
    void rejectsMissingToken() {
        assertFalse(withToken(null).isConfigured());
        assertFalse(withToken("").isConfigured());
        assertFalse(withToken("   ").isConfigured());
    }

    @Test
    @DisplayName("rejects every placeholder the documentation hands out")
    void rejectsDocumentationPlaceholders() {
        // Regression: the check knew only "your-offline-token-here" while the README told
        // people to paste "your-token-here". A copied example counted as configured and
        // failed later as an opaque 401 from SSO.
        for (String placeholder : RedHatApiConfig.TOKEN_PLACEHOLDERS) {
            assertFalse(withToken(placeholder).isConfigured(), placeholder);
            assertFalse(withToken("  " + placeholder + "  ").isConfigured(),
                    "a padded placeholder is still a placeholder: " + placeholder);
        }
    }
}
