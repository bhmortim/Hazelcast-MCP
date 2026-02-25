package com.hazelcast.mcp.prompts;

import com.hazelcast.mcp.config.McpServerConfig;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltInPromptsTest {

    @Test
    void builtInPromptsRegistered() {
        BuiltInPrompts prompts = new BuiltInPrompts();
        List<McpServerFeatures.SyncPromptSpecification> specs = prompts.getPromptSpecifications();

        assertEquals(3, specs.size());

        List<String> names = specs.stream()
                .map(s -> s.prompt().name())
                .toList();

        assertTrue(names.contains("cache-lookup"));
        assertTrue(names.contains("data-exploration"));
        assertTrue(names.contains("vector-search"));
    }

    @Test
    void cacheLookupPromptHasRequiredArguments() {
        BuiltInPrompts prompts = new BuiltInPrompts();
        Prompt cacheLookup = prompts.getPromptSpecifications().stream()
                .filter(s -> s.prompt().name().equals("cache-lookup"))
                .findFirst()
                .orElseThrow()
                .prompt();

        assertEquals(2, cacheLookup.arguments().size());
        assertTrue(cacheLookup.arguments().stream().anyMatch(a -> a.name().equals("mapName")));
        assertTrue(cacheLookup.arguments().stream().anyMatch(a -> a.name().equals("key")));
    }

    @Test
    void customPromptsLoadedFromConfig() {
        McpServerConfig.PromptArgumentConfig arg = new McpServerConfig.PromptArgumentConfig();
        arg.setName("greeting");
        arg.setDescription("Greeting text");
        arg.setRequired(true);
        arg.setDefaultValue("Hello");

        McpServerConfig.CustomPromptConfig custom = new McpServerConfig.CustomPromptConfig();
        custom.setName("my-prompt");
        custom.setDescription("Test custom prompt");
        custom.setTemplate("Say {{greeting}} to the cluster");
        custom.setArguments(List.of(arg));

        BuiltInPrompts prompts = new BuiltInPrompts(List.of(custom));
        List<McpServerFeatures.SyncPromptSpecification> specs = prompts.getPromptSpecifications();

        // 3 built-in + 1 custom
        assertEquals(4, specs.size());

        McpServerFeatures.SyncPromptSpecification customSpec = specs.stream()
                .filter(s -> s.prompt().name().equals("my-prompt"))
                .findFirst()
                .orElseThrow();

        assertEquals("Test custom prompt", customSpec.prompt().description());
        assertEquals(1, customSpec.prompt().arguments().size());
    }

    @Test
    void customPromptTemplateSubstitution() {
        McpServerConfig.PromptArgumentConfig arg = new McpServerConfig.PromptArgumentConfig();
        arg.setName("mapName");
        arg.setDescription("Map name");
        arg.setRequired(true);
        arg.setDefaultValue("default-map");

        McpServerConfig.CustomPromptConfig custom = new McpServerConfig.CustomPromptConfig();
        custom.setName("test-template");
        custom.setDescription("Test template");
        custom.setTemplate("Check map {{mapName}} in the cluster");
        custom.setArguments(List.of(arg));

        BuiltInPrompts prompts = new BuiltInPrompts(List.of(custom));

        McpServerFeatures.SyncPromptSpecification spec = prompts.getPromptSpecifications().stream()
                .filter(s -> s.prompt().name().equals("test-template"))
                .findFirst()
                .orElseThrow();

        // Call the handler with arguments
        GetPromptRequest request = new GetPromptRequest("test-template", Map.of("mapName", "session-cache"));
        GetPromptResult result = spec.promptHandler().apply(null, request);

        assertNotNull(result);
        assertEquals(1, result.messages().size());
        String content = ((TextContent) result.messages().get(0).content()).text();
        assertTrue(content.contains("session-cache"));
        assertFalse(content.contains("{{mapName}}"));
    }

    @Test
    void customPromptUsesDefaultWhenArgMissing() {
        McpServerConfig.PromptArgumentConfig arg = new McpServerConfig.PromptArgumentConfig();
        arg.setName("name");
        arg.setDescription("Name");
        arg.setRequired(false);
        arg.setDefaultValue("World");

        McpServerConfig.CustomPromptConfig custom = new McpServerConfig.CustomPromptConfig();
        custom.setName("greeting");
        custom.setDescription("Greeting");
        custom.setTemplate("Hello {{name}}!");
        custom.setArguments(List.of(arg));

        BuiltInPrompts prompts = new BuiltInPrompts(List.of(custom));

        McpServerFeatures.SyncPromptSpecification spec = prompts.getPromptSpecifications().stream()
                .filter(s -> s.prompt().name().equals("greeting"))
                .findFirst()
                .orElseThrow();

        // Call without providing the arg
        GetPromptRequest request = new GetPromptRequest("greeting", Map.of());
        GetPromptResult result = spec.promptHandler().apply(null, request);
        String content = ((TextContent) result.messages().get(0).content()).text();
        assertTrue(content.contains("Hello World!"));
    }

    @Test
    void noCustomPromptsIsHandledGracefully() {
        BuiltInPrompts prompts = new BuiltInPrompts(null);
        assertEquals(3, prompts.getPromptSpecifications().size());
    }
}
