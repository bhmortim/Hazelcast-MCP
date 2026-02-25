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
class MultiMapToolsTest {

    @Mock
    HazelcastInstance client;

    private MultiMapTools multiMapTools;
    private MultiMapTools restrictedMultiMapTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        multiMapTools = new MultiMapTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setMultimaps(List.of("allowed-multimap"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedMultiMapTools = new MultiMapTools(client, restrictedAc);
    }

    @Test
    void sevenToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = multiMapTools.getToolSpecifications();
        assertEquals(7, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = multiMapTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("multimap_put"));
        assertTrue(names.contains("multimap_get"));
        assertTrue(names.contains("multimap_remove"));
        assertTrue(names.contains("multimap_keys"));
        assertTrue(names.contains("multimap_values"));
        assertTrue(names.contains("multimap_size"));
        assertTrue(names.contains("multimap_value_count"));
    }

    @Test
    void multiMapPutDeniedForUnallowedMultiMap() {
        McpServerFeatures.SyncToolSpecification putSpec = restrictedMultiMapTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("multimap_put"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = putSpec.callHandler().apply(null,
                new CallToolRequest("multimap_put", Map.of("multiMapName", "secret-multimap", "key", "k1", "value", "v1")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : multiMapTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : multiMapTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
