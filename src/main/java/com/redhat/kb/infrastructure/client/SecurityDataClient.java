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
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Client for the Red Hat Security Data API.
 *
 * <p>Unlike the Knowledge Base, this API is public: it takes no credential, so no
 * subscription is spent and the caller's entitlements do not apply. It answers questions a
 * Knowledge Base search cannot — the CVSS score, which releases are affected, and whether
 * Red Hat intends to ship a fix — because those are structured fields rather than prose.
 */
@ApplicationScoped
public class SecurityDataClient {

    private static final String BASE_URL = "https://access.redhat.com/hydra/rest/securitydata";

    /** CVE identifiers look like CVE-2024-6387. */
    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,19}", Pattern.CASE_INSENSITIVE);

    /** Guards against an oversized response exhausting the heap. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RedHatApiConfig config;

    @Inject
    public SecurityDataClient(RedHatApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    /**
     * Looks up a single CVE.
     *
     * @return the raw record, or empty when Red Hat has no entry for that identifier
     * @throws KnowledgeBaseException if the identifier is malformed or the API fails
     */
    @CacheResult(cacheName = "cve-lookup")
    public Optional<JsonNode> lookupCve(String cveId) {
        String normalized = normalize(cveId);

        String url = BASE_URL + "/cve/" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + ".json";

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
            throw new KnowledgeBaseException("Request to the Security Data API was interrupted");
        } catch (IOException e) {
            throw new KnowledgeBaseException("Could not reach the Red Hat Security Data API");
        }

        int status = response.statusCode();
        if (status == Response.Status.NOT_FOUND.getStatusCode()) {
            return Optional.empty();
        }
        if (status != Response.Status.OK.getStatusCode()) {
            throw new KnowledgeBaseException(
                    "Could not look up " + normalized + ": the Security Data API returned HTTP " + status);
        }

        try {
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new KnowledgeBaseException("Received a malformed response from the Security Data API");
        }
    }

    /**
     * Accepts a bare identifier with or without the CVE prefix, so a caller passing
     * "2024-6387" is not turned away for a formatting detail.
     */
    private static String normalize(String cveId) {
        if (cveId == null || cveId.isBlank()) {
            throw new KnowledgeBaseException("A CVE identifier is required, for example CVE-2024-6387");
        }
        String candidate = cveId.strip().toUpperCase();
        if (!candidate.startsWith("CVE-")) {
            candidate = "CVE-" + candidate;
        }
        if (!CVE_ID.matcher(candidate).matches()) {
            throw new KnowledgeBaseException(
                    "\"" + cveId.strip() + "\" is not a CVE identifier; expected the form CVE-2024-6387");
        }
        return candidate;
    }

    private static HttpResponse.BodyHandler<String> boundedBodyHandler() {
        return info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArray(),
                bytes -> {
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new KnowledgeBaseException("Security Data response exceeded the size limit");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                });
    }
}
