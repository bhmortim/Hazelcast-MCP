package com.hazelcast.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Configuration model for the Hazelcast MCP Server.
 * Loaded from hazelcast-mcp.yaml or environment variables.
 */
public class McpServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(McpServerConfig.class);
    private static final String DEFAULT_CONFIG_FILE = "hazelcast-mcp.yaml";
    private static final String ENV_PREFIX = "HAZELCAST_MCP_";

    private HazelcastConfig hazelcast = new HazelcastConfig();
    private McpConfig mcp = new McpConfig();
    private AccessConfig access = new AccessConfig();

    // --- Nested config classes ---

    public static class HazelcastConfig {
        private ClusterConfig cluster = new ClusterConfig();
        private SecurityConfig security = new SecurityConfig();
        private TlsConfig tls = new TlsConfig();
        private SerializationConfig serialization = new SerializationConfig();

        public ClusterConfig getCluster() { return cluster; }
        public void setCluster(ClusterConfig cluster) { this.cluster = cluster; }
        public SecurityConfig getSecurity() { return security; }
        public void setSecurity(SecurityConfig security) { this.security = security; }
        public TlsConfig getTls() { return tls; }
        public void setTls(TlsConfig tls) { this.tls = tls; }
        public SerializationConfig getSerialization() { return serialization; }
        public void setSerialization(SerializationConfig serialization) { this.serialization = serialization; }
    }

    public static class ClusterConfig {
        private String name = "dev";
        private List<String> members = List.of("127.0.0.1:5701");

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getMembers() { return members; }
        public void setMembers(List<String> members) { this.members = members; }
    }

    public static class SecurityConfig {
        private String username = "";
        private String password = "";
        private String token = "";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class TlsConfig {
        private boolean enabled = false;
        private String keystore = "";
        private String keystorePassword = "";
        private String keystoreType = "JKS";
        private String truststore = "";
        private String truststorePassword = "";
        private String truststoreType = "JKS";
        private String protocol = "TLSv1.3";
        private boolean mutualAuth = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getKeystore() { return keystore; }
        public void setKeystore(String keystore) { this.keystore = keystore; }
        public String getKeystorePassword() { return keystorePassword; }
        public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }
        public String getKeystoreType() { return keystoreType; }
        public void setKeystoreType(String keystoreType) { this.keystoreType = keystoreType; }
        public String getTruststore() { return truststore; }
        public void setTruststore(String truststore) { this.truststore = truststore; }
        public String getTruststorePassword() { return truststorePassword; }
        public void setTruststorePassword(String truststorePassword) { this.truststorePassword = truststorePassword; }
        public String getTruststoreType() { return truststoreType; }
        public void setTruststoreType(String truststoreType) { this.truststoreType = truststoreType; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public boolean isMutualAuth() { return mutualAuth; }
        public void setMutualAuth(boolean mutualAuth) { this.mutualAuth = mutualAuth; }
    }

    public static class SerializationConfig {
        private CompactConfig compact = new CompactConfig();

        public CompactConfig getCompact() { return compact; }
        public void setCompact(CompactConfig compact) { this.compact = compact; }
    }

    public static class CompactConfig {
        private List<String> classes = List.of();

        public List<String> getClasses() { return classes; }
        public void setClasses(List<String> classes) { this.classes = classes; }
    }

    public static class McpConfig {
        private ServerInfo server = new ServerInfo();
        private List<CustomPromptConfig> prompts = List.of();

        public ServerInfo getServer() { return server; }
        public void setServer(ServerInfo server) { this.server = server; }
        public List<CustomPromptConfig> getPrompts() { return prompts; }
        public void setPrompts(List<CustomPromptConfig> prompts) { this.prompts = prompts; }
    }

    public static class CustomPromptConfig {
        private String name = "";
        private String description = "";
        private String template = "";
        private List<PromptArgumentConfig> arguments = List.of();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTemplate() { return template; }
        public void setTemplate(String template) { this.template = template; }
        public List<PromptArgumentConfig> getArguments() { return arguments; }
        public void setArguments(List<PromptArgumentConfig> arguments) { this.arguments = arguments; }
    }

    public static class PromptArgumentConfig {
        private String name = "";
        private String description = "";
        private boolean required = false;
        private String defaultValue = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    }

    public static class ServerInfo {
        private String name = "hazelcast-mcp-server";
        private String version = "1.0.0";
        private String transport = "stdio";
        private HttpConfig http = new HttpConfig();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public HttpConfig getHttp() { return http; }
        public void setHttp(HttpConfig http) { this.http = http; }
    }

    public static class HttpConfig {
        private int port = 8080;
        private String host = "0.0.0.0";

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
    }

    public static class AccessConfig {
        private String mode = "all"; // "all", "allowlist", "denylist"
        private AllowlistConfig allowlist = new AllowlistConfig();
        private DenylistConfig denylist = new DenylistConfig();
        private OperationsConfig operations = new OperationsConfig();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public AllowlistConfig getAllowlist() { return allowlist; }
        public void setAllowlist(AllowlistConfig allowlist) { this.allowlist = allowlist; }
        public DenylistConfig getDenylist() { return denylist; }
        public void setDenylist(DenylistConfig denylist) { this.denylist = denylist; }
        public OperationsConfig getOperations() { return operations; }
        public void setOperations(OperationsConfig operations) { this.operations = operations; }
    }

    public static class AllowlistConfig {
        private List<String> maps = List.of();
        private List<String> queues = List.of();
        private List<String> vectors = List.of();
        private List<String> lists = List.of();
        private List<String> sets = List.of();
        private List<String> multimaps = List.of();
        private List<String> topics = List.of();
        private List<String> ringbuffers = List.of();
        private List<String> atomics = List.of();

        public List<String> getMaps() { return maps; }
        public void setMaps(List<String> maps) { this.maps = maps; }
        public List<String> getQueues() { return queues; }
        public void setQueues(List<String> queues) { this.queues = queues; }
        public List<String> getVectors() { return vectors; }
        public void setVectors(List<String> vectors) { this.vectors = vectors; }
        public List<String> getLists() { return lists; }
        public void setLists(List<String> lists) { this.lists = lists; }
        public List<String> getSets() { return sets; }
        public void setSets(List<String> sets) { this.sets = sets; }
        public List<String> getMultimaps() { return multimaps; }
        public void setMultimaps(List<String> multimaps) { this.multimaps = multimaps; }
        public List<String> getTopics() { return topics; }
        public void setTopics(List<String> topics) { this.topics = topics; }
        public List<String> getRingbuffers() { return ringbuffers; }
        public void setRingbuffers(List<String> ringbuffers) { this.ringbuffers = ringbuffers; }
        public List<String> getAtomics() { return atomics; }
        public void setAtomics(List<String> atomics) { this.atomics = atomics; }
    }

    public static class DenylistConfig {
        private List<String> maps = List.of();
        private List<String> queues = List.of();
        private List<String> lists = List.of();
        private List<String> sets = List.of();
        private List<String> multimaps = List.of();
        private List<String> topics = List.of();
        private List<String> ringbuffers = List.of();
        private List<String> atomics = List.of();

        public List<String> getMaps() { return maps; }
        public void setMaps(List<String> maps) { this.maps = maps; }
        public List<String> getQueues() { return queues; }
        public void setQueues(List<String> queues) { this.queues = queues; }
        public List<String> getLists() { return lists; }
        public void setLists(List<String> lists) { this.lists = lists; }
        public List<String> getSets() { return sets; }
        public void setSets(List<String> sets) { this.sets = sets; }
        public List<String> getMultimaps() { return multimaps; }
        public void setMultimaps(List<String> multimaps) { this.multimaps = multimaps; }
        public List<String> getTopics() { return topics; }
        public void setTopics(List<String> topics) { this.topics = topics; }
        public List<String> getRingbuffers() { return ringbuffers; }
        public void setRingbuffers(List<String> ringbuffers) { this.ringbuffers = ringbuffers; }
        public List<String> getAtomics() { return atomics; }
        public void setAtomics(List<String> atomics) { this.atomics = atomics; }
    }

    public static class OperationsConfig {
        private boolean sql = true;
        private boolean write = true;
        private boolean clear = false; // destructive ops disabled by default

        public boolean isSql() { return sql; }
        public void setSql(boolean sql) { this.sql = sql; }
        public boolean isWrite() { return write; }
        public void setWrite(boolean write) { this.write = write; }
        public boolean isClear() { return clear; }
        public void setClear(boolean clear) { this.clear = clear; }
    }

    // --- Top-level getters/setters ---

    public HazelcastConfig getHazelcast() { return hazelcast; }
    public void setHazelcast(HazelcastConfig hazelcast) { this.hazelcast = hazelcast; }
    public McpConfig getMcp() { return mcp; }
    public void setMcp(McpConfig mcp) { this.mcp = mcp; }
    public AccessConfig getAccess() { return access; }
    public void setAccess(AccessConfig access) { this.access = access; }

    // --- Loading ---

    /**
     * Load configuration from file, then apply environment variable overrides.
     */
    public static McpServerConfig load(String configPath) {
        McpServerConfig config;
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        try {
            Path path = configPath != null ? Path.of(configPath) : Path.of(DEFAULT_CONFIG_FILE);
            if (Files.exists(path)) {
                logger.info("Loading configuration from: {}", path.toAbsolutePath());
                config = yamlMapper.readValue(path.toFile(), McpServerConfig.class);
            } else {
                // Try classpath
                InputStream is = McpServerConfig.class.getClassLoader()
                        .getResourceAsStream(DEFAULT_CONFIG_FILE);
                if (is != null) {
                    logger.info("Loading configuration from classpath: {}", DEFAULT_CONFIG_FILE);
                    config = yamlMapper.readValue(is, McpServerConfig.class);
                } else {
                    logger.info("No configuration file found, using defaults");
                    config = new McpServerConfig();
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load configuration file, using defaults: {}", e.getMessage());
            config = new McpServerConfig();
        }

        // Apply environment variable overrides
        applyEnvOverrides(config);
        return config;
    }

    private static void applyEnvOverrides(McpServerConfig config) {
        String clusterName = System.getenv(ENV_PREFIX + "CLUSTER_NAME");
        if (clusterName != null) {
            config.getHazelcast().getCluster().setName(clusterName);
        }

        String clusterMembers = System.getenv(ENV_PREFIX + "CLUSTER_MEMBERS");
        if (clusterMembers != null) {
            config.getHazelcast().getCluster().setMembers(List.of(clusterMembers.split(",")));
        }

        String username = System.getenv(ENV_PREFIX + "SECURITY_USERNAME");
        if (username != null) {
            config.getHazelcast().getSecurity().setUsername(username);
        }

        String password = System.getenv(ENV_PREFIX + "SECURITY_PASSWORD");
        if (password != null) {
            config.getHazelcast().getSecurity().setPassword(password);
        }

        String token = System.getenv(ENV_PREFIX + "SECURITY_TOKEN");
        if (token != null) {
            config.getHazelcast().getSecurity().setToken(token);
        }

        String transport = System.getenv(ENV_PREFIX + "TRANSPORT");
        if (transport != null) {
            config.getMcp().getServer().setTransport(transport);
        }

        String accessMode = System.getenv(ENV_PREFIX + "ACCESS_MODE");
        if (accessMode != null) {
            config.getAccess().setMode(accessMode);
        }
    }
}
