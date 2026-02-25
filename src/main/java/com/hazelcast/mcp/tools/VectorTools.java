package com.hazelcast.mcp.tools;

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

import java.lang.reflect.Method;
import java.util.*;

/**
 * MCP Tool implementations for Hazelcast VectorCollection operations (P0-3).
 * Covers: vector_search, vector_put, vector_get, vector_delete.
 *
 * Uses reflection to call VectorCollection API at runtime so the server compiles
 * against Hazelcast 5.5 but works with VectorCollection when available (5.7+/6.0+).
 * Gracefully degrades with clear error messages if the module is not present.
 */
public class VectorTools {

    private static final Logger logger = LoggerFactory.getLogger(VectorTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;
    private final boolean vectorModuleAvailable;

    // Cached reflection references
    private Class<?> vectorCollectionClass;
    private Class<?> vectorDocumentClass;
    private Class<?> vectorValuesClass;
    private Class<?> searchOptionsClass;
    private Class<?> searchResultClass;

    public VectorTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
        this.vectorModuleAvailable = initVectorReflection();
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                vectorSearch(),
                vectorPut(),
                vectorGet(),
                vectorDelete()
        );
    }

    private McpServerFeatures.SyncToolSpecification vectorSearch() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "collectionName": { "type": "string", "description": "Name of the VectorCollection" },
                    "vector": {
                      "type": "array",
                      "items": { "type": "number" },
                      "description": "Query vector (array of floats) for similarity search"
                    },
                    "topK": { "type": "integer", "description": "Number of nearest neighbors to return", "default": 10 },
                    "includeValue": { "type": "boolean", "description": "Whether to include document values in results", "default": true },
                    "includeVectors": { "type": "boolean", "description": "Whether to include vectors in results", "default": false }
                  },
                  "required": ["collectionName", "vector"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("vector_search")
                        .description("Perform similarity search on a Hazelcast VectorCollection. Returns topK nearest neighbors with scores.")
                        .inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (!vectorModuleAvailable) {
                        return unavailableResult();
                    }
                    String collectionName = (String) args.get("collectionName");
                    if (!accessController.isVectorAccessible(collectionName)) {
                        return errorResult(accessController.getDenialMessage("search", collectionName));
                    }
                    try {
                        return doVectorSearch(collectionName, args);
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "vector_search", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification vectorPut() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "collectionName": { "type": "string", "description": "Name of the VectorCollection" },
                    "key": { "type": "string", "description": "Document key" },
                    "value": { "description": "Document value (any JSON)" },
                    "vector": {
                      "type": "array",
                      "items": { "type": "number" },
                      "description": "Vector embedding for the document"
                    },
                    "indexName": { "type": "string", "description": "Name of the vector index (required if collection has multiple indexes)" }
                  },
                  "required": ["collectionName", "key", "value", "vector"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("vector_put").description("Store a document with vector embedding in a Hazelcast VectorCollection").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (!vectorModuleAvailable) {
                        return unavailableResult();
                    }
                    if (!accessController.isWriteAllowed()) {
                        return errorResult(accessController.getDenialMessage("put", (String) args.get("collectionName")));
                    }
                    try {
                        return doVectorPut(args);
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "vector_put", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification vectorGet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "collectionName": { "type": "string", "description": "Name of the VectorCollection" },
                    "key": { "type": "string", "description": "Document key to retrieve" }
                  },
                  "required": ["collectionName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("vector_get").description("Retrieve a document by key from a Hazelcast VectorCollection").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (!vectorModuleAvailable) {
                        return unavailableResult();
                    }
                    try {
                        return doVectorGet(args);
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "vector_get", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification vectorDelete() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "collectionName": { "type": "string", "description": "Name of the VectorCollection" },
                    "key": { "type": "string", "description": "Document key to remove" }
                  },
                  "required": ["collectionName", "key"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("vector_delete").description("Remove a document by key from a Hazelcast VectorCollection").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (!vectorModuleAvailable) {
                        return unavailableResult();
                    }
                    if (!accessController.isWriteAllowed()) {
                        return errorResult(accessController.getDenialMessage("delete", (String) args.get("collectionName")));
                    }
                    try {
                        return doVectorDelete(args);
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "vector_delete", client));
                    }
                }
        );
    }

    // --- Reflection-based VectorCollection operations ---

    /**
     * Perform vector similarity search via reflection.
     * Equivalent to: VectorCollection.getCollection(client, name).searchAsync(searchVector, options).get()
     */
    @SuppressWarnings("unchecked")
    private CallToolResult doVectorSearch(String collectionName, Map<String, Object> args) throws Exception {
        List<Number> vectorList = (List<Number>) args.get("vector");
        float[] vector = toFloatArray(vectorList);
        int topK = args.get("topK") != null ? ((Number) args.get("topK")).intValue() : 10;

        // Get VectorCollection instance
        Object collection = getVectorCollection(collectionName);

        // Build SearchOptions via reflection
        // SearchOptions.builder().limit(topK).includeValue().build()
        Class<?> searchOptionsBuilderClass = Class.forName("com.hazelcast.vector.SearchOptions$Builder");
        Method builderMethod = searchOptionsClass.getMethod("builder");
        Object builder = builderMethod.invoke(null);
        Method limitMethod = searchOptionsBuilderClass.getMethod("limit", int.class);
        builder = limitMethod.invoke(builder, topK);

        boolean includeValue = args.get("includeValue") == null || (Boolean) args.get("includeValue");
        if (includeValue) {
            Method includeValueMethod = searchOptionsBuilderClass.getMethod("includeValue");
            builder = includeValueMethod.invoke(builder);
        }

        boolean includeVectors = args.get("includeVectors") != null && (Boolean) args.get("includeVectors");
        if (includeVectors) {
            Method includeVectorsMethod = searchOptionsBuilderClass.getMethod("includeVectors");
            builder = includeVectorsMethod.invoke(builder);
        }

        Method buildMethod = searchOptionsBuilderClass.getMethod("build");
        Object searchOptions = buildMethod.invoke(builder);

        // Create VectorValues.of(vector)
        Method ofMethod = vectorValuesClass.getMethod("of", float[].class);
        Object vectorValues = ofMethod.invoke(null, vector);

        // collection.searchAsync(vectorValues, searchOptions).toCompletableFuture().get()
        Method searchMethod = collection.getClass().getMethod("searchAsync", vectorValuesClass, searchOptionsClass);
        Object searchStage = searchMethod.invoke(collection, vectorValues, searchOptions);
        Method toCompletableFuture = searchStage.getClass().getMethod("toCompletableFuture");
        Object future = toCompletableFuture.invoke(searchStage);
        Method getMethod = future.getClass().getMethod("get");
        Object searchResults = getMethod.invoke(future);

        // Convert results to JSON
        List<Map<String, Object>> resultList = new ArrayList<>();
        if (searchResults instanceof Iterable<?> iterable) {
            for (Object result : iterable) {
                Map<String, Object> entry = new LinkedHashMap<>();
                try {
                    Method getKey = result.getClass().getMethod("getKey");
                    entry.put("key", String.valueOf(getKey.invoke(result)));
                } catch (Exception ignored) {}
                try {
                    Method getScore = result.getClass().getMethod("getScore");
                    entry.put("score", getScore.invoke(result));
                } catch (Exception ignored) {}
                try {
                    Method getValue = result.getClass().getMethod("getValue");
                    Object val = getValue.invoke(result);
                    entry.put("value", JsonSerializer.toJson(val));
                } catch (Exception ignored) {}
                resultList.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", collectionName);
        response.put("topK", topK);
        response.put("dimensions", vector.length);
        response.put("resultCount", resultList.size());
        response.put("results", resultList);

        return textResult(JsonSerializer.toJsonString(response));
    }

    /**
     * Put a document with vector into VectorCollection via reflection.
     */
    @SuppressWarnings("unchecked")
    private CallToolResult doVectorPut(Map<String, Object> args) throws Exception {
        String collectionName = (String) args.get("collectionName");
        String key = (String) args.get("key");
        Object value = args.get("value");
        List<Number> vectorList = (List<Number>) args.get("vector");
        float[] vector = toFloatArray(vectorList);

        Object collection = getVectorCollection(collectionName);

        // Create VectorValues
        Method ofMethod = vectorValuesClass.getMethod("of", float[].class);
        Object vectorValues = ofMethod.invoke(null, vector);

        // Create VectorDocument.of(value, vectorValues)
        Method docOfMethod = vectorDocumentClass.getMethod("of",
                Object.class, vectorValuesClass);
        Object doc = docOfMethod.invoke(null, JsonSerializer.toHazelcastJson(value), vectorValues);

        // collection.putAsync(key, doc).toCompletableFuture().get()
        Method putMethod = collection.getClass().getMethod("putAsync", Object.class, vectorDocumentClass);
        Object stage = putMethod.invoke(collection, key, doc);
        Method toCompletableFuture = stage.getClass().getMethod("toCompletableFuture");
        Object future = toCompletableFuture.invoke(stage);
        future.getClass().getMethod("get").invoke(future);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("collection", collectionName);
        response.put("key", key);
        response.put("dimensions", vector.length);
        return textResult(JsonSerializer.toJsonString(response));
    }

    /**
     * Get a document by key from VectorCollection via reflection.
     */
    private CallToolResult doVectorGet(Map<String, Object> args) throws Exception {
        String collectionName = (String) args.get("collectionName");
        String key = (String) args.get("key");

        Object collection = getVectorCollection(collectionName);

        // collection.getAsync(key).toCompletableFuture().get()
        Method getMethod = collection.getClass().getMethod("getAsync", Object.class);
        Object stage = getMethod.invoke(collection, key);
        Method toCompletableFuture = stage.getClass().getMethod("toCompletableFuture");
        Object future = toCompletableFuture.invoke(stage);
        Object doc = future.getClass().getMethod("get").invoke(future);

        if (doc == null) {
            return textResult("{\"found\":false,\"key\":\"" + key + "\",\"collection\":\"" + collectionName + "\"}");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("collection", collectionName);
        response.put("key", key);

        // Extract value from VectorDocument
        try {
            Method getValue = doc.getClass().getMethod("getValue");
            response.put("value", JsonSerializer.toJson(getValue.invoke(doc)));
        } catch (Exception ignored) {}

        return textResult(JsonSerializer.toJsonString(response));
    }

    /**
     * Delete a document by key from VectorCollection via reflection.
     */
    private CallToolResult doVectorDelete(Map<String, Object> args) throws Exception {
        String collectionName = (String) args.get("collectionName");
        String key = (String) args.get("key");

        Object collection = getVectorCollection(collectionName);

        // collection.deleteAsync(key).toCompletableFuture().get()
        Method deleteMethod = collection.getClass().getMethod("deleteAsync", Object.class);
        Object stage = deleteMethod.invoke(collection, key);
        Method toCompletableFuture = stage.getClass().getMethod("toCompletableFuture");
        Object future = toCompletableFuture.invoke(stage);
        future.getClass().getMethod("get").invoke(future);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "deleted");
        response.put("collection", collectionName);
        response.put("key", key);
        return textResult(JsonSerializer.toJsonString(response));
    }

    // --- Helper methods ---

    /**
     * Get a VectorCollection instance via reflection.
     * Equivalent to: VectorCollection.getCollection(client, name)
     */
    private Object getVectorCollection(String name) throws Exception {
        Method getCollection = vectorCollectionClass.getMethod("getCollection",
                HazelcastInstance.class, String.class);
        return getCollection.invoke(null, client, name);
    }

    /**
     * Initialize reflection references for VectorCollection classes.
     * Returns true if the vector module is available.
     */
    private boolean initVectorReflection() {
        try {
            vectorCollectionClass = Class.forName("com.hazelcast.vector.VectorCollection");
            vectorDocumentClass = Class.forName("com.hazelcast.vector.VectorDocument");
            vectorValuesClass = Class.forName("com.hazelcast.vector.VectorValues");
            searchOptionsClass = Class.forName("com.hazelcast.vector.SearchOptions");
            searchResultClass = Class.forName("com.hazelcast.vector.SearchResult");
            logger.info("VectorCollection module detected on classpath — vector tools fully operational");
            return true;
        } catch (ClassNotFoundException e) {
            logger.info("VectorCollection module not found on classpath. Vector tools will return informational messages. "
                    + "To enable vector operations, add hazelcast-vector dependency (Hazelcast 5.7+/6.0+).");
            return false;
        }
    }

    private static float[] toFloatArray(List<Number> numbers) {
        float[] result = new float[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            result[i] = numbers.get(i).floatValue();
        }
        return result;
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true, null, null);
    }

    private static CallToolResult unavailableResult() {
        return errorResult("VectorCollection module is not available in this Hazelcast version. "
                + "Vector operations require Hazelcast 5.7+/6.0+ with the hazelcast-vector module on the classpath. "
                + "Add the dependency and restart the server to enable vector search, put, get, and delete operations.");
    }
}
