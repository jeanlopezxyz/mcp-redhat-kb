package com.redhat.kb.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Client for the Red Hat Product Life Cycle API.
 *
 * <p>Public, like the Security Data API: no credential, no subscription spent. It answers
 * support-window questions ("when does OpenShift 4.14 reach end of life?") that a Knowledge
 * Base search cannot, because the dates are structured data rather than article text.
 */
@ApplicationScoped
public class LifecycleClient {

    private static final String BASE_URL = "https://access.redhat.com/product-life-cycles/api/v1/products";

    /** Guards against an oversized response exhausting the heap. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RedHatApiConfig config;

    @Inject
    public LifecycleClient(RedHatApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    /**
     * Looks up the life cycle of a product by name.
     *
     * @return the product record, or empty when no product matches that name
     */
    @CacheResult(cacheName = "lifecycle-lookup")
    public Optional<JsonNode> lookupProduct(String productName) {
        if (productName == null || productName.isBlank()) {
            throw new KnowledgeBaseException("A product name is required");
        }

        String url = BASE_URL + "?name=" + URLEncoder.encode(productName.strip(), StandardCharsets.UTF_8);

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            response = httpClient.send(request, boundedBodyHandler());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException("Request to the Product Life Cycle API was interrupted");
        } catch (IOException e) {
            throw new KnowledgeBaseException("Could not reach the Red Hat Product Life Cycle API");
        }

        if (response.statusCode() != Response.Status.OK.getStatusCode()) {
            throw new KnowledgeBaseException(
                    "Could not look up the life cycle: the API returned HTTP " + response.statusCode());
        }

        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            return data.isArray() && !data.isEmpty()
                    ? Optional.of(data.get(0))
                    : Optional.empty();
        } catch (IOException e) {
            throw new KnowledgeBaseException("Received a malformed response from the Product Life Cycle API");
        }
    }

    private static HttpResponse.BodyHandler<String> boundedBodyHandler() {
        return info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArray(),
                bytes -> {
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new KnowledgeBaseException("Life cycle response exceeded the size limit");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                });
    }
}
