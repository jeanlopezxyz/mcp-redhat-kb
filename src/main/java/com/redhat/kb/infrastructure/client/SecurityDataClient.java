package com.redhat.kb.infrastructure.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** CVE identifiers look like CVE-2024-6387. */
    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,19}", Pattern.CASE_INSENSITIVE);

    /** Most a response body may occupy; enforced while streaming, never after buffering. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** How this API is bounded and named in failure messages the MCP client sees. */
    private static final BoundedJsonHttp.ApiProfile API = new BoundedJsonHttp.ApiProfile(
            MAX_RESPONSE_BYTES,
            "Request to the Security Data API was interrupted",
            "Could not reach the Red Hat Security Data API",
            "Security Data response exceeded the size limit",
            "Received a malformed response from the Security Data API");

    private final String baseUrl;
    private final BoundedJsonHttp http;

    @Inject
    public SecurityDataClient(RedHatApiConfig config, BoundedJsonHttp http) {
        this.baseUrl = config.urls().securityData();
        this.http = http;
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

        String url = baseUrl + "/cve/" + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + ".json";

        BoundedJsonHttp.ApiResponse response = http.get(url, API, "Accept", "application/json");

        if (response.status() == Response.Status.NOT_FOUND.getStatusCode()) {
            // "Not tracked by Red Hat" is an answer, not a failure.
            return Optional.empty();
        }
        if (!response.isOk()) {
            throw new KnowledgeBaseException(
                    "Could not look up " + normalized + ": the Security Data API returned HTTP "
                            + response.status());
        }

        return Optional.of(http.readTree(response, API));
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
}
