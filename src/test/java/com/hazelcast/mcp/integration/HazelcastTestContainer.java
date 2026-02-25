package com.hazelcast.mcp.integration;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * TestContainers-based Hazelcast server for integration tests.
 * Starts a single-node Hazelcast cluster in Docker.
 *
 * Usage:
 * <pre>
 * &#64;Container
 * static HazelcastTestContainer hazelcast = new HazelcastTestContainer();
 * </pre>
 */
public class HazelcastTestContainer extends GenericContainer<HazelcastTestContainer> {

    private static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("hazelcast/hazelcast:5.5");
    private static final int HAZELCAST_PORT = 5701;

    public HazelcastTestContainer() {
        this(DEFAULT_IMAGE);
    }

    public HazelcastTestContainer(DockerImageName imageName) {
        super(imageName);
        withExposedPorts(HAZELCAST_PORT);
        withEnv("HZ_CLUSTERNAME", "test-cluster");
        waitingFor(Wait.forLogMessage(".*Members \\{size:1.*", 1)
                .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * Get the mapped host port for the Hazelcast member.
     */
    public int getHazelcastPort() {
        return getMappedPort(HAZELCAST_PORT);
    }

    /**
     * Get a pre-configured Hazelcast client connected to this container.
     */
    public HazelcastInstance createClient() {
        ClientConfig config = new ClientConfig();
        config.setClusterName("test-cluster");
        config.getNetworkConfig().addAddress(getHost() + ":" + getHazelcastPort());
        config.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(10_000);
        return HazelcastClient.newHazelcastClient(config);
    }

    /**
     * Get the member address in host:port format.
     */
    public String getMemberAddress() {
        return getHost() + ":" + getHazelcastPort();
    }
}
