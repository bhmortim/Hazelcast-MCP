package com.hazelcast.mcp.resources;

import com.hazelcast.cluster.Member;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.mcp.connection.HazelcastConnectionManager;
import com.hazelcast.mcp.serialization.JsonSerializer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Resource implementations for Hazelcast cluster discovery (P0-4).
 * Provides read-only contextual information about the cluster and data structures.
 */
public class ClusterResources {

    private static final Logger logger = LoggerFactory.getLogger(ClusterResources.class);

    private final HazelcastConnectionManager connectionManager;

    public ClusterResources(HazelcastConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public List<McpServerFeatures.SyncResourceSpecification> getResourceSpecifications() {
        return List.of(
                clusterInfo(),
                clusterHealth(),
                structuresList(),
                mapInfo(),
                vectorInfo()
        );
    }

    /**
     * hazelcast://cluster/info — Cluster name, version, member list, connection state.
     */
    private McpServerFeatures.SyncResourceSpecification clusterInfo() {
        return new McpServerFeatures.SyncResourceSpecification(
                new Resource("hazelcast://cluster/info", "Cluster Info",
                        "Hazelcast cluster name, version, member count, and connection state",
                        "application/json", null),
                (exchange, request) -> {
                    try {
                        HazelcastInstance client = connectionManager.getClient();
                        Set<Member> members = client.getCluster().getMembers();

                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("clusterName", client.getConfig().getClusterName());
                        info.put("memberCount", members.size());
                        info.put("members", members.stream()
                                .map(m -> Map.of(
                                        "address", m.getAddress().toString(),
                                        "uuid", m.getUuid().toString(),
                                        "liteMember", m.isLiteMember()
                                ))
                                .collect(Collectors.toList()));
                        info.put("connected", true);

                        String json = JsonSerializer.toJsonString(info);
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://cluster/info", "application/json", json)));
                    } catch (Exception e) {
                        String error = "{\"connected\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://cluster/info", "application/json", error)));
                    }
                }
        );
    }

    /**
     * hazelcast://cluster/health — Health check: connected/disconnected, latency.
     */
    private McpServerFeatures.SyncResourceSpecification clusterHealth() {
        return new McpServerFeatures.SyncResourceSpecification(
                new Resource("hazelcast://cluster/health", "Cluster Health",
                        "Health check showing connection status, member count, and response latency",
                        "application/json", null),
                (exchange, request) -> {
                    try {
                        var health = connectionManager.getHealth();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", health.status());
                        result.put("connected", health.connected());
                        result.put("memberCount", health.memberCount());
                        result.put("latencyMs", health.latencyMs());

                        String json = JsonSerializer.toJsonString(result);
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://cluster/health", "application/json", json)));
                    } catch (Exception e) {
                        String error = "{\"status\":\"ERROR\",\"connected\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://cluster/health", "application/json", error)));
                    }
                }
        );
    }

    /**
     * hazelcast://structures/list — All distributed objects with type and size.
     */
    private McpServerFeatures.SyncResourceSpecification structuresList() {
        return new McpServerFeatures.SyncResourceSpecification(
                new Resource("hazelcast://structures/list", "Data Structures",
                        "List of all distributed objects in the cluster (maps, queues, topics, etc.) with type and size",
                        "application/json", null),
                (exchange, request) -> {
                    try {
                        HazelcastInstance client = connectionManager.getClient();
                        Collection<DistributedObject> objects = client.getDistributedObjects();

                        List<Map<String, Object>> structures = new ArrayList<>();
                        for (DistributedObject obj : objects) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", obj.getName());
                            entry.put("serviceName", obj.getServiceName());
                            entry.put("type", getSimpleType(obj.getServiceName()));

                            // Add size for maps
                            if (obj instanceof IMap<?, ?> map) {
                                try {
                                    entry.put("size", map.size());
                                } catch (Exception e) {
                                    entry.put("size", "unknown");
                                }
                            }

                            structures.add(entry);
                        }

                        // Sort by type then name
                        structures.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("type"))
                                .thenComparing(m -> (String) m.get("name")));

                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("totalStructures", structures.size());
                        result.put("structures", structures);

                        String json = JsonSerializer.toJsonString(result);
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://structures/list", "application/json", json)));
                    } catch (Exception e) {
                        String error = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                        return new ReadResourceResult(List.of(
                                new TextResourceContents("hazelcast://structures/list", "application/json", error)));
                    }
                }
        );
    }

    /**
     * hazelcast://map/{name}/info — Map configuration, entry count, memory usage, Near Cache stats.
     * Uses a URI template so MCP clients can request info for any map by name.
     */
    private McpServerFeatures.SyncResourceSpecification mapInfo() {
        return new McpServerFeatures.SyncResourceSpecification(
                new Resource("hazelcast://map/{name}/info", "Map Info",
                        "Detailed information about a specific IMap: entry count, configuration, and Near Cache stats. "
                                + "Replace {name} with the map name, e.g. hazelcast://map/session-cache/info",
                        "application/json", null),
                (exchange, request) -> {
                    try {
                        String uri = request.uri();
                        String mapName = extractMapName(uri);
                        HazelcastInstance client = connectionManager.getClient();
                        IMap<Object, Object> map = client.getMap(mapName);

                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("name", mapName);
                        info.put("entryCount", map.size());
                        info.put("type", "IMap");

                        // Local map stats if available
                        try {
                            var stats = map.getLocalMapStats();
                            Map<String, Object> statsMap = new LinkedHashMap<>();
                            statsMap.put("ownedEntryCount", stats.getOwnedEntryCount());
                            statsMap.put("backupEntryCount", stats.getBackupEntryCount());
                            statsMap.put("ownedEntryMemoryCost", stats.getOwnedEntryMemoryCost());
                            statsMap.put("heapCost", stats.getHeapCost());
                            statsMap.put("hits", stats.getHits());
                            statsMap.put("lastAccessTime", stats.getLastAccessTime());
                            statsMap.put("lastUpdateTime", stats.getLastUpdateTime());
                            statsMap.put("putOperationCount", stats.getPutOperationCount());
                            statsMap.put("getOperationCount", stats.getGetOperationCount());
                            statsMap.put("removeOperationCount", stats.getRemoveOperationCount());

                            // Near Cache stats
                            var nearCacheStats = stats.getNearCacheStats();
                            if (nearCacheStats != null) {
                                Map<String, Object> ncStats = new LinkedHashMap<>();
                                ncStats.put("hits", nearCacheStats.getHits());
                                ncStats.put("misses", nearCacheStats.getMisses());
                                ncStats.put("ownedEntryCount", nearCacheStats.getOwnedEntryCount());
                                ncStats.put("ownedEntryMemoryCost", nearCacheStats.getOwnedEntryMemoryCost());
                                double hitRatio = nearCacheStats.getHits() + nearCacheStats.getMisses() > 0
                                        ? (double) nearCacheStats.getHits() / (nearCacheStats.getHits() + nearCacheStats.getMisses())
                                        : 0.0;
                                ncStats.put("hitRatio", Math.round(hitRatio * 10000.0) / 100.0);
                                statsMap.put("nearCache", ncStats);
                            }

                            info.put("localStats", statsMap);
                        } catch (Exception e) {
                            info.put("localStats", "unavailable (client-side stats may not be enabled)");
                        }

                        String json = JsonSerializer.toJsonString(info);
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(uri, "application/json", json)));
                    } catch (Exception e) {
                        String error = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(request.uri(), "application/json", error)));
                    }
                }
        );
    }

    /**
     * hazelcast://vector/{name}/info — VectorCollection index config, document count, dimensions.
     */
    private McpServerFeatures.SyncResourceSpecification vectorInfo() {
        return new McpServerFeatures.SyncResourceSpecification(
                new Resource("hazelcast://vector/{name}/info", "VectorCollection Info",
                        "Information about a VectorCollection: index configuration, document count, dimensions. "
                                + "Replace {name} with the collection name.",
                        "application/json", null),
                (exchange, request) -> {
                    try {
                        String uri = request.uri();
                        String collectionName = extractVectorName(uri);

                        // Check if VectorCollection module is available
                        try {
                            Class.forName("com.hazelcast.vector.VectorCollection");
                        } catch (ClassNotFoundException e) {
                            String msg = JsonSerializer.toJsonString(Map.of(
                                    "name", collectionName,
                                    "available", false,
                                    "message", "VectorCollection module not available. Requires Hazelcast 5.7+ with hazelcast-vector module."
                            ));
                            return new ReadResourceResult(List.of(
                                    new TextResourceContents(uri, "application/json", msg)));
                        }

                        // Placeholder — will be filled in when VectorCollection API is available
                        String msg = JsonSerializer.toJsonString(Map.of(
                                "name", collectionName,
                                "available", true,
                                "message", "VectorCollection details require Hazelcast 5.7+ runtime. Module detected on classpath."
                        ));
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(uri, "application/json", msg)));
                    } catch (Exception e) {
                        String error = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(request.uri(), "application/json", error)));
                    }
                }
        );
    }

    /**
     * Extract map name from URI like "hazelcast://map/session-cache/info"
     */
    private static String extractMapName(String uri) {
        // hazelcast://map/{name}/info
        String prefix = "hazelcast://map/";
        String suffix = "/info";
        if (uri.startsWith(prefix) && uri.endsWith(suffix)) {
            return uri.substring(prefix.length(), uri.length() - suffix.length());
        }
        // Fallback: try to get the template parameter
        return uri.replace("hazelcast://map/", "").replace("/info", "");
    }

    /**
     * Extract vector collection name from URI like "hazelcast://vector/my-collection/info"
     */
    private static String extractVectorName(String uri) {
        String prefix = "hazelcast://vector/";
        String suffix = "/info";
        if (uri.startsWith(prefix) && uri.endsWith(suffix)) {
            return uri.substring(prefix.length(), uri.length() - suffix.length());
        }
        return uri.replace("hazelcast://vector/", "").replace("/info", "");
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String getSimpleType(String serviceName) {
        return switch (serviceName) {
            case "hz:impl:mapService" -> "IMap";
            case "hz:impl:queueService" -> "IQueue";
            case "hz:impl:topicService" -> "ITopic";
            case "hz:impl:reliableTopicService" -> "ReliableTopic";
            case "hz:impl:listService" -> "IList";
            case "hz:impl:setService" -> "ISet";
            case "hz:impl:multiMapService" -> "MultiMap";
            case "hz:impl:replicatedMapService" -> "ReplicatedMap";
            case "hz:impl:ringbufferService" -> "Ringbuffer";
            case "hz:impl:atomicLongService" -> "AtomicLong";
            default -> serviceName;
        };
    }
}
