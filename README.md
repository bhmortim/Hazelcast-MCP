# Hazelcast Client MCP Server

An open-source [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that wraps the Hazelcast Java Client, enabling AI agents (Claude, Cursor, VS Code Copilot, custom LangChain agents) to interact with Hazelcast clusters via natural language tools.

## Features

**Tools** (15 operations AI agents can invoke):
- **IMap CRUD**: `map_get`, `map_put`, `map_delete`, `map_get_all`, `map_put_all`, `map_size`, `map_keys`, `map_values`, `map_contains_key`, `map_clear`
- **SQL**: `sql_execute` — SELECT, INSERT, UPDATE, DELETE with parameterized queries and pagination
- **VectorCollection**: `vector_search`, `vector_put`, `vector_get`, `vector_delete` (requires Hazelcast 5.7+)

**Resources** (read-only cluster context):
- `hazelcast://cluster/info` — Cluster name, version, member list
- `hazelcast://cluster/health` — Connection status and latency
- `hazelcast://structures/list` — All distributed objects with type and size

**Prompts** (reusable interaction patterns):
- `cache-lookup` — Check cache hit/miss with explanation
- `data-exploration` — Discover and sample cluster data
- `vector-search` — Semantic similarity search workflow

## Quick Start

### Prerequisites
- Java 17+
- A running Hazelcast cluster (5.5+)

### Build
```bash
./mvnw clean package -DskipTests
```

### Configure

Create `hazelcast-mcp.yaml` (or use environment variables):

```yaml
hazelcast:
  cluster:
    name: "dev"
    members:
      - "127.0.0.1:5701"

mcp:
  server:
    name: "hazelcast-mcp-server"
    version: "1.0.0"
    transport: "stdio"

access:
  mode: "all"
  operations:
    sql: true
    write: true
    clear: false  # destructive ops disabled by default
```

### Run with Claude Desktop

Add to your Claude Desktop MCP config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "hazelcast": {
      "command": "java",
      "args": ["-jar", "/path/to/hazelcast-mcp-server-1.0.0-SNAPSHOT.jar"],
      "env": {
        "HAZELCAST_MCP_CLUSTER_NAME": "dev",
        "HAZELCAST_MCP_CLUSTER_MEMBERS": "127.0.0.1:5701"
      }
    }
  }
}
```

### Run with Cursor

Add to `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "hazelcast": {
      "command": "java",
      "args": ["-jar", "/path/to/hazelcast-mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

## Configuration Reference

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `HAZELCAST_MCP_CLUSTER_NAME` | Cluster name | `dev` |
| `HAZELCAST_MCP_CLUSTER_MEMBERS` | Comma-separated member addresses | `127.0.0.1:5701` |
| `HAZELCAST_MCP_SECURITY_USERNAME` | Authentication username | (empty) |
| `HAZELCAST_MCP_SECURITY_PASSWORD` | Authentication password | (empty) |
| `HAZELCAST_MCP_SECURITY_TOKEN` | Token-based auth | (empty) |
| `HAZELCAST_MCP_TRANSPORT` | Transport type (`stdio` or `http`) | `stdio` |
| `HAZELCAST_MCP_ACCESS_MODE` | Access control mode (`all`, `allowlist`, `denylist`) | `all` |

### Access Control

Restrict which data structures and operations are exposed:

```yaml
access:
  mode: "allowlist"
  allowlist:
    maps: ["session-cache", "user-profiles"]
    vectors: ["document-embeddings"]
  operations:
    sql: true
    write: false   # read-only mode
    clear: false   # destructive ops always require explicit enable
```

## Architecture

```
AI Agent (Claude, Cursor, etc.)
    │ MCP Protocol (stdio)
    ▼
Hazelcast Client MCP Server
    ├── Tool Registry (map_*, sql_*, vector_*)
    ├── Resource Provider (cluster info, structures)
    ├── Prompt Templates (cache-lookup, data-exploration)
    ├── Access Controller (allowlist/denylist)
    └── Hazelcast Client Adapter
            │ Hazelcast Binary Protocol
            ▼
    Hazelcast Cluster
```

## Technology Stack

- **Language**: Java 17+
- **MCP SDK**: [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) v0.12.1
- **Hazelcast Client**: Hazelcast 5.5+
- **Build**: Maven, publishes fat JAR
- **Transport**: stdio (local), Streamable HTTP (planned)

## License

Apache License 2.0
