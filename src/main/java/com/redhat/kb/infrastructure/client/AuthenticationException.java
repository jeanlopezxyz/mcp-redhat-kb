package com.redhat.kb.infrastructure.client;

/**
 * Raised when authentication against Red Hat SSO fails.
 *
 * <p>Messages carried by this exception are surfaced to the MCP client, so they must never
 * include response bodies or token material.
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}
