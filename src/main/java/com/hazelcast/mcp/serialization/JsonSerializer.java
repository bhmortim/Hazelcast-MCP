package com.hazelcast.mcp.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hazelcast.core.HazelcastJsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles serialization/deserialization between MCP JSON and Hazelcast values.
 * Supports: HazelcastJsonValue (pass-through), Compact (schema-based), primitives.
 */
public class JsonSerializer {

    private static final Logger logger = LoggerFactory.getLogger(JsonSerializer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Convert a Hazelcast value to a JSON-compatible object for MCP responses.
     */
    public static Object toJson(Object value) {
        if (value == null) {
            return null;
        }

        // HazelcastJsonValue: pass-through
        if (value instanceof HazelcastJsonValue jsonValue) {
            try {
                return mapper.readTree(jsonValue.getValue());
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse HazelcastJsonValue, returning as string: {}", e.getMessage());
                return jsonValue.getValue();
            }
        }

        // Primitive types: pass-through
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        // Map: recursively convert
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return result;
        }

        // Iterable: recursively convert
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }

        // Try Jackson serialization as fallback
        try {
            return mapper.valueToTree(value);
        } catch (Exception e) {
            // Cannot serialize: return type info with error
            return Map.of(
                    "_type", value.getClass().getName(),
                    "_error", "Cannot serialize to JSON. Type '" + value.getClass().getSimpleName()
                            + "' is not supported. Supported formats: HazelcastJsonValue, Compact, JSON primitives."
            );
        }
    }

    /**
     * Convert a JSON value from MCP input to a HazelcastJsonValue for storage.
     */
    public static HazelcastJsonValue toHazelcastJson(Object value) {
        if (value == null) {
            return new HazelcastJsonValue("null");
        }
        try {
            String json = mapper.writeValueAsString(value);
            return new HazelcastJsonValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot convert value to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a JSON string into a tree.
     */
    public static JsonNode parseJson(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    /**
     * Serialize an object to JSON string.
     */
    public static String toJsonString(Object value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    /**
     * Get the shared ObjectMapper.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}
