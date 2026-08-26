package com.redhat.kb.application.service;

import com.redhat.kb.infrastructure.client.CredentialResolver;
import com.redhat.kb.infrastructure.client.KnowledgeBaseClient;
import com.redhat.kb.infrastructure.client.SearchPage;
import com.redhat.kb.infrastructure.client.RedHatCredential;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Application service for Red Hat Knowledge Base operations.
 * Provides search and retrieval of technical articles, solutions, and documentation.
 *
 * <p>Each call resolves the credential of the current caller, so the Knowledge Base is read
 * with that person's entitlements rather than through a single shared account.
 */
@ApplicationScoped
public class KnowledgeBaseService {

    private final KnowledgeBaseClient kbClient;
    private final CredentialResolver credentials;

    @Inject
    public KnowledgeBaseService(KnowledgeBaseClient kbClient, CredentialResolver credentials) {
        this.kbClient = kbClient;
        this.credentials = credentials;
    }

    /**
     * Whether this server can serve a caller who supplies no token of their own.
     *
     * <p>False does not mean the server is unusable: callers that send their own token are
     * still served. It reports whether a shared fallback exists.
     */
    public boolean hasSharedCredential() {
        return credentials.hasUsableFallback();
    }

    /**
     * Resolves the credential serving the current request.
     *
     * <p>Exposed so callers can audit and rate-limit by credential before doing work.
     *
     * @throws com.redhat.kb.infrastructure.client.MissingCredentialException when none is available
     */
    public RedHatCredential currentCredential() {
        return credentials.resolve();
    }

    /**
     * Searches the Knowledge Base for articles matching the query.
     *
     * @param credential The credential whose entitlements apply
     * @param query Search terms (e.g., "CrashLoopBackOff OpenShift")
     * @param maxResults Maximum number of results to return
     * @param product Filter by product name (optional)
     * @param documentType Filter by type: Solution, Documentation, Article (optional)
     * @return List of matching articles
     */
    public SearchPage search(RedHatCredential credential, String query, int maxResults,
            String product, String documentType) {
        return kbClient.search(credential, query, maxResults, product, documentType);
    }

    /**
     * Gets the full content of a Knowledge Base article by its ID.
     *
     * @param credential The credential whose entitlements apply
     * @param articleId The article/solution ID (e.g., "5049001")
     * @return The article with full content, or empty if not found
     */
    public Optional<KnowledgeBaseArticleDto> getArticle(RedHatCredential credential, String articleId) {
        return kbClient.getArticle(credential, articleId);
    }
}
