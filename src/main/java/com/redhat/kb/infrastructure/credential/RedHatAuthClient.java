package com.redhat.kb.infrastructure.credential;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Exchanges Red Hat offline tokens for access tokens.
 *
 * <p>Access tokens are cached per credential, keyed by the credential's fingerprint, so one
 * caller's token is never handed to another. The bean is a singleton shared across
 * concurrent requests, so each entry is published atomically and refreshes are serialized
 * per credential rather than globally.
 */
@ApplicationScoped
public class RedHatAuthClient {

    private static final Logger LOG = Logger.getLogger(RedHatAuthClient.class);

    /**
     * Upper bound on how long an access token is reused. The {@code exp} claim of a direct
     * JWT is read without signature verification, so it is treated as a hint rather than as
     * authority: re-checking periodically limits the window in which a revoked token would
     * still be used.
     */
    private static final Duration MAX_CACHE_DURATION = Duration.ofMinutes(5);

    /** Guards against a stalled SSO holding a caller for the whole request. */
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(90);

    /** Bounds the cache so a stream of distinct credentials cannot exhaust memory. */
    private static final int MAX_CACHED_CREDENTIALS = 500;

    /** Access token and its expiry, published together so readers never see a mismatch. */
    private record TokenState(String token, Instant expiry) {
        boolean isValid() {
            return Instant.now().isBefore(expiry);
        }
    }

    private final RedHatApiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Keyed by credential fingerprint, never by the token itself. Caffeine enforces the
     * size bound unconditionally, where a plain map swept for expired entries only shrinks
     * when entries happen to be stale — a burst of distinct credentials that are all still
     * valid would grow it past any limit.
     */
    private final Cache<String, TokenState> tokenCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_CREDENTIALS)
            .expireAfterWrite(MAX_CACHE_DURATION)
            .build();

    /**
     * Refreshes currently running, so concurrent callers for the same credential join the
     * one in flight instead of each opening its own SSO round trip.
     */
    private final ConcurrentMap<String, CompletableFuture<TokenState>> inFlight = new ConcurrentHashMap<>();

    @Inject
    public RedHatAuthClient(RedHatApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    /**
     * Returns a valid access token for the given credential, refreshing it when the cached
     * one is missing or expired.
     */
    public String getAccessToken(RedHatCredential credential) {
        String fingerprint = credential.fingerprint();
        TokenState cached = tokenCache.getIfPresent(fingerprint);
        if (cached != null && cached.isValid()) {
            return cached.token();
        }

        // The SSO round trip runs outside the cache's per-key lock: holding it across a
        // network call would stall every other credential mapping to the same lock stripe
        // for as long as SSO takes to answer.
        CompletableFuture<TokenState> refresh = inFlight.computeIfAbsent(
                fingerprint,
                key -> CompletableFuture.supplyAsync(() -> exchange(credential)));

        try {
            TokenState state = refresh.get(REFRESH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            tokenCache.put(fingerprint, state);
            return state.token();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("Interrupted while refreshing the access token");
        } catch (TimeoutException e) {
            LOG.errorf("Timed out refreshing the access token for credential %s", fingerprint);
            throw new AuthenticationException("Timed out refreshing the access token");
        } catch (ExecutionException e) {
            // Unwrap so callers keep seeing the typed failure exchange() raised.
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            LOG.errorf(e.getCause(), "Could not refresh the token for credential %s", fingerprint);
            throw new AuthenticationException("Could not refresh the access token");
        } finally {
            inFlight.remove(fingerprint, refresh);
        }
    }

    /**
     * Detects if the provided token is a direct JWT (access token) rather than an offline
     * token that has to be exchanged.
     */
    private boolean isJwtToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            JsonNode json = decodePayload(parts[1]);

            // "typ": "Offline" marks an offline token, which must be exchanged.
            if (json.has("typ") && "Offline".equals(json.get("typ").asText())) {
                return false;
            }
            return json.has("exp");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the expiration of a JWT, clamped to {@link #MAX_CACHE_DURATION}.
     */
    private Instant getJwtExpiry(String token) {
        Instant ceiling = Instant.now().plus(MAX_CACHE_DURATION);
        try {
            JsonNode json = decodePayload(token.split("\\.")[1]);
            if (json.has("exp")) {
                Instant claimed = Instant.ofEpochSecond(json.get("exp").asLong());
                return claimed.isBefore(ceiling) ? claimed : ceiling;
            }
            LOG.debug("JWT has no exp claim; falling back to the maximum cache duration");
        } catch (Exception e) {
            LOG.debug("Could not parse JWT expiry; falling back to the maximum cache duration");
        }
        return ceiling;
    }

    private JsonNode decodePayload(String segment) throws java.io.IOException {
        String payload = new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload);
    }

    /**
     * Exchanges an offline token with Red Hat SSO.
     *
     * <p>Failures never carry the SSO response body or the underlying exception message
     * outward: those can echo the submitted token, and this message reaches the MCP client.
     * Details go to the log, identified by fingerprint rather than by token.
     */
    private TokenState exchange(RedHatCredential credential) {
        String token = credential.token();

        if (isJwtToken(token)) {
            return new TokenState(token, getJwtExpiry(token));
        }

        HttpResponse<String> response;
        try {
            String requestBody = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(config.sso().clientId(), StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.sso().tokenUrl()))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("Authentication with Red Hat SSO was interrupted");
        } catch (Exception e) {
            LOG.errorf(e, "Could not reach Red Hat SSO for credential %s", credential.fingerprint());
            throw new AuthenticationException("Could not reach Red Hat SSO");
        }

        if (response.statusCode() != Response.Status.OK.getStatusCode()) {
            LOG.errorf("Red Hat SSO rejected credential %s with HTTP %d",
                    credential.fingerprint(), response.statusCode());
            throw new AuthenticationException(
                    "Red Hat SSO rejected the token (HTTP " + response.statusCode()
                            + "). Verify that it is a valid, non-expired offline token.");
        }

        try {
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode accessTokenNode = json.get("access_token");
            JsonNode expiresInNode = json.get("expires_in");

            if (accessTokenNode == null || expiresInNode == null) {
                throw new AuthenticationException(
                        "Red Hat SSO returned a response without access_token or expires_in");
            }

            long ttl = Math.max(1, expiresInNode.asLong() - config.sso().tokenRenewalBufferSeconds());
            Instant expiry = Instant.now().plusSeconds(ttl);
            Instant ceiling = Instant.now().plus(MAX_CACHE_DURATION);

            return new TokenState(accessTokenNode.asText(), expiry.isBefore(ceiling) ? expiry : ceiling);
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Could not parse the Red Hat SSO response", e);
            throw new AuthenticationException("Could not parse the Red Hat SSO response");
        }
    }

}
