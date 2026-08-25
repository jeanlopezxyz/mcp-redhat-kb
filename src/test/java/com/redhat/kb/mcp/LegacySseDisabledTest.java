package com.redhat.kb.mcp;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.not;

/**
 * Pins down that the deprecated HTTP+SSE transport stays closed.
 *
 * <p>HTTP+SSE was deprecated in specification revision 2025-03-26 in favour of Streamable
 * HTTP. The extension publishes {@code {rootPath}/sse} alongside {@code /mcp} and offers no
 * switch to disable it, so the route is closed by an HTTP policy instead. Leaving a
 * deprecated transport listening would be an unmaintained second way into the same tools.
 *
 * <p>This profile deliberately leaves authentication off, so the assertions show the SSE
 * route is closed on its own merits rather than by the authentication policy.
 */
@QuarkusTest
@TestProfile(LegacySseDisabledTest.OpenServerProfile.class)
class LegacySseDisabledTest {

    public static class OpenServerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "redhat.api.offline-token", "test-token",
                    "quarkus.oidc.enabled", "false",
                    // Streamable HTTP is wide open here; only SSE should be refused.
                    "quarkus.http.auth.permission.mcp.policy", "permit");
        }
    }

    @Test
    @DisplayName("refuses the SSE endpoint even when Streamable HTTP is open")
    void refusesSseEndpoint() {
        RestAssured.given()
                .header("Accept", "text/event-stream")
                .when().get("/mcp/sse")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("refuses paths beneath the SSE endpoint")
    void refusesSseSubPaths() {
        // The 2024-11-05 transport posted messages to a second endpoint advertised by the
        // SSE stream; nothing under that prefix should answer either.
        RestAssured.given()
                .when().post("/mcp/sse/message")
                .then().statusCode(403);
    }

    @Test
    @DisplayName("keeps Streamable HTTP reachable")
    void streamableHttpStillWorks() {
        // The point is to remove the deprecated transport, not the working one.
        RestAssured.given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Method", "server/discover")
                .header("Mcp-Name", "server/discover")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{
                          "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                          "io.modelcontextprotocol/clientInfo":{"name":"test","version":"1"},
                          "io.modelcontextprotocol/clientCapabilities":{}}}}
                        """)
                .when().post("/mcp")
                .then().statusCode(200)
                .body(not(org.hamcrest.Matchers.containsString("\"error\"")));
    }
}
