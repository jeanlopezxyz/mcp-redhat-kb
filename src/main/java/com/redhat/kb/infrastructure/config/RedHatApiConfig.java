package com.redhat.kb.infrastructure.config;

import java.util.List;
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
     * Base URLs of the upstream Red Hat APIs.
     *
     * <p>Overridable so a test can point a client at a local stub and stay offline;
     * production deployments have no reason to change them. The defaults are the values
     * the clients previously hardcoded.
     */
    Urls urls();

    /**
     * Checks if the service is properly configured.
     */
    /**
     * Placeholders the documentation hands out in place of a real token. They are rejected
     * as "not configured": a copied example otherwise passes this check and fails much
     * later, as an opaque 401 from Red Hat SSO.
     */
    List<String> TOKEN_PLACEHOLDERS = List.of(
            "your-offline-token-here", "your-offline-token", "your-token-here", "your-token");

    default boolean isConfigured() {
        return offlineToken()
                .map(String::strip)
                .filter(token -> !token.isBlank())
                .filter(token -> !TOKEN_PLACEHOLDERS.contains(token))
                .isPresent();
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

    interface Urls {
        /** Knowledge Base search (the credentialed Hydra API). */
        @WithDefault("https://access.redhat.com/hydra/rest/search/kcs")
        String knowledgeBase();

        /** Security Data API (public CVE records, no credential). */
        @WithDefault("https://access.redhat.com/hydra/rest/securitydata")
        String securityData();

        /** Product Life Cycle API (public support windows, no credential). */
        @WithDefault("https://access.redhat.com/product-life-cycles/api/v1/products")
        String lifecycle();
    }
}
