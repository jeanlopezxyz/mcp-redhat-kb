package com.redhat.kb.mcp;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Verifies that the HTTP transport is closed when authentication is enabled.
 *
 * <p>This is the control that stops an unauthenticated caller from spending the operator's
 * Red Hat subscription, so it is asserted rather than assumed. The OIDC layer itself is not
 * contacted: a request without a token must be refused before any token validation happens.
 */
@QuarkusTest
@TestProfile(McpAuthenticationTest.AuthEnabledProfile.class)
class McpAuthenticationTest {

    /** Applies the deployment posture: the MCP endpoints demand an authenticated caller. */
    public static class AuthEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "redhat.api.offline-token", "test-placeholder-token",
                    // OIDC discovery would need a live Keycloak; the permission layer is
                    // what this test exercises.
                    "quarkus.oidc.enabled", "false",
                    "quarkus.http.auth.permission.mcp.paths", "/mcp,/mcp/*",
                    "quarkus.http.auth.permission.mcp.policy", "authenticated");
        }
    }

    // With no authentication mechanism installed there is nothing to challenge with, so
    // Quarkus denies outright with 403. A deployment with OIDC enabled answers 401 plus a
    // WWW-Authenticate challenge. Either way the request never reaches a tool, which is
    // what these tests pin down.
    private static final int DENIED = 403;

    @Test
    @DisplayName("refuses an unauthenticated call to the MCP endpoint")
    void refusesAnonymousMcpCall() {
        RestAssured.given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)
                .when().post("/mcp")
                .then().statusCode(DENIED)
                // Nothing about the catalogue leaks to an anonymous caller.
                .body(not(containsString("searchKnowledgeBase")));
    }

    @Test
    @DisplayName("blocks the deprecated SSE transport outright")
    void blocksLegacySseTransport() {
        // HTTP+SSE was deprecated in specification revision 2025-03-26. The extension
        // publishes the route regardless, so the deny policy is what actually closes it —
        // and this holds whether or not authentication is enabled.
        RestAssured.given()
                .when().get("/mcp/sse")
                .then().statusCode(DENIED);
    }

    @Test
    @DisplayName("rejects a bearer token this server cannot verify")
    void rejectsUnverifiableToken() {
        RestAssured.given()
                .contentType("application/json")
                .header("Authorization", "Bearer not-a-valid-token")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)
                .when().post("/mcp")
                .then().statusCode(DENIED);
    }

    @Test
    @DisplayName("keeps the resource metadata path reachable without a token")
    void metadataStaysPublic() {
        // Clients read this document precisely because they do not have a token yet,
        // so it must never sit behind the authenticated policy.
        RestAssured.given()
                .when().get("/.well-known/oauth-protected-resource")
                .then().statusCode(not(DENIED));
    }
}
