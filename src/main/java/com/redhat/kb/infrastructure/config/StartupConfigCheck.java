package com.redhat.kb.infrastructure.config;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Reports configuration that will not behave the way its name suggests.
 *
 * <p>Security settings fail quietly when they do not apply: an operator who sets one and
 * sees no error reasonably assumes it took effect. These warnings run once at startup so
 * that assumption is corrected before the server is in use.
 */
@ApplicationScoped
public class StartupConfigCheck {

    private static final Logger LOG = Logger.getLogger(StartupConfigCheck.class);

    private final RedHatApiConfig config;
    private final boolean stdioEnabled;
    private final boolean oidcEnabled;
    private final boolean httpEnabled;
    private final String httpHost;
    private final int httpPort;

    @Inject
    public StartupConfigCheck(
            RedHatApiConfig config,
            @ConfigProperty(name = "quarkus.mcp.server.stdio.enabled", defaultValue = "false")
            boolean stdioEnabled,
            @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "false")
            boolean oidcEnabled,
            @ConfigProperty(name = "quarkus.http.host-enabled", defaultValue = "true")
            boolean httpEnabled,
            @ConfigProperty(name = "quarkus.http.host", defaultValue = "127.0.0.1")
            String httpHost,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "9081")
            int httpPort) {
        this.config = config;
        this.stdioEnabled = stdioEnabled;
        this.oidcEnabled = oidcEnabled;
        this.httpEnabled = httpEnabled;
        this.httpHost = httpHost;
        this.httpPort = httpPort;
    }

    void onStart(@Observes StartupEvent event) {
        warnIfUserTokenRequiredOnStdio();
        warnIfHttpIsUnauthenticated();
        warnIfNoCredentialAvailable();
    }

    /**
     * Over stdio the client launches this process and passes REDHAT_TOKEN through its own
     * environment, so that value is already the caller's personal credential. There is no
     * second user to isolate from, and no header to carry a different token.
     */
    private void warnIfUserTokenRequiredOnStdio() {
        if (config.requireUserToken() && stdioEnabled && !httpEnabled) {
            LOG.warn("redhat.api.require-user-token does not apply to the stdio transport: "
                    + "REDHAT_TOKEN in this process's environment is already the caller's own "
                    + "credential. The setting is enforced on the HTTP transport only.");
        }
    }

    /**
     * An HTTP endpoint without authentication lets anyone who reaches the port spend the
     * subscription behind the configured token.
     */
    private void warnIfHttpIsUnauthenticated() {
        if (httpEnabled && !oidcEnabled && config.isConfigured() && !config.requireUserToken()) {
            // Loopback is only reachable from this machine, so the same configuration is
            // routine in development and dangerous once the port is published.
            if (boundBeyondLoopback()) {
                LOG.errorf("This server is bound to %s with no authentication while a shared "
                        + "REDHAT_TOKEN is configured: anyone who reaches port %d can spend that "
                        + "subscription. Set MCP_OIDC_ENABLED=true or MCP_REQUIRE_USER_TOKEN=true, "
                        + "or bind back to 127.0.0.1.", httpHost, httpPort);
                return;
            }
            LOG.warn("The HTTP transport is enabled without authentication while a shared "
                    + "REDHAT_TOKEN is configured. Anyone able to reach this port can use that "
                    + "subscription. Set MCP_OIDC_ENABLED=true, or MCP_REQUIRE_USER_TOKEN=true, "
                    + "before exposing it beyond localhost.");
        }
    }

    /** Whether the bind address accepts connections from outside this machine. */
    private boolean boundBeyondLoopback() {
        String host = httpHost.strip();
        return !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
    }

    /**
     * A server with no shared token still serves callers who bring their own, so this is a
     * warning rather than a failure.
     */
    private void warnIfNoCredentialAvailable() {
        if (!config.isConfigured() && !config.requireUserToken()) {
            LOG.warn("No REDHAT_TOKEN is configured. Callers must supply their own token in "
                    + "the X-Red-Hat-Token header, or every request will fail.");
        }
    }
}
