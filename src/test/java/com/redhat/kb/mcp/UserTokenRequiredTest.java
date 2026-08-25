package com.redhat.kb.mcp;

import java.util.Map;

import com.redhat.kb.infrastructure.client.CredentialResolver;

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
                    "quarkus.http.auth.permission.mcp.policy", "permit");
        }
    }

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
