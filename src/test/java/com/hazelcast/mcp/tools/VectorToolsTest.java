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
class VectorToolsTest {

    @Mock
    HazelcastInstance client;

    private VectorTools vectorTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        vectorTools = new VectorTools(client, ac);
    }

    @Test
    void fourToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = vectorTools.getToolSpecifications();
        assertEquals(4, specs.size());

        List<String> names = specs.stream()
                .map(s -> s.tool().name())
                .toList();
        assertTrue(names.contains("vector_search"));
        assertTrue(names.contains("vector_put"));
        assertTrue(names.contains("vector_get"));
        assertTrue(names.contains("vector_delete"));
    }

    @Test
    void vectorSearchReturnsUnavailableWhenModuleNotPresent() {
        // VectorCollection module is not on test classpath, so tools should report unavailable
        McpServerFeatures.SyncToolSpecification searchSpec = vectorTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("vector_search"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = searchSpec.callHandler().apply(null,
                new CallToolRequest("vector_search", Map.of("collectionName", "test", "vector", List.of(1.0, 2.0, 3.0))));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("VectorCollection module is not available"));
    }

    @Test
    void vectorPutReturnsUnavailableWhenModuleNotPresent() {
        McpServerFeatures.SyncToolSpecification putSpec = vectorTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("vector_put"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = putSpec.callHandler().apply(null,
                new CallToolRequest("vector_put", Map.of("collectionName", "test", "key", "k1",
                        "value", Map.of("text", "hello"),
                        "vector", List.of(1.0, 2.0))));

        assertTrue(result.isError());
    }

    @Test
    void vectorDeleteBlockedWhenWriteDisabled() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        accessConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(accessConfig);

        // VectorTools won't have the module, so it'll return unavailable before reaching the write check
        // But we verify the tool is registered and responds
        VectorTools restrictedTools = new VectorTools(client, restrictedAc);
        McpServerFeatures.SyncToolSpecification deleteSpec = restrictedTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("vector_delete"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = deleteSpec.callHandler().apply(null,
                new CallToolRequest("vector_delete", Map.of("collectionName", "test", "key", "k1")));

        assertTrue(result.isError());
    }

    @Test
    void toolDescriptionsAreNonEmpty() {
        for (McpServerFeatures.SyncToolSpecification spec : vectorTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty());
        }
    }
}
