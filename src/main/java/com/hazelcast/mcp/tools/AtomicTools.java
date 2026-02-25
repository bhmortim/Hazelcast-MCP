package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.hazelcast.core.HazelcastException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP Tool implementation for Hazelcast CP Subsystem AtomicLong operations.
 * Supports atomic get, set, increment, decrement, and compare-and-set operations.
 */
public class AtomicTools {

    private static final Logger logger = LoggerFactory.getLogger(AtomicTools.class);
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());
    private static final String CP_SUBSYSTEM_ERROR = "CP Subsystem is not configured on this cluster. AtomicLong requires CP Subsystem to be enabled.";

    private final HazelcastInstance client;
    private final AccessController accessController;

    public AtomicTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                atomicGet(),
                atomicSet(),
                atomicIncrement(),
                atomicDecrement(),
                atomicCompareAndSet()
        );
    }

    // --- atomic_get ---
    private McpServerFeatures.SyncToolSpecification atomicGet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "description": "Name of the AtomicLong" }
                  },
                  "required": ["name"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("atomic_get").description("Get the current value of an AtomicLong").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String name = (String) args.get("name");
                    try {
                        if (!accessController.isAtomicAccessible(name)) {
                            return errorResult(accessController.getDenialMessage("get", name));
                        }
                        IAtomicLong atomic = client.getCPSubsystem().getAtomicLong(name);
                        long value = atomic.get();
                        return textResult(JsonSerializer.toJsonString(Map.of("name", name, "value", value)));
                    } catch (HazelcastException e) {
                        if (e.getMessage() != null && e.getMessage().contains("CP Subsystem")) {
                            return errorResult(CP_SUBSYSTEM_ERROR);
                        }
                        return errorResult(ErrorTranslator.translate(e, "atomic_get", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "atomic_get", client));
                    }
                }
        );
    }

    // --- atomic_set ---
    private McpServerFeatures.SyncToolSpecification atomicSet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "description": "Name of the AtomicLong" },
                    "value": { "type": "integer", "description": "Value to set" }
                  },
                  "required": ["name", "value"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("atomic_set").description("Set the value of an AtomicLong").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String name = (String) args.get("name");
                    long value = ((Number) args.get("value")).longValue();
                    try {
                        if (!accessController.isAtomicAccessible(name)) {
                            return errorResult(accessController.getDenialMessage("set", name));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("set", name));
                        }
                        IAtomicLong atomic = client.getCPSubsystem().getAtomicLong(name);
                        atomic.set(value);
                        return textResult(String.format("Set AtomicLong '%s' to %d", name, value));
                    } catch (HazelcastException e) {
                        if (e.getMessage() != null && e.getMessage().contains("CP Subsystem")) {
                            return errorResult(CP_SUBSYSTEM_ERROR);
                        }
                        return errorResult(ErrorTranslator.translate(e, "atomic_set", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "atomic_set", client));
                    }
                }
        );
    }

    // --- atomic_increment ---
    private McpServerFeatures.SyncToolSpecification atomicIncrement() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "description": "Name of the AtomicLong" }
                  },
                  "required": ["name"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("atomic_increment").description("Increment an AtomicLong and return new value").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String name = (String) args.get("name");
                    try {
                        if (!accessController.isAtomicAccessible(name)) {
                            return errorResult(accessController.getDenialMessage("increment", name));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("increment", name));
                        }
                        IAtomicLong atomic = client.getCPSubsystem().getAtomicLong(name);
                        long newValue = atomic.incrementAndGet();
                        return textResult(JsonSerializer.toJsonString(Map.of("name", name, "newValue", newValue)));
                    } catch (HazelcastException e) {
                        if (e.getMessage() != null && e.getMessage().contains("CP Subsystem")) {
                            return errorResult(CP_SUBSYSTEM_ERROR);
                        }
                        return errorResult(ErrorTranslator.translate(e, "atomic_increment", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "atomic_increment", client));
                    }
                }
        );
    }

    // --- atomic_decrement ---
    private McpServerFeatures.SyncToolSpecification atomicDecrement() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "description": "Name of the AtomicLong" }
                  },
                  "required": ["name"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("atomic_decrement").description("Decrement an AtomicLong and return new value").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String name = (String) args.get("name");
                    try {
                        if (!accessController.isAtomicAccessible(name)) {
                            return errorResult(accessController.getDenialMessage("decrement", name));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("decrement", name));
                        }
                        IAtomicLong atomic = client.getCPSubsystem().getAtomicLong(name);
                        long newValue = atomic.decrementAndGet();
                        return textResult(JsonSerializer.toJsonString(Map.of("name", name, "newValue", newValue)));
                    } catch (HazelcastException e) {
                        if (e.getMessage() != null && e.getMessage().contains("CP Subsystem")) {
                            return errorResult(CP_SUBSYSTEM_ERROR);
                        }
                        return errorResult(ErrorTranslator.translate(e, "atomic_decrement", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "atomic_decrement", client));
                    }
                }
        );
    }

    // --- atomic_compare_and_set ---
    private McpServerFeatures.SyncToolSpecification atomicCompareAndSet() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string", "description": "Name of the AtomicLong" },
                    "expected": { "type": "integer", "description": "Expected current value" },
                    "update": { "type": "integer", "description": "New value to set if expected matches" }
                  },
                  "required": ["name", "expected", "update"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("atomic_compare_and_set").description("Atomic compare-and-set operation on AtomicLong").inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String name = (String) args.get("name");
                    long expected = ((Number) args.get("expected")).longValue();
                    long update = ((Number) args.get("update")).longValue();
                    try {
                        if (!accessController.isAtomicAccessible(name)) {
                            return errorResult(accessController.getDenialMessage("compare_and_set", name));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult(accessController.getDenialMessage("compare_and_set", name));
                        }
                        IAtomicLong atomic = client.getCPSubsystem().getAtomicLong(name);
                        boolean success = atomic.compareAndSet(expected, update);
                        return textResult(JsonSerializer.toJsonString(Map.of(
                                "name", name,
                                "expected", expected,
                                "update", update,
                                "success", success
                        )));
                    } catch (HazelcastException e) {
                        if (e.getMessage() != null && e.getMessage().contains("CP Subsystem")) {
                            return errorResult(CP_SUBSYSTEM_ERROR);
                        }
                        return errorResult(ErrorTranslator.translate(e, "atomic_compare_and_set", client));
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "atomic_compare_and_set", client));
                    }
                }
        );
    }

    // --- Helpers ---

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true, null, null);
    }
}
