package com.redhat.kb.mcp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.security.identity.SecurityIdentity;
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

    @Test
    @DisplayName("allows calls up to the limit")
    void allowsUpToTheLimit() {
        RateLimiter limiter = new RateLimiter(anonymous(), 3);

        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("refuses the call that exceeds the limit")
    void refusesBeyondTheLimit() {
        RateLimiter limiter = new RateLimiter(anonymous(), 2);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");

        assertFalse(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("counts each credential separately")
    void countsPerCredential() {
        // One caller exhausting their quota must not lock out everyone else.
        RateLimiter limiter = new RateLimiter(anonymous(), 2);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");

        assertFalse(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("bob"), "Bob was throttled by Alice's usage");
    }

    @Test
    @DisplayName("counts by authenticated identity when there is one")
    void countsPerIdentity() {
        // Rotating the Red Hat credential must not reset the caller's own quota.
        RateLimiter limiter = new RateLimiter(authenticatedAs("alice@example.com"), 2);
        limiter.tryAcquire("credential-one");
        limiter.tryAcquire("credential-one");

        assertFalse(limiter.tryAcquire("credential-two"),
                "the same principal evaded their limit by switching credentials");
    }

    @Test
    @DisplayName("separates distinct authenticated identities")
    void separatesIdentities() {
        RateLimiter alice = new RateLimiter(authenticatedAs("alice@example.com"), 1);
        RateLimiter bob = new RateLimiter(authenticatedAs("bob@example.com"), 1);

        assertTrue(alice.tryAcquire("shared-credential"));
        assertTrue(bob.tryAcquire("shared-credential"),
                "Bob was throttled by Alice, despite being a different principal");
    }

    @Test
    @DisplayName("disables throttling when the limit is zero")
    void zeroDisablesLimiting() {
        RateLimiter limiter = new RateLimiter(anonymous(), 0);

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
        RateLimiter limiter = new RateLimiter(anonymous(), limit);

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
        assertEquals(42, new RateLimiter(anonymous(), 42).callsPerMinute());
    }
}
