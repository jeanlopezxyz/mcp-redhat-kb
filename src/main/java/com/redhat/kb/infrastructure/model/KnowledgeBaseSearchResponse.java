package com.redhat.kb.infrastructure.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for Red Hat Knowledge Base search response (Hydra API).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeBaseSearchResponse {

    @JsonProperty("response")
    private Response response;

    public Response getResponse() {
        return response;
    }

    public void setResponse(Response response) {
        this.response = response;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        @JsonProperty("numFound")
        private int numFound;

        @JsonProperty("docs")
        private List<KnowledgeBaseArticle> docs;

        public int getNumFound() {
            return numFound;
        }

        public void setNumFound(int numFound) {
            this.numFound = numFound;
        }

        public List<KnowledgeBaseArticle> getDocs() {
            return docs;
        }

        public void setDocs(List<KnowledgeBaseArticle> docs) {
            this.docs = docs;
        }
    }
}
