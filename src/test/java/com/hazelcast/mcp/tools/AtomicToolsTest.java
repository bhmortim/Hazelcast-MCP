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
class AtomicToolsTest {

    @Mock
    HazelcastInstance client;

    private AtomicTools atomicTools;
    private AtomicTools restrictedAtomicTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        atomicTools = new AtomicTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setAtomics(List.of("allowed-atomic"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedAtomicTools = new AtomicTools(client, restrictedAc);
    }

    @Test
    void fiveToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = atomicTools.getToolSpecifications();
        assertEquals(5, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = atomicTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("atomic_get"));
        assertTrue(names.contains("atomic_set"));
        assertTrue(names.contains("atomic_increment"));
        assertTrue(names.contains("atomic_decrement"));
        assertTrue(names.contains("atomic_compare_and_set"));
    }

    @Test
    void atomicGetDeniedForUnallowedAtomic() {
        McpServerFeatures.SyncToolSpecification getSpec = restrictedAtomicTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("atomic_get"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = getSpec.callHandler().apply(null,
                new CallToolRequest("atomic_get", Map.of("name", "secret-atomic")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : atomicTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : atomicTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
