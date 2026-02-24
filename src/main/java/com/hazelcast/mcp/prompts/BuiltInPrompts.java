package com.hazelcast.mcp.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;

/**
 * Built-in MCP Prompt templates for common Hazelcast interaction patterns (P0-10).
 * Ships with: cache-lookup, data-exploration, vector-search.
 */
public class BuiltInPrompts {

    public List<McpServerFeatures.SyncPromptSpecification> getPromptSpecifications() {
        return List.of(
                cacheLookup(),
                dataExploration(),
                vectorSearch()
        );
    }

    /**
     * cache-lookup: Check cache, explain hit/miss, show value.
     */
    private McpServerFeatures.SyncPromptSpecification cacheLookup() {
        return new McpServerFeatures.SyncPromptSpecification(
                new Prompt("cache-lookup",
                        "Look up a key in a Hazelcast Map cache and explain whether it was a hit or miss",
                        List.of(
                                new PromptArgument("mapName", "Name of the cache map to query", true),
                                new PromptArgument("key", "Key to look up in the cache", true)
                        )),
                (exchange, request) -> {
                    String mapName = (String) request.arguments().getOrDefault("mapName", "my-cache");
                    String key = (String) request.arguments().getOrDefault("key", "my-key");

                    String prompt = String.format("""
                            I need to look up a value in a Hazelcast cache. Please follow these steps:

                            1. Use the `map_contains_key` tool to check if key '%s' exists in map '%s'
                            2. If the key exists (CACHE HIT):
                               - Use `map_get` to retrieve the value
                               - Display the value in a readable format
                               - Explain that this was a cache hit
                            3. If the key does not exist (CACHE MISS):
                               - Explain that this was a cache miss
                               - Suggest checking if the map name is correct using the `hazelcast://structures/list` resource
                               - Show the total size of the map using `map_size`

                            Map: %s
                            Key: %s
                            """, key, mapName, mapName, key);

                    return new GetPromptResult(
                            "Cache lookup for key '" + key + "' in map '" + mapName + "'",
                            List.of(new PromptMessage(Role.USER, new TextContent(prompt)))
                    );
                }
        );
    }

    /**
     * data-exploration: Discover available structures, sample data.
     */
    private McpServerFeatures.SyncPromptSpecification dataExploration() {
        return new McpServerFeatures.SyncPromptSpecification(
                new Prompt("data-exploration",
                        "Discover available Hazelcast data structures and sample their contents",
                        List.of(
                                new PromptArgument("focus", "Optional: specific map or structure to focus on", false)
                        )),
                (exchange, request) -> {
                    String focus = (String) request.arguments().getOrDefault("focus", "");

                    String prompt;
                    if (focus != null && !focus.isEmpty()) {
                        prompt = String.format("""
                                I want to explore the Hazelcast data structure '%s'. Please:

                                1. Use `map_size` to check how many entries it has
                                2. Use `map_keys` with limit 20 to see a sample of keys
                                3. Pick 3-5 interesting keys and use `map_get` to show their values
                                4. Summarize the data structure: what kind of data it holds, key patterns, value structure
                                5. If the values contain structured JSON, describe the schema

                                Structure to explore: %s
                                """, focus, focus);
                    } else {
                        prompt = """
                                I want to explore all data in this Hazelcast cluster. Please:

                                1. Read the `hazelcast://cluster/info` resource to understand the cluster
                                2. Read the `hazelcast://structures/list` resource to see all data structures
                                3. For each IMap found:
                                   - Show its name and size
                                   - Use `map_keys` with limit 5 to show sample keys
                                4. Provide a summary of:
                                   - How many structures exist and their types
                                   - Which maps have the most data
                                   - Suggested next steps for deeper exploration
                                """;
                    }

                    return new GetPromptResult(
                            "Data exploration" + (focus.isEmpty() ? " (full cluster)" : " for '" + focus + "'"),
                            List.of(new PromptMessage(Role.USER, new TextContent(prompt)))
                    );
                }
        );
    }

    /**
     * vector-search: Semantic similarity search workflow.
     */
    private McpServerFeatures.SyncPromptSpecification vectorSearch() {
        return new McpServerFeatures.SyncPromptSpecification(
                new Prompt("vector-search",
                        "Perform a semantic similarity search against a Hazelcast VectorCollection",
                        List.of(
                                new PromptArgument("collectionName", "Name of the VectorCollection", true),
                                new PromptArgument("query", "Natural language query to search for", true),
                                new PromptArgument("topK", "Number of results to return (default: 5)", false)
                        )),
                (exchange, request) -> {
                    String collection = (String) request.arguments().getOrDefault("collectionName", "my-vectors");
                    String query = (String) request.arguments().getOrDefault("query", "");
                    String topK = (String) request.arguments().getOrDefault("topK", "5");

                    String prompt = String.format("""
                            I want to perform a semantic similarity search. Please:

                            1. Note: To perform a vector search, you need a vector embedding of the query.
                               The query is: "%s"
                               You would need to generate an embedding using your own model or an embedding API.

                            2. Once you have the embedding vector, use `vector_search` with:
                               - collectionName: '%s'
                               - vector: [the embedding array]
                               - topK: %s

                            3. For each result returned:
                               - Show the document key and value
                               - Show the similarity score
                               - Explain why this result is relevant to the query

                            4. Summarize the search results and suggest follow-up queries

                            Collection: %s
                            Query: %s
                            TopK: %s
                            """, query, collection, topK, collection, query, topK);

                    return new GetPromptResult(
                            "Vector similarity search in '" + collection + "' for: " + query,
                            List.of(new PromptMessage(Role.USER, new TextContent(prompt)))
                    );
                }
        );
    }
}
