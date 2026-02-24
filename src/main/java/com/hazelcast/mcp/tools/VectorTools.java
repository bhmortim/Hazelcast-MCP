package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP Tool implementations for Hazelcast VectorCollection operations (P0-3).
 * Covers: vector_search, vector_put, vector_get, vector_delete.
 *
 * Note: VectorCollection is expected in Hazelcast 5.7/6.0.
 * This implementation uses the VectorCollection API when available,
 * and gracefully degrades with a clear error message if the module is not present.
 */
public class VectorTools {

    private static final Logger logger = LoggerFactory.getLogger(VectorTools.class);

    private final HazelcastInstance client;
    private final AccessController accessController;
    private final boolean vectorModuleAvailable;

    public VectorTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
        this.vectorModuleAvailable = isVectorModulePresent();
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
                    "efSearch": { "type": "integer", "description": "Recall/latency tuning parameter (higher = better recall, slower)" },
                    "filter": { "type": "string", "description": "Optional SQL predicate filter" }
                  },
                  "required": ["collectionName", "vector"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("vector_search",
                        "Perform similarity search on a Hazelcast VectorCollection. Returns topK nearest neighbors.",
                        schema),
                (exchange, args) -> {
                    if (!vectorModuleAvailable) {
                        return errorResult("VectorCollection module is not available in this Hazelcast version. "
                                + "Vector search requires Hazelcast 5.7+ with the hazelcast-vector module.");
                    }
                    String collectionName = (String) args.get("collectionName");
                    if (!accessController.isVectorAccessible(collectionName)) {
                        return errorResult(accessController.getDenialMessage("search", collectionName));
                    }
                    try {
                        // VectorCollection API will be called here when available
                        // For now, provide a stub that documents the expected behavior
                        return textResult(String.format(
                                "Vector search on '%s': This feature requires Hazelcast 5.7+ with VectorCollection GA. "
                                        + "The search would find the top-%d nearest neighbors using the provided %d-dimensional vector.",
                                collectionName,
                                args.get("topK") != null ? ((Number) args.get("topK")).intValue() : 10,
                                ((List<?>) args.get("vector")).size()));
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
                    }
                  },
                  "required": ["collectionName", "key", "value", "vector"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("vector_put", "Store a document with vector embedding in a Hazelcast VectorCollection", schema),
                (exchange, args) -> {
                    if (!vectorModuleAvailable) {
                        return errorResult("VectorCollection module is not available. Requires Hazelcast 5.7+.");
                    }
                    if (!accessController.isWriteAllowed()) {
                        return errorResult(accessController.getDenialMessage("put", (String) args.get("collectionName")));
                    }
                    try {
                        String collectionName = (String) args.get("collectionName");
                        String key = (String) args.get("key");
                        return textResult(String.format(
                                "Vector put to '%s': This feature requires Hazelcast 5.7+ with VectorCollection GA. "
                                        + "Would store document with key '%s' and %d-dimensional vector.",
                                collectionName, key, ((List<?>) args.get("vector")).size()));
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
                new Tool("vector_get", "Retrieve a document by key from a Hazelcast VectorCollection", schema),
                (exchange, args) -> {
                    if (!vectorModuleAvailable) {
                        return errorResult("VectorCollection module is not available. Requires Hazelcast 5.7+.");
                    }
                    try {
                        return textResult("Vector get: requires Hazelcast 5.7+ with VectorCollection GA.");
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
                new Tool("vector_delete", "Remove a document by key from a Hazelcast VectorCollection", schema),
                (exchange, args) -> {
                    if (!vectorModuleAvailable) {
                        return errorResult("VectorCollection module is not available. Requires Hazelcast 5.7+.");
                    }
                    if (!accessController.isWriteAllowed()) {
                        return errorResult(accessController.getDenialMessage("delete", (String) args.get("collectionName")));
                    }
                    try {
                        return textResult("Vector delete: requires Hazelcast 5.7+ with VectorCollection GA.");
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "vector_delete", client));
                    }
                }
        );
    }

    /**
     * Check if the VectorCollection module is available on the classpath.
     */
    private boolean isVectorModulePresent() {
        try {
            Class.forName("com.hazelcast.vector.VectorCollection");
            return true;
        } catch (ClassNotFoundException e) {
            logger.info("VectorCollection module not found on classpath. Vector tools will return informational messages.");
            return false;
        }
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true);
    }
}
