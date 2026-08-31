package com.redhat.kb.infrastructure.client;

import com.redhat.kb.infrastructure.http.BoundedJsonHttp;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    /** How many candidate names an ambiguity message lists before summarising. */
    private static final int MAX_SUGGESTIONS = 5;

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
     * Looks up the life cycle of a product by its full official name.
     *
     * @return the product record, or empty when nothing matches that name
     * @throws KnowledgeBaseException when the name matches several products, since
     *         answering with one of them would pass another product's dates off as the
     *         answer with nothing marking the substitution
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
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }

        String requested = productName.strip();
        for (JsonNode product : data) {
            if (product.path("name").asText("").equalsIgnoreCase(requested)) {
                return Optional.of(product);
            }
        }

        // No exact match, and the API matches substrings: "OpenShift" returns thirteen
        // products spanning Shipwright, logging and OCP itself. Answering with any one of
        // them presents another product's end-of-life dates as the answer, and the caller
        // cannot tell. Naming the candidates lets the model ask again with a full name.
        throw new KnowledgeBaseException(ambiguousMatch(data, requested));
    }

    /** Lists what the name matched, so the caller can retry with a full product name. */
    private static String ambiguousMatch(JsonNode data, String requested) {
        List<String> names = new ArrayList<>();
        for (JsonNode product : data) {
            if (names.size() == MAX_SUGGESTIONS) {
                break;
            }
            names.add(product.path("name").asText(""));
        }
        String more = data.size() > names.size() ? ", and " + (data.size() - names.size()) + " more" : "";
        return "\"" + requested + "\" matches " + data.size() + " products, so no life cycle was"
                + " returned: it must be a product's full official name. Did you mean "
                + String.join("; ", names) + more + "?";
    }

}
