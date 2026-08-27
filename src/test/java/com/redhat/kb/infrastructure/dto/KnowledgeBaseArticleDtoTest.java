package com.redhat.kb.infrastructure.dto;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hydra API types the solution_* fields loosely: they arrive either as an array of
 * strings or as the bare string "subscriber_only".
 */
class KnowledgeBaseArticleDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("deserializes solution fields given as arrays")
    void deserializesArrayForm() throws Exception {
        String json = """
                {"id":"5049001","title":"Pod stuck","solution_resolution":["step one","step two"]}
                """;

        KnowledgeBaseArticleDto dto = mapper.readValue(json, KnowledgeBaseArticleDto.class);

        assertEquals("5049001", dto.getId());
        assertEquals(List.of("step one", "step two"), dto.getSolutionResolution());
    }

    @Test
    @DisplayName("maps the subscriber_only marker to no content")
    void mapsSubscriberOnlyToNull() throws Exception {
        String json = """
                {"id":"1","solution_resolution":"subscriber_only","solution_rootcause":"subscriber_only"}
                """;

        KnowledgeBaseArticleDto dto = mapper.readValue(json, KnowledgeBaseArticleDto.class);

        assertNull(dto.getSolutionResolution());
        assertNull(dto.getSolutionRootcause());
        assertTrue(dto.isSubscriberOnly());
    }

    @Test
    @DisplayName("distinguishes withheld content from an article that simply lacks the section")
    void absentFieldIsNotSubscriberOnly() throws Exception {
        // Both leave getSolutionResolution() null, so only the flag tells them apart — and
        // the difference decides whether a token would help.
        KnowledgeBaseArticleDto dto = mapper.readValue(
                "{\"id\":\"1\",\"title\":\"No solution section\"}", KnowledgeBaseArticleDto.class);

        assertNull(dto.getSolutionResolution());
        assertFalse(dto.isSubscriberOnly());
    }

    @Test
    @DisplayName("wraps a plain string solution field into a single-element list")
    void wrapsPlainString() throws Exception {
        KnowledgeBaseArticleDto dto = mapper.readValue(
                "{\"id\":\"1\",\"solution_resolution\":\"just restart it\"}", KnowledgeBaseArticleDto.class);

        assertEquals(List.of("just restart it"), dto.getSolutionResolution());
    }

    @Test
    @DisplayName("ignores unknown fields returned by the API")
    void ignoresUnknownFields() throws Exception {
        KnowledgeBaseArticleDto dto = mapper.readValue(
                "{\"id\":\"1\",\"brandNewField\":\"whatever\"}", KnowledgeBaseArticleDto.class);

        assertEquals("1", dto.getId());
    }

    @Test
    @DisplayName("tolerates a search response whose docs array is absent")
    void tolerantOfMissingDocs() throws Exception {
        KnowledgeBaseSearchResponseDto response = mapper.readValue(
                "{\"response\":{\"numFound\":0}}", KnowledgeBaseSearchResponseDto.class);

        assertNotNull(response.getResponse());
        assertNull(response.getResponse().getDocs());
    }

    @Test
    @DisplayName("parses a well-formed search response")
    void parsesSearchResponse() throws Exception {
        String json = """
                {"response":{"numFound":1,"docs":[{"id":"5049001","title":"Pod stuck"}]}}
                """;

        KnowledgeBaseSearchResponseDto response = mapper.readValue(json, KnowledgeBaseSearchResponseDto.class);

        assertEquals(1, response.getResponse().getNumFound());
        assertTrue(response.getResponse().getDocs().size() == 1);
        assertEquals("5049001", response.getResponse().getDocs().get(0).getId());
    }
}
