package com.redhat.kb.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Caps how many Knowledge Base calls a single caller may make per minute.
 *
 * <p>Limits are counted per identity rather than per source address: several users often
 * share an egress address, and a caller's credential is the thing whose Red Hat quota is
 * actually being spent. Without this, one misbehaving agent loop can exhaust the
 * subscription's rate limit for everyone.
 */
@ApplicationScoped
public class RateLimiter {

    /** Length of the counting window. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Bounds the tracking map so a stream of distinct callers cannot exhaust memory. */
    private static final int MAX_TRACKED_CALLERS = 10_000;

    /** Calls made in the current window, and when that window started. */
    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant startedAt = Instant.now();

        /** @return true when the call fits within the limit */
        boolean tryAcquire(int limit) {
            if (Instant.now().isAfter(startedAt.plus(WINDOW))) {
                synchronized (this) {
                    if (Instant.now().isAfter(startedAt.plus(WINDOW))) {
                        startedAt = Instant.now();
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= limit;
        }

        boolean isStale() {
            return Instant.now().isAfter(startedAt.plus(WINDOW.multipliedBy(2)));
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Instance<SecurityIdentity> identity;
    private final int callsPerMinute;

    @Inject
    public RateLimiter(Instance<SecurityIdentity> identity,
            @ConfigProperty(name = "mcp.rate-limit.calls-per-minute", defaultValue = "60")
            int callsPerMinute) {
        this.identity = identity;
        this.callsPerMinute = callsPerMinute;
    }

    /**
     * Registers a call by the given caller.
     *
     * @param credentialFingerprint identifies the credential when there is no authenticated
     *        principal, so stdio and unauthenticated HTTP are still bounded
     * @return true when the call is allowed
     */
    public boolean tryAcquire(String credentialFingerprint) {
        if (callsPerMinute <= 0) {
            return true;
        }
        if (windows.size() >= MAX_TRACKED_CALLERS) {
            windows.values().removeIf(Window::isStale);
        }
        return windows.computeIfAbsent(callerKey(credentialFingerprint), key -> new Window())
                .tryAcquire(callsPerMinute);
    }

    public int callsPerMinute() {
        return callsPerMinute;
    }

    /**
     * Prefers the authenticated principal; a caller cannot escape their limit by rotating
     * credentials while keeping the same identity, nor the reverse.
     */
    private String callerKey(String credentialFingerprint) {
        try {
            if (identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous()) {
                    return "sub:" + current.getPrincipal().getName();
                }
            }
        } catch (RuntimeException e) {
            // No identity in scope; fall back to the credential.
        }
        return "cred:" + credentialFingerprint;
    }
}
