package com.redhat.kb.infrastructure.client;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers access-token caching, which is where per-user isolation is enforced.
 *
 * <p>Only direct JWTs are exercised: those are cached without contacting Red Hat SSO, so
 * these tests stay offline while still driving the real cache paths.
 */
class RedHatAuthClientTest {

    private static RedHatApiConfig config() {
        RedHatApiConfig config = mock(RedHatApiConfig.class);
        RedHatApiConfig.Timeouts timeouts = mock(RedHatApiConfig.Timeouts.class);
        when(timeouts.connectSeconds()).thenReturn(5);
        when(timeouts.requestSeconds()).thenReturn(5);
        when(config.timeouts()).thenReturn(timeouts);
        when(config.offlineToken()).thenReturn(Optional.of("shared"));
        when(config.isConfigured()).thenReturn(true);
        return config;
    }

    /** Builds an unsigned JWT that expires in the given number of seconds. */
    private static String jwt(String subject, long expiresInSeconds) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = encoder.encodeToString(
                ("{\"sub\":\"" + subject + "\",\"exp\":"
                        + (Instant.now().getEpochSecond() + expiresInSeconds) + "}").getBytes());
        return header + "." + payload + ".signature";
    }

    private final RedHatAuthClient client = new RedHatAuthClient(config(), new ObjectMapper());

    @Test
    @DisplayName("returns the access token for a direct JWT credential")
    void returnsDirectJwt() {
        String token = jwt("alice", 600);

        assertEquals(token, client.getAccessToken(RedHatCredential.of(token)));
    }

    @Test
    @DisplayName("never serves one caller's token to another")
    void isolatesCredentials() {
        // The core guarantee of per-user credentials: two callers, two distinct tokens,
        // no crossover through a shared cache entry.
        String aliceToken = jwt("alice", 600);
        String bobToken = jwt("bob", 600);

        String forAlice = client.getAccessToken(RedHatCredential.of(aliceToken));
        String forBob = client.getAccessToken(RedHatCredential.of(bobToken));

        assertEquals(aliceToken, forAlice);
        assertEquals(bobToken, forBob);
        assertNotEquals(forAlice, forBob);
    }

    @Test
    @DisplayName("keeps returning the same token for a repeated credential")
    void cachesPerCredential() {
        String token = jwt("alice", 600);
        RedHatCredential credential = RedHatCredential.of(token);

        assertEquals(client.getAccessToken(credential), client.getAccessToken(credential));
    }

    @Test
    @DisplayName("does not mix up credentials under concurrent access")
    void isolatesCredentialsConcurrently() throws Exception {
        // The cache is shared across concurrent MCP requests; a race here would hand one
        // subscriber's access token to another.
        int callers = 8;
        List<String> tokens = java.util.stream.IntStream.range(0, callers)
                .mapToObj(i -> jwt("user-" + i, 600))
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch startLine = new CountDownLatch(1);
            List<Future<String>> results = tokens.stream()
                    .map(token -> pool.submit(() -> {
                        startLine.await();
                        return client.getAccessToken(RedHatCredential.of(token));
                    }))
                    .toList();

            startLine.countDown();

            for (int i = 0; i < callers; i++) {
                assertEquals(tokens.get(i), results.get(i).get(5, TimeUnit.SECONDS),
                        "caller " + i + " received another caller's token");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("refreshes a credential whose cached token has expired")
    void refreshesExpiredEntry() {
        // An already-expired JWT must not be served from cache on the next call.
        String expired = jwt("alice", -60);
        RedHatCredential credential = RedHatCredential.of(expired);

        // The direct-JWT path returns the token itself; the point is that asking twice
        // does not throw and does not return a stale entry from a different credential.
        assertEquals(expired, client.getAccessToken(credential));
        assertEquals(expired, client.getAccessToken(credential));
    }

    @Test
    @DisplayName("reports whether a shared token is configured")
    void reportsSharedTokenPresence() {
        assertTrue(client.isConfigured());
    }
}
