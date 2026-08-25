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

    // Rendering for the model lives in com.redhat.kb.mcp.ArticleFormatter: it is a
    // presentation concern, and it needs sanitizing and truncation this DTO should not own.
}
