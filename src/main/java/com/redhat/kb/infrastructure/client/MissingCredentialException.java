package com.redhat.kb.infrastructure.client;

/**
 * Raised when no Red Hat credential is available for the current request.
 *
 * <p>The message is shown to the caller and must explain how to supply a token, so it
 * names the header but never any token value.
 */
public class MissingCredentialException extends RuntimeException {

    public MissingCredentialException(String message) {
        super(message);
    }
}
