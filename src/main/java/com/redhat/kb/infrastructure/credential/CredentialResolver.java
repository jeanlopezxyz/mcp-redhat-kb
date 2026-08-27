package com.redhat.kb.infrastructure.credential;

import java.util.Optional;

import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import io.vertx.core.http.HttpServerRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Decides which Red Hat credential serves the current request.
 *
 * <p>Per-user credentials are preferred: when a caller supplies their own offline token,
 * the Knowledge Base is read with their entitlements and Red Hat's audit trail records
 * them, rather than everyone appearing as a single shared account.
 *
 * <p>The shared token configured on the server is only a fallback, and can be switched off
 * entirely with {@code redhat.api.require-user-token=true} so that no caller can read the
 * Knowledge Base through someone else's subscription.
 */
@ApplicationScoped
public class CredentialResolver {

    /** Header carrying the caller's own Red Hat offline token. */
    public static final String USER_TOKEN_HEADER = "X-Red-Hat-Token";

    private static final Logger LOG = Logger.getLogger(CredentialResolver.class);

    private final RedHatApiConfig config;
    private final Instance<HttpServerRequest> currentRequest;
    private final boolean httpTransportEnabled;

    @Inject
    public CredentialResolver(RedHatApiConfig config, Instance<HttpServerRequest> currentRequest,
            @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true")
            boolean httpTransportEnabled) {
        this.config = config;
        this.currentRequest = currentRequest;
        this.httpTransportEnabled = httpTransportEnabled;
    }

    /**
     * Resolves the credential for this request.
     *
     * @throws MissingCredentialException when neither a user token nor a usable shared
     *         token is available
     */
    public RedHatCredential resolve() {
        Optional<RedHatCredential> userCredential = userToken();

        if (userCredential.isPresent()) {
            LOG.debugf("Using caller-supplied credential %s", userCredential.get().fingerprint());
            return userCredential.get();
        }

        // Requiring a per-user token is a multi-user concern, and stdio has no second user:
        // the client launched this process and supplied REDHAT_TOKEN through its own
        // environment, so that value is already the caller's personal credential. Enforcing
        // the rule here would only break a setup that was never at risk.
        if (config.requireUserToken() && isHttpRequest()) {
            throw new MissingCredentialException(
                    "This server requires your own Red Hat token. Send it in the "
                            + USER_TOKEN_HEADER + " header. Generate one at "
                            + "https://access.redhat.com/management/api");
        }

        return sharedCredential().orElseThrow(() -> new MissingCredentialException(
                isHttpRequest()
                        ? "No Red Hat token available. Send your own token in the "
                                + USER_TOKEN_HEADER + " header, or configure REDHAT_TOKEN on the server."
                        : "No Red Hat token available. Set REDHAT_TOKEN in this process's "
                                + "environment. Generate one at https://access.redhat.com/management/api"));
    }

    /**
     * Whether this call can carry a header at all.
     *
     * <p>Determined from the active transport rather than from the request context: the
     * {@code HttpServerRequest} bean stays resolvable even under stdio, so asking it would
     * report HTTP for a process that has no HTTP server running.
     */
    private boolean isHttpRequest() {
        return httpTransportEnabled;
    }

    /**
     * Reads the caller's token from the request, when one is in scope. The stdio transport
     * has no HTTP request, so this is empty there and the shared token applies.
     */
    private Optional<RedHatCredential> userToken() {
        if (!currentRequest.isResolvable()) {
            return Optional.empty();
        }
        try {
            String header = currentRequest.get().getHeader(USER_TOKEN_HEADER);
            return header == null || header.isBlank()
                    ? Optional.empty()
                    : Optional.of(RedHatCredential.of(header));
        } catch (RuntimeException e) {
            // No active request context (for example, stdio): fall back to the shared token.
            return Optional.empty();
        }
    }

    private Optional<RedHatCredential> sharedCredential() {
        return config.isConfigured()
                ? config.offlineToken().map(RedHatCredential::of)
                : Optional.empty();
    }

    /**
     * Whether this server can serve a caller who supplies no token of their own.
     *
     * <p>Over stdio the configured token always applies, since the requirement to bring
     * your own is an HTTP-transport rule.
     */
    public boolean hasUsableFallback() {
        if (!config.isConfigured()) {
            return false;
        }
        return !config.requireUserToken() || !isHttpRequest();
    }
}
