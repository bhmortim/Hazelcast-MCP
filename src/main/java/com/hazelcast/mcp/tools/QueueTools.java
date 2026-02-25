package com.hazelcast.mcp.tools;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
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
 * MCP Tool implementations for Hazelcast IQueue operations.
 * Covers: offer, poll, peek, size, drain, clear.
 */
public class QueueTools {

    private static final Logger logger = LoggerFactory.getLogger(QueueTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public QueueTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                queueOffer(),
                queuePoll(),
                queuePeek(),
                queueSize(),
                queueDrain(),
                queueClear()
        );
    }

    // --- queue_offer ---
    private McpServerFeatures.SyncToolSpecification queueOffer() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" },
                    "value": { "description": "Value to add to the queue (any JSON)" }
                  },
                  "required": ["queueName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_offer").description("Add item to queue").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("offer", queueName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("offer", queueName));
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        Object hzValue = JsonSerializer.toHazelcastJson(value);
                        boolean success = queue.offer(hzValue);
                        if (success) {
                            return textResult(String.format("Successfully added item to queue '%s'", queueName));
                        } else {
                            return textResult(String.format("Failed to add item to queue '%s' (queue may be full)", queueName));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_offer", client));
                    }
                }
        );
    }

    // --- queue_poll ---
    private McpServerFeatures.SyncToolSpecification queuePoll() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" }
                  },
                  "required": ["queueName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_poll").description("Remove and return head element from queue").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("poll", queueName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("poll", queueName));
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        Object value = queue.poll();
                        if (value != null) {
                            Object jsonValue = JsonSerializer.toJson(value);
                            return textResult(JsonSerializer.toJsonString(Map.of(
                                    "queue", queueName,
                                    "value", jsonValue)));
                        } else {
                            return textResult(String.format("Queue '%s' is empty", queueName));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_poll", client));
                    }
                }
        );
    }

    // --- queue_peek ---
    private McpServerFeatures.SyncToolSpecification queuePeek() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" }
                  },
                  "required": ["queueName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_peek").description("View head element without removing from queue").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("get", queueName));
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        Object value = queue.peek();
                        if (value != null) {
                            Object jsonValue = JsonSerializer.toJson(value);
                            return textResult(JsonSerializer.toJsonString(Map.of(
                                    "queue", queueName,
                                    "value", jsonValue)));
                        } else {
                            return textResult(String.format("Queue '%s' is empty", queueName));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_peek", client));
                    }
                }
        );
    }

    // --- queue_size ---
    private McpServerFeatures.SyncToolSpecification queueSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" }
                  },
                  "required": ["queueName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_size").description("Get the number of elements in a queue").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("get", queueName));
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        return textResult(String.format("Queue '%s' contains %d elements", queueName, queue.size()));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_size", client));
                    }
                }
        );
    }

    // --- queue_drain ---
    private McpServerFeatures.SyncToolSpecification queueDrain() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" },
                    "maxElements": { "type": "integer", "description": "Maximum elements to drain (default 100)" }
                  },
                  "required": ["queueName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_drain").description("Remove multiple items from queue").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    int maxElements = args.get("maxElements") != null ? ((Number) args.get("maxElements")).intValue() : 100;
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("drain", queueName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("drain", queueName));
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        List<Object> drained = new ArrayList<>();
                        queue.drainTo(drained, maxElements);
                        List<Object> jsonDrained = new ArrayList<>();
                        for (Object item : drained) {
                            jsonDrained.add(JsonSerializer.toJson(item));
                        }
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "queue", queueName,
                                "drained", jsonDrained.size(),
                                "maxElements", maxElements,
                                "items", jsonDrained)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_drain", client));
                    }
                }
        );
    }

    // --- queue_clear ---
    private McpServerFeatures.SyncToolSpecification queueClear() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "queueName": { "type": "string", "description": "Name of the Hazelcast IQueue" },
                    "confirm": { "type": "boolean", "description": "Must be true to confirm deletion of all items" }
                  },
                  "required": ["queueName", "confirm"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("queue_clear").description("Remove all items from a queue (requires confirmation)").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String queueName = (String) args.get("queueName");
                    Boolean confirm = (Boolean) args.get("confirm");
                    try {
                        if (!accessController.isQueueAccessible(queueName)) {
                            return errorResult(accessController.getDenialMessage("clear", queueName));
                        }
                        if (!accessController.isClearAllowed()) {
                            return errorResult(accessController.getDenialMessage("clear", queueName));
                        }
                        if (confirm == null || !confirm) {
                            return errorResult("Safety check: set 'confirm: true' to clear all items from queue '"
                                    + queueName + "'. This operation cannot be undone.");
                        }
                        IQueue<Object> queue = client.getQueue(queueName);
                        int sizeBefore = queue.size();
                        queue.clear();
                        return textResult(String.format("Cleared queue '%s'. Removed %d items.", queueName, sizeBefore));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "queue_clear", client));
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
