package com.hazelcast.mcp.tools;

import com.hazelcast.collection.IList;
import com.hazelcast.collection.ISet;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
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
import java.util.stream.Collectors;

/**
 * MCP Tool implementations for Hazelcast IList and ISet operations.
 * Covers: list_add, list_get, list_remove, list_size, list_sublist,
 *         set_add, set_remove, set_contains, set_size, set_get_all.
 */
public class CollectionTools {

    private static final Logger logger = LoggerFactory.getLogger(CollectionTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public CollectionTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                listAdd(),
                listGet(),
                listRemove(),
                listSize(),
                listSublist(),
                setAdd(),
                setRemove(),
                setContains(),
                setSize(),
                setGetAll()
        );
    }

    // ============ IList Tools ============

    // --- list_add ---
    private McpServerFeatures.SyncToolSpecification listAdd() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "listName": { "type": "string", "description": "Name of the Hazelcast IList" },
                    "value": { "description": "Value to add to list" },
                    "index": { "type": "integer", "description": "Optional index at which to insert the value" }
                  },
                  "required": ["listName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_add").description("Add item to list").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String listName = (String) args.get("listName");
                    Object value = args.get("value");
                    Integer index = args.get("index") != null ? ((Number) args.get("index")).intValue() : null;
                    try {
                        if (!accessController.isListAccessible(listName)) {
                            return errorResult(accessController.getDenialMessage("add", listName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("add", listName));
                        }
                        IList<HazelcastJsonValue> list = client.getList(listName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        if (index != null) {
                            list.add(index, hzValue);
                            return textResult(String.format("Added value at index %d in list '%s'", index, listName));
                        } else {
                            boolean added = list.add(hzValue);
                            return textResult(String.format("Added value to list '%s': %s", listName, added ? "success" : "failed"));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_add", client));
                    }
                }
        );
    }

    // --- list_get ---
    private McpServerFeatures.SyncToolSpecification listGet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "listName": { "type": "string", "description": "Name of the Hazelcast IList" },
                    "index": { "type": "integer", "description": "Index of the item to retrieve" }
                  },
                  "required": ["listName", "index"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_get").description("Get item by index").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String listName = (String) args.get("listName");
                    int index = ((Number) args.get("index")).intValue();
                    try {
                        if (!accessController.isListAccessible(listName)) {
                            return errorResult(accessController.getDenialMessage("get", listName));
                        }
                        IList<Object> list = client.getList(listName);
                        Object value = list.get(index);
                        Object jsonValue = JsonSerializer.toJson(value);
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "list", listName,
                                "index", index,
                                "value", jsonValue)));
                    } catch (IndexOutOfBoundsException e) {
                        return errorResult(String.format("Index %d out of bounds for list '%s'", index, listName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_get", client));
                    }
                }
        );
    }

    // --- list_remove ---
    private McpServerFeatures.SyncToolSpecification listRemove() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "listName": { "type": "string", "description": "Name of the Hazelcast IList" },
                    "index": { "type": "integer", "description": "Index of the item to remove" }
                  },
                  "required": ["listName", "index"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_remove").description("Remove item by index").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String listName = (String) args.get("listName");
                    int index = ((Number) args.get("index")).intValue();
                    try {
                        if (!accessController.isListAccessible(listName)) {
                            return errorResult(accessController.getDenialMessage("remove", listName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("remove", listName));
                        }
                        IList<Object> list = client.getList(listName);
                        Object removed = list.remove(index);
                        Object jsonValue = JsonSerializer.toJson(removed);
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "list", listName,
                                "index", index,
                                "removed", jsonValue)));
                    } catch (IndexOutOfBoundsException e) {
                        return errorResult(String.format("Index %d out of bounds for list '%s'", index, listName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_remove", client));
                    }
                }
        );
    }

    // --- list_size ---
    private McpServerFeatures.SyncToolSpecification listSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "listName": { "type": "string", "description": "Name of the Hazelcast IList" }
                  },
                  "required": ["listName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_size").description("Get list size").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String listName = (String) args.get("listName");
                    try {
                        if (!accessController.isListAccessible(listName)) {
                            return errorResult(accessController.getDenialMessage("get", listName));
                        }
                        IList<Object> list = client.getList(listName);
                        return textResult(String.format("List '%s' contains %d items", listName, list.size()));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_size", client));
                    }
                }
        );
    }

    // --- list_sublist ---
    private McpServerFeatures.SyncToolSpecification listSublist() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "listName": { "type": "string", "description": "Name of the Hazelcast IList" },
                    "fromIndex": { "type": "integer", "description": "Starting index (inclusive)" },
                    "toIndex": { "type": "integer", "description": "Ending index (exclusive)" }
                  },
                  "required": ["listName", "fromIndex", "toIndex"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("list_sublist").description("Get range of items").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String listName = (String) args.get("listName");
                    int fromIndex = ((Number) args.get("fromIndex")).intValue();
                    int toIndex = ((Number) args.get("toIndex")).intValue();
                    try {
                        if (!accessController.isListAccessible(listName)) {
                            return errorResult(accessController.getDenialMessage("get", listName));
                        }
                        IList<Object> list = client.getList(listName);
                        List<Object> sublist = list.subList(fromIndex, toIndex);
                        List<Object> jsonValues = sublist.stream()
                                .map(JsonSerializer::toJson)
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "list", listName,
                                "fromIndex", fromIndex,
                                "toIndex", toIndex,
                                "returned", jsonValues.size(),
                                "items", jsonValues)));
                    } catch (IndexOutOfBoundsException e) {
                        return errorResult(String.format("Invalid range [%d, %d) for list '%s'", fromIndex, toIndex, listName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "list_sublist", client));
                    }
                }
        );
    }

    // ============ ISet Tools ============

    // --- set_add ---
    private McpServerFeatures.SyncToolSpecification setAdd() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "setName": { "type": "string", "description": "Name of the Hazelcast ISet" },
                    "value": { "description": "Value to add to set" }
                  },
                  "required": ["setName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("set_add").description("Add item to set").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String setName = (String) args.get("setName");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isSetAccessible(setName)) {
                            return errorResult(accessController.getDenialMessage("add", setName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("add", setName));
                        }
                        ISet<HazelcastJsonValue> set = client.getSet(setName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        boolean added = set.add(hzValue);
                        return textResult(String.format("Added value to set '%s': %s", setName, added ? "new" : "already exists"));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "set_add", client));
                    }
                }
        );
    }

    // --- set_remove ---
    private McpServerFeatures.SyncToolSpecification setRemove() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "setName": { "type": "string", "description": "Name of the Hazelcast ISet" },
                    "value": { "description": "Value to remove from set" }
                  },
                  "required": ["setName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("set_remove").description("Remove item from set").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String setName = (String) args.get("setName");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isSetAccessible(setName)) {
                            return errorResult(accessController.getDenialMessage("remove", setName));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("remove", setName));
                        }
                        ISet<HazelcastJsonValue> set = client.getSet(setName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        boolean removed = set.remove(hzValue);
                        return textResult(String.format("Removed value from set '%s': %s", setName, removed ? "success" : "not found"));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "set_remove", client));
                    }
                }
        );
    }

    // --- set_contains ---
    private McpServerFeatures.SyncToolSpecification setContains() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "setName": { "type": "string", "description": "Name of the Hazelcast ISet" },
                    "value": { "description": "Value to check" }
                  },
                  "required": ["setName", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("set_contains").description("Check if item exists").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String setName = (String) args.get("setName");
                    Object value = args.get("value");
                    try {
                        if (!accessController.isSetAccessible(setName)) {
                            return errorResult(accessController.getDenialMessage("get", setName));
                        }
                        ISet<HazelcastJsonValue> set = client.getSet(setName);
                        HazelcastJsonValue hzValue = JsonSerializer.toHazelcastJson(value);
                        boolean contains = set.contains(hzValue);
                        return textResult(String.format("Value %s in set '%s'", contains ? "EXISTS" : "DOES NOT EXIST", setName));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "set_contains", client));
                    }
                }
        );
    }

    // --- set_size ---
    private McpServerFeatures.SyncToolSpecification setSize() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "setName": { "type": "string", "description": "Name of the Hazelcast ISet" }
                  },
                  "required": ["setName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("set_size").description("Get set size").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String setName = (String) args.get("setName");
                    try {
                        if (!accessController.isSetAccessible(setName)) {
                            return errorResult(accessController.getDenialMessage("get", setName));
                        }
                        ISet<Object> set = client.getSet(setName);
                        return textResult(String.format("Set '%s' contains %d items", setName, set.size()));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "set_size", client));
                    }
                }
        );
    }

    // --- set_get_all ---
    private McpServerFeatures.SyncToolSpecification setGetAll() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "setName": { "type": "string", "description": "Name of the Hazelcast ISet" },
                    "limit": { "type": "integer", "description": "Max values to return (default 100)", "default": 100 }
                  },
                  "required": ["setName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("set_get_all").description("Get all items").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String setName = (String) args.get("setName");
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    try {
                        if (!accessController.isSetAccessible(setName)) {
                            return errorResult(accessController.getDenialMessage("get", setName));
                        }
                        ISet<Object> set = client.getSet(setName);
                        int totalSize = set.size();
                        List<Object> values = set.stream()
                                .limit(limit)
                                .map(JsonSerializer::toJson)
                                .collect(Collectors.toList());
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "set", setName,
                                "totalItems", totalSize,
                                "returned", values.size(),
                                "limit", limit,
                                "items", values)));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "set_get_all", client));
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
