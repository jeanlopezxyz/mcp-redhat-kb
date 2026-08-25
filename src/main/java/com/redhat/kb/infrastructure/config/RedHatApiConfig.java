package com.redhat.kb.infrastructure.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for Red Hat Knowledge Base API.
 */
@ConfigMapping(prefix = "redhat.api")
public interface RedHatApiConfig {

    /**
     * Shared Red Hat offline token, used when a caller supplies none of their own.
     */
    Optional<String> offlineToken();

    /**
     * Whether every caller must supply their own Red Hat token.
     *
     * <p>When true the shared token is never used to serve a request, so no caller can read
     * the Knowledge Base through another account's entitlements and Red Hat's audit trail
     * attributes each call to the person who made it.
     */
    @WithDefault("false")
    boolean requireUserToken();

    /**
     * SSO configuration.
     */
    Sso sso();

    /**
     * Connection timeouts.
     */
    Timeouts timeouts();

    /**
     * Checks if the service is properly configured.
     */
    default boolean isConfigured() {
        return offlineToken().isPresent() &&
               !offlineToken().get().isBlank() &&
               !offlineToken().get().equals("your-offline-token-here");
    }

    interface Sso {
        @WithDefault("https://sso.redhat.com/auth/realms/redhat-external/protocol/openid-connect/token")
        String tokenUrl();

        @WithDefault("rhsm-api")
        String clientId();

        @WithDefault("60")
        int tokenRenewalBufferSeconds();
    }

    interface Timeouts {
        @WithDefault("30")
        int connectSeconds();

        @WithDefault("60")
        int requestSeconds();
    }
}
