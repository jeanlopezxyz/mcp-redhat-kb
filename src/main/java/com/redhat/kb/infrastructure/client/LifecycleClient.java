package com.redhat.kb.infrastructure.client;

import com.redhat.kb.infrastructure.http.BoundedJsonHttp;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Client for the Red Hat Product Life Cycle API.
 *
 * <p>Public, like the Security Data API: no credential, no subscription spent. It answers
 * support-window questions ("when does OpenShift 4.14 reach end of life?") that a Knowledge
 * Base search cannot, because the dates are structured data rather than article text.
 */
@ApplicationScoped
public class LifecycleClient {

    /** Most a response body may occupy; enforced while streaming, never after buffering. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** How this API is bounded and named in failure messages the MCP client sees. */
    private static final BoundedJsonHttp.ApiProfile API = new BoundedJsonHttp.ApiProfile(
            MAX_RESPONSE_BYTES,
            "Request to the Product Life Cycle API was interrupted",
            "Could not reach the Red Hat Product Life Cycle API",
            "Life cycle response exceeded the size limit",
            "Received a malformed response from the Product Life Cycle API");

    private final String baseUrl;
    private final BoundedJsonHttp http;

    @Inject
    public LifecycleClient(RedHatApiConfig config, BoundedJsonHttp http) {
        this.baseUrl = config.urls().lifecycle();
        this.http = http;
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

        String url = baseUrl + "?name=" + URLEncoder.encode(productName.strip(), StandardCharsets.UTF_8);

        BoundedJsonHttp.ApiResponse response = http.get(url, API, "Accept", "application/json");

        if (!response.isOk()) {
            throw new KnowledgeBaseException(
                    "Could not look up the life cycle: the API returned HTTP " + response.status());
        }

        JsonNode data = http.readTree(response, API).path("data");
        return data.isArray() && !data.isEmpty()
                ? Optional.of(data.get(0))
                : Optional.empty();
    }
}
