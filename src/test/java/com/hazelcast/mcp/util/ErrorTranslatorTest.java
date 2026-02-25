package com.hazelcast.mcp.util;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorTranslatorTest {

    @Mock
    HazelcastInstance client;

    @Test
    void notFoundErrorListsAvailableStructures() {
        when(client.getDistributedObjects()).thenReturn(Collections.emptyList());

        String result = ErrorTranslator.translate(
                new RuntimeException("Map 'missing-map' does not exist"),
                "map_get", client);

        assertTrue(result.contains("not found"));
        assertTrue(result.contains("map_get"));
    }

    @Test
    void connectionErrorGivesHelpfulMessage() {
        String result = ErrorTranslator.translate(
                new RuntimeException("Connection refused"),
                "map_put", client);

        assertTrue(result.contains("Not connected"));
        assertTrue(result.contains("map_put"));
    }

    @Test
    void sqlErrorIncludesMessage() {
        String result = ErrorTranslator.translate(
                new RuntimeException("SQL syntax error near 'SELCT'"),
                "sql_execute", client);

        assertTrue(result.contains("SQL error"));
        assertTrue(result.contains("SELCT"));
    }

    @Test
    void serializationErrorGivesGuidance() {
        String result = ErrorTranslator.translate(
                new RuntimeException("ClassNotFoundException during serialization"),
                "map_get", client);

        assertTrue(result.contains("Serialization error"));
        assertTrue(result.contains("HazelcastJsonValue"));
    }

    @Test
    void timeoutErrorIsRecognized() {
        String result = ErrorTranslator.translate(
                new RuntimeException("Operation timeout after 30000ms"),
                "map_get_all", client);

        assertTrue(result.contains("timed out"));
    }

    @Test
    void genericErrorDoesNotExposeStackTrace() {
        RuntimeException e = new RuntimeException("Something unexpected happened");
        String result = ErrorTranslator.translate(e, "unknown_op", client);

        assertTrue(result.contains("Something unexpected happened"));
        assertFalse(result.contains("at com."));
        assertFalse(result.contains("java.lang"));
    }

    @Test
    void nullMessageHandledGracefully() {
        String result = ErrorTranslator.translate(
                new RuntimeException(), "test_op", client);

        assertTrue(result.contains("RuntimeException"));
    }
}
