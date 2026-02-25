package com.hazelcast.mcp.access;

import com.hazelcast.mcp.config.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Enforces access control policies based on configuration.
 * Controls which data structures are visible and which operations are allowed.
 */
public class AccessController {

    private static final Logger logger = LoggerFactory.getLogger(AccessController.class);

    private final McpServerConfig.AccessConfig accessConfig;

    public AccessController(McpServerConfig.AccessConfig accessConfig) {
        this.accessConfig = accessConfig;
    }

    /**
     * Check if a map is accessible based on allowlist/denylist configuration.
     */
    public boolean isMapAccessible(String mapName) {
        return isStructureAccessible(mapName, "map");
    }

    /**
     * Check if a vector collection is accessible.
     */
    public boolean isVectorAccessible(String collectionName) {
        return isStructureAccessible(collectionName, "vector");
    }

    /**
     * Check if a queue is accessible.
     */
    public boolean isQueueAccessible(String queueName) {
        return isStructureAccessible(queueName, "queue");
    }

    /**
     * Check if a list is accessible.
     */
    public boolean isListAccessible(String listName) {
        return isStructureAccessible(listName, "list");
    }

    /**
     * Check if a set is accessible.
     */
    public boolean isSetAccessible(String setName) {
        return isStructureAccessible(setName, "set");
    }

    /**
     * Check if a MultiMap is accessible.
     */
    public boolean isMultiMapAccessible(String multiMapName) {
        return isStructureAccessible(multiMapName, "multimap");
    }

    /**
     * Check if a topic is accessible.
     */
    public boolean isTopicAccessible(String topicName) {
        return isStructureAccessible(topicName, "topic");
    }

    /**
     * Check if a ringbuffer is accessible.
     */
    public boolean isRingbufferAccessible(String ringbufferName) {
        return isStructureAccessible(ringbufferName, "ringbuffer");
    }

    /**
     * Check if an atomic long is accessible.
     */
    public boolean isAtomicAccessible(String atomicName) {
        return isStructureAccessible(atomicName, "atomic");
    }

    /**
     * Check if write operations are allowed.
     */
    public boolean isWriteAllowed() {
        return accessConfig.getOperations().isWrite();
    }

    /**
     * Check if SQL operations are allowed.
     */
    public boolean isSqlAllowed() {
        return accessConfig.getOperations().isSql();
    }

    /**
     * Check if destructive operations (clear, etc.) are allowed.
     */
    public boolean isClearAllowed() {
        return accessConfig.getOperations().isClear();
    }

    /**
     * Get a human-readable denial message.
     */
    public String getDenialMessage(String operation, String structureName) {
        if (!isWriteAllowed() && isWriteOperation(operation)) {
            return "Write operations are disabled in the server configuration. "
                    + "Set 'access.operations.write: true' in hazelcast-mcp.yaml to enable writes.";
        }
        if (!isSqlAllowed() && "sql".equals(operation)) {
            return "SQL operations are disabled in the server configuration. "
                    + "Set 'access.operations.sql: true' in hazelcast-mcp.yaml to enable SQL.";
        }
        if (!isClearAllowed() && "clear".equals(operation)) {
            return "Destructive operations (clear) are disabled by default for safety. "
                    + "Set 'access.operations.clear: true' in hazelcast-mcp.yaml to enable.";
        }
        return "Access denied to '" + structureName + "'. Check the access control configuration in hazelcast-mcp.yaml.";
    }

    private boolean isStructureAccessible(String name, String type) {
        String mode = accessConfig.getMode();

        if ("all".equalsIgnoreCase(mode)) {
            return true;
        }

        if ("allowlist".equalsIgnoreCase(mode)) {
            List<String> allowed = switch (type) {
                case "map" -> accessConfig.getAllowlist().getMaps();
                case "vector" -> accessConfig.getAllowlist().getVectors();
                case "queue" -> accessConfig.getAllowlist().getQueues();
                case "list" -> accessConfig.getAllowlist().getLists();
                case "set" -> accessConfig.getAllowlist().getSets();
                case "multimap" -> accessConfig.getAllowlist().getMultimaps();
                case "topic" -> accessConfig.getAllowlist().getTopics();
                case "ringbuffer" -> accessConfig.getAllowlist().getRingbuffers();
                case "atomic" -> accessConfig.getAllowlist().getAtomics();
                default -> List.of();
            };
            return allowed.isEmpty() || allowed.contains(name);
        }

        if ("denylist".equalsIgnoreCase(mode)) {
            List<String> denied = switch (type) {
                case "map" -> accessConfig.getDenylist().getMaps();
                case "queue" -> accessConfig.getDenylist().getQueues();
                case "list" -> accessConfig.getDenylist().getLists();
                case "set" -> accessConfig.getDenylist().getSets();
                case "multimap" -> accessConfig.getDenylist().getMultimaps();
                case "topic" -> accessConfig.getDenylist().getTopics();
                case "ringbuffer" -> accessConfig.getDenylist().getRingbuffers();
                case "atomic" -> accessConfig.getDenylist().getAtomics();
                default -> List.of();
            };
            return !denied.contains(name);
        }

        return true;
    }

    private boolean isWriteOperation(String operation) {
        return switch (operation) {
            case "put", "delete", "clear", "put_all", "put_if_absent", "replace",
                 "offer", "poll", "drain", "add", "remove", "publish",
                 "set", "increment", "decrement", "compare_and_set" -> true;
            default -> false;
        };
    }
}
