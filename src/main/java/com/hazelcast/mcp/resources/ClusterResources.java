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
                structuresList()
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
