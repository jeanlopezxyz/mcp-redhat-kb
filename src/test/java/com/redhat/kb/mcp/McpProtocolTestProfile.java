package com.redhat.kb.mcp;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Supplies a placeholder token so the server considers itself configured.
 *
 * <p>The value is deliberately not a usable credential: these tests exercise the tool
 * catalogue and the input validation that runs before any outbound call.
 */
public class McpProtocolTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "redhat.api.offline-token", "test-placeholder-token",
                "quarkus.mcp.server.traffic-logging.enabled", "false",
                // These tests cover the tool contract, not authentication. The
                // Keycloak-protected configuration is exercised by McpAuthenticationTest.
                "quarkus.oidc.enabled", "false",
                "quarkus.http.auth.permission.mcp.policy", "permit");
    }
}
