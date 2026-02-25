package com.hazelcast.mcp.tools;

import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
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
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

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
                mapClear(),
                listStructures(),
                mapPutIfAbsent(),
                mapReplace(),
                mapEntrySet()
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
                Tool.builder().name("map_get").description("Retrieve a value from a Hazelcast Map by key").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_put").description("Store a key-value pair in a Hazelcast Map").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_delete").description("Remove an entry from a Hazelcast Map by key").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_get_all").description("Retrieve multiple entries from a Hazelcast Map by keys").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_put_all").description("Store multiple key-value pairs in a Hazelcast Map").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_size").description("Get the number of entries in a Hazelcast Map").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_keys").description("List keys in a Hazelcast Map (with optional limit)").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_values").description("List values in a Hazelcast Map (with optional limit)").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_contains_key").description("Check if a key exists in a Hazelcast Map").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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
                Tool.builder().name("map_clear").description("Remove ALL entries from a Hazelcast Map (requires confirmation)").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
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

    // --- list_structures ---
    private McpServerFeatures.SyncToolSpecification listStructures() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "type": { "type": "string", "description": "Filter by type (IMap, IQueue, IList, etc.)" }
                  },
                  "required": []
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_structures").description("Discover all distributed objects on the cluster").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String typeFilter = args.get("type") != null ? (String) args.get("type") : null;
                    try {
                        Collection<DistributedObject> objects = client.getDistributedObjects();
                        Map<String, String> serviceMap = Map.ofEntries(
                                Map.entry("hz:impl:mapService", "IMap"),
                                Map.entry("hz:impl:queueService", "IQueue"),
                                Map.entry("hz:impl:topicService", "ITopic"),
                                Map.entry("hz:impl:reliableTopicService", "ReliableTopic"),
                                Map.entry("hz:impl:listService", "IList"),
                                Map.entry("hz:impl:setService", "ISet"),
                                Map.entry("hz:impl:multiMapService", "MultiMap"),
                                Map.entry("hz:impl:replicatedMapService", "ReplicatedMap"),
                                Map.entry("hz:impl:ringbufferService", "Ringbuffer"),
                                Map.entry("hz:impl:atomicLongService", "AtomicLong")
                        );
                        List<Map<String, Object>> results = new ArrayList<>();
                        for (DistributedObject obj : objects) {
                            String serviceName = obj.getServiceName();
                            String simpleType = serviceMap.getOrDefault(serviceName, "Unknown");
                            if (typeFilter != null && !typeFilter.equals(simpleType)) {
                                continue;
                            }
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", obj.getName());
                            entry.put("type", simpleType);
                            if (obj instanceof IMap) {
                                entry.put("size", ((IMap<?, ?>) obj).size());
                            }
                            results.add(entry);
                        }
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "total", results.size(),
                                "filter", typeFilter != null ? typeFilter : "none",
                                "objects", results)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_structures", client));
                    }
                }
        );
    }

    // --- map_put_if_absent ---
    private McpServerFeatures.SyncToolSpecification mapPutIfAbsent() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to insert" },
                    "value": { "description": "Value to store (any JSON)" },
                    "ttl": { "type": "integer", "description": "Time-to-live in seconds (optional)" }
                  },
                  "required": ["mapName", "key", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("map_put_if_absent").description("Atomic insert-only operation").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    Object value = args.get("value");
                    Integer ttl = args.get("ttl") != null ? ((Number) args.get("ttl")).intValue() : null;
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("put_if_absent", mapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("put_if_absent", mapName));
                        }
                        IMap<String, HazelcastJsonValue> map = client.getMap(mapName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        HazelcastJsonValue previous;
                        if (ttl != null && ttl > 0) {
                            previous = map.putIfAbsent(key, hzValue, ttl, TimeUnit.SECONDS);
                        } else {
                            previous = map.putIfAbsent(key, hzValue);
                        }
                        if (previous == null) {
                            return textResult(String.format("Successfully inserted key '%s' in map '%s'%s",
                                    key, mapName, ttl != null ? " (TTL: " + ttl + "s)" : ""));
                        } else {
                            Object prevJson = JsonSerializer.toJson(previous);
                            return textResult(String.format("Key '%s' already exists in map '%s'. Previous value: %s",
                                    key, mapName, JsonSerializer.toJsonString(prevJson)));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_put_if_absent", client));
                    }
                }
        );
    }

    // --- map_replace ---
    private McpServerFeatures.SyncToolSpecification mapReplace() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "key": { "type": "string", "description": "Key to replace" },
                    "value": { "description": "New value (any JSON)" },
                    "oldValue": { "description": "Old value for CAS operation (optional)" }
                  },
                  "required": ["mapName", "key", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("map_replace").description("Atomic replace with optional compare-and-set").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String mapName = (String) args.get("mapName");
                    String key = (String) args.get("key");
                    Object value = args.get("value");
                    Object oldValue = args.get("oldValue");
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("replace", mapName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("replace", mapName));
                        }
                        IMap<String, HazelcastJsonValue> map = client.getMap(mapName);
                        HazelcastJsonValue newHzValue = JsonSerializer.toHazelcastJson(value);
                        if (oldValue != null) {
                            HazelcastJsonValue oldHzValue = JsonSerializer.toHazelcastJson(oldValue);
                            boolean replaced = map.replace(key, oldHzValue, newHzValue);
                            if (replaced) {
                                return textResult(String.format("Successfully replaced key '%s' in map '%s'", key, mapName));
                            } else {
                                return textResult(String.format("Key '%s' or old value did not match in map '%s'", key, mapName));
                            }
                        } else {
                            HazelcastJsonValue previous = map.replace(key, newHzValue);
                            if (previous != null) {
                                Object prevJson = JsonSerializer.toJson(previous);
                                return textResult(String.format("Replaced key '%s' in map '%s'. Previous value: %s",
                                        key, mapName, JsonSerializer.toJsonString(prevJson)));
                            } else {
                                return textResult(String.format("Key '%s' was not present in map '%s'", key, mapName));
                            }
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_replace", client));
                    }
                }
        );
    }

    // --- map_entry_set ---
    private McpServerFeatures.SyncToolSpecification mapEntrySet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mapName": { "type": "string", "description": "Name of the Hazelcast IMap" },
                    "predicate": { "type": "string", "description": "SQL predicate string (optional)" },
                    "limit": { "type": "integer", "description": "Limit results (default 100)" }
                  },
                  "required": ["mapName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("map_entry_set").description("Bulk key-value retrieval with optional predicate").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String mapName = (String) args.get("mapName");
                    String predicateStr = args.get("predicate") != null ? (String) args.get("predicate") : null;
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isMapAccessible(mapName)) {
                            return errorResult(accessController.getDenialMessage("get", mapName));
                        }
                        IMap<String, Object> map = client.getMap(mapName);
                        Set<Map.Entry<String, Object>> entrySet;
                        if (predicateStr != null) {
                            Predicate<String, Object> predicate = Predicates.sql(predicateStr);
                            entrySet = map.entrySet(predicate);
                        } else {
                            entrySet = map.entrySet();
                        }
                        List<Map<String, Object>> results = entrySet.stream()
                                .limit(limit)
                                .map(entry -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("key", entry.getKey());
                                    item.put("value", JsonSerializer.toJson(entry.getValue()));
                                    return item;
                                })
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "map", mapName,
                                "predicate", predicateStr != null ? predicateStr : "none",
                                "limit", limit,
                                "returned", results.size(),
                                "entries", results)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "map_entry_set", client));
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
