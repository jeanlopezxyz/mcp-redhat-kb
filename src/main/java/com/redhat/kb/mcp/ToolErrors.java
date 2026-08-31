package com.redhat.kb.mcp;

import com.redhat.kb.infrastructure.credential.AuthenticationException;
import com.redhat.kb.infrastructure.credential.MissingCredentialException;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;

import io.quarkiverse.mcp.server.ToolResponse;
import org.jboss.logging.Logger;

/**
 * Turns a failure into the error a tool hands back.
 *
 * <p>Shared by every tool so the rules hold uniformly. Both tool classes grew their own
 * copy and the two drifted: one relayed authentication failures and the other reported them
 * as "check the server logs", which is the wrong advice for a caller who can fix the
 * problem by sending a token.
 */
final class ToolErrors {

    private static final Logger LOG = Logger.getLogger(ToolErrors.class);

    private ToolErrors() {
        // Utility class
    }

    /**
     * Builds the client-facing error.
     *
     * <p>Only our own typed exceptions are relayed: an arbitrary exception message can carry
     * a response body or a credential, so anything else is logged and answered generically.
     * Relayed messages are sanitized because they quote upstream text — the life cycle API's
     * product names, for one — and an error renders outside the untrusted fence, in the part
     * of the answer the model reads as the server's own words.
     */
    static ToolResponse toResponse(String context, Exception e) {
        if (e instanceof MissingCredentialException) {
            // An actionable configuration problem, not a server fault: no stack trace.
            LOG.debugf("Request without a usable Red Hat credential: %s", e.getMessage());
            return ToolResponse.error("Error: " + ContentSanitizer.clean(e.getMessage()));
        }

        LOG.errorf(e, "%s", context);
        if (e instanceof KnowledgeBaseException || e instanceof AuthenticationException) {
            return ToolResponse.error("Error: " + ContentSanitizer.clean(e.getMessage()));
        }
        return ToolResponse.error("Error: " + context + ". Check the server logs for details.");
    }
}
