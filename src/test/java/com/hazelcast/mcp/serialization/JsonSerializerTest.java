package com.hazelcast.mcp.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.hazelcast.core.HazelcastJsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonSerializerTest {

    @Test
    void nullReturnsNull() {
        assertNull(JsonSerializer.toJson(null));
    }

    @Test
    void stringPassThrough() {
        assertEquals("hello", JsonSerializer.toJson("hello"));
    }

    @Test
    void numberPassThrough() {
        assertEquals(42, JsonSerializer.toJson(42));
        assertEquals(3.14, JsonSerializer.toJson(3.14));
    }

    @Test
    void booleanPassThrough() {
        assertEquals(true, JsonSerializer.toJson(true));
        assertEquals(false, JsonSerializer.toJson(false));
    }

    @Test
    void hazelcastJsonValueParsed() {
        HazelcastJsonValue value = new HazelcastJsonValue("{\"name\":\"Alice\",\"age\":30}");
        Object result = JsonSerializer.toJson(value);
        assertNotNull(result);
        assertTrue(result instanceof JsonNode);
        JsonNode node = (JsonNode) result;
        assertEquals("Alice", node.get("name").asText());
        assertEquals(30, node.get("age").asInt());
    }

    @Test
    void malformedHazelcastJsonReturnsString() {
        HazelcastJsonValue value = new HazelcastJsonValue("not valid json{{{");
        Object result = JsonSerializer.toJson(value);
        assertEquals("not valid json{{{", result);
    }

    @Test
    void mapRecursivelyConverted() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", "value");
        input.put("nested", new HazelcastJsonValue("{\"inner\":true}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonSerializer.toJson(input);
        assertEquals("value", result.get("key"));
        assertTrue(result.get("nested") instanceof JsonNode);
    }

    @Test
    void toHazelcastJsonFromString() {
        HazelcastJsonValue result = JsonSerializer.toHazelcastJson("hello");
        assertEquals("\"hello\"", result.getValue());
    }

    @Test
    void toHazelcastJsonFromMap() {
        Map<String, Object> data = Map.of("name", "Bob", "active", true);
        HazelcastJsonValue result = JsonSerializer.toHazelcastJson(data);
        String json = result.getValue();
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"Bob\""));
    }

    @Test
    void toHazelcastJsonFromNull() {
        HazelcastJsonValue result = JsonSerializer.toHazelcastJson(null);
        assertEquals("null", result.getValue());
    }

    @Test
    void parseJsonReturnsTree() throws JsonProcessingException {
        JsonNode node = JsonSerializer.parseJson("{\"x\":1}");
        assertEquals(1, node.get("x").asInt());
    }

    @Test
    void toJsonStringSerializesCorrectly() throws JsonProcessingException {
        Map<String, Object> data = Map.of("a", 1, "b", "two");
        String json = JsonSerializer.toJsonString(data);
        assertTrue(json.contains("\"a\":1") || json.contains("\"a\": 1"));
        assertTrue(json.contains("\"b\":\"two\"") || json.contains("\"b\": \"two\""));
    }

    @Test
    void getMapperReturnsNonNull() {
        assertNotNull(JsonSerializer.getMapper());
    }
}
