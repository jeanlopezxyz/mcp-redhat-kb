package com.redhat.kb.mcp;

import java.util.List;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkiverse.mcp.server.test.McpAssured.ToolInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the tool catalogue does not depend on which connection asks for it.
 *
 * <p>The 2026-07-28 specification states the set of tools <em>"MUST NOT vary
 * per-connection"</em> and that servers <em>"SHOULD return tools in a deterministic
 * order"</em>. Both matter for the same reason: clients cache the catalogue and models
 * reason over it — a server that shows different tools, or the same tools shuffled, to
 * different connections makes that cache wrong and tool selection nondeterministic.
 * The assertions compare full ordered name lists rather than sets, so a reordering
 * regression fails as loudly as a missing tool.
 */
@QuarkusTest
@TestProfile(McpProtocolTestProfile.class)
class ToolsListInvarianceTest {

    @Test
    @DisplayName("serves the same tools in the same order to two distinct connections")
    void listsIdenticalToolsAcrossConnections() {
        // Two independent clients, each with its own initialize handshake: the closest
        // this test harness gets to two unrelated callers connecting to the server.
        List<String> first = toolNamesSeenBy(McpAssured.newConnectedStreamableClient());
        List<String> second = toolNamesSeenBy(McpAssured.newConnectedStreamableClient());

        assertThat(second)
                .as("the tool set must not vary per-connection, and its order should be deterministic")
                .containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("serves the same order on repeated listings over one connection")
    void listsIdenticalToolsOnRepeatedCalls() {
        // Determinism within a connection is the weaker half of the same promise: if the
        // order shifted between calls here, the cross-connection assertion above would
        // only pass by luck.
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        List<String> first = toolNamesSeenBy(client);
        List<String> second = toolNamesSeenBy(client);

        assertThat(second).containsExactlyElementsOf(first);
    }

    /** Order is the point of these tests, so the names are kept as an ordered list. */
    private static List<String> toolNamesSeenBy(McpStreamableTestClient client) {
        var names = new java.util.ArrayList<String>();
        client.when()
                .toolsList(page -> page.tools().stream().map(ToolInfo::name).forEach(names::add))
                .thenAssertResults();
        assertThat(names).as("a connected client must see a non-empty catalogue").isNotEmpty();
        return List.copyOf(names);
    }
}
