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
class QueueToolsTest {

    @Mock
    HazelcastInstance client;

    private QueueTools queueTools;
    private QueueTools restrictedQueueTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        queueTools = new QueueTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setQueues(List.of("allowed-queue"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedQueueTools = new QueueTools(client, restrictedAc);
    }

    @Test
    void sixToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = queueTools.getToolSpecifications();
        assertEquals(6, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = queueTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("queue_offer"));
        assertTrue(names.contains("queue_poll"));
        assertTrue(names.contains("queue_peek"));
        assertTrue(names.contains("queue_size"));
        assertTrue(names.contains("queue_drain"));
        assertTrue(names.contains("queue_clear"));
    }

    @Test
    void queueOfferDeniedForUnallowedQueue() {
        McpServerFeatures.SyncToolSpecification offerSpec = restrictedQueueTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("queue_offer"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = offerSpec.callHandler().apply(null,
                new CallToolRequest("queue_offer", Map.of("queueName", "secret-queue", "value", "item")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : queueTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : queueTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
