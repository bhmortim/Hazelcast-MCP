package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP Tool implementation for Hazelcast Topic operations.
 * Supports publish and topic info operations.
 */
public class TopicTools {

    private static final Logger logger = LoggerFactory.getLogger(TopicTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public TopicTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                topicPublish(),
                topicInfo()
        );
    }

    // --- topic_publish ---
    private McpServerFeatures.SyncToolSpecification topicPublish() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "topicName": { "type": "string", "description": "Name of the topic" },
                    "message": { "description": "Message to publish (any JSON)" }
                  },
                  "required": ["topicName", "message"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("topic_publish").description("Publish a message to a Hazelcast Topic").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String topicName = (String) args.get("topicName");
                    Object message = args.get("message");
                    try {
                        if (!accessController.isTopicAccessible(topicName)) {
                            return errorResult(accessController.getDenialMessage("publish", topicName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("publish", topicName));
                        }
                        ITopic<Object> topic = client.getTopic(topicName);
                        topic.publish(JsonSerializer.toHazelcastJson(message));
                        return textResult(String.format("Published message to topic '%s'", topicName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "topic_publish", client));
                    }
                }
        );
    }

    // --- topic_info ---
    private McpServerFeatures.SyncToolSpecification topicInfo() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "topicName": { "type": "string", "description": "Name of the topic" }
                  },
                  "required": ["topicName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("topic_info").description("Get statistics about a Hazelcast Topic").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String topicName = (String) args.get("topicName");
                    try {
                        if (!accessController.isTopicAccessible(topicName)) {
                            return errorResult(accessController.getDenialMessage("get", topicName));
                        }
                        ITopic<Object> topic = client.getTopic(topicName);
                        try {
                            Object stats = topic.getLocalTopicStats();
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("topicName", topicName);
                            if (stats != null) {
                                info.put("stats", JsonSerializer.toJson(stats));
                            } else {
                                info.put("stats", "Local topic stats not available on client");
                            }
                            return textResult(JsonSerializer.toJsonString(info));
                        } catch (Exception statsEx) {
                            // Stats may not be available, return basic info
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("topicName", topicName);
                            info.put("stats", "Topic stats not available");
                            return textResult(JsonSerializer.toJsonString(info));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "topic_info", client));
                    }
                }
        );
    }

    // --- Helpers ---

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true, null, null);
    }
}
