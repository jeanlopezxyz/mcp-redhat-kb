package com.redhat.kb.mcp;

import java.util.Map;

import com.redhat.kb.infrastructure.credential.CredentialResolver;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.vertx.core.MultiMap;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a deployment that requires every caller to bring their own Red Hat token.
 *
 * <p>Drives the real MCP protocol so the header actually travels the request path, rather
 * than trusting that the resolver would have been wired correctly.
 */
@QuarkusTest
@TestProfile(UserTokenRequiredTest.RequireUserTokenProfile.class)
class UserTokenRequiredTest {

    private static final String SHARED_TOKEN = "shared-server-token";
    private static final String CALLER_TOKEN = "caller-supplied-placeholder-token";

    public static class RequireUserTokenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    // A shared token exists but must never serve a request.
                    "redhat.api.offline-token", SHARED_TOKEN,
                    "redhat.api.require-user-token", "true",
                    "quarkus.oidc.enabled", "false",
                    "quarkus.http.auth.permission.mcp.policy", "permit",
                    // Pointed at an unroutable address so the suite stays offline. These
                    // tests assert where a call stops (at credential resolution or past
                    // it), never what Red Hat answers, so a connection that cannot be
                    // established serves them exactly as well as the real endpoint --
                    // while keeping placeholder tokens off sso.redhat.com and the suite
                    // green without a network.
                    "redhat.api.sso.token-url", UNROUTABLE_URL,
                    "redhat.api.urls.knowledge-base", UNROUTABLE_URL,
                    // Failing fast keeps the no-network path from waiting on the default
                    // 30s connect timeout for every call.
                    "redhat.api.timeouts.connect-seconds", "1",
                    "redhat.api.timeouts.request-seconds", "1");
        }
    }

    /**
     * TEST-NET-1 (RFC 5737) is reserved for documentation and is not routable, so a
     * request to it fails locally instead of reaching any real host.
     */
    private static final String UNROUTABLE_URL = "http://192.0.2.1:9";

    private static String search(McpStreamableTestClient client) {
        StringBuilder captured = new StringBuilder();
        client.when()
                .toolsCall("searchKnowledgeBase", Map.of("query", "crashloop"),
                        response -> captured.append(response.content().get(0).asText().text()))
                .thenAssertResults();
        return captured.toString();
    }

    @Test
    @DisplayName("refuses a call that carries no user token")
    void refusesCallWithoutUserToken() {
        // The shared token is configured, but require-user-token forbids spending it on
        // behalf of a caller who supplied nothing.
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        String message = search(client);

        assertTrue(message.contains(CredentialResolver.USER_TOKEN_HEADER),
                "the caller should be told which header to use, got: " + message);
    }

    @Test
    @DisplayName("never names the shared token in the refusal")
    void refusalDoesNotLeakSharedToken() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        assertFalse(search(client).contains(SHARED_TOKEN));
    }

    @Test
    @DisplayName("accepts the caller's token and fails upstream, not at the gate")
    void acceptsUserSuppliedToken() {
        // With a token present the request gets past credential resolution; the call then
        // fails at Red Hat because this placeholder is not a real token. Seeing an upstream
        // error rather than the "send your token" message proves the header was picked up.
        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setAdditionalHeaders(request -> MultiMap.caseInsensitiveMultiMap()
                        .add(CredentialResolver.USER_TOKEN_HEADER, CALLER_TOKEN))
                .build()
                .connect();

        String message = search(client);

        assertFalse(message.contains("This server requires your own Red Hat token"),
                "credential resolution should have accepted the header, got: " + message);
        // The caller's own token is not echoed back to them either.
        assertFalse(message.contains(CALLER_TOKEN));
    }

    @Test
    @DisplayName("validates arguments before asking for a credential")
    void validatesArgumentsFirst() {
        // A blank query is rejected on its own merits, so the caller gets the useful
        // message instead of being told to supply a token first.
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        StringBuilder captured = new StringBuilder();
        client.when()
                .toolsCall("searchKnowledgeBase", Map.of("query", "   "),
                        response -> captured.append(response.content().get(0).asText().text()))
                .thenAssertResults();

        assertTrue(captured.toString().contains("query is required"),
                "expected argument validation first, got: " + captured);
    }
}
