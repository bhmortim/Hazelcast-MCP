package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;
import com.hazelcast.sql.SqlRowMetadata;
import com.hazelcast.sql.SqlStatement;
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
 * MCP Tool implementation for Hazelcast SQL query execution (P0-2).
 * Supports SELECT, INSERT, UPDATE, DELETE with parameterized queries and pagination.
 */
public class SqlTools {

    private static final Logger logger = LoggerFactory.getLogger(SqlTools.class);
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final JacksonMcpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final HazelcastInstance client;
    private final AccessController accessController;

    public SqlTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(
                sqlExecute(),
                sqlCreateMapping(),
                sqlDropMapping(),
                sqlShowMappings()
        );
    }

    private McpServerFeatures.SyncToolSpecification sqlExecute() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "SQL query to execute. Supports SELECT, INSERT, UPDATE, DELETE. Use ? for parameters."
                    },
                    "params": {
                      "type": "array",
                      "items": {},
                      "description": "Query parameters (positional, matching ? placeholders)"
                    },
                    "pageSize": {
                      "type": "integer",
                      "description": "Maximum number of rows to return (default: 100)",
                      "default": 100
                    }
                  },
                  "required": ["query"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("sql_execute")
                        .description("Execute a SQL query against Hazelcast and return results as JSON. "
                                + "Supports SELECT, INSERT, UPDATE, DELETE. Use parameterized queries (?) to prevent injection.")
                        .inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String query = (String) args.get("query");
                    @SuppressWarnings("unchecked")
                    List<Object> params = args.get("params") != null ? (List<Object>) args.get("params") : List.of();
                    int pageSize = args.get("pageSize") != null
                            ? ((Number) args.get("pageSize")).intValue() : DEFAULT_PAGE_SIZE;

                    try {
                        if (!accessController.isSqlAllowed()) {
                            return errorResult(accessController.getDenialMessage("sql", ""));
                        }

                        // Check for write operations
                        String upperQuery = query.trim().toUpperCase();
                        if ((upperQuery.startsWith("INSERT") || upperQuery.startsWith("UPDATE")
                                || upperQuery.startsWith("DELETE")) && !accessController.isWriteAllowed()) {
                            return errorResult("Write SQL operations (INSERT/UPDATE/DELETE) are disabled. "
                                    + "Set 'access.operations.write: true' in hazelcast-mcp.yaml to enable.");
                        }

                        SqlStatement statement = new SqlStatement(query);
                        for (Object param : params) {
                            statement.addParameter(param);
                        }

                        try (SqlResult result = client.getSql().execute(statement)) {
                            if (result.updateCount() >= 0) {
                                // DML statement (INSERT, UPDATE, DELETE)
                                return textResult(String.format("Query executed successfully. Rows affected: %d",
                                        result.updateCount()));
                            }

                            // SELECT: collect rows
                            SqlRowMetadata metadata = result.getRowMetadata();
                            List<String> columns = new ArrayList<>();
                            for (int i = 0; i < metadata.getColumnCount(); i++) {
                                columns.add(metadata.getColumn(i).getName());
                            }

                            List<Map<String, Object>> rows = new ArrayList<>();
                            int count = 0;
                            for (SqlRow row : result) {
                                if (count >= pageSize) break;
                                Map<String, Object> rowMap = new LinkedHashMap<>();
                                for (String col : columns) {
                                    rowMap.put(col, JsonSerializer.toJson(row.getObject(col)));
                                }
                                rows.add(rowMap);
                                count++;
                            }

                            Map<String, Object> response = new LinkedHashMap<>();
                            response.put("columns", columns);
                            response.put("rowCount", rows.size());
                            response.put("pageSize", pageSize);
                            response.put("hasMore", count >= pageSize);
                            response.put("rows", rows);

                            return textResult(JsonSerializer.toJsonString(response));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "sql_execute", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification sqlCreateMapping() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mappingName": {
                      "type": "string",
                      "description": "Name of the mapping to create"
                    },
                    "mapName": {
                      "type": "string",
                      "description": "Name of the external IMap"
                    },
                    "keyFormat": {
                      "type": "string",
                      "description": "Key format (e.g., 'varchar')"
                    },
                    "valueFormat": {
                      "type": "string",
                      "description": "Value format (e.g., 'json')"
                    }
                  },
                  "required": ["mappingName", "mapName", "keyFormat", "valueFormat"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("sql_create_mapping")
                        .description("Create a SQL mapping for an external IMap")
                        .inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String mappingName = (String) args.get("mappingName");
                    String mapName = (String) args.get("mapName");
                    String keyFormat = (String) args.get("keyFormat");
                    String valueFormat = (String) args.get("valueFormat");

                    try {
                        if (!accessController.isSqlAllowed()) {
                            return errorResult(accessController.getDenialMessage("sql", ""));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult("Write SQL operations (CREATE MAPPING) are disabled. "
                                    + "Set 'access.operations.write: true' in hazelcast-mcp.yaml to enable.");
                        }

                        String sql = String.format(
                                "CREATE MAPPING IF NOT EXISTS \"%s\" EXTERNAL NAME \"%s\" TYPE IMap OPTIONS ('keyFormat'='%s', 'valueFormat'='%s')",
                                mappingName, mapName, keyFormat, valueFormat
                        );

                        try (SqlResult result = client.getSql().execute(sql)) {
                            return textResult(String.format("Mapping created successfully.%nSQL: %s", sql));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "sql_create_mapping", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification sqlDropMapping() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mappingName": {
                      "type": "string",
                      "description": "Name of the mapping to drop"
                    }
                  },
                  "required": ["mappingName"]
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("sql_drop_mapping")
                        .description("Drop a SQL mapping")
                        .inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String mappingName = (String) args.get("mappingName");

                    try {
                        if (!accessController.isSqlAllowed()) {
                            return errorResult(accessController.getDenialMessage("sql", ""));
                        }
                        if (!accessController.isWriteAllowed()) {
                            return errorResult("Write SQL operations (DROP MAPPING) are disabled. "
                                    + "Set 'access.operations.write: true' in hazelcast-mcp.yaml to enable.");
                        }

                        String sql = String.format("DROP MAPPING IF EXISTS \"%s\"", mappingName);

                        try (SqlResult result = client.getSql().execute(sql)) {
                            return textResult(String.format("Mapping dropped successfully.%nSQL: %s", sql));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "sql_drop_mapping", client));
                    }
                }
        );
    }

    private McpServerFeatures.SyncToolSpecification sqlShowMappings() {
        String schema = """
                {
                  "type": "object",
                  "properties": {}
                }
                """;
        return new McpServerFeatures.SyncToolSpecification(
                Tool.builder().name("sql_show_mappings")
                        .description("List all SQL mappings")
                        .inputSchema(JSON_MAPPER, schema).build(),
                (exchange, request) -> {
                    try {
                        if (!accessController.isSqlAllowed()) {
                            return errorResult(accessController.getDenialMessage("sql", ""));
                        }

                        try (SqlResult result = client.getSql().execute("SHOW MAPPINGS")) {
                            List<String> mappings = new ArrayList<>();
                            for (SqlRow row : result) {
                                String mappingName = row.getObject(0).toString();
                                mappings.add(mappingName);
                            }
                            return textResult(JsonSerializer.toJsonString(mappings));
                        }
                    } catch (Exception e) {
                        return errorResult(ErrorTranslator.translate(e, "sql_show_mappings", client));
                    }
                }
        );
    }

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true, null, null);
    }
}
