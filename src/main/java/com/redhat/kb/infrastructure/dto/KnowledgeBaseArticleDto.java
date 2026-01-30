package com.redhat.kb.infrastructure.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * DTO for a Red Hat Knowledge Base article.
 * Note: Solution fields can be either List<String> (with content) or String ("subscriber_only").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeBaseArticleDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("abstract")
    private String abstractText;

    @JsonProperty("documentKind")
    private String documentKind;

    @JsonProperty("view_uri")
    private String viewUri;

    @JsonProperty("product")
    private List<String> product;

    @JsonProperty("issue")
    private List<String> issue;

    // Solution fields - can be List<String> or String ("subscriber_only")
    private List<String> solutionEnvironment;
    private List<String> solutionRootcause;
    private List<String> solutionResolution;
    private List<String> solutionDiagnosticsteps;

    @JsonProperty("lastModifiedDate")
    private String lastModifiedDate;

    @JsonProperty("createdDate")
    private String createdDate;

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAbstractText() {
        return abstractText;
    }

    public void setAbstractText(String abstractText) {
        this.abstractText = abstractText;
    }

    public String getDocumentKind() {
        return documentKind;
    }

    public void setDocumentKind(String documentKind) {
        this.documentKind = documentKind;
    }

    public String getViewUri() {
        return viewUri;
    }

    public void setViewUri(String viewUri) {
        this.viewUri = viewUri;
    }

    public List<String> getProduct() {
        return product;
    }

    public void setProduct(List<String> product) {
        this.product = product;
    }

    public List<String> getIssue() {
        return issue;
    }

    public void setIssue(List<String> issue) {
        this.issue = issue;
    }

    public List<String> getSolutionEnvironment() {
        return solutionEnvironment;
    }

    @JsonSetter("solution_environment")
    public void setSolutionEnvironment(Object value) {
        this.solutionEnvironment = convertToList(value);
    }

    public List<String> getSolutionRootcause() {
        return solutionRootcause;
    }

    @JsonSetter("solution_rootcause")
    public void setSolutionRootcause(Object value) {
        this.solutionRootcause = convertToList(value);
    }

    public List<String> getSolutionResolution() {
        return solutionResolution;
    }

    @JsonSetter("solution_resolution")
    public void setSolutionResolution(Object value) {
        this.solutionResolution = convertToList(value);
    }

    public List<String> getSolutionDiagnosticsteps() {
        return solutionDiagnosticsteps;
    }

    @JsonSetter("solution_diagnosticsteps")
    public void setSolutionDiagnosticsteps(Object value) {
        this.solutionDiagnosticsteps = convertToList(value);
    }

    /**
     * Converts a polymorphic JSON value to List<String>.
     * Handles both List<String> and single String ("subscriber_only").
     */
    @SuppressWarnings("unchecked")
    private List<String> convertToList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List) {
            return (List<String>) value;
        }
        if (value instanceof String str) {
            if ("subscriber_only".equals(str)) {
                return null;
            }
            return List.of(str);
        }
        return null;
    }

    public String getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(String lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    /**
     * Formats the article to display as a search summary.
     */
    public String toSearchSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ID: ").append(id).append("\n");
        sb.append("Type: ").append(documentKind != null ? documentKind : "N/A").append("\n");
        sb.append("Title: ").append(title).append("\n");
        sb.append("URL: ").append(viewUri).append("\n");
        if (product != null && !product.isEmpty()) {
            sb.append("Products: ").append(String.join(", ", product)).append("\n");
        }
        if (abstractText != null && !abstractText.isEmpty()) {
            String truncated = abstractText.length() > 200
                ? abstractText.substring(0, 200) + "..."
                : abstractText;
            sb.append("Summary: ").append(truncated).append("\n");
        }
        return sb.toString();
    }

    /**
     * Formats the article with full solution content.
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(documentKind != null ? documentKind : "Article").append(" ===\n\n");
        sb.append("ID: ").append(id).append("\n");
        sb.append("Title: ").append(title).append("\n");
        sb.append("URL: ").append(viewUri).append("\n");

        if (product != null && !product.isEmpty()) {
            sb.append("Products: ").append(String.join(", ", product)).append("\n");
        }

        if (solutionEnvironment != null && !solutionEnvironment.isEmpty()) {
            sb.append("\n--- Environment ---\n");
            for (String env : solutionEnvironment) {
                sb.append(env).append("\n");
            }
        }

        if (issue != null && !issue.isEmpty()) {
            sb.append("\n--- Issue ---\n");
            for (String iss : issue) {
                sb.append(iss).append("\n");
            }
        }

        if (solutionRootcause != null && !solutionRootcause.isEmpty()) {
            sb.append("\n--- Root Cause ---\n");
            for (String rc : solutionRootcause) {
                sb.append(rc).append("\n");
            }
        }

        if (solutionDiagnosticsteps != null && !solutionDiagnosticsteps.isEmpty()) {
            sb.append("\n--- Diagnostic Steps ---\n");
            for (String step : solutionDiagnosticsteps) {
                sb.append(step).append("\n");
            }
        }

        if (solutionResolution != null && !solutionResolution.isEmpty()) {
            sb.append("\n--- Resolution ---\n");
            for (String res : solutionResolution) {
                sb.append(res).append("\n");
            }
        }

        if (lastModifiedDate != null) {
            sb.append("\nLast Modified: ").append(lastModifiedDate).append("\n");
        }

        return sb.toString();
    }
}
