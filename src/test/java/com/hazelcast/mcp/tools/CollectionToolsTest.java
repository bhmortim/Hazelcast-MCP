package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.config.McpServerConfig;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CollectionToolsTest {

    @Mock
    HazelcastInstance client;

    private CollectionTools collectionTools;
    private CollectionTools restrictedCollectionTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        collectionTools = new CollectionTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setLists(List.of("allowed-list"));
        allowlist.setSets(List.of("allowed-set"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedCollectionTools = new CollectionTools(client, restrictedAc);
    }

    @Test
    void tenToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = collectionTools.getToolSpecifications();
        assertEquals(10, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = collectionTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        // List tools
        assertTrue(names.contains("list_add"));
        assertTrue(names.contains("list_get"));
        assertTrue(names.contains("list_remove"));
        assertTrue(names.contains("list_size"));
        assertTrue(names.contains("list_sublist"));

        // Set tools
        assertTrue(names.contains("set_add"));
        assertTrue(names.contains("set_remove"));
        assertTrue(names.contains("set_contains"));
        assertTrue(names.contains("set_size"));
        assertTrue(names.contains("set_get_all"));
    }

    @Test
    void listAddDeniedForUnallowedList() {
        McpServerFeatures.SyncToolSpecification addSpec = restrictedCollectionTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("list_add"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = addSpec.callHandler().apply(null,
                new CallToolRequest("list_add", Map.of("listName", "secret-list", "value", "item")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void setAddDeniedForUnallowedSet() {
        McpServerFeatures.SyncToolSpecification addSpec = restrictedCollectionTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("set_add"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = addSpec.callHandler().apply(null,
                new CallToolRequest("set_add", Map.of("setName", "secret-set", "value", "item")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : collectionTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : collectionTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
