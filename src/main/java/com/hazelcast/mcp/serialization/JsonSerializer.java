package com.hazelcast.mcp.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.nio.serialization.compact.CompactReader;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // GenericRecord (Compact serialization): convert using schema
        if (value instanceof GenericRecord record) {
            return genericRecordToMap(record);
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

    /**
     * Convert a GenericRecord (Compact serialization) to a Map using its schema.
     * Iterates over all field names and extracts values by field type.
     */
    private static Map<String, Object> genericRecordToMap(GenericRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> fieldNames = record.getFieldNames();

        for (String fieldName : fieldNames) {
            try {
                Object fieldValue = readGenericRecordField(record, fieldName);
                result.put(fieldName, toJson(fieldValue));
            } catch (Exception e) {
                logger.warn("Failed to read Compact field '{}': {}", fieldName, e.getMessage());
                result.put(fieldName, "<unreadable: " + e.getMessage() + ">");
            }
        }

        // Add type metadata via class name
        try {
            result.put("_compactType", record.getClass().getSimpleName());
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    /**
     * Read a single field from a GenericRecord.
     * Uses the GenericRecord API which auto-detects field types.
     */
    private static Object readGenericRecordField(GenericRecord record, String fieldName) {
        // GenericRecord doesn't expose field types directly in all versions,
        // so we try reading as common types in order of likelihood
        try {
            // Try string first (most common in JSON-origin data)
            return record.getString(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getInt32(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getInt64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getFloat64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getFloat32(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getBoolean(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getInt16(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getInt8(fieldName);
        } catch (Exception ignored) {}
        try {
            // Nested Compact record
            GenericRecord nested = record.getGenericRecord(fieldName);
            if (nested != null) return nested;
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfString(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfInt32(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfInt64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfFloat64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfBoolean(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getArrayOfGenericRecord(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getNullableBoolean(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getNullableInt32(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getNullableInt64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getNullableFloat64(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getDecimal(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getTime(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getDate(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getTimestamp(fieldName);
        } catch (Exception ignored) {}
        try {
            return record.getTimestampWithTimezone(fieldName);
        } catch (Exception ignored) {}

        return "<unknown field type>";
    }
}
