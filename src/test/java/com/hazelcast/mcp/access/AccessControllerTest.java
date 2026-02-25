package com.hazelcast.mcp.access;

import com.hazelcast.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessControllerTest {

    private AccessController createController(String mode) {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        config.setMode(mode);
        return new AccessController(config);
    }

    @Test
    void allModeAllowsEverything() {
        AccessController ac = createController("all");
        assertTrue(ac.isMapAccessible("any-map"));
        assertTrue(ac.isVectorAccessible("any-vector"));
        assertTrue(ac.isWriteAllowed());
        assertTrue(ac.isSqlAllowed());
    }

    @Test
    void allowlistModeBlocksUnlistedMaps() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        config.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setMaps(List.of("allowed-map", "another-map"));
        config.setAllowlist(allowlist);

        AccessController ac = new AccessController(config);
        assertTrue(ac.isMapAccessible("allowed-map"));
        assertTrue(ac.isMapAccessible("another-map"));
        assertFalse(ac.isMapAccessible("blocked-map"));
    }

    @Test
    void allowlistModeBlocksUnlistedVectors() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        config.setMode("allowlist");
        McpServerConfig.AllowlistConfig allowlist = new McpServerConfig.AllowlistConfig();
        allowlist.setVectors(List.of("embeddings"));
        config.setAllowlist(allowlist);

        AccessController ac = new AccessController(config);
        assertTrue(ac.isVectorAccessible("embeddings"));
        assertFalse(ac.isVectorAccessible("other-vectors"));
    }

    @Test
    void denylistModeBlocksDeniedMaps() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        config.setMode("denylist");
        McpServerConfig.DenylistConfig denylist = new McpServerConfig.DenylistConfig();
        denylist.setMaps(List.of("secret-map", "internal-map"));
        config.setDenylist(denylist);

        AccessController ac = new AccessController(config);
        assertFalse(ac.isMapAccessible("secret-map"));
        assertFalse(ac.isMapAccessible("internal-map"));
        assertTrue(ac.isMapAccessible("public-map"));
    }

    @Test
    void operationsCanBeDisabled() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        ops.setSql(false);
        ops.setClear(false);
        config.setOperations(ops);

        AccessController ac = new AccessController(config);
        assertFalse(ac.isWriteAllowed());
        assertFalse(ac.isSqlAllowed());
        assertFalse(ac.isClearAllowed());
    }

    @Test
    void writeDenialMessage() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(false);
        config.setOperations(ops);
        AccessController ac = new AccessController(config);

        String writeMsg = ac.getDenialMessage("put", "my-map");
        assertTrue(writeMsg.contains("Write operations are disabled"));
        assertTrue(writeMsg.contains("access.operations.write"));
    }

    @Test
    void sqlDenialMessage() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setSql(false);
        config.setOperations(ops);
        AccessController ac = new AccessController(config);

        String sqlMsg = ac.getDenialMessage("sql", "query");
        assertTrue(sqlMsg.contains("SQL operations are disabled"));
    }

    @Test
    void clearDenialMessage() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        McpServerConfig.OperationsConfig ops = new McpServerConfig.OperationsConfig();
        ops.setWrite(true);  // write must be enabled so clear check is reached
        ops.setClear(false);
        config.setOperations(ops);
        AccessController ac = new AccessController(config);

        String clearMsg = ac.getDenialMessage("clear", "my-map");
        assertTrue(clearMsg.contains("Destructive operations"));
    }

    @Test
    void emptyAllowlistAllowsAll() {
        McpServerConfig.AccessConfig config = new McpServerConfig.AccessConfig();
        config.setMode("allowlist");
        // Empty allowlist maps = allow all
        AccessController ac = new AccessController(config);
        assertTrue(ac.isMapAccessible("any-map"));
    }
}
