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
class MapToolsTest {

    @Mock
    HazelcastInstance client;

    private MapTools mapTools;
    private MapTools restrictedMapTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        mapTools = new MapTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setMaps(List.of("allowed-map"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedMapTools = new MapTools(client, restrictedAc);
    }

    @Test
    void tenToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = mapTools.getToolSpecifications();
        assertEquals(10, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = mapTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("map_get"));
        assertTrue(names.contains("map_put"));
        assertTrue(names.contains("map_delete"));
        assertTrue(names.contains("map_get_all"));
        assertTrue(names.contains("map_put_all"));
        assertTrue(names.contains("map_size"));
        assertTrue(names.contains("map_keys"));
        assertTrue(names.contains("map_values"));
        assertTrue(names.contains("map_contains_key"));
        assertTrue(names.contains("map_clear"));
    }

    @Test
    void mapGetDeniedForUnallowedMap() {
        McpServerFeatures.SyncToolSpecification getSpec = restrictedMapTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("map_get"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = getSpec.callHandler().apply(null,
                new CallToolRequest("map_get", Map.of("mapName", "secret-map", "key", "k1")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied"));
    }

    @Test
    void mapPutDeniedWhenWriteDisabled() {
        McpServerFeatures.SyncToolSpecification putSpec = restrictedMapTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("map_put"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = putSpec.callHandler().apply(null,
                new CallToolRequest("map_put", Map.of("mapName", "allowed-map", "key", "k1", "value", "v1")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Write operations are disabled"));
    }

    @Test
    void mapClearDeniedWhenClearDisabled() {
        McpServerFeatures.SyncToolSpecification clearSpec = restrictedMapTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("map_clear"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = clearSpec.callHandler().apply(null,
                new CallToolRequest("map_clear", Map.of("mapName", "allowed-map", "confirm", true)));

        assertTrue(result.isError());
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : mapTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : mapTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
