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
class RingbufferToolsTest {

    @Mock
    HazelcastInstance client;

    private RingbufferTools ringbufferTools;
    private RingbufferTools restrictedRingbufferTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        ringbufferTools = new RingbufferTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setRingbuffers(List.of("allowed-ringbuffer"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedRingbufferTools = new RingbufferTools(client, restrictedAc);
    }

    @Test
    void fiveToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = ringbufferTools.getToolSpecifications();
        assertEquals(5, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = ringbufferTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("ringbuffer_add"));
        assertTrue(names.contains("ringbuffer_read"));
        assertTrue(names.contains("ringbuffer_read_many"));
        assertTrue(names.contains("ringbuffer_size"));
        assertTrue(names.contains("ringbuffer_capacity"));
    }

    @Test
    void ringbufferAddDeniedForUnallowedRingbuffer() {
        McpServerFeatures.SyncToolSpecification addSpec = restrictedRingbufferTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("ringbuffer_add"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = addSpec.callHandler().apply(null,
                new CallToolRequest("ringbuffer_add", Map.of("ringbufferName", "secret-ringbuffer", "value", "item")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : ringbufferTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : ringbufferTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
