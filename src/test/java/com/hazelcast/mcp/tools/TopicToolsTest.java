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
class TopicToolsTest {

    @Mock
    HazelcastInstance client;

    private TopicTools topicTools;
    private TopicTools restrictedTopicTools;

    @BeforeEach
    void setUp() {
        McpServerConfig.AccessConfig accessConfig = new McpServerConfig.AccessConfig();
        AccessController ac = new AccessController(accessConfig);
        topicTools = new TopicTools(client, ac);

        // Create restricted access controller
        McpServerConfig.AccessConfig restrictedConfig = new McpServerConfig.AccessConfig();
        restrictedConfig.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setTopics(List.of("allowed-topic"));
        restrictedConfig.setAllowlist(allowlist);
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setClear(false);
        restrictedConfig.setOperations(ops);
        AccessController restrictedAc = new AccessController(restrictedConfig);
        restrictedTopicTools = new TopicTools(client, restrictedAc);
    }

    @Test
    void twoToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = topicTools.getToolSpecifications();
        assertEquals(2, specs.size());
    }

    @Test
    void allExpectedToolNamesPresent() {
        List<String> names = topicTools.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();

        assertTrue(names.contains("topic_publish"));
        assertTrue(names.contains("topic_info"));
    }

    @Test
    void topicPublishDeniedForUnallowedTopic() {
        McpServerFeatures.SyncToolSpecification publishSpec = restrictedTopicTools.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("topic_publish"))
                .findFirst()
                .orElseThrow();

        CallToolResult result = publishSpec.callHandler().apply(null,
                new CallToolRequest("topic_publish", Map.of("topicName", "secret-topic", "message", "msg")));

        assertTrue(result.isError());
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("Access denied") || text.contains("disabled"),
                "Expected access denial message but got: " + text);
    }

    @Test
    void allToolsHaveDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : topicTools.getToolSpecifications()) {
            assertNotNull(spec.tool().description());
            assertFalse(spec.tool().description().isEmpty(),
                    "Tool " + spec.tool().name() + " has empty description");
        }
    }

    @Test
    void allToolsHaveInputSchemas() {
        for (McpServerFeatures.SyncToolSpecification spec : topicTools.getToolSpecifications()) {
            assertNotNull(spec.tool().inputSchema(),
                    "Tool " + spec.tool().name() + " has null input schema");
        }
    }
}
