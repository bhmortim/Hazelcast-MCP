package com.hazelcast.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigTest {

    @Test
    void defaultConfigHasExpectedValues() {
        McpServerConfig config = new McpServerConfig();

        assertEquals("dev", config.getHazelcast().getCluster().getName());
        assertEquals(1, config.getHazelcast().getCluster().getMembers().size());
        assertEquals("127.0.0.1:5701", config.getHazelcast().getCluster().getMembers().get(0));
        assertEquals("hazelcast-mcp-server", config.getMcp().getServer().getName());
        assertEquals("1.0.0", config.getMcp().getServer().getVersion());
        assertEquals("stdio", config.getMcp().getServer().getTransport());
        assertEquals("all", config.getAccess().getMode());
        assertTrue(config.getAccess().getOperations().isSql());
        assertTrue(config.getAccess().getOperations().isWrite());
        assertFalse(config.getAccess().getOperations().isClear());
    }

    @Test
    void loadFromYamlFile(@TempDir Path tempDir) throws IOException {
        String yaml = """
                hazelcast:
                  cluster:
                    name: production
                    members:
                      - 10.0.0.1:5701
                      - 10.0.0.2:5701
                  security:
                    username: admin
                    password: secret
                  tls:
                    enabled: true
                    keystore: /path/to/keystore.jks
                    keystorePassword: kspass
                    truststore: /path/to/truststore.jks
                    truststorePassword: tspass
                    protocol: TLSv1.2
                    mutualAuth: true
                mcp:
                  server:
                    name: test-server
                    version: 2.0.0
                  prompts:
                    - name: custom-prompt
                      description: A custom prompt
                      template: "Hello {{name}}"
                      arguments:
                        - name: name
                          description: User name
                          required: true
                          defaultValue: World
                access:
                  mode: allowlist
                  allowlist:
                    maps:
                      - session-cache
                      - user-data
                    vectors:
                      - embeddings
                  operations:
                    sql: false
                    write: true
                    clear: true
                """;

        Path configFile = tempDir.resolve("hazelcast-mcp.yaml");
        Files.writeString(configFile, yaml);

        McpServerConfig config = McpServerConfig.load(configFile.toString());

        assertEquals("production", config.getHazelcast().getCluster().getName());
        assertEquals(2, config.getHazelcast().getCluster().getMembers().size());
        assertEquals("admin", config.getHazelcast().getSecurity().getUsername());
        assertEquals("secret", config.getHazelcast().getSecurity().getPassword());
        assertTrue(config.getHazelcast().getTls().isEnabled());
        assertEquals("/path/to/keystore.jks", config.getHazelcast().getTls().getKeystore());
        assertEquals("kspass", config.getHazelcast().getTls().getKeystorePassword());
        assertEquals("TLSv1.2", config.getHazelcast().getTls().getProtocol());
        assertTrue(config.getHazelcast().getTls().isMutualAuth());
        assertEquals("test-server", config.getMcp().getServer().getName());
        assertEquals("2.0.0", config.getMcp().getServer().getVersion());
        assertEquals("allowlist", config.getAccess().getMode());
        assertEquals(2, config.getAccess().getAllowlist().getMaps().size());
        assertFalse(config.getAccess().getOperations().isSql());
        assertTrue(config.getAccess().getOperations().isClear());

        // Custom prompts
        assertEquals(1, config.getMcp().getPrompts().size());
        McpServerConfig.CustomPromptConfig prompt = config.getMcp().getPrompts().get(0);
        assertEquals("custom-prompt", prompt.getName());
        assertEquals("Hello {{name}}", prompt.getTemplate());
        assertEquals(1, prompt.getArguments().size());
        assertTrue(prompt.getArguments().get(0).isRequired());
    }

    @Test
    void loadMissingFileReturnsDefaults() {
        McpServerConfig config = McpServerConfig.load("/nonexistent/path.yaml");
        assertEquals("dev", config.getHazelcast().getCluster().getName());
    }

    @Test
    void tlsConfigDefaults() {
        McpServerConfig.TlsConfig tls = new McpServerConfig.TlsConfig();
        assertFalse(tls.isEnabled());
        assertEquals("JKS", tls.getKeystoreType());
        assertEquals("JKS", tls.getTruststoreType());
        assertEquals("TLSv1.3", tls.getProtocol());
        assertFalse(tls.isMutualAuth());
    }
}
