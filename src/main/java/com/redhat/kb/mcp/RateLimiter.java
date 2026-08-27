package com.redhat.kb.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Caps how many Knowledge Base calls a single caller may make per minute.
 *
 * <p>Limits are counted per caller, not globally: several users often share one server,
 * and without separation one misbehaving agent loop can exhaust the subscription's rate
 * limit for everyone. See {@link #callerKey} for how a caller is identified.
 *
 * <p>The window is fixed, not sliding: a caller can fit up to twice the limit across a
 * window boundary (the tail of one window plus the head of the next). That is enough to
 * stop a runaway agent loop, which is this limiter's job; it is not a defence against a
 * deliberate attacker — that is what authentication is for.
 */
@ApplicationScoped
public class RateLimiter {

    /** Length of the counting window. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Bounds the tracking cache so a stream of distinct callers cannot exhaust memory. */
    private static final int MAX_TRACKED_CALLERS = 10_000;

    /**
     * Key used when no caller signal exists at all — stdio, where the client launched this
     * process for itself. One bucket is then that single user's bucket, not a shared one.
     */
    private static final String SINGLE_LOCAL_CALLER = "local";

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
    }

    /**
     * Windows by caller key. Caffeine enforces the size bound unconditionally, where a
     * plain map with periodic stale-sweeping only shrinks when entries happen to be old —
     * a burst of distinct callers within one window would grow it past any limit. Entries
     * idle for two windows would restart their count on the next call anyway, so expiring
     * them changes nothing a caller can observe.
     */
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CALLERS)
            .expireAfterAccess(WINDOW.multipliedBy(2))
            .build();

    private final Instance<SecurityIdentity> identity;
    private final Instance<HttpServerRequest> request;
    private final int callsPerMinute;

    @Inject
    public RateLimiter(Instance<SecurityIdentity> identity,
            Instance<HttpServerRequest> request,
            @ConfigProperty(name = "mcp.rate-limit.calls-per-minute", defaultValue = "60")
            int callsPerMinute) {
        this.identity = identity;
        this.request = request;
        this.callsPerMinute = callsPerMinute;
    }

    /**
     * Registers a call made with a Red Hat credential.
     *
     * @param credentialFingerprint identifies the credential when there is no authenticated
     *        principal, so stdio and unauthenticated HTTP are still bounded
     * @return true when the call is allowed
     */
    public boolean tryAcquire(String credentialFingerprint) {
        return acquire(Optional.of(credentialFingerprint));
    }

    /**
     * Registers a call to a credential-less tool (the public Red Hat APIs). With no
     * credential to count by, unauthenticated callers are told apart by remote address.
     *
     * @return true when the call is allowed
     */
    public boolean tryAcquire() {
        return acquire(Optional.empty());
    }

    private boolean acquire(Optional<String> credentialFingerprint) {
        if (callsPerMinute <= 0) {
            return true;
        }
        return windows.get(callerKey(credentialFingerprint), key -> new Window())
                .tryAcquire(callsPerMinute);
    }

    public int callsPerMinute() {
        return callsPerMinute;
    }

    /**
     * The most identifying signal available wins, in this order:
     *
     * <ol>
     * <li>Authenticated principal — a caller cannot escape their limit by rotating
     * credentials or hopping addresses while keeping the same identity.</li>
     * <li>Credential fingerprint — the credential is the thing whose Red Hat quota is
     * actually spent, and several users often share an egress address.</li>
     * <li>Remote address — for the public credential-less tools on unauthenticated HTTP
     * it is the only signal left; without it every caller would share one bucket and a
     * single loop could starve them all.</li>
     * <li>A constant — only reachable on stdio, where there is no HTTP request and the
     * process serves exactly one user.</li>
     * </ol>
     */
    private String callerKey(Optional<String> credentialFingerprint) {
        return authenticatedPrincipal()
                .or(() -> credentialFingerprint.map(fingerprint -> "cred:" + fingerprint))
                .or(this::remoteAddress)
                .orElse(SINGLE_LOCAL_CALLER);
    }

    private Optional<String> authenticatedPrincipal() {
        try {
            if (identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous()) {
                    return Optional.of("sub:" + current.getPrincipal().getName());
                }
            }
        } catch (RuntimeException e) {
            // No identity in scope; fall through to the next signal.
        }
        return Optional.empty();
    }

    private Optional<String> remoteAddress() {
        try {
            if (request.isResolvable()) {
                return Optional.of("addr:" + request.get().remoteAddress().hostAddress());
            }
        } catch (RuntimeException e) {
            // No active HTTP request, as on stdio.
        }
        return Optional.empty();
    }
}
