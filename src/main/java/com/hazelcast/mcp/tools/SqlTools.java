package com.hazelcast.mcp.tools;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.access.AccessController;
import com.hazelcast.mcp.serialization.JsonSerializer;
import com.hazelcast.mcp.util.ErrorTranslator;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;
import com.hazelcast.sql.SqlRowMetadata;
import com.hazelcast.sql.SqlStatement;
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

    private final HazelcastInstance client;
    private final AccessController accessController;

    public SqlTools(HazelcastInstance client, AccessController accessController) {
        this.client = client;
        this.accessController = accessController;
    }

    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return List.of(sqlExecute());
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
                new Tool("sql_execute",
                        "Execute a SQL query against Hazelcast and return results as JSON. "
                                + "Supports SELECT, INSERT, UPDATE, DELETE. Use parameterized queries (?) to prevent injection.",
                        schema),
                (exchange, args) -> {
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

    private static CallToolResult textResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false);
    }

    private static CallToolResult errorResult(String error) {
        return new CallToolResult(List.of(new TextContent(error)), true);
    }
}
