package com.hazelcast.mcp.integration;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.config.McpServerConfig;
import com.hazelcast.mcp.tools.MapTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MapTools running against a real Hazelcast cluster.
 * These tests require Docker and are automatically skipped if Docker is unavailable.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MapToolsIntegrationTest {

    @Container
    static HazelcastTestContainer hazelcast = new HazelcastTestContainer();

    private static HazelcastInstance client;
    private static MapTools mapTools;

    @BeforeAll
    static void setUp() {
        client = hazelcast.createClient();
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        accessConfig.getOperations().setClear(true);
        AccessController ac = new AccessController(accessConfig);
        mapTools = new MapTools(client, ac);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    private McpServerFeatures.SyncToolSpecification getTool(String name) {
        return mapTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private CallToolResult callTool(String name, Map<String, Object> args) {
        return getTool(name).callHandler().apply(null, new CallToolRequest(name, args));
    }

    private String getResultText(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    @Order(1)
    void mapPutStoresValue() {
        CallToolResult result = callTool("map_put",
                Map.of("mapName", "test-map",
                        "key", "user:1",
                        "value", Map.of("name", "Alice", "age", 30)));

        assertFalse(result.isError(), "Expected success but got: " + getResultText(result));
        String text = getResultText(result);
        assertTrue(text.contains("user:1"));
    }

    @Test
    @Order(2)
    void mapGetRetrievesValue() {
        CallToolResult result = callTool("map_get",
                Map.of("mapName", "test-map", "key", "user:1"));

        assertFalse(result.isError());
        String text = getResultText(result);
        assertTrue(text.contains("Alice"));
    }

    @Test
    @Order(3)
    void mapContainsKeyFindsExistingKey() {
        CallToolResult result = callTool("map_contains_key",
                Map.of("mapName", "test-map", "key", "user:1"));

        assertFalse(result.isError());
        String text = getResultText(result);
        assertTrue(text.contains("true") || text.contains("exists"));
    }

    @Test
    @Order(4)
    void mapSizeReturnsCorrectCount() {
        // Put a second entry
        callTool("map_put",
                Map.of("mapName", "test-map", "key", "user:2",
                        "value", Map.of("name", "Bob")));

        CallToolResult result = callTool("map_size",
                Map.of("mapName", "test-map"));

        assertFalse(result.isError());
        String text = getResultText(result);
        assertTrue(text.contains("2"));
    }

    @Test
    @Order(5)
    void mapKeysReturnsList() {
        CallToolResult result = callTool("map_keys",
                Map.of("mapName", "test-map"));

        assertFalse(result.isError());
        String text = getResultText(result);
        assertTrue(text.contains("user:1"));
        assertTrue(text.contains("user:2"));
    }

    @Test
    @Order(6)
    void mapDeleteRemovesEntry() {
        CallToolResult result = callTool("map_delete",
                Map.of("mapName", "test-map", "key", "user:2"));

        assertFalse(result.isError());

        // Verify key is gone
        CallToolResult getResult = callTool("map_get",
                Map.of("mapName", "test-map", "key", "user:2"));
        String text = getResultText(getResult);
        assertTrue(text.contains("null") || text.contains("not found") || text.contains("\"value\":null"));
    }

    @Test
    @Order(7)
    void mapGetAllRetrievesMultiple() {
        callTool("map_put",
                Map.of("mapName", "test-map", "key", "user:3", "value", "val3"));
        callTool("map_put",
                Map.of("mapName", "test-map", "key", "user:4", "value", "val4"));

        CallToolResult result = callTool("map_get_all",
                Map.of("mapName", "test-map", "keys", List.of("user:1", "user:3", "user:4")));

        assertFalse(result.isError());
        String text = getResultText(result);
        assertTrue(text.contains("user:1"));
        assertTrue(text.contains("user:3"));
    }

    @Test
    @Order(8)
    void mapClearRemovesAll() {
        CallToolResult result = callTool("map_clear",
                Map.of("mapName", "test-map", "confirm", true));

        assertFalse(result.isError());

        // Verify empty
        CallToolResult sizeResult = callTool("map_size",
                Map.of("mapName", "test-map"));
        assertTrue(getResultText(sizeResult).contains("0"));
    }

    @Test
    void mapClearRequiresConfirmation() {
        CallToolResult result = callTool("map_clear",
                Map.of("mapName", "test-map"));

        assertTrue(result.isError());
        assertTrue(getResultText(result).contains("confirm"));
    }
}
