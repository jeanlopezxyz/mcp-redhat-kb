package com.redhat.kb.infrastructure.client;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

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
 * Drives {@link KnowledgeBaseClient} against a local stub of the Hydra API.
 *
 * <p>These are the paths that decide what message the model sees when Red Hat misbehaves:
 * status mapping, malformed bodies and the response-size bound. The credential is a direct
 * JWT, so no SSO exchange happens and the tests stay offline.
 */
class KnowledgeBaseClientHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mirrors the client's production bound; the boundary tests pin its exact edge. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final String EMPTY_RESULT = "{\"response\":{\"numFound\":0,\"docs\":[]}}";

    private StubApiServer server;
    private KnowledgeBaseClient client;
    private RedHatCredential credential;
    private String accessToken;

    @BeforeEach
    void startStub() {
        server = StubApiServer.start();
        RedHatApiConfig config = configPointingAt(server.url());
        accessToken = jwt("tester", 600);
        credential = RedHatCredential.of(accessToken);
        client = new KnowledgeBaseClient(config,
                new RedHatAuthClient(config, MAPPER),
                new BoundedJsonHttp(config, MAPPER));
    }

    @AfterEach
    void stopStub() {
        server.close();
    }

    @Test
    @DisplayName("parses documents and the total match count on a successful search")
    void searchParsesDocumentsAndTotal() {
        server.respond(200, """
                {"response":{"numFound":42,"docs":[
                  {"id":"5049001","title":"Pod stuck in CrashLoopBackOff"}
                ]}}""");

        SearchPage page = client.search(credential, "crashloop", 5, null, null);

        assertEquals(1, page.articles().size());
        assertEquals("5049001", page.articles().get(0).getId());
        assertEquals(42, page.totalFound());
    }

    @Test
    @DisplayName("sends the resolved access token as a bearer header")
    void searchSendsBearerToken() {
        server.respond(200, EMPTY_RESULT);

        client.search(credential, "etcd", 5, null, null);

        assertEquals("Bearer " + accessToken, server.lastRequestHeader("Authorization").orElseThrow());
    }

    @Test
    @DisplayName("returns the first document for an article lookup")
    void getArticleReturnsFirstDocument() {
        server.respond(200, """
                {"response":{"numFound":1,"docs":[{"id":"12345","title":"Some solution"}]}}""");

        assertEquals("12345", client.getArticle(credential, "12345").orElseThrow().getId());
    }

    @Test
    @DisplayName("rejects a non-numeric article ID before any request is made")
    void getArticleRejectsInvalidIdWithoutCallingTheApi() {
        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.getArticle(credential, "abc; drop"));

        assertEquals("Article ID must be numeric", e.getMessage());
        assertTrue(server.lastRequestUri().isEmpty(), "the invalid ID still reached the API");
    }

    @Test
    @DisplayName("maps each upstream status to its actionable message")
    void mapsStatusCodesToActionableMessages() {
        // One test per row would restart the stub five times for the same assertion shape;
        // the map keeps status and expected message side by side instead.
        Map<Integer, String> expected = Map.of(
                401, "Could not search the Knowledge Base: authentication failed - "
                        + "REDHAT_TOKEN may be expired or invalid (HTTP 401)",
                403, "Could not search the Knowledge Base: access denied - the article may "
                        + "require a subscription your account lacks (HTTP 403)",
                404, "Could not search the Knowledge Base: the requested resource does not "
                        + "exist (HTTP 404)",
                429, "Could not search the Knowledge Base: rate limit exceeded - retry in a "
                        + "few moments (HTTP 429)",
                503, "Could not search the Knowledge Base: the Red Hat Knowledge Base API is "
                        + "unavailable (HTTP 503)");

        expected.forEach((status, message) -> {
            server.respond(status, "");

            KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                    () -> client.search(credential, "query-" + status, 5, null, null));

            assertEquals(message, e.getMessage());
        });
    }

    @Test
    @DisplayName("reports a malformed body as such, not as a connectivity problem")
    void reportsMalformedBody() {
        server.respond(200, "<html>this is not JSON</html>");

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.search(credential, "etcd", 5, null, null));

        assertEquals("Received a malformed response from the Knowledge Base API", e.getMessage());
    }

    @Test
    @DisplayName("refuses a streamed body over the limit with the size message, not the connectivity one")
    void refusesOversizedStreamedBody() {
        // Chunked: no Content-Length, so the bound can only hold while reading. Before the
        // fix this message was swallowed into "Could not reach the Red Hat Knowledge Base
        // API" (the mapper's exception was wrapped in an IOException by HttpClient.send).
        server.respondChunked(200, new byte[MAX_BYTES + 1]);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.search(credential, "etcd", 5, null, null));

        assertEquals("Knowledge Base response exceeded the size limit", e.getMessage());
    }

    @Test
    @DisplayName("refuses a declared oversized Content-Length before reading the body")
    void refusesOversizedDeclaredLength() {
        server.respondDeclaringLength(200, MAX_BYTES + 1L);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> client.search(credential, "etcd", 5, null, null));

        assertEquals("Knowledge Base response exceeded the size limit", e.getMessage());
    }

    @Test
    @DisplayName("accepts a body exactly at the size limit")
    void acceptsBodyExactlyAtTheLimit() {
        // Padding with trailing whitespace keeps the JSON valid while pinning the exact
        // boundary: at the limit passes, one byte more (previous test) is refused.
        server.respondChunked(200,
                (EMPTY_RESULT + " ".repeat(MAX_BYTES - EMPTY_RESULT.length())).getBytes());

        SearchPage page = client.search(credential, "boundary", 5, null, null);

        assertEquals(0, page.totalFound());
    }

    private static RedHatApiConfig configPointingAt(String stubUrl) {
        RedHatApiConfig config = mock(RedHatApiConfig.class);
        RedHatApiConfig.Timeouts timeouts = mock(RedHatApiConfig.Timeouts.class);
        when(timeouts.connectSeconds()).thenReturn(5);
        when(timeouts.requestSeconds()).thenReturn(10);
        when(config.timeouts()).thenReturn(timeouts);
        RedHatApiConfig.Urls urls = mock(RedHatApiConfig.Urls.class);
        when(urls.knowledgeBase()).thenReturn(stubUrl);
        when(config.urls()).thenReturn(urls);
        return config;
    }

    /** Builds an unsigned JWT so the auth client returns it directly, with no SSO call. */
    private static String jwt(String subject, long expiresInSeconds) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = encoder.encodeToString(
                ("{\"sub\":\"" + subject + "\",\"exp\":"
                        + (Instant.now().getEpochSecond() + expiresInSeconds) + "}").getBytes());
        return header + "." + payload + ".signature";
    }
}
