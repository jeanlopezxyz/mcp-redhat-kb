package com.redhat.kb.mcp;

import java.util.Map;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkiverse.mcp.server.test.McpAssured.ToolAnnotations;
import io.quarkiverse.mcp.server.test.McpAssured.ToolInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the server over the real MCP protocol.
 *
 * <p>These assertions are a contract test for what the model actually receives: the tool
 * catalogue, the generated JSON Schema and the tool annotations are the interface an agent
 * reasons about, so a change to any of them should fail here rather than silently alter
 * how models use this server.
 *
 * <p>Runs with a placeholder token, so it covers the catalogue and the validation paths
 * without reaching the Red Hat API.
 */
@QuarkusTest
@TestProfile(McpProtocolTestProfile.class)
class KnowledgeBaseToolsProtocolTest {

    private McpStreamableTestClient client;

    @BeforeEach
    void connect() {
        client = McpAssured.newConnectedStreamableClient();
    }

    @Test
    @DisplayName("exposes exactly the two consolidated tools")
    void exposesTwoTools() {
        client.when()
                .toolsList(page -> {
                    assertEquals(4, page.size(),
                            "the catalogue should stay small; overlapping tools degrade tool selection");
                    assertNotNull(page.findByName("searchKnowledgeBase"));
                    assertNotNull(page.findByName("getArticle"));
                    assertNotNull(page.findByName("lookupCve"));
                    assertNotNull(page.findByName("getProductLifecycle"));
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("declares both tools as read-only and idempotent")
    void declaresReadOnlyAnnotations() {
        client.when()
                .toolsList(page -> {
                    for (String name : new String[] {"searchKnowledgeBase", "getArticle", "lookupCve", "getProductLifecycle"}) {
                        ToolAnnotations annotations = page.findByName(name).annotations()
                                .orElseThrow(() -> new AssertionError(name + " declares no annotations"));

                        assertTrue(annotations.readOnlyHint(), name + " must be read-only");
                        assertFalse(annotations.destructiveHint(), name + " must not be destructive");
                        assertTrue(annotations.idempotentHint(), name + " must be idempotent");
                        assertTrue(annotations.openWorldHint(), name + " queries an external system");
                    }
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("types maxResults as an integer rather than a string")
    void typesMaxResultsAsInteger() {
        client.when()
                .toolsList(page -> {
                    ToolInfo tool = page.findByName("searchKnowledgeBase");
                    JsonObject properties = tool.inputSchema().getJsonObject("properties");

                    assertEquals("integer", properties.getJsonObject("maxResults").getString("type"));
                    assertEquals("string", properties.getJsonObject("query").getString("type"));
                    // Only the query is mandatory; the filters are optional.
                    assertEquals(1, tool.inputSchema().getJsonArray("required").size());
                    assertEquals("query", tool.inputSchema().getJsonArray("required").getString(0));
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("rejects a blank query without calling the API")
    void rejectsBlankQuery() {
        client.when()
                .toolsCall("searchKnowledgeBase", Map.of("query", "   "), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().get(0).asText().text().contains("query is required"));
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("rejects a query beyond the length limit")
    void rejectsOverlongQuery() {
        client.when()
                .toolsCall("searchKnowledgeBase", Map.of("query", "a".repeat(1001)), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().get(0).asText().text().contains("too long"));
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("rejects a non-numeric article ID instead of forwarding it to the query")
    void rejectsNonNumericArticleId() {
        client.when()
                .toolsCall("getArticle", Map.of("articleId", "1 OR documentKind:Solution"), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().get(0).asText().text().contains("numeric"));
                })
                .thenAssertResults();
    }

    @Test
    @DisplayName("rejects a blank article ID")
    void rejectsBlankArticleId() {
        client.when()
                .toolsCall("getArticle", Map.of("articleId", ""), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.content().get(0).asText().text().contains("articleId is required"));
                })
                .thenAssertResults();
    }
}
