package com.redhat.kb.application.service;

import com.redhat.kb.infrastructure.config.RedHatApiConfig;
import com.redhat.kb.infrastructure.client.KnowledgeBaseClient;
import com.redhat.kb.infrastructure.client.RedHatAuthClient;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_MAX_RESULTS;

/**
 * Application service for Red Hat Knowledge Base operations.
 * Provides search and retrieval of technical articles, solutions, and documentation.
 */
@ApplicationScoped
public class KnowledgeBaseService {

    private final RedHatApiConfig config;
    private final KnowledgeBaseClient kbClient;
    private final RedHatAuthClient authClient;

    @Inject
    public KnowledgeBaseService(RedHatApiConfig config, KnowledgeBaseClient kbClient, RedHatAuthClient authClient) {
        this.config = config;
        this.kbClient = kbClient;
        this.authClient = authClient;
    }

    /**
     * Verifies if the service is correctly configured.
     */
    public boolean isConfigured() {
        return authClient.isConfigured();
    }

    /**
     * Searches the Knowledge Base for articles matching the query.
     *
     * @param query Search terms (e.g., "CrashLoopBackOff OpenShift")
     * @param maxResults Maximum number of results to return (default: 10)
     * @param product Filter by product name (optional)
     * @param documentType Filter by type: Solution, Documentation, Article (optional)
     * @return List of matching articles
     */
    public List<KnowledgeBaseArticleDto> search(String query, int maxResults, String product, String documentType) {
        if (!isConfigured()) {
            return Collections.emptyList();
        }

        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        return kbClient.search(query, limit, product, documentType);
    }

    /**
     * Searches the Knowledge Base without document type filter.
     */
    public List<KnowledgeBaseArticleDto> search(String query, int maxResults, String product) {
        return search(query, maxResults, product, null);
    }

    /**
     * Searches the Knowledge Base with default settings.
     */
    public List<KnowledgeBaseArticleDto> search(String query) {
        return search(query, DEFAULT_MAX_RESULTS, null, null);
    }

    /**
     * Gets the full content of a Knowledge Base article by its ID.
     *
     * @param articleId The article/solution ID (e.g., "5049001")
     * @return The article with full content, or empty if not found
     */
    public Optional<KnowledgeBaseArticleDto> getArticle(String articleId) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        if (articleId == null || articleId.isBlank()) {
            return Optional.empty();
        }

        return kbClient.getSolution(articleId);
    }

    /**
     * Searches for solutions related to a specific error message.
     * Optimized for troubleshooting scenarios.
     *
     * @param errorMessage The error message to search for
     * @param product Optional product filter
     * @return List of solutions that may help resolve the error
     */
    public List<KnowledgeBaseArticleDto> searchForError(String errorMessage, String product) {
        return search(errorMessage, DEFAULT_MAX_RESULTS, product, "Solution");
    }

    /**
     * Searches for documentation articles.
     *
     * @param topic The documentation topic
     * @param product Optional product filter
     * @return List of documentation articles
     */
    public List<KnowledgeBaseArticleDto> searchDocumentation(String topic, String product) {
        return search(topic, DEFAULT_MAX_RESULTS, product, "Documentation");
    }
}
