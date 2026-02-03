package com.redhat.kb.mcp;

import com.redhat.kb.application.service.KnowledgeBaseService;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_MAX_RESULTS;
import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_PRODUCT;
import static com.redhat.kb.KnowledgeBaseConstants.ERROR_NOT_CONFIGURED;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_QUERY_LENGTH;
import static com.redhat.kb.KnowledgeBaseConstants.MAX_RESULTS;
import static com.redhat.kb.KnowledgeBaseConstants.MIN_RESULTS;

/**
 * MCP Tools for Red Hat Knowledge Base.
 */
@ApplicationScoped
public class KnowledgeBaseTools {

    private static final Logger LOG = Logger.getLogger(KnowledgeBaseTools.class);

    @Inject
    KnowledgeBaseService kbService;

    @Tool(description = "Search Red Hat Knowledge Base for solutions and articles. "
            + "Use error messages or technical keywords. Filter by product or documentType.")
    public Uni<ToolResponse> searchKnowledgeBase(
            @ToolArg(description = "Search keywords") String query,
            @ToolArg(description = "Max results 1-50 (default: 10)", defaultValue = "") String maxResultsStr,
            @ToolArg(description = "Product filter: 'Red Hat OpenShift Container Platform', 'Red Hat Enterprise Linux' (default: Red Hat OpenShift Container Platform)", defaultValue = "") String product,
            @ToolArg(description = "Type: 'Solution', 'Documentation', 'Article'", defaultValue = "") String documentType) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error(ERROR_NOT_CONFIGURED);
            }
            if (query == null || query.isBlank()) {
                return ToolResponse.error("Error: query is required");
            }
            if (query.length() > MAX_QUERY_LENGTH) {
                return ToolResponse.error("Error: query too long (max " + MAX_QUERY_LENGTH + " chars)");
            }

            try {
                int limit = parseMaxResults(maxResultsStr);
                String validProduct = (product == null || product.isBlank()) ? "" : product.trim();
                String validDocType = (documentType == null || documentType.isBlank()) ? "" : documentType.trim();

                List<KnowledgeBaseArticleDto> results = kbService.search(query.trim(), limit, validProduct, validDocType);

                if (results.isEmpty()) {
                    return ToolResponse.success(new TextContent("No results found for: " + query));
                }
                return ToolResponse.success(new TextContent(formatResults(results, "Search", query)));
            } catch (Exception e) {
                LOG.errorf("Search failed: %s", e.getMessage());
                return ToolResponse.error(formatError("Search failed", e));
            }
        });
    }

    @Tool(description = "Get full content of a Knowledge Base article. Use article ID from search results.")
    public Uni<ToolResponse> getSolution(@ToolArg(description = "Article ID (numeric)") String solutionId) {
        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error(ERROR_NOT_CONFIGURED);
            }
            if (solutionId == null || solutionId.isBlank()) {
                return ToolResponse.error("Error: solutionId is required");
            }

            try {
                Optional<KnowledgeBaseArticleDto> solution = kbService.getArticle(solutionId.trim());
                if (solution.isEmpty()) {
                    return ToolResponse.error("Error: Solution not found - " + solutionId);
                }
                return ToolResponse.success(new TextContent(solution.get().toDetailedString()));
            } catch (Exception e) {
                LOG.errorf("Get solution failed: %s", e.getMessage());
                return ToolResponse.error(formatError("Get solution failed", e));
            }
        });
    }

    @Tool(description = "Search for solutions to an error message. Optimized for troubleshooting.")
    public Uni<ToolResponse> troubleshootError(
            @ToolArg(description = "Error message") String errorMessage,
            @ToolArg(description = "Product (default: Red Hat OpenShift Container Platform)", defaultValue = "") String product) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error(ERROR_NOT_CONFIGURED);
            }
            if (errorMessage == null || errorMessage.isBlank()) {
                return ToolResponse.error("Error: errorMessage is required");
            }
            if (errorMessage.length() > MAX_QUERY_LENGTH) {
                return ToolResponse.error("Error: errorMessage too long (max " + MAX_QUERY_LENGTH + " chars)");
            }

            try {
                String validProduct = (product == null || product.isBlank()) ? DEFAULT_PRODUCT : product.trim();
                List<KnowledgeBaseArticleDto> results = kbService.searchForError(errorMessage.trim(), validProduct);

                if (results.isEmpty()) {
                    return ToolResponse.success(new TextContent("No solutions found for error: " + errorMessage));
                }
                return ToolResponse.success(new TextContent(formatResults(results, "Error", errorMessage)));
            } catch (Exception e) {
                LOG.errorf("Troubleshoot failed: %s", e.getMessage());
                return ToolResponse.error(formatError("Troubleshoot failed", e));
            }
        });
    }

    @Tool(description = "Find KB solutions for a Prometheus/OpenShift alert name.")
    public Uni<ToolResponse> findSolutionForAlert(
            @ToolArg(description = "Alert name (e.g., 'KubePodCrashLooping')") String alertName,
            @ToolArg(description = "Product (default: Red Hat OpenShift Container Platform)", defaultValue = "") String product) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error(ERROR_NOT_CONFIGURED);
            }
            if (alertName == null || alertName.isBlank()) {
                return ToolResponse.error("Error: alertName is required");
            }
            if (alertName.length() > MAX_QUERY_LENGTH) {
                return ToolResponse.error("Error: alertName too long (max " + MAX_QUERY_LENGTH + " chars)");
            }

            try {
                String validProduct = (product == null || product.isBlank()) ? DEFAULT_PRODUCT : product.trim();
                List<KnowledgeBaseArticleDto> results = kbService.searchForError(alertName.trim(), validProduct);

                if (results.isEmpty()) {
                    return ToolResponse.success(new TextContent("No solutions found for alert: " + alertName));
                }
                return ToolResponse.success(new TextContent(formatResults(results, "Alert", alertName)));
            } catch (Exception e) {
                LOG.errorf("Find solution for alert failed: %s", e.getMessage());
                return ToolResponse.error(formatError("Find solution failed", e));
            }
        });
    }

    @Tool(description = "Search Red Hat documentation for how-to guides and best practices.")
    public Uni<ToolResponse> searchDocumentation(
            @ToolArg(description = "Topic to search") String topic,
            @ToolArg(description = "Product (default: Red Hat OpenShift Container Platform)", defaultValue = "") String product) {

        return Uni.createFrom().item(() -> {
            if (!kbService.isConfigured()) {
                return ToolResponse.error(ERROR_NOT_CONFIGURED);
            }
            if (topic == null || topic.isBlank()) {
                return ToolResponse.error("Error: topic is required");
            }
            if (topic.length() > MAX_QUERY_LENGTH) {
                return ToolResponse.error("Error: topic too long (max " + MAX_QUERY_LENGTH + " chars)");
            }

            try {
                String validProduct = (product == null || product.isBlank()) ? DEFAULT_PRODUCT : product.trim();
                List<KnowledgeBaseArticleDto> results = kbService.searchDocumentation(topic.trim(), validProduct);

                if (results.isEmpty()) {
                    return ToolResponse.success(new TextContent("No documentation found for: " + topic));
                }
                return ToolResponse.success(new TextContent(formatResults(results, "Topic", topic)));
            } catch (Exception e) {
                LOG.errorf("Search documentation failed: %s", e.getMessage());
                return ToolResponse.error(formatError("Search documentation failed", e));
            }
        });
    }

    private String formatResults(List<KnowledgeBaseArticleDto> results, String label, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Results for ").append(label).append(": ").append(value).append(" ===\n");
        sb.append("Found: ").append(results.size()).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            sb.append("--- ").append(i + 1).append(" ---\n");
            sb.append(results.get(i).toSearchSummary()).append("\n");
        }

        sb.append("\nUse getSolution with article ID for full content.");
        return sb.toString();
    }

    private String formatError(String message, Exception e) {
        String detail = e.getMessage();
        return (detail == null || detail.isBlank())
                ? "Error: " + message
                : "Error: " + message + " - " + detail;
    }

    private int parseMaxResults(String maxResultsStr) {
        if (maxResultsStr == null || maxResultsStr.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int value = Integer.parseInt(maxResultsStr.trim());
            return Math.max(MIN_RESULTS, Math.min(MAX_RESULTS, value));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }
}
