package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MCP Tool implementations for Hazelcast IMap operations (P0-1).
 * Covers: get, put, delete, get_all, put_all, size, keys, values, contains_key, clear.
 */
public class MapTools {

    private static final Logger logger = LoggerFactory.getLogger(MapTools.class);

    private final HazelcastInstance client;
    private final AccessController accessController;

    public MapTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                mapGet(),
                mapPut(),
                mapDelete(),
                mapGetAll(),
                mapPutAll(),
                mapSize(),
                mapKeys(),
                mapValues(),
                mapContainsKey(),
                mapClear()
        );
    }

    // --- map_get ---
    private McpServerFeatures.SyncToolSpecification mapGet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to retrieve" }
                  },
                  "required": ["mapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_get", "Retrieve a value from a Hazelcast Map by key", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        Object value = map.get(key);
                        if (value == null) {
                            return textResult(String.format("Key '%s' not found in map '%s'. Map size: %d",
                                    key, mapName, map.size()));
                        }
                        Object jsonValue = JsonSerializer.toJson(value);
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "map", mapName, "key", key, "value", jsonValue)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_get", client));
                    }
                }
        );
    }

    // --- map_put ---
    private McpServerFeatures.SyncToolSpecification mapPut() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to store" },
                    "value": { "description": "Value to store (any JSON)" },
                    "ttl": { "type": "integer", "description": "Time-to-live in seconds (optional)" }
                  },
                  "required": ["mapName", "key", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_put", "Store a key-value pair in a Hazelcast Map", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    Object value = args.get("value");
                    Integer ttl = args.get("ttl") != null ? ((Number) args.get("ttl")).intValue() : null;
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("put", mapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("put", mapName));
                        }
                        IMap<String, HazelcastJsonValue> map = client.getMap(mapName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        if (ttl != null && ttl > 0) {
                            map.put(key, hzValue, ttl, TimeUnit.SECONDS);
                        } else {
                            map.put(key, hzValue);
                        }
                        return textResult(String.format("Stored key '%s' in map '%s'%s",
                                key, mapName, ttl != null ? " (TTL: " + ttl + "s)" : ""));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_put", client));
                    }
                }
        );
    }

    // --- map_delete ---
    private McpServerFeatures.SyncToolSpecification mapDelete() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to remove" }
                  },
                  "required": ["mapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_delete", "Remove an entry from a Hazelcast Map by key", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("delete", mapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("delete", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        Object removed = map.remove(key);
                        if (removed != null) {
                            return textResult(String.format("Removed key '%s' from map '%s'", key, mapName));
                        } else {
                            return textResult(String.format("Key '%s' was not present in map '%s'", key, mapName));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_delete", client));
                    }
                }
        );
    }

    // --- map_get_all ---
    private McpServerFeatures.SyncToolSpecification mapGetAll() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "keys": { "type": "array", "items": { "type": "string" }, "description": "Keys to retrieve" }
                  },
                  "required": ["mapName", "keys"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_get_all", "Retrieve multiple entries from a Hazelcast Map by keys", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    @SuppressWarnings("unchecked")
                    List<String> keys = (List<String>) args.get("keys");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        Map<String, Object> result = map.getAll(new HashSet<>(keys));
                        Map<String, Object> jsonResult = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> entry : result.entrySet()) {
                            jsonResult.put(entry.getKey(), JsonSerializer.toJson(entry.getValue()));
                        }
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "map", mapName,
                                "found", jsonResult.size(),
                                "requested", keys.size(),
                                "entries", jsonResult)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_get_all", client));
                    }
                }
        );
    }

    // --- map_put_all ---
    private McpServerFeatures.SyncToolSpecification mapPutAll() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "entries": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "key": { "type": "string" },
                          "value": {}
                        },
                        "required": ["key", "value"]
                      },
                      "description": "Array of {key, value} entries to store"
                    }
                  },
                  "required": ["mapName", "entries"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_put_all", "Store multiple key-value pairs in a Hazelcast Map", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> entries = (List<Map<String, Object>>) args.get("entries");
                    try {
                        if (!accessController.isMapAccessible(mapName) || !accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("put", mapName));
                        }
                        IMap<String, HazelcastJsonValue> map = client.getMap(mapName);
                        Map<String, HazelcastJsonValue> batch = new LinkedHashMap<>();
                        for (Map<String, Object> entry : entries) {
                            String key = (String) entry.get("key");
                            batch.put(key, JsonSerializer.toHazelcastJson(entry.get("value")));
                        }
                        map.putAll(batch);
                        return textResult(String.format("Stored %d entries in map '%s'", batch.size(), mapName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_put_all", client));
                    }
                }
        );
    }

    // --- map_size ---
    private McpServerFeatures.SyncToolSpecification mapSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" }
                  },
                  "required": ["mapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_size", "Get the number of entries in a Hazelcast Map", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        return textResult(String.format("Map '%s' contains %d entries", mapName, map.size()));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_size", client));
                    }
                }
        );
    }

    // --- map_keys ---
    private McpServerFeatures.SyncToolSpecification mapKeys() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "limit": { "type": "integer", "description": "Max keys to return (default 100)", "default": 100 }
                  },
                  "required": ["mapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_keys", "List keys in a Hazelcast Map (with optional limit)", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        Set<String> allKeys = map.keySet();
                        List<String> keys = allKeys.stream().limit(limit).collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "map", mapName,
                                "totalKeys", allKeys.size(),
                                "returned", keys.size(),
                                "limit", limit,
                                "keys", keys)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_keys", client));
                    }
                }
        );
    }

    // --- map_values ---
    private McpServerFeatures.SyncToolSpecification mapValues() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "limit": { "type": "integer", "description": "Max values to return (default 100)", "default": 100 }
                  },
                  "required": ["mapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_values", "List values in a Hazelcast Map (with optional limit)", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        int totalSize = map.size();
                        List<Object> values = map.values().stream()
                                .limit(limit)
                                .map(JsonSerializer::toJson)
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "map", mapName,
                                "totalEntries", totalSize,
                                "returned", values.size(),
                                "limit", limit,
                                "values", values)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_values", client));
                    }
                }
        );
    }

    // --- map_contains_key ---
    private McpServerFeatures.SyncToolSpecification mapContainsKey() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to check" }
                  },
                  "required": ["mapName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_contains_key", "Check if a key exists in a Hazelcast Map", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        boolean exists = map.containsKey(key);
                        return textResult(String.format("Key '%s' %s in map '%s'",
                                key, exists ? "EXISTS" : "DOES NOT EXIST", mapName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_contains_key", client));
                    }
                }
        );
    }

    // --- map_clear ---
    private McpServerFeatures.SyncToolSpecification mapClear() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "confirm": { "type": "boolean", "description": "Must be true to confirm deletion of all entries" }
                  },
                  "required": ["mapName", "confirm"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("map_clear", "Remove ALL entries from a Hazelcast Map (requires confirmation)", schema, null, null, null, null),
                (exchange, args) -> {
                    String mapName = (String) args.get("mapName");
                    Boolean confirm = (Boolean) args.get("confirm");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("clear", mapName));
                        }
                        if (!accessController.isClearAllowed()) {
                            return errorResult(accessController.getDenialMessage("clear", mapName));
                        }
                        if (confirm == null || !confirm) {
                            return errorResult("Safety check: set 'confirm: true' to clear all entries from map '"
                                    + mapName + "'. This operation cannot be undone.");
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        int sizeBefore = map.size();
                        map.clear();
                        return textResult(String.format("Cleared map '%s'. Removed %d entries.", mapName, sizeBefore));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_clear", client));
                    }
                }
        );
    }

    // --- Helpers ---

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true);
    }
}
