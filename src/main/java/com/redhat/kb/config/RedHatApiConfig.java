package com.redhat.kb.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuracion para la API de Knowledge Base de Red Hat.
 */
@ConfigMapping(prefix = "redhat.api")
public interface RedHatApiConfig {

    /**
     * Token offline de Red Hat para autenticacion.
     */
    Optional<String> offlineToken();

    /**
     * Configuracion de SSO.
     */
    Sso sso();

    /**
     * Timeouts de conexion.
     */
    Timeouts timeouts();

    /**
     * Verifica si el servicio esta configurado correctamente.
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
