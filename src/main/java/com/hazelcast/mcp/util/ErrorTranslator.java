package com.hazelcast.mcp.util;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.DistributedObject;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Translates Hazelcast exceptions into human-readable, actionable MCP error messages.
 * Never exposes raw stack traces to the AI agent (P0-7).
 */
public class ErrorTranslator {

    /**
     * Translate a Hazelcast exception into an actionable error message.
     */
    public static String translate(Exception e, String context, HazelcastInstance client) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        // Map not found
        if (message.contains("does not exist") || message.contains("not found")) {
            String availableStructures = getAvailableStructures(client);
            return String.format("%s: '%s' not found. Available data structures: %s",
                    context, extractName(message), availableStructures);
        }

        // Connection issues
        if (message.contains("not connected") || message.contains("Connection refused")
                || message.contains("Target disconnected")) {
            return String.format("%s: Not connected to Hazelcast cluster. "
                    + "Check that the cluster is running and the connection configuration is correct.", context);
        }

        // SQL errors
        if (message.contains("SQL") || message.contains("query")) {
            return String.format("%s: SQL error - %s. "
                    + "Check query syntax and ensure the target map has a SQL mapping configured.", context, message);
        }

        // Serialization errors
        if (message.contains("serialization") || message.contains("Compact")
                || message.contains("ClassNotFoundException")) {
            return String.format("%s: Serialization error - %s. "
                    + "Ensure the data is stored as HazelcastJsonValue or Compact format.", context, message);
        }

        // Timeout
        if (message.contains("timeout") || message.contains("Timeout")) {
            return String.format("%s: Operation timed out. The cluster may be under heavy load.", context);
        }

        // Generic fallback (no stack trace)
        return String.format("%s: %s", context, message);
    }

    /**
     * Get a summary of available distributed objects for helpful error messages.
     */
    public static String getAvailableStructures(HazelcastInstance client) {
        try {
            Collection<DistributedObject> objects = client.getDistributedObjects();
            if (objects.isEmpty()) {
                return "(no data structures found)";
            }
            return objects.stream()
                    .map(obj -> obj.getName() + " (" + obj.getServiceName() + ")")
                    .sorted()
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "(unable to list structures)";
        }
    }

    private static String extractName(String message) {
        // Try to extract a name from the error message
        if (message.contains("'")) {
            int start = message.indexOf("'");
            int end = message.indexOf("'", start + 1);
            if (end > start) {
                return message.substring(start + 1, end);
            }
        }
        return "unknown";
    }
}
