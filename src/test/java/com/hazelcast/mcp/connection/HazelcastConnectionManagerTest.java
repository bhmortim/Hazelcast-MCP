package com.hazelcast.mcp.connection;

import com.hazelcast.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HazelcastConnectionManagerTest {

    @Test
    void isConnectedReturnsFalseBeforeConnect() {
        McpServerConfig config = new McpServerConfig();
        HazelcastConnectionManager manager = new HazelcastConnectionManager(config);
        assertFalse(manager.isConnected());
    }

    @Test
    void getClientThrowsWhenNotConnected() {
        McpServerConfig config = new McpServerConfig();
        HazelcastConnectionManager manager = new HazelcastConnectionManager(config);
        assertThrows(IllegalStateException.class, manager::getClient);
    }

    @Test
    void healthReturnsDisconnectedBeforeConnect() {
        McpServerConfig config = new McpServerConfig();
        HazelcastConnectionManager manager = new HazelcastConnectionManager(config);
        HazelcastConnectionManager.ConnectionHealth health = manager.getHealth();

        assertFalse(health.connected());
        assertEquals("DISCONNECTED", health.status());
        assertEquals(0, health.memberCount());
        assertEquals(-1, health.latencyMs());
    }

    @Test
    void closeIsIdempotent() {
        McpServerConfig config = new McpServerConfig();
        HazelcastConnectionManager manager = new HazelcastConnectionManager(config);
        // Should not throw even when not connected
        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);
    }

    @Test
    void connectionHealthRecordFieldAccess() {
        var health = new HazelcastConnectionManager.ConnectionHealth(true, "CONNECTED", 3, 5);
        assertTrue(health.connected());
        assertEquals("CONNECTED", health.status());
        assertEquals(3, health.memberCount());
        assertEquals(5, health.latencyMs());
    }
}
