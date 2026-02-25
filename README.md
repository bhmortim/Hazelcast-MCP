# Hazelcast Client MCP Server

An open-source [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that wraps the Hazelcast Java Client, enabling AI agents (Claude, Cursor, VS Code Copilot, custom LangChain agents) to interact with Hazelcast clusters via natural language tools.

## Docker Quickstart

The fastest way to try the MCP server — zero setup, includes demo data:

```bash
docker compose up
```

This spins up:

1. **Hazelcast** — Single node cluster with Jet enabled
2. **Demo Data Loader** — Populates realistic financial/trading data, then exits
3. **MCP Server** — Connected to Hazelcast, ready for AI agent integration

### What's in the demo data?

The loader populates four IMaps with a financial trading dataset, then runs Jet batch jobs:

| IMap | Contents | Records |
|------|----------|---------|
| `market-data` | Live quotes — bid, ask, last, volume, change | 12 symbols |
| `trades` | Trade blotter — symbol, side, qty, price, account | ~80 trades |
| `positions` | Portfolio positions — computed from trades, with P&L | ~15 positions |
| `risk-metrics` | Risk aggregations — VaR, Sharpe, exposure per account | 3 accounts |

Symbols include AAPL, GOOGL, MSFT, AMZN, TSLA, JPM, GS, NVDA, META, NFLX, BAC, INTC across three hedge fund / asset management accounts.

### Connect Claude Desktop (Docker + SSE)

The easiest way to connect Claude Desktop to the Docker setup is via SSE transport:

**Step 1:** Start the stack in SSE mode:
```bash
HAZELCAST_MCP_TRANSPORT=sse docker compose up
```

**Step 2:** Add to your Claude Desktop config (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "hazelcast": {
      "url": "http://localhost:3000/sse"
    }
  }
}
```

**Step 3:** Restart Claude Desktop. You should see "hazelcast" in your MCP servers list.

### Try these prompts in Claude

Once connected, try asking Claude:

```
"Show me all ticker symbols in the market-data map"
"Get Apple's current market data"
"Find all Tesla buy trades using SQL"
"What's the risk profile for account ACCT-HF-001?"
"How many open positions do we have?"
"Show me the top 5 trades by notional value"
"List all data structures in the cluster"
```

These map to MCP tool calls like `map/keys market-data`, `map/get trades TRD-00001`,
`sql SELECT * FROM trades WHERE symbol='TSLA' AND side='BUY'`, etc.

### Connect Claude Desktop (Docker + stdio)

If you prefer stdio transport, you can use `docker exec`:

```json
{
  "mcpServers": {
    "hazelcast": {
      "command": "docker",
      "args": ["exec", "-i", "hazelcast-mcp-server",
               "java", "-Xmx512m", "-cp", "hazelcast-mcp-server.jar",
               "com.hazelcast.mcp.server.HazelcastMcpServer",
               "/app/hazelcast-mcp.yaml"]
    }
  }
}
```

### SSE mode (network-accessible)

To expose the MCP server over HTTP with Server-Sent Events:

```bash
HAZELCAST_MCP_TRANSPORT=sse docker compose up
```

The SSE endpoint will be available at `http://localhost:3000/sse`. Any MCP client that supports SSE can connect to this URL.

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

**Transports**:
- **stdio** — Standard MCP transport for local AI clients (Claude Desktop, Cursor)
- **SSE** — HTTP Server-Sent Events for network-accessible MCP (embedded Jetty)

## Quick Start (without Docker)

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
    transport: "stdio"    # or "sse" for HTTP
    http:
      port: 3000
      host: "0.0.0.0"

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

### SSE mode with Claude Desktop

For network-accessible MCP via SSE, start the server with SSE transport:

```bash
HAZELCAST_MCP_TRANSPORT=sse java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar
```

Then configure Claude Desktop to connect via SSE:

```json
{
  "mcpServers": {
    "hazelcast": {
      "url": "http://localhost:3000/sse"
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
| `HAZELCAST_MCP_TRANSPORT` | Transport type (`stdio` or `sse`) | `stdio` |
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
    │ MCP Protocol (stdio or SSE)
    ▼
Hazelcast Client MCP Server
    ├── Tool Registry (map_*, sql_*, vector_*)
    ├── Resource Provider (cluster info, structures)
    ├── Prompt Templates (cache-lookup, data-exploration)
    ├── Access Controller (allowlist/denylist)
    ├── Transport Layer (stdio / embedded Jetty SSE)
    └── Hazelcast Client Adapter
            │ Hazelcast Binary Protocol
            ▼
    Hazelcast Cluster
```

## Technology Stack

- **Language**: Java 17+
- **MCP SDK**: [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) v0.12.1
- **Hazelcast Client**: Hazelcast 5.5+
- **Transport**: stdio (local), SSE via embedded Jetty 11
- **Build**: Maven, publishes fat JAR
- **Docker**: Multi-stage build, Compose quickstart with demo data

## License

Apache License 2.0
