package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.ringbuffer.ReadResultSet;
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
import java.util.concurrent.ExecutionException;

/**
 * MCP Tool implementation for Hazelcast Ringbuffer operations.
 * Supports add, read, read_many, size, and capacity operations.
 */
public class RingbufferTools {

    private static final Logger logger = LoggerFactory.getLogger(RingbufferTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public RingbufferTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                ringbufferAdd(),
                ringbufferRead(),
                ringbufferReadMany(),
                ringbufferSize(),
                ringbufferCapacity()
        );
    }

    // --- ringbuffer_add ---
    private McpServerFeatures.SyncToolSpecification ringbufferAdd() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ringbufferName": { "type": "string", "description": "Name of the ringbuffer" },
                    "value": { "description": "Value to add (any JSON)" }
                  },
                  "required": ["ringbufferName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("ringbuffer_add").description("Add a value to a Hazelcast Ringbuffer").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String ringbufferName = (String) args.get("ringbufferName");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isRingbufferAccessible(ringbufferName)) {
                            return errorResult(accessController.getDenialMessage("add", ringbufferName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("add", ringbufferName));
                        }
                        Ringbuffer<Object> ringbuffer = client.getRingbuffer(ringbufferName);
                        long sequence = ringbuffer.add(JsonSerializer.toHazelcastJson(value));
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "ringbufferName", ringbufferName,
                                "sequence", sequence
                        )));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_add", client));
                    }
                }
        );
    }

    // --- ringbuffer_read ---
    private McpServerFeatures.SyncToolSpecification ringbufferRead() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ringbufferName": { "type": "string", "description": "Name of the ringbuffer" },
                    "sequence": { "type": "integer", "description": "Sequence number to read" }
                  },
                  "required": ["ringbufferName", "sequence"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("ringbuffer_read").description("Read a single item from a Ringbuffer by sequence").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String ringbufferName = (String) args.get("ringbufferName");
                    long sequence = ((Number) args.get("sequence")).longValue();
                    try {
                        if (!accessController.isRingbufferAccessible(ringbufferName)) {
                            return errorResult(accessController.getDenialMessage("read", ringbufferName));
                        }
                        Ringbuffer<Object> ringbuffer = client.getRingbuffer(ringbufferName);
                        Object item = ringbuffer.readOne(sequence);
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "ringbufferName", ringbufferName,
                                "sequence", sequence,
                                "value", JsonSerializer.toJson(item)
                        )));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_read", client));
                    }
                }
        );
    }

    // --- ringbuffer_read_many ---
    private McpServerFeatures.SyncToolSpecification ringbufferReadMany() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ringbufferName": { "type": "string", "description": "Name of the ringbuffer" },
                    "startSequence": { "type": "integer", "description": "Starting sequence number" },
                    "minCount": { "type": "integer", "description": "Minimum number of items to read" },
                    "maxCount": { "type": "integer", "description": "Maximum number of items to read" }
                  },
                  "required": ["ringbufferName", "startSequence", "minCount", "maxCount"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("ringbuffer_read_many").description("Read multiple items from a Ringbuffer").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String ringbufferName = (String) args.get("ringbufferName");
                    long startSequence = ((Number) args.get("startSequence")).longValue();
                    int minCount = ((Number) args.get("minCount")).intValue();
                    int maxCount = ((Number) args.get("maxCount")).intValue();
                    try {
                        if (!accessController.isRingbufferAccessible(ringbufferName)) {
                            return errorResult(accessController.getDenialMessage("read", ringbufferName));
                        }
                        Ringbuffer<Object> ringbuffer = client.getRingbuffer(ringbufferName);
                        ReadResultSet<Object> resultSet = ringbuffer.readManyAsync(startSequence, minCount, maxCount, null)
                                .toCompletableFuture().get();
                        List<Object> items = new ArrayList<>();
                        for (Object item : resultSet) {
                            items.add(JsonSerializer.toJson(item));
                        }
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "ringbufferName", ringbufferName,
                                "startSequence", startSequence,
                                "itemsRead", items.size(),
                                "items", items
                        )));
                    } catch (ExecutionException | InterruptedException e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_read_many", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_read_many", client));
                    }
                }
        );
    }

    // --- ringbuffer_size ---
    private McpServerFeatures.SyncToolSpecification ringbufferSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ringbufferName": { "type": "string", "description": "Name of the ringbuffer" }
                  },
                  "required": ["ringbufferName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("ringbuffer_size").description("Get the current size of a Ringbuffer").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String ringbufferName = (String) args.get("ringbufferName");
                    try {
                        if (!accessController.isRingbufferAccessible(ringbufferName)) {
                            return errorResult(accessController.getDenialMessage("get", ringbufferName));
                        }
                        Ringbuffer<Object> ringbuffer = client.getRingbuffer(ringbufferName);
                        long size = ringbuffer.size();
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "ringbufferName", ringbufferName,
                                "size", size
                        )));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_size", client));
                    }
                }
        );
    }

    // --- ringbuffer_capacity ---
    private McpServerFeatures.SyncToolSpecification ringbufferCapacity() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ringbufferName": { "type": "string", "description": "Name of the ringbuffer" }
                  },
                  "required": ["ringbufferName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("ringbuffer_capacity").description("Get the capacity of a Ringbuffer").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String ringbufferName = (String) args.get("ringbufferName");
                    try {
                        if (!accessController.isRingbufferAccessible(ringbufferName)) {
                            return errorResult(accessController.getDenialMessage("get", ringbufferName));
                        }
                        Ringbuffer<Object> ringbuffer = client.getRingbuffer(ringbufferName);
                        long capacity = ringbuffer.capacity();
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "ringbufferName", ringbufferName,
                                "capacity", capacity
                        )));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "ringbuffer_capacity", client));
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
