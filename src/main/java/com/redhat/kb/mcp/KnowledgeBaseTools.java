package com.redhat.kb.mcp;

import com.redhat.kb.service.KnowledgeBaseService;
import com.redhat.kb.dto.KnowledgeBaseArticleDto;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * MCP Tools for Red Hat Knowledge Base.
 *
 * This server provides 2 tools for searching and retrieving technical documentation:
 *
 * - searchKnowledgeBase: Find solutions and articles for technical problems
 * - getSolution: Get the full content of a KB article
 */
@ApplicationScoped
public class KnowledgeBaseTools {

    @Inject
    KnowledgeBaseService kbService;

    @Tool(description = "Search Red Hat Knowledge Base for solutions and articles. " +
            "Use technical keywords from error messages for best results. " +
            "Examples: 'CrashLoopBackOff openshift', 'etcd timeout', 'oauth authentication error'. " +
            "Filter by documentType: 'Solution' (proven fixes), 'Documentation', 'Article'. " +
            "After finding relevant results, use getSolution to get full content.")
    Uni<ToolResponse> searchKnowledgeBase(
            @ToolArg(description = "Search keywords (use error messages or technical terms)") String query,
            @ToolArg(description = "Maximum number of results", defaultValue = "10") int maxResults,
            @ToolArg(description = "Filter by product (e.g. 'OpenShift', 'RHEL')", defaultValue = "") String product,
            @ToolArg(description = "Document type: 'Solution', 'Documentation', 'Article'", defaultValue = "") String documentType) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error("Service is not configured. Set the REDHAT_TOKEN environment variable with your access token.\n" +
                       "You can generate a token at: https://access.redhat.com/management/api");
            }

            if (query == null || query.isBlank()) {
                return ToolResponse.error("You must provide a search term.");
            }

            try {
                List<KnowledgeBaseArticleDto> results = kbService.search(query, maxResults, product, documentType);

                if (results.isEmpty()) {
                    return ToolResponse.success(new TextContent(
                        "No articles found for: " + query + "\n\n" +
                        "Suggestions:\n" +
                        "- Try more generic terms\n" +
                        "- Use keywords from the error message\n" +
                        "- If no solution found, create a support case"));
                }

                StringBuilder sb = new StringBuilder();
                sb.append("=== Knowledge Base Results (").append(results.size()).append(") ===\n");
                sb.append("Search: ").append(query).append("\n\n");

                for (int i = 0; i < results.size(); i++) {
                    sb.append("--- Result ").append(i + 1).append(" ---\n");
                    sb.append(results.get(i).toSearchSummary()).append("\n");
                }

                sb.append("\nUse getSolution with the article ID to see the full content.");

                return ToolResponse.success(new TextContent(sb.toString()));
            } catch (Exception e) {
                return ToolResponse.error("ERROR searching Knowledge Base: " + e.getMessage());
            }
        });
    }

    @Tool(description = "Get the full content of a Knowledge Base article or solution. " +
            "Use after searchKnowledgeBase to get complete details including: " +
            "root cause analysis, diagnostic steps, and resolution instructions. " +
            "Example: getSolution solutionId='5049001'")
    Uni<ToolResponse> getSolution(
            @ToolArg(description = "Article/Solution ID from searchKnowledgeBase results") String solutionId) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error("Service is not configured. Set the REDHAT_TOKEN environment variable with your access token.\n" +
                       "You can generate a token at: https://access.redhat.com/management/api");
            }

            if (solutionId == null || solutionId.isBlank()) {
                return ToolResponse.error("You must provide the solution ID.");
            }

            try {
                Optional<KnowledgeBaseArticleDto> solution = kbService.getArticle(solutionId);

                if (solution.isEmpty()) {
                    return ToolResponse.error("Solution not found with ID: " + solutionId);
                }

                return ToolResponse.success(new TextContent(solution.get().toDetailedString()));
            } catch (Exception e) {
                return ToolResponse.error("ERROR getting solution: " + e.getMessage());
            }
        });
    }
}
