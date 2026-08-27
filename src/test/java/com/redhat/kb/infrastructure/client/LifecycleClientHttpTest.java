package com.redhat.kb.infrastructure.client;

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
 * Drives {@link LifecycleClient} against a local stub of the Product Life Cycle API.
 */
class LifecycleClientHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mirrors the client's production bound; the boundary tests pin its exact edge. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private StubApiServer server;
    private LifecycleClient client;

    @BeforeEach
    void startStub() {
        server = StubApiServer.start();
        RedHatApiConfig config = configPointingAt(server.url());
        client = new LifecycleClient(config, new BoundedJsonHttp(config, MAPPER));
    }

    @AfterEach
    void stopStub() {
        server.close();
    }

    @Test
    @DisplayName("returns the first matching product record")
    void returnsFirstProduct() {
        server.respond(200, """
                {"data":[{"name":"OpenShift Container Platform 4","versions":[]}]}""");

        JsonNode product = client.lookupProduct("OpenShift Container Platform 4").orElseThrow();

        assertEquals("OpenShift Container Platform 4", product.get("name").asText());
    }

    @Test
    @DisplayName("URL-encodes the product name into the query")
    void encodesProductName() {
        server.respond(200, "{\"data\":[]}");

        client.lookupProduct("OpenShift Container Platform 4");

        assertEquals("name=OpenShift+Container+Platform+4",
                server.lastRequestUri().orElseThrow().getRawQuery());
    }

    @Test
    @DisplayName("returns empty when no product matches")
    void returnsEmptyForNoMatch() {
        server.respond(200, "{\"data\":[]}");

        assertEquals(Optional.empty(), client.lookupProduct("No Such Product"));
    }

    @Test
    @DisplayName("returns empty when the response has no data array")
    void returnsEmptyWhenDataIsMissing() {
        server.respond(200, "{}");

        assertEquals(Optional.empty(), client.lookupProduct("No Such Product"));
    }

    @Test
    @DisplayName("names the status when the lookup fails")
    void reportsFailureStatuses() {
        for (int status : new int[] {404, 429, 500, 503}) {
            server.respond(status, "");

            KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                    () -> client.lookupProduct("product-" + status));

            assertEquals("Could not look up the life cycle: the API returned HTTP " + status,
                    e.getMessage());
        }
    }

    @Test
    @DisplayName("reports a malformed body as such, not as a connectivity problem")
    void reportsMalformedBody() {
        server.respond(200, "not json at all");

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.lookupProduct("OpenShift"));

        assertEquals("Received a malformed response from the Product Life Cycle API", e.getMessage());
    }

    @Test
    @DisplayName("refuses a body over the size limit with the size message")
    void refusesOversizedBody() {
        server.respondChunked(200, new byte[MAX_BYTES + 1]);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.lookupProduct("OpenShift"));

        assertEquals("Life cycle response exceeded the size limit", e.getMessage());
    }

    @Test
    @DisplayName("rejects a blank product name before any request is made")
    void rejectsBlankProductName() {
        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.lookupProduct("   "));

        assertEquals("A product name is required", e.getMessage());
        assertTrue(server.lastRequestUri().isEmpty(), "a blank name still reached the API");
    }

    private static RedHatApiConfig configPointingAt(String stubUrl) {
        RedHatApiConfig config = mock(RedHatApiConfig.class);
        RedHatApiConfig.Timeouts timeouts = mock(RedHatApiConfig.Timeouts.class);
        when(timeouts.connectSeconds()).thenReturn(5);
        when(timeouts.requestSeconds()).thenReturn(10);
        when(config.timeouts()).thenReturn(timeouts);
        RedHatApiConfig.Urls urls = mock(RedHatApiConfig.Urls.class);
        when(urls.lifecycle()).thenReturn(stubUrl);
        when(config.urls()).thenReturn(urls);
        return config;
    }
}
