package com.redhat.kb.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the ways an upstream API misbehaves without ever returning a clean status code.
 *
 * <p>{@code StubApiServer} answers requests; these are the cases where the far end does
 * not, and they are the ones that hang a tool call rather than failing it. A server that
 * accepts a connection and then stalls, or sends a Content-Length it never satisfies, is
 * indistinguishable from a slow one until the timeout fires — so the assertion is that a
 * timeout exists at all and that what surfaces is the typed exception carrying this API's
 * name, never a raw {@code IOException} or a partial body.
 *
 * <p>Timeouts are set to a second here. The production defaults (30s connect, 60s request)
 * are right for a real API and wrong for a test suite.
 */
class BoundedJsonHttpFaultTest {

    private static final int MAX_RESPONSE_BYTES = 1024;

    private static final BoundedJsonHttp.ApiProfile API = new BoundedJsonHttp.ApiProfile(
            MAX_RESPONSE_BYTES,
            "Request to the Test API was interrupted",
            "Could not reach the Test API",
            "Test API response exceeded the size limit",
            "Received a malformed response from the Test API");

    private WireMockServer wireMock;
    private BoundedJsonHttp http;

    @BeforeEach
    void startUpstream() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        http = new BoundedJsonHttp(config(), new ObjectMapper());
    }

    @AfterEach
    void stopUpstream() {
        wireMock.stop();
    }

    @Test
    @DisplayName("gives up on an upstream that accepts the request and then stalls")
    void timesOutOnAStalledResponse() {
        wireMock.stubFor(get(urlPathEqualTo("/stall"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(5_000)));

        assertThatThrownBy(() -> http.get(url("/stall"), API))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("Could not reach the Test API");
    }

    @Test
    @DisplayName("reports a body that stops mid-transfer as unreachable, not as data")
    void refusesATruncatedBody() {
        // Promises more than it sends, then closes: the JSON parses to nothing useful and
        // must not be handed on as a short article.
        wireMock.stubFor(get(urlPathEqualTo("/cut"))
                .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        assertThatThrownBy(() -> http.get(url("/cut"), API))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("Could not reach the Test API");
    }

    @Test
    @DisplayName("refuses a body larger than the cap while it streams")
    void refusesAnOversizedBody() {
        wireMock.stubFor(get(urlPathEqualTo("/huge"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("x".repeat(MAX_RESPONSE_BYTES * 4))));

        assertThatThrownBy(() -> http.get(url("/huge"), API))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("exceeded the size limit");
    }

    @Test
    @DisplayName("never reads the body of a failed response")
    void doesNotReadNonSuccessBodies() {
        // A 500 whose body carries something that must not reach the model.
        wireMock.stubFor(get(urlPathEqualTo("/leaky"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("token=sha256~leaked-secret")));

        BoundedJsonHttp.ApiResponse response = http.get(url("/leaky"), API);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.isOk()).isFalse();
        assertThat(response.body())
                .describedAs("an error body can echo a credential; it is not downloaded")
                .doesNotContain("leaked-secret");
    }

    @Test
    @DisplayName("rejects a 200 that is not the JSON the client expected")
    void rejectsMalformedJson() {
        wireMock.stubFor(get(urlPathEqualTo("/html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("<html><body>Gateway error</body></html>")));

        BoundedJsonHttp.ApiResponse response = http.get(url("/html"), API);

        assertThatThrownBy(() -> http.readTree(response, API))
                .isInstanceOf(KnowledgeBaseException.class)
                .hasMessageContaining("malformed response");
    }

    @Test
    @DisplayName("passes the headers a caller supplies")
    void sendsSuppliedHeaders() {
        wireMock.stubFor(get(urlPathEqualTo("/ok"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        http.get(url("/ok"), API, "Accept", "application/json");

        wireMock.verify(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/ok"))
                .withHeader("Accept", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("application/json")));
    }

    private String url(String path) {
        return wireMock.baseUrl() + path;
    }

    /** Production timeouts are minutes long; a stalled test should fail in seconds. */
    private static RedHatApiConfig config() {
        return new RedHatApiConfig() {
            @Override
            public Optional<String> offlineToken() {
                return Optional.empty();
            }

            @Override
            public boolean requireUserToken() {
                return false;
            }

            @Override
            public Sso sso() {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public Timeouts timeouts() {
                return new Timeouts() {
                    @Override
                    public int connectSeconds() {
                        return 1;
                    }

                    @Override
                    public int requestSeconds() {
                        return 1;
                    }
                };
            }

            @Override
            public Urls urls() {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };
    }
}
