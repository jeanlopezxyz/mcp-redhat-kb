package com.redhat.kb.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.sun.net.httpserver.HttpServer;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the structured content of every covered call against the output schema the
 * server itself publishes over {@code tools/list}.
 *
 * <p>The 2026-07-28 specification makes this unconditional: <em>"If an output schema is
 * provided: Servers MUST provide structured results that conform to this schema."</em>
 * {@link StructuredContentContractTest} only pins that structured content is present;
 * presence is not conformance — a payload with a missing field or a wrong type still
 * breaks clients whose SDKs validate and then discard the whole response. This test closes
 * that gap by fetching the schema the way a client would (from the {@code tools/list}
 * answer, not from the source), and running a real JSON Schema validation over the wire
 * payload.
 *
 * <p>The not-found branches get their own cases because they are where the MUST breaks
 * most easily: the success path serializes a fully populated record, while "nothing found"
 * is hand-built (an empty {@code CveDetail}, a {@code LifecycleDetail} with no versions)
 * and can drift from the schema without any compiler noticing.
 */
@QuarkusTest
@TestProfile(OutputSchemaConformanceTest.StubApiProfile.class)
class OutputSchemaConformanceTest {

    /**
     * A found CVE, shaped like the Security Data API's real answer for the fields the
     * server maps. Served from a stub so the assertion depends on the mapping, not on the
     * live API being reachable or still returning this record.
     */
    private static final String FOUND_CVE_JSON = """
            {
              "name": "CVE-2024-6387",
              "threat_severity": "Important",
              "cvss3": {"cvss3_base_score": "8.1"},
              "details": ["A signal handler race condition was found in OpenSSH's server."],
              "affected_release": [
                {"product_name": "Red Hat Enterprise Linux 9",
                 "advisory": "RHSA-2024:4312",
                 "package": "openssh-8.7p1-38.el9_4.1"}
              ],
              "package_state": [
                {"product_name": "Red Hat Enterprise Linux 8",
                 "fix_state": "Not affected",
                 "package_name": "openssh"}
              ],
              "mitigation": {"value": "Set LoginGraceTime to 0 in sshd_config."}
            }
            """;

    /**
     * Started once for the profile because Quarkus reads the configuration before any test
     * instance exists, so the port has to be known by then. Same pattern as
     * {@link StructuredContentContractTest}: offline and deterministic on purpose.
     */
    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Longest-prefix matching: the specific CVE answers 200, every other id 404 —
            // which is how the real API distinguishes "tracked" from "not tracked".
            server.createContext("/cve/CVE-2024-6387.json",
                    exchange -> respond(exchange, 200, FOUND_CVE_JSON));
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

    public static class StubApiProfile implements QuarkusTestProfile {
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpStreamableTestClient client;

    @BeforeEach
    void connect() {
        client = McpAssured.newConnectedStreamableClient();
    }

    @Test
    @DisplayName("publishes an output schema for every tool")
    void publishesOutputSchemaForEveryTool() {
        // Without a published schema the conformance obligation below has nothing to bind
        // to, so its presence is asserted first and by name — a tool dropped from the
        // outputSchema declaration should fail here, not by a null further down.
        client.when()
                .toolsList(page -> {
                    for (String name : new String[] {
                            "searchKnowledgeBase", "getArticle", "lookupCve", "getProductLifecycle"}) {
                        assertThat(page.findByName(name).outputSchema())
                                .as("%s must publish the output schema it is annotated with", name)
                                .isNotNull();
                    }
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("returns a found CVE conforming to the published output schema")
    void cveFoundConformsToPublishedSchema() {
        assertConformingCall("lookupCve", Map.of("cveId", "CVE-2024-6387"));
    }

    @Test
    @DisplayName("returns an untracked CVE conforming to the published output schema")
    void cveNotFoundConformsToPublishedSchema() {
        // The stub answers 404: this exercises the hand-built CveDetail.notFound record,
        // the branch most likely to drift from the schema unnoticed.
        assertConformingCall("lookupCve", Map.of("cveId", "CVE-1999-0001"));
    }

    @Test
    @DisplayName("returns an unknown product life cycle conforming to the published output schema")
    void lifecycleNotFoundConformsToPublishedSchema() {
        // Empty data array upstream: the hand-built LifecycleDetail with no versions.
        assertConformingCall("getProductLifecycle", Map.of("product", "Nonexistent Product ZZZ"));
    }

    /**
     * Fetches the tool's schema from {@code tools/list} — the client's view, not the
     * server's source — then validates the structured content of a successful call
     * against it with a real JSON Schema engine.
     */
    private void assertConformingCall(String toolName, Map<String, Object> args) {
        AtomicReference<JsonObject> publishedSchema = new AtomicReference<>();
        client.when()
                .toolsList(page -> publishedSchema.set(page.findByName(toolName).outputSchema()))
                .thenAssertResults();
        assertThat(publishedSchema.get())
                .as("%s must publish an output schema for conformance to be checkable", toolName)
                .isNotNull();
        // An empty schema accepts any document, which would make the validation below
        // pass vacuously; requiring properties keeps this test honest.
        assertThat(publishedSchema.get().getJsonObject("properties"))
                .as("the published schema of %s must constrain something", toolName)
                .isNotNull();

        AtomicReference<Object> structured = new AtomicReference<>();
        client.when()
                .toolsCall(toolName, args, response -> {
                    assertThat(response.isError())
                            .as("only successful results are bound by the output schema")
                            .isFalse();
                    structured.set(response.structuredContent());
                })
                .thenAssertResults();
        assertThat(structured.get())
                .as("a tool with an output schema must return structured content")
                .isNotNull();

        Set<ValidationMessage> violations = validate(publishedSchema.get(), structured.get());
        assertThat(violations)
                .as("structuredContent of %s(%s) must conform to the schema the server publishes",
                        toolName, args)
                .isEmpty();
    }

    private static Set<ValidationMessage> validate(JsonObject schema, Object structuredContent) {
        try {
            // 2020-12 is the dialect the MCP specification prescribes for tool schemas;
            // an explicit $schema in the published document would still take precedence.
            JsonSchema compiled = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(MAPPER.readTree(schema.encode()));
            // Round-trip through JSON so the validated document is exactly what went over
            // the wire, regardless of what type McpAssured decoded it into.
            JsonNode payload = MAPPER.readTree(Json.encode(structuredContent));
            return compiled.validate(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse schema or structured content", e);
        }
    }
}
