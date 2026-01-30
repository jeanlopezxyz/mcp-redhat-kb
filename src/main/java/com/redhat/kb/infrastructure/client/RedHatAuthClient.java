package com.redhat.kb.infrastructure.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Client for Red Hat authentication.
 * Supports direct JWT tokens and SSO offline tokens.
 */
@ApplicationScoped
public class RedHatAuthClient {

    private final RedHatApiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private String cachedAccessToken;
    private Instant tokenExpiry;
    private Boolean isDirectJwt = null;

    @Inject
    public RedHatAuthClient(RedHatApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    /**
     * Gets a valid access token.
     */
    public String getAccessToken() {
        if (cachedAccessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedAccessToken;
        }
        return refreshAccessToken();
    }

    /**
     * Detects if the provided token is a direct JWT (access token).
     */
    private boolean isJwtToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode json = objectMapper.readTree(payload);

            // If it has "typ": "Offline", it's an offline token that must be exchanged
            if (json.has("typ") && "Offline".equals(json.get("typ").asText())) {
                return false;
            }

            // If it has "exp", it's a direct JWT (access token)
            return json.has("exp");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the expiration date from a JWT token.
     */
    private Instant getJwtExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode json = objectMapper.readTree(payload);
            if (json.has("exp")) {
                long expSeconds = json.get("exp").asLong();
                return Instant.ofEpochSecond(expSeconds);
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return Instant.now().plusSeconds(3600);
    }

    /**
     * Refreshes the access token.
     */
    private String refreshAccessToken() {
        try {
            String token = config.offlineToken()
                    .orElseThrow(() -> new RuntimeException("Token not configured. Set REDHAT_TOKEN."));

            if (isDirectJwt == null) {
                isDirectJwt = isJwtToken(token);
            }

            if (isDirectJwt) {
                cachedAccessToken = token;
                tokenExpiry = getJwtExpiry(token);
                return cachedAccessToken;
            }

            String requestBody = String.format(
                    "grant_type=refresh_token&client_id=%s&refresh_token=%s",
                    config.sso().clientId(),
                    token
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.sso().tokenUrl()))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == Response.Status.OK.getStatusCode()) {
                JsonNode json = objectMapper.readTree(response.body());

                JsonNode accessTokenNode = json.get("access_token");
                JsonNode expiresInNode = json.get("expires_in");

                if (accessTokenNode == null || expiresInNode == null) {
                    throw new RuntimeException("Invalid response from Red Hat SSO: missing access_token or expires_in");
                }

                cachedAccessToken = accessTokenNode.asText();
                int expiresIn = expiresInNode.asInt();
                tokenExpiry = Instant.now().plusSeconds(expiresIn - config.sso().tokenRenewalBufferSeconds());
                return cachedAccessToken;
            } else {
                throw new RuntimeException("Error getting token from Red Hat SSO: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error authenticating with Red Hat: " + e.getMessage(), e);
        }
    }

    /**
     * Checks if the service is properly configured.
     */
    public boolean isConfigured() {
        return config.isConfigured();
    }
}
