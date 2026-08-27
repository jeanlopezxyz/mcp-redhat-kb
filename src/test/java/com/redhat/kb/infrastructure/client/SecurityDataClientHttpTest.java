package com.redhat.kb.infrastructure.client;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SecurityDataClient} against a local stub of the Security Data API.
 *
 * <p>The 404 branch deserves particular attention: for this API "not found" means "Red Hat
 * does not track this CVE", which is an answer the model should relay, not an error.
 */
class SecurityDataClientHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mirrors the client's production bound; the boundary tests pin its exact edge. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private StubApiServer server;
    private SecurityDataClient client;

    @BeforeEach
    void startStub() {
        server = StubApiServer.start();
        RedHatApiConfig config = configPointingAt(server.url());
        client = new SecurityDataClient(config, new BoundedJsonHttp(config, MAPPER));
    }

    @AfterEach
    void stopStub() {
        server.close();
    }

    @Test
    @DisplayName("returns the raw CVE record on success")
    void returnsCveRecord() {
        server.respond(200, """
                {"name":"CVE-2024-6387","threat_severity":"Important"}""");

        JsonNode record = client.lookupCve("CVE-2024-6387").orElseThrow();

        assertEquals("Important", record.get("threat_severity").asText());
    }

    @Test
    @DisplayName("normalizes a bare identifier and requests the canonical resource")
    void normalizesBareIdentifier() {
        server.respond(200, "{\"name\":\"CVE-2024-6387\"}");

        client.lookupCve("2024-6387");

        assertEquals("/cve/CVE-2024-6387.json", server.lastRequestUri().orElseThrow().getPath());
    }

    @Test
    @DisplayName("treats 404 as 'not tracked by Red Hat', not as a failure")
    void treats404AsEmpty() {
        server.respond(404, "");

        assertEquals(Optional.empty(), client.lookupCve("CVE-2024-9999"));
    }

    @Test
    @DisplayName("names the API and the status when the lookup fails")
    void reportsFailureStatuses() {
        for (int status : new int[] {401, 403, 429, 500, 503}) {
            server.respond(status, "");

            KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                    () -> client.lookupCve("CVE-2024-" + (1000 + status)));

            assertEquals("Could not look up CVE-2024-" + (1000 + status)
                    + ": the Security Data API returned HTTP " + status, e.getMessage());
        }
    }

    @Test
    @DisplayName("reports a malformed body as such, not as a connectivity problem")
    void reportsMalformedBody() {
        server.respond(200, "not json at all");

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.lookupCve("CVE-2024-6387"));

        assertEquals("Received a malformed response from the Security Data API", e.getMessage());
    }

    @Test
    @DisplayName("refuses a body over the size limit with the size message")
    void refusesOversizedBody() {
        server.respondChunked(200, new byte[MAX_BYTES + 1]);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.lookupCve("CVE-2024-6387"));

        assertEquals("Security Data response exceeded the size limit", e.getMessage());
    }

    @Test
    @DisplayName("rejects malformed identifiers before any request is made")
    void rejectsMalformedIdentifiers() {
        Map<String, String> expected = Map.of(
                "  ", "A CVE identifier is required, for example CVE-2024-6387",
                "not-a-cve", "\"not-a-cve\" is not a CVE identifier; expected the form CVE-2024-6387");

        expected.forEach((input, message) -> {
            KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                    () -> client.lookupCve(input));

            assertEquals(message, e.getMessage());
        });

        assertTrue(server.lastRequestUri().isEmpty(), "an invalid identifier still reached the API");
    }

    private static RedHatApiConfig configPointingAt(String stubUrl) {
        RedHatApiConfig config = mock(RedHatApiConfig.class);
        RedHatApiConfig.Timeouts timeouts = mock(RedHatApiConfig.Timeouts.class);
        when(timeouts.connectSeconds()).thenReturn(5);
        when(timeouts.requestSeconds()).thenReturn(10);
        when(config.timeouts()).thenReturn(timeouts);
        RedHatApiConfig.Urls urls = mock(RedHatApiConfig.Urls.class);
        when(urls.securityData()).thenReturn(stubUrl);
        when(config.urls()).thenReturn(urls);
        return config;
    }
}
