package com.redhat.kb.infrastructure.credential;

import java.util.Optional;

import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers which credential serves a request.
 *
 * <p>These rules decide whose Red Hat subscription is spent and whose entitlements apply,
 * so each branch is pinned down explicitly.
 */
class CredentialResolverTest {

    private static final String USER_TOKEN = "user-supplied-offline-token";
    private static final String SHARED_TOKEN = "server-shared-offline-token";

    /** Builds a config stub; a null shared token means none is configured. */
    private static RedHatApiConfig config(String sharedToken, boolean requireUserToken) {
        RedHatApiConfig config = mock(RedHatApiConfig.class);
        when(config.offlineToken()).thenReturn(Optional.ofNullable(sharedToken));
        when(config.requireUserToken()).thenReturn(requireUserToken);
        when(config.isConfigured()).thenReturn(sharedToken != null && !sharedToken.isBlank());
        return config;
    }

    /** Builds a request scope carrying the given header value; null means no header. */
    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> requestWithHeader(String headerValue) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader(anyString())).thenReturn(headerValue);

        Instance<HttpServerRequest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(request);
        return instance;
    }

    /** Builds a scope with no active HTTP request, as on the stdio transport. */
    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> noRequest() {
        Instance<HttpServerRequest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    @Test
    @DisplayName("uses the caller's token when the header carries one")
    void prefersUserToken() {
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, false), requestWithHeader(USER_TOKEN), true);

        assertEquals(USER_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("prefers the caller's token even when a shared one exists")
    void userTokenWinsOverSharedToken() {
        // The whole point of per-user credentials: the shared subscription must not be
        // spent on behalf of someone who supplied their own.
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, false), requestWithHeader(USER_TOKEN), true);

        assertEquals(USER_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("falls back to the shared token when the caller sends none")
    void fallsBackToSharedToken() {
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, false), requestWithHeader(null), true);

        assertEquals(SHARED_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("treats a blank header as no token at all")
    void blankHeaderFallsBack() {
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, false), requestWithHeader("   "), true);

        assertEquals(SHARED_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("refuses the shared token when user tokens are required")
    void refusesSharedTokenWhenUserTokenRequired() {
        // With require-user-token on, nobody reads the Knowledge Base through another
        // account's entitlements, even though a shared token is configured.
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, true), requestWithHeader(null), true);

        MissingCredentialException e =
                assertThrows(MissingCredentialException.class, resolver::resolve);
        assertTrue(e.getMessage().contains(CredentialResolver.USER_TOKEN_HEADER));
    }

    @Test
    @DisplayName("still serves a caller who supplies their own token when it is required")
    void servesUserTokenWhenRequired() {
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, true), requestWithHeader(USER_TOKEN), true);

        assertEquals(USER_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("serves a caller's token even with no shared token configured")
    void servesUserTokenWithoutSharedToken() {
        CredentialResolver resolver =
                new CredentialResolver(config(null, false), requestWithHeader(USER_TOKEN), true);

        assertEquals(USER_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("explains how to supply a token when none is available")
    void explainsMissingCredential() {
        CredentialResolver resolver =
                new CredentialResolver(config(null, false), requestWithHeader(null), true);

        MissingCredentialException e =
                assertThrows(MissingCredentialException.class, resolver::resolve);
        assertTrue(e.getMessage().contains(CredentialResolver.USER_TOKEN_HEADER));
    }

    @Test
    @DisplayName("uses the shared token when there is no HTTP request, as on stdio")
    void usesSharedTokenOnStdio() {
        CredentialResolver resolver = new CredentialResolver(config(SHARED_TOKEN, false), noRequest(), false);

        assertEquals(SHARED_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("still serves stdio when user tokens are required")
    void stdioIgnoresUserTokenRequirement() {
        // Requiring a per-user token is a multi-user rule. Over stdio the client launched
        // this process and passed REDHAT_TOKEN through its own environment, so that value
        // is already the caller's personal credential: there is nobody to isolate them
        // from, and no header to carry a different token. Enforcing it here would break a
        // setup that was never at risk.
        CredentialResolver resolver = new CredentialResolver(config(SHARED_TOKEN, true), noRequest(), false);

        assertEquals(SHARED_TOKEN, resolver.resolve().token());
    }

    @Test
    @DisplayName("still fails on stdio when no token is configured at all")
    void stdioFailsWithoutAnyToken() {
        CredentialResolver resolver = new CredentialResolver(config(null, true), noRequest(), false);

        MissingCredentialException e =
                assertThrows(MissingCredentialException.class, resolver::resolve);
        // stdio cannot send headers, so the guidance must point at the environment.
        assertTrue(e.getMessage().contains("REDHAT_TOKEN"));
        assertFalse(e.getMessage().contains(CredentialResolver.USER_TOKEN_HEADER),
                "stdio callers cannot set a header; the message should not ask them to");
    }

    @Test
    @DisplayName("keeps enforcing the requirement over HTTP")
    void httpStillEnforcesRequirement() {
        // The stdio exemption must not weaken the transport the rule exists for.
        CredentialResolver resolver =
                new CredentialResolver(config(SHARED_TOKEN, true), requestWithHeader(null), true);

        assertThrows(MissingCredentialException.class, resolver::resolve);
    }

    @Test
    @DisplayName("gives different callers different cache identities")
    void differentCallersGetDifferentFingerprints() {
        // Cache partitioning depends on this: same fingerprint would mean one caller's
        // cached results could be served to another.
        String first = new CredentialResolver(config(null, false), requestWithHeader("token-a"), true)
                .resolve().fingerprint();
        String second = new CredentialResolver(config(null, false), requestWithHeader("token-b"), true)
                .resolve().fingerprint();

        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
    }
}
