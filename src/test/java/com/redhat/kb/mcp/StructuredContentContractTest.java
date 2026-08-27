package com.redhat.kb.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

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

    /**
     * Serves the two "nothing found" answers this contract is about, so the assertions
     * depend on the empty-result branches rather than on Red Hat still having no record
     * for a particular CVE. Previously these tests called the live API: they broke
     * without a network and sent test traffic to production.
     *
     * <p>Started once for the profile because Quarkus reads the configuration before any
     * test instance exists, so the port has to be known by then.
     */
    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // The Security Data API answers 404 for a CVE it does not track.
            server.createContext("/cve/", exchange -> respond(exchange, 404, ""));
            // The Life Cycle API answers 200 with an empty data array for an unknown product.
            server.createContext("/products", exchange -> respond(exchange, 200, "{\"data\":[]}"));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the stub API server", e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange; OutputStream out = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            out.write(bytes);
        }
    }

    private static String stubUrl() {
        return "http://127.0.0.1:" + STUB.getAddress().getPort();
    }

    public static class OpenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "redhat.api.offline-token", "test-token",
                    "quarkus.oidc.enabled", "false",
                    "quarkus.http.auth.permission.mcp.policy", "permit",
                    "redhat.api.urls.security-data", stubUrl(),
                    "redhat.api.urls.lifecycle", stubUrl() + "/products");
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
        // The stub answers 404, as the real API does for a CVE Red Hat does not track.
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
