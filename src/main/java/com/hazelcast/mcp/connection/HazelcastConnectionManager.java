package com.hazelcast.mcp.connection;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.config.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Hazelcast client connection lifecycle.
 * Handles configuration, connection, reconnection, and shutdown.
 */
public class HazelcastConnectionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastConnectionManager.class);

    private final McpServerConfig config;
    private volatile HazelcastInstance client;

    public HazelcastConnectionManager(McpServerConfig config) {
        this.config = config;
    }

    /**
     * Connect to the Hazelcast cluster using the provided configuration.
     */
    public synchronized HazelcastInstance connect() {
        if (client != null && client.getLifecycleService().isRunning()) {
            logger.debug("Already connected to Hazelcast cluster");
            return client;
        }

        logger.info("Connecting to Hazelcast cluster '{}' at {}",
                config.getHazelcast().getCluster().getName(),
                config.getHazelcast().getCluster().getMembers());

        ClientConfig clientConfig = buildClientConfig();
        client = HazelcastClient.newHazelcastClient(clientConfig);

        logger.info("Connected to Hazelcast cluster: {}", client.getCluster().getClusterState());
        return client;
    }

    /**
     * Get the current Hazelcast client instance.
     * @throws IllegalStateException if not connected
     */
    public HazelcastInstance getClient() {
        if (client == null || !client.getLifecycleService().isRunning()) {
            throw new IllegalStateException(
                    "Not connected to Hazelcast cluster. Call connect() first.");
        }
        return client;
    }

    /**
     * Check if the client is currently connected.
     */
    public boolean isConnected() {
        return client != null && client.getLifecycleService().isRunning();
    }

    /**
     * Get connection health information.
     */
    public ConnectionHealth getHealth() {
        if (!isConnected()) {
            return new ConnectionHealth(false, "DISCONNECTED", 0, -1);
        }
        try {
            long start = System.nanoTime();
            // Simple health check: get cluster members
            int memberCount = client.getCluster().getMembers().size();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new ConnectionHealth(true, "CONNECTED", memberCount, latencyMs);
        } catch (Exception e) {
            return new ConnectionHealth(false, "ERROR: " + e.getMessage(), 0, -1);
        }
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            logger.info("Shutting down Hazelcast client connection");
            try {
                client.shutdown();
            } catch (Exception e) {
                logger.warn("Error during Hazelcast client shutdown: {}", e.getMessage());
            }
            client = null;
        }
    }

    private ClientConfig buildClientConfig() {
        ClientConfig clientConfig = new ClientConfig();

        // Cluster configuration
        clientConfig.setClusterName(config.getHazelcast().getCluster().getName());
        for (String member : config.getHazelcast().getCluster().getMembers()) {
            clientConfig.getNetworkConfig().addAddress(member.trim());
        }

        // Authentication
        McpServerConfig.SecurityConfig security = config.getHazelcast().getSecurity();
        if (security.getUsername() != null && !security.getUsername().isEmpty()) {
            clientConfig.getSecurityConfig()
                    .setUsernamePasswordIdentityConfig(security.getUsername(), security.getPassword());
        }

        // Connection retry
        clientConfig.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(30_000);

        // Compact serialization is enabled by default in 5.x

        return clientConfig;
    }

    /**
     * Health check data.
     */
    public record ConnectionHealth(
            boolean connected,
            String status,
            int memberCount,
            long latencyMs
    ) {}
}
