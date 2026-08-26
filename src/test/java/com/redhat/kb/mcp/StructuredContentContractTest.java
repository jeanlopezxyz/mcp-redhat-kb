package com.redhat.kb.mcp;

import java.util.Map;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that a tool declaring an output schema returns structured content on success.
 *
 * <p>The specification treats the schema as a promise: a successful response without
 * structured content is a protocol violation, and clients reject the whole call with
 * "declares an output schema but returned no structured content" — the tool's message never
 * reaches the model. The empty-result branches are the easy ones to get wrong, because they
 * are the paths that return prose and nothing else.
 */
@QuarkusTest
@TestProfile(StructuredContentContractTest.OpenProfile.class)
class StructuredContentContractTest {

    public static class OpenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "redhat.api.offline-token", "test-token",
                    "quarkus.oidc.enabled", "false",
                    "quarkus.http.auth.permission.mcp.policy", "permit");
        }
    }

    private McpStreamableTestClient client;

    @BeforeEach
    void connect() {
        client = McpAssured.newConnectedStreamableClient();
    }

    @Test
    @DisplayName("returns structured content when no CVE record exists")
    void cveNotFoundCarriesStructuredContent() {
        // CVE-1999-0001 predates Red Hat's tracking, so the API answers 404.
        client.when()
                .toolsCall("lookupCve", Map.of("cveId", "CVE-1999-0001"), response -> {
                    assertTrue(response.content().get(0).asText().text().contains("No Red Hat record"));
                    assertNotNull(response.structuredContent(),
                            "an empty CVE result must still satisfy the declared output schema");
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("returns structured content when no product life cycle exists")
    void lifecycleNotFoundCarriesStructuredContent() {
        client.when()
                .toolsCall("getProductLifecycle", Map.of("product", "Nonexistent Product ZZZ"), response -> {
                    assertTrue(response.content().get(0).asText().text().contains("No life cycle published"));
                    assertNotNull(response.structuredContent(),
                            "an empty life cycle result must still satisfy the declared output schema");
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("rejects a malformed CVE identifier as an error, not a violation")
    void malformedCveIsAnError() {
        // isError responses are exempt: the schema governs successful results only.
        client.when()
                .toolsCall("lookupCve", Map.of("cveId", "not-a-cve"), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().get(0).asText().text().contains("not a CVE identifier"));
                })
                .thenAssertResults();
    }
}
