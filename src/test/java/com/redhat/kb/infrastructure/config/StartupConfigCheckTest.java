package com.redhat.kb.infrastructure.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the startup warnings, which are the whole of MCP07's "escalates when bound wider
 * without auth".
 *
 * <p>The severity is the message here, not a detail of it: an operator who publishes the
 * port is told at ERROR, while the same settings on loopback are routine in development and
 * stay a WARN. A check that fired at one level for both would train people to ignore it.
 */
class StartupConfigCheckTest {

    private static final String LOGGER_NAME = StartupConfigCheck.class.getName();

    private final List<LogRecord> records = new ArrayList<>();
    private java.util.logging.Logger logger;
    private Handler handler;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureLog() {
        logger = java.util.logging.Logger.getLogger(LOGGER_NAME);
        originalUseParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseLog() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(originalUseParentHandlers);
    }

    @Test
    @DisplayName("escalates to an error when an unauthenticated server is bound beyond loopback")
    void escalatesWhenBoundBeyondLoopback() {
        check(config("sha256~token", false), false, true, true, "0.0.0.0").onStart(null);

        LogRecord record = single();
        assertEquals(Level.SEVERE, record.getLevel(), "publishing the port deserves an error");
        assertTrue(message(record).contains("0.0.0.0"), message(record));
    }

    @Test
    @DisplayName("keeps the same misconfiguration a warning on loopback")
    void warnsOnLoopback() {
        for (String loopback : new String[] {"127.0.0.1", "localhost", "LOCALHOST", "::1"}) {
            records.clear();
            check(config("sha256~token", false), false, true, true, loopback).onStart(null);

            assertEquals(Level.WARNING, single().getLevel(), "unexpected level for " + loopback);
        }
    }

    @Test
    @DisplayName("stays silent when authentication is enabled")
    void silentWhenOidcEnabled() {
        check(config("sha256~token", false), true, true, true, "0.0.0.0").onStart(null);

        assertTrue(records.isEmpty(), () -> "expected no warning, got: " + messages());
    }

    @Test
    @DisplayName("stays silent when every caller must bring their own token")
    void silentWhenUserTokenRequired() {
        check(config("sha256~token", true), false, true, true, "0.0.0.0").onStart(null);

        assertTrue(records.isEmpty(), () -> "expected no warning, got: " + messages());
    }

    @Test
    @DisplayName("warns that require-user-token does not apply to stdio")
    void warnsThatRequireUserTokenIsHttpOnly() {
        check(config("sha256~token", true), false, false, true, "127.0.0.1").onStart(null);

        LogRecord record = single();
        assertEquals(Level.WARNING, record.getLevel());
        assertTrue(message(record).contains("stdio"), message(record));
    }

    @Test
    @DisplayName("warns when no credential is available at all")
    void warnsWhenNoCredentialAvailable() {
        check(config(null, false), false, false, false, "127.0.0.1").onStart(null);

        LogRecord record = single();
        assertEquals(Level.WARNING, record.getLevel());
        assertTrue(message(record).contains("REDHAT_TOKEN"), message(record));
    }

    @Test
    @DisplayName("says nothing when a caller-supplied token is the only credential expected")
    void silentWhenUserTokensAreTheDesign() {
        check(config(null, true), true, true, false, "0.0.0.0").onStart(null);

        assertFalse(records.stream().anyMatch(r -> message(r).contains("No REDHAT_TOKEN")),
                () -> "requiring user tokens is a complete configuration: " + messages());
    }

    private LogRecord single() {
        assertEquals(1, records.size(), () -> "expected exactly one entry, got: " + messages());
        return records.getFirst();
    }

    private String messages() {
        return records.stream().map(StartupConfigCheckTest::message).toList().toString();
    }

    private static String message(LogRecord record) {
        Object[] parameters = record.getParameters();
        return parameters == null || parameters.length == 0
                ? String.valueOf(record.getMessage())
                : String.format(record.getMessage(), parameters);
    }

    private static StartupConfigCheck check(RedHatApiConfig config, boolean oidcEnabled,
            boolean httpEnabled, boolean stdioEnabled, String host) {
        return new StartupConfigCheck(config, stdioEnabled, oidcEnabled, httpEnabled, host, 9081);
    }

    /** Minimal stand-in: only the token and the per-user requirement matter here. */
    private static RedHatApiConfig config(String token, boolean requireUserToken) {
        return new RedHatApiConfig() {
            @Override
            public Optional<String> offlineToken() {
                return Optional.ofNullable(token);
            }

            @Override
            public boolean requireUserToken() {
                return requireUserToken;
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
}
