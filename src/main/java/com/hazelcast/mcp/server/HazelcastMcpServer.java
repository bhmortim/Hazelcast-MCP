package com.hazelcast.mcp.server;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.config.McpServerConfig;
import com.hazelcast.mcp.connection.HazelcastConnectionManager;
import com.hazelcast.mcp.prompts.BuiltInPrompts;
import com.hazelcast.mcp.resources.ClusterResources;
import com.hazelcast.mcp.tools.MapTools;
import com.hazelcast.mcp.tools.SqlTools;
import com.hazelcast.mcp.tools.VectorTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Hazelcast Client MCP Server.
 * Supports stdio (default), Streamable HTTP, and legacy SSE transports.
 *
 * <ul>
 *   <li>stdio — Standard MCP stdio transport for local AI client integration</li>
 *   <li>sse — Streamable HTTP transport for network-accessible MCP (protocol 2025-06-18)</li>
 *   <li>sse-legacy — Legacy SSE transport (protocol 2024-11-05 only)</li>
 * </ul>
 */
public class HazelcastMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastMcpServer.class);

    private McpSyncServer mcpServer;
    private HazelcastConnectionManager connectionManager;
    private McpServerConfig config;
    private Server jettyServer; // only used for HTTP transports

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
            BuiltInPrompts builtInPrompts = new BuiltInPrompts(config.getMcp().getPrompts());

            // 5. Collect all specifications
            List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
            allTools.addAll(mapTools.getToolSpecifications());
            allTools.addAll(sqlTools.getToolSpecifications());
            allTools.addAll(vectorTools.getToolSpecifications());

            List<McpServerFeatures.SyncResourceSpecification> allResources = new ArrayList<>();
            allResources.addAll(clusterResources.getResourceSpecifications());

            List<McpServerFeatures.SyncPromptSpecification> allPrompts = new ArrayList<>();
            allPrompts.addAll(builtInPrompts.getPromptSpecifications());

            // 6. Build transport based on config
            String transport = config.getMcp().getServer().getTransport();
            if ("sse".equalsIgnoreCase(transport) || "http".equalsIgnoreCase(transport)) {
                startStreamableHttpTransport(allTools, allResources, allPrompts);
            } else if ("sse-legacy".equalsIgnoreCase(transport)) {
                startSseTransport(allTools, allResources, allPrompts);
            } else {
                startStdioTransport(allTools, allResources, allPrompts);
            }

            logger.info("Hazelcast MCP Server started successfully");
            logger.info("  Transport: {}", transport);
            if ("sse".equalsIgnoreCase(transport) || "http".equalsIgnoreCase(transport)) {
                logger.info("  Streamable HTTP endpoint: http://{}:{}/mcp",
                        config.getMcp().getServer().getHttp().getHost(),
                        config.getMcp().getServer().getHttp().getPort());
            } else if ("sse-legacy".equalsIgnoreCase(transport)) {
                logger.info("  SSE endpoint: http://{}:{}/sse",
                        config.getMcp().getServer().getHttp().getHost(),
                        config.getMcp().getServer().getHttp().getPort());
            }
            logger.info("  Tools registered: {}", allTools.size());
            logger.info("  Resources registered: {}", allResources.size());
            logger.info("  Prompts registered: {}", allPrompts.size());
            logger.info("  Cluster: {} ({} members)",
                    config.getHazelcast().getCluster().getName(),
                    client.getCluster().getMembers().size());

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

            // Keep HTTP server alive (stdio blocks on stdin naturally)
            if ("sse".equalsIgnoreCase(transport) || "http".equalsIgnoreCase(transport)
                    || "sse-legacy".equalsIgnoreCase(transport)) {
                Thread.currentThread().join();
            }

        } catch (Exception e) {
            logger.error("Failed to start Hazelcast MCP Server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private void startStdioTransport(
            List<McpServerFeatures.SyncToolSpecification> tools,
            List<McpServerFeatures.SyncResourceSpecification> resources,
            List<McpServerFeatures.SyncPromptSpecification> prompts) {

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

        mcpServer = McpServer.sync(transportProvider)
                .serverInfo(config.getMcp().getServer().getName(),
                        config.getMcp().getServer().getVersion())
                .capabilities(ServerCapabilities.builder()
                        .resources(false, true)
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .build();
    }

    /**
     * Start Streamable HTTP transport (MCP protocol 2025-06-18).
     * This is the recommended transport for network-accessible MCP servers.
     */
    private void startStreamableHttpTransport(
            List<McpServerFeatures.SyncToolSpecification> tools,
            List<McpServerFeatures.SyncResourceSpecification> resources,
            List<McpServerFeatures.SyncPromptSpecification> prompts) throws Exception {

        int port = config.getMcp().getServer().getHttp().getPort();
        String host = config.getMcp().getServer().getHttp().getHost();

        // Create Streamable HTTP transport provider (supports protocol 2025-06-18)
        HttpServletStreamableServerTransportProvider streamableTransport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                        .mcpEndpoint("/mcp")
                        .build();

        // Build MCP server with Streamable HTTP transport
        mcpServer = McpServer.sync(streamableTransport)
                .serverInfo(config.getMcp().getServer().getName(),
                        config.getMcp().getServer().getVersion())
                .capabilities(ServerCapabilities.builder()
                        .resources(false, true)
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .build();

        // Set up embedded Jetty 12 server
        jettyServer = new Server(port);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder("mcp-streamable", streamableTransport), "/*");
        jettyServer.setHandler(context);

        jettyServer.start();
        logger.info("Jetty Streamable HTTP server listening on {}:{}", host, port);
    }

    /**
     * Start legacy SSE transport (MCP protocol 2024-11-05 only).
     * Use transport: "sse-legacy" in config to use this transport.
     */
    private void startSseTransport(
            List<McpServerFeatures.SyncToolSpecification> tools,
            List<McpServerFeatures.SyncResourceSpecification> resources,
            List<McpServerFeatures.SyncPromptSpecification> prompts) throws Exception {

        int port = config.getMcp().getServer().getHttp().getPort();
        String host = config.getMcp().getServer().getHttp().getHost();

        // Create SSE transport provider (protocol 2024-11-05 only)
        HttpServletSseServerTransportProvider sseTransport =
                HttpServletSseServerTransportProvider.builder()
                        .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                        .messageEndpoint("/mcp/message")
                        .build();

        // Build MCP server with SSE transport
        mcpServer = McpServer.sync(sseTransport)
                .serverInfo(config.getMcp().getServer().getName(),
                        config.getMcp().getServer().getVersion())
                .capabilities(ServerCapabilities.builder()
                        .resources(false, true)
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .build();

        // Set up embedded Jetty 12 server
        jettyServer = new Server(port);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder("mcp-sse", sseTransport), "/*");
        jettyServer.setHandler(context);

        jettyServer.start();
        logger.info("Jetty SSE server listening on {}:{}", host, port);
    }

    public void stop() {
        logger.info("Shutting down Hazelcast MCP Server...");
        try {
            if (jettyServer != null) {
                jettyServer.stop();
            }
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
