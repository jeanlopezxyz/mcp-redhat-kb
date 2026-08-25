package com.redhat.kb.mcp;

import java.util.Optional;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Records who invoked which tool.
 *
 * <p>Because the Knowledge Base is read with a credential this server holds, Red Hat's own
 * audit trail cannot attribute a call to the person who made it. This log closes that gap,
 * and is what makes per-identity rate limiting and incident review possible.
 *
 * <p>Entries carry identifiers only — the Keycloak subject and a credential fingerprint —
 * never tokens, and never the caller's full query, which can contain internal hostnames or
 * error text from their systems.
 */
@ApplicationScoped
public class ToolAuditLog {

    private static final Logger LOG = Logger.getLogger("com.redhat.kb.audit");

    /** Truncation bound for the query preview kept in the audit record. */
    private static final int QUERY_PREVIEW_CHARS = 60;

    private final Instance<SecurityIdentity> identity;
    private final Instance<HttpServerRequest> request;

    @Inject
    public ToolAuditLog(Instance<SecurityIdentity> identity, Instance<HttpServerRequest> request) {
        this.identity = identity;
        this.request = request;
    }

    /**
     * Records a tool invocation.
     *
     * @param tool the tool name
     * @param argument the primary argument, truncated before it is written
     * @param credentialFingerprint identifies the credential used, never the token itself
     */
    public void record(String tool, String argument, String credentialFingerprint) {
        LOG.infof("tool=%s subject=%s source=%s credential=%s arg=\"%s\"",
                tool, subject(), sourceAddress(), credentialFingerprint, preview(argument));
    }

    /**
     * Records a refused invocation, so repeated failures are visible.
     */
    public void recordDenied(String tool, String reason) {
        LOG.warnf("tool=%s subject=%s source=%s denied=\"%s\"",
                tool, subject(), sourceAddress(), reason);
    }

    /**
     * The authenticated principal, or {@code anonymous} when the transport carries no
     * identity (stdio, or HTTP with authentication switched off).
     */
    private String subject() {
        try {
            if (identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous()) {
                    return current.getPrincipal().getName();
                }
            }
        } catch (RuntimeException e) {
            // No identity in scope; fall through.
        }
        return "anonymous";
    }

    private String sourceAddress() {
        try {
            if (request.isResolvable()) {
                return request.get().remoteAddress().hostAddress();
            }
        } catch (RuntimeException e) {
            // No active HTTP request, as on stdio.
        }
        return "local";
    }

    /**
     * Keeps a short, quote-safe excerpt so an audit line stays greppable and cannot be
     * broken up by the argument's own characters.
     */
    private static String preview(String argument) {
        String text = Optional.ofNullable(argument).orElse("").replaceAll("[\"\\n\\r]", " ").strip();
        return text.length() <= QUERY_PREVIEW_CHARS
                ? text
                : text.substring(0, QUERY_PREVIEW_CHARS) + "...";
    }
}
