package com.redhat.kb.infrastructure.client;

/**
 * Raised when a Knowledge Base (Hydra API) call fails.
 *
 * <p>The message is written for the MCP client and already names the failure mode (expired
 * token, rate limit, upstream outage) with its status code, so it must never carry response
 * bodies or credentials.
 */
public class KnowledgeBaseException extends RuntimeException {

    public KnowledgeBaseException(String message) {
        super(message);
    }
}
