package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.hazelcast.multimap.MultiMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Tool implementations for Hazelcast MultiMap operations.
 * Covers: multimap_put, multimap_get, multimap_remove, multimap_keys,
 *         multimap_values, multimap_size, multimap_value_count.
 */
public class MultiMapTools {

    private static final Logger logger = LoggerFactory.getLogger(MultiMapTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public MultiMapTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                multiMapPut(),
                multiMapGet(),
                multiMapRemove(),
                multiMapKeys(),
                multiMapValues(),
                multiMapSize(),
                multiMapValueCount()
        );
    }

    // --- multimap_put ---
    private McpServerFeatures.SyncToolSpecification multiMapPut() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "key": { "type": "string", "description": "Key to store value for" },
                    "value": { "description": "Value to add (allows multiple values per key)" }
                  },
                  "required": ["multiMapName", "key", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_put").description("Add value for key (allows multiple values per key)").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    String key = (String) args.get("key");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("put", multiMapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("put", multiMapName));
                        }
                        MultiMap<String, HazelcastJsonValue> multiMap = client.getMultiMap(multiMapName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        boolean added = multiMap.put(key, hzValue);
                        return textResult(String.format("Added value for key '%s' in multimap '%s': %s", key, multiMapName, added ? "success" : "failed"));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_put", client));
                    }
                }
        );
    }

    // --- multimap_get ---
    private McpServerFeatures.SyncToolSpecification multiMapGet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "key": { "type": "string", "description": "Key to retrieve values for" }
                  },
                  "required": ["multiMapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_get").description("Get all values for key").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    String key = (String) args.get("key");
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("get", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        Collection<Object> values = multiMap.get(key);
                        List<Object> jsonValues = values.stream()
                                .map(JsonSerializer::toJson)
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "multimap", multiMapName,
                                "key", key,
                                "count", jsonValues.size(),
                                "values", jsonValues)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_get", client));
                    }
                }
        );
    }

    // --- multimap_remove ---
    private McpServerFeatures.SyncToolSpecification multiMapRemove() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "key": { "type": "string", "description": "Key to remove from" },
                    "value": { "description": "Optional value to remove. If omitted, all values for key are removed" }
                  },
                  "required": ["multiMapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_remove").description("Remove entry").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    String key = (String) args.get("key");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("remove", multiMapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("remove", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        if (value != null) {
                            HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                            boolean removed = multiMap.remove(key, hzValue);
                            return textResult(String.format("Removed value for key '%s' from multimap '%s': %s", key, multiMapName, removed ? "success" : "not found"));
                        } else {
                            Collection<Object> removed = multiMap.remove(key);
                            List<Object> jsonValues = removed.stream()
                                    .map(JsonSerializer::toJson)
                                    .collect(Collectors.toList());
                            return textResult(JsonSerializer.toJsonString(Map.of(
                                    "multimap", multiMapName,
                                    "key", key,
                                    "removedCount", jsonValues.size(),
                                    "removedValues", jsonValues)));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_remove", client));
                    }
                }
        );
    }

    // --- multimap_keys ---
    private McpServerFeatures.SyncToolSpecification multiMapKeys() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "limit": { "type": "integer", "description": "Max keys to return (default 100)", "default": 100 }
                  },
                  "required": ["multiMapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_keys").description("List all keys").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("get", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        Set<String> allKeys = multiMap.keySet();
                        List<String> keys = allKeys.stream().limit(limit).collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "multimap", multiMapName,
                                "totalKeys", allKeys.size(),
                                "returned", keys.size(),
                                "limit", limit,
                                "keys", keys)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_keys", client));
                    }
                }
        );
    }

    // --- multimap_values ---
    private McpServerFeatures.SyncToolSpecification multiMapValues() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "limit": { "type": "integer", "description": "Max values to return (default 100)", "default": 100 }
                  },
                  "required": ["multiMapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_values").description("List all values").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("get", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        int totalSize = multiMap.size();
                        List<Object> values = multiMap.values().stream()
                                .limit(limit)
                                .map(JsonSerializer::toJson)
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "multimap", multiMapName,
                                "totalEntries", totalSize,
                                "returned", values.size(),
                                "limit", limit,
                                "values", values)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_values", client));
                    }
                }
        );
    }

    // --- multimap_size ---
    private McpServerFeatures.SyncToolSpecification multiMapSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" }
                  },
                  "required": ["multiMapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_size").description("Total entry count").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("get", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        return textResult(String.format("MultiMap '%s' contains %d entries", multiMapName, multiMap.size()));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_size", client));
                    }
                }
        );
    }

    // --- multimap_value_count ---
    private McpServerFeatures.SyncToolSpecification multiMapValueCount() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "multiMapName": { "type": "string", "description": "Name of the Hazelcast MultiMap" },
                    "key": { "type": "string", "description": "Key to count values for" }
                  },
                  "required": ["multiMapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("multimap_value_count").description("Count values for a specific key").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String multiMapName = (String) args.get("multiMapName");
                    String key = (String) args.get("key");
                    try {
                        if (!accessController.isMultiMapAccessible(multiMapName)) {
                            return errorResult(accessController.getDenialMessage("get", multiMapName));
                        }
                        MultiMap<String, Object> multiMap = client.getMultiMap(multiMapName);
                        int count = multiMap.valueCount(key);
                        return textResult(String.format("Key '%s' in multimap '%s' has %d values", key, multiMapName, count));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "multimap_value_count", client));
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
