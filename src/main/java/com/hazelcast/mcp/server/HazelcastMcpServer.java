package com.hazelcast.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.config.McpServerConfig;
import com.hazelcast.mcp.connection.HazelcastConnectionManager;
import com.hazelcast.mcp.prompts.BuiltInPrompts;
import com.hazelcast.mcp.resources.ClusterResources;
import com.hazelcast.mcp.tools.MapTools;
import com.hazelcast.mcp.tools.SqlTools;
import com.hazelcast.mcp.tools.VectorTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Hazelcast Client MCP Server.
 * Bootstraps the MCP server with stdio transport, registers all tools, resources, and prompts.
 */
public class HazelcastMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastMcpServer.class);

    private McpSyncServer mcpServer;
    private HazelcastConnectionManager connectionManager;
    private McpServerConfig config;

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : null;
        HazelcastMcpServer server = new HazelcastMcpServer();
        server.start(configPath);
    }

    public void start(String configPath) {
        try {
            // 1. Load configuration
            config = McpServerConfig.load(configPath);
            logger.info("Hazelcast MCP Server v{} starting...", config.getMcp().getServer().getVersion());

            // 2. Connect to Hazelcast
            connectionManager = new HazelcastConnectionManager(config);
            HazelcastInstance client = connectionManager.connect();

            // 3. Create access controller
            AccessController accessController = new AccessController(config.getAccess());

            // 4. Create tool/resource/prompt providers
            MapTools mapTools = new MapTools(client, accessController);
            SqlTools sqlTools = new SqlTools(client, accessController);
            VectorTools vectorTools = new VectorTools(client, accessController);
            ClusterResources clusterResources = new ClusterResources(connectionManager);
            BuiltInPrompts builtInPrompts = new BuiltInPrompts();

            // 5. Collect all specifications
            List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
            allTools.addAll(mapTools.getToolSpecifications());
            allTools.addAll(sqlTools.getToolSpecifications());
            allTools.addAll(vectorTools.getToolSpecifications());

            List<McpServerFeatures.SyncResourceSpecification> allResources = new ArrayList<>();
            allResources.addAll(clusterResources.getResourceSpecifications());

            List<McpServerFeatures.SyncPromptSpecification> allPrompts = new ArrayList<>();
            allPrompts.addAll(builtInPrompts.getPromptSpecifications());

            // 6. Build and start MCP server with stdio transport
            StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(new ObjectMapper());

            mcpServer = McpServer.sync(transportProvider)
                    .serverInfo(config.getMcp().getServer().getName(),
                            config.getMcp().getServer().getVersion())
                    .capabilities(ServerCapabilities.builder()
                            .resources(false, true)
                            .tools(true)
                            .prompts(true)
                            .logging()
                            .build())
                    .tools(allTools)
                    .resources(allResources)
                    .prompts(allPrompts)
                    .build();

            logger.info("Hazelcast MCP Server started successfully");
            logger.info("  Transport: {}", config.getMcp().getServer().getTransport());
            logger.info("  Tools registered: {}", allTools.size());
            logger.info("  Resources registered: {}", allResources.size());
            logger.info("  Prompts registered: {}", allPrompts.size());
            logger.info("  Cluster: {} ({} members)",
                    config.getHazelcast().getCluster().getName(),
                    client.getCluster().getMembers().size());

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        } catch (Exception e) {
            logger.error("Failed to start Hazelcast MCP Server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public void stop() {
        logger.info("Shutting down Hazelcast MCP Server...");
        try {
            if (mcpServer != null) {
                mcpServer.close();
            }
            if (connectionManager != null) {
                connectionManager.close();
            }
            logger.info("Hazelcast MCP Server stopped.");
        } catch (Exception e) {
            logger.error("Error during shutdown: {}", e.getMessage(), e);
        }
    }
}
