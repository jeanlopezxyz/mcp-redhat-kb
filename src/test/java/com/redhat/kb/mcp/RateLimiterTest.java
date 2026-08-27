package com.redhat.kb.mcp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    /** A scope with no authenticated identity, as on stdio. */
    @SuppressWarnings("unchecked")
    private static Instance<SecurityIdentity> anonymous() {
        Instance<SecurityIdentity> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    /** A scope carrying the given authenticated principal. */
    @SuppressWarnings("unchecked")
    private static Instance<SecurityIdentity> authenticatedAs(String name) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(() -> name);

        Instance<SecurityIdentity> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(identity);
        return instance;
    }

    /** A scope with no HTTP request in flight, as on stdio. */
    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> noRequest() {
        Instance<HttpServerRequest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    /** A scope carrying an HTTP request from the given address. */
    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> requestFrom(String hostAddress) {
        SocketAddress address = mock(SocketAddress.class);
        when(address.hostAddress()).thenReturn(hostAddress);

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.remoteAddress()).thenReturn(address);

        Instance<HttpServerRequest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(request);
        return instance;
    }

    /** The common case in these tests: no identity, no HTTP request. */
    private static RateLimiter localLimiter(int callsPerMinute) {
        return new RateLimiter(anonymous(), noRequest(), callsPerMinute);
    }

    @Test
    @DisplayName("allows calls up to the limit")
    void allowsUpToTheLimit() {
        RateLimiter limiter = localLimiter(3);

        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("refuses the call that exceeds the limit")
    void refusesBeyondTheLimit() {
        RateLimiter limiter = localLimiter(2);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");

        assertFalse(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("counts each credential separately")
    void countsPerCredential() {
        // One caller exhausting their quota must not lock out everyone else.
        RateLimiter limiter = localLimiter(2);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");

        assertFalse(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("bob"), "Bob was throttled by Alice's usage");
    }

    @Test
    @DisplayName("counts by authenticated identity when there is one")
    void countsPerIdentity() {
        // Rotating the Red Hat credential must not reset the caller's own quota.
        RateLimiter limiter = new RateLimiter(authenticatedAs("alice@example.com"), noRequest(), 2);
        limiter.tryAcquire("credential-one");
        limiter.tryAcquire("credential-one");

        assertFalse(limiter.tryAcquire("credential-two"),
                "the same principal evaded their limit by switching credentials");
    }

    @Test
    @DisplayName("separates distinct authenticated identities")
    void separatesIdentities() {
        RateLimiter alice = new RateLimiter(authenticatedAs("alice@example.com"), noRequest(), 1);
        RateLimiter bob = new RateLimiter(authenticatedAs("bob@example.com"), noRequest(), 1);

        assertTrue(alice.tryAcquire("shared-credential"));
        assertTrue(bob.tryAcquire("shared-credential"),
                "Bob was throttled by Alice, despite being a different principal");
    }

    @Test
    @DisplayName("disables throttling when the limit is zero")
    void zeroDisablesLimiting() {
        RateLimiter limiter = localLimiter(0);

        for (int i = 0; i < 500; i++) {
            assertTrue(limiter.tryAcquire("alice"));
        }
    }

    @Test
    @DisplayName("does not exceed the limit under concurrent calls")
    void enforcesLimitConcurrently() throws Exception {
        // A limiter that miscounts under load is a limiter that does not limit: the
        // counter must not let extra calls slip through on parallel requests.
        int limit = 10;
        int attempts = 100;
        RateLimiter limiter = localLimiter(limit);

        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch startLine = new CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, attempts)
                    .mapToObj(i -> pool.submit(() -> {
                        try {
                            startLine.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (limiter.tryAcquire("alice")) {
                            allowed.incrementAndGet();
                        }
                    }))
                    .toList();

            startLine.countDown();
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, allowed.get(),
                "expected exactly " + limit + " calls through, got " + allowed.get());
    }

    @Test
    @DisplayName("reports the configured limit for the refusal message")
    void reportsConfiguredLimit() {
        assertEquals(42, localLimiter(42).callsPerMinute());
    }

    @Test
    @DisplayName("separates credential-less callers by remote address")
    void separatesPublicCallersByAddress() {
        // The public CVE and life cycle tools carry no Red Hat credential, so without the
        // address every unauthenticated caller shared one bucket and a single agent loop
        // starved the rest.
        RateLimiter first = new RateLimiter(anonymous(), requestFrom("203.0.113.7"), 1);
        RateLimiter second = new RateLimiter(anonymous(), requestFrom("198.51.100.4"), 1);

        assertTrue(first.tryAcquire());
        assertTrue(second.tryAcquire(), "a second address was throttled by the first one's usage");
    }

    @Test
    @DisplayName("still counts a credential-less caller against their own limit")
    void boundsPublicCallerAtSameAddress() {
        RateLimiter limiter = new RateLimiter(anonymous(), requestFrom("203.0.113.7"), 2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "the address bucket did not enforce the limit");
    }

    @Test
    @DisplayName("prefers the authenticated principal over the remote address")
    void identityOutranksAddress() {
        // Two calls from different addresses, authenticated as the same principal, share
        // one bucket: the identity is the more specific signal, so hopping addresses
        // behind a proxy must not hand the same person a fresh quota. The request scope
        // returns a different address per call; separate limiter instances would each
        // carry their own cache and prove nothing.
        HttpServerRequest firstAddress = requestFrom("203.0.113.7").get();
        HttpServerRequest secondAddress = requestFrom("198.51.100.4").get();

        @SuppressWarnings("unchecked")
        Instance<HttpServerRequest> movingCaller = mock(Instance.class);
        when(movingCaller.isResolvable()).thenReturn(true);
        when(movingCaller.get()).thenReturn(firstAddress, secondAddress);

        RateLimiter limiter = new RateLimiter(
                authenticatedAs("alice@example.com"), movingCaller, 1);

        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(),
                "the same principal evaded their limit by changing address");
    }

    @Test
    @DisplayName("bounds a credential-less caller on stdio, where there is no address")
    void boundsLocalCallerWithoutRequest() {
        // stdio has neither identity nor request: one bucket is correct there, because the
        // client launched this process for itself and is the only caller.
        RateLimiter limiter = localLimiter(2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
