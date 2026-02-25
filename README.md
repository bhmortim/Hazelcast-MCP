# Hazelcast Client MCP Server

An open-source [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that wraps the Hazelcast Java Client, enabling AI agents (Claude, Cursor, VS Code Copilot, custom LangChain agents) to interact with Hazelcast clusters via natural language tools.

## Docker Quickstart

The fastest way to try the MCP server — zero setup, includes demo data:

```bash
docker compose up
```

This spins up:

1. **Hazelcast** — Single node cluster with Jet enabled
2. **Demo Data Loader + Live Feeds** — Seeds financial data, then streams live crypto/stock/news
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

### Live Data Feeds

After loading seed data, the demo container starts streaming **real-time data** from public APIs:

| Feed | API | Key Required? | Polling Interval | IMap |
|------|-----|---------------|-----------------|------|
| **Crypto** | [CoinGecko](https://www.coingecko.com/en/api) | No | 30 seconds | `crypto` |
| **Stocks** | [Alpha Vantage](https://www.alphavantage.co/support/#api-key) | Yes (free) | 5 minutes | `stocks` |
| **News** | [NewsAPI](https://newsapi.org/register) | Yes (free) | 10 minutes | `news` |
| **Insights** | Jet pipeline (internal) | No | 5 minutes | `market-insights` |

**Crypto works out of the box** — no API keys needed. Stocks and news are optional enrichments.

To enable all feeds, pass your free API keys:

```bash
ALPHA_VANTAGE_API_KEY=your_key NEWS_API_KEY=your_key docker compose up
```

**Get free API keys:**
- Alpha Vantage: [alphavantage.co/support/#api-key](https://www.alphavantage.co/support/#api-key) (25 requests/day)
- NewsAPI: [newsapi.org/register](https://newsapi.org/register) (100 requests/day)

#### Live data prompts for Claude

```
"What's Bitcoin trading at right now?"
"Show me all crypto prices"
"What are the latest market headlines?"
"How is the market sentiment today?"
"Compare crypto vs stock performance"
"Who are the biggest movers right now?"
"Give me a full market briefing"
```

### Connect Claude Desktop (Docker + Streamable HTTP)

The easiest way to connect Claude Desktop to the Docker setup is via Streamable HTTP transport.
Claude Desktop only speaks stdio to child processes, so we use `mcp-remote` as a
stdio-to-HTTP bridge (requires [Node.js](https://nodejs.org/) 18+).

**Step 1:** Start the stack in HTTP mode:
```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse docker compose up

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; docker compose up
```

**Step 2:** Add to your Claude Desktop config:

| OS | Config file path |
|----|-----------------|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |

```json
{
  "mcpServers": {
    "hazelcast": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:3000/mcp"]
    }
  }
}
```

> **Note:** `mcp-remote` is auto-installed on first run via `npx -y`. If you already
> have other entries in `mcpServers`, merge the `"hazelcast"` key into the existing object.

**Step 3:** Restart Claude Desktop (fully quit and reopen). Look for the hammer icon
in the chat input — click it to verify the Hazelcast tools are listed.

**Troubleshooting:**
- **No hammer icon?** Make sure Docker is running and the MCP server container is healthy
  (`docker compose ps`). The HTTP endpoint must be available _before_ Claude Desktop starts.
- **JSON parse error on launch?** Validate your config at [jsonlint.com](https://jsonlint.com/).
  A common mistake is a missing comma between `"preferences"` and `"mcpServers"` blocks.
- **Windows `HAZELCAST_MCP_TRANSPORT=sse` not recognized?** PowerShell requires the
  `$env:VAR="value"; command` syntax shown above. The Unix `VAR=value command` form
  only works in bash/zsh.

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

### Streamable HTTP mode (network-accessible)

To expose the MCP server over Streamable HTTP (MCP protocol 2025-06-18):

```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse docker compose up

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; docker compose up
```

The Streamable HTTP endpoint will be available at `http://localhost:3000/mcp`. Any MCP
client that supports Streamable HTTP can connect directly. For Claude Desktop, use the
`mcp-remote` bridge as described above.

> **Legacy SSE:** If you need the older SSE transport (protocol 2024-11-05), set
> `transport: "sse-legacy"` in the YAML config. The SSE endpoint uses `/sse` with
> message endpoint at `/mcp/message`.

## Features

**Tools** (15 operations AI agents can invoke):
- **IMap CRUD**: `map_get`, `map_put`, `map_delete`, `map_get_all`, `map_put_all`, `map_size`, `map_keys`, `map_values`, `map_contains_key`, `map_clear`
- **SQL**: `sql_execute` — SELECT, INSERT, UPDATE, DELETE with parameterized queries and pagination
- **VectorCollection**: `vector_search`, `vector_put`, `vector_get`, `vector_delete`

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
- **Streamable HTTP** — Recommended HTTP transport for network-accessible MCP (protocol 2025-06-18)
- **SSE (legacy)** — Older HTTP Server-Sent Events transport (protocol 2024-11-05)

## Quick Start (without Docker)

### Prerequisites
- Java 17+
- A running Hazelcast cluster (5.5+ / 5.6 recommended)

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
    transport: "stdio"    # or "sse" for Streamable HTTP, "sse-legacy" for legacy SSE
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

### Streamable HTTP mode with Claude Desktop

For network-accessible MCP via Streamable HTTP, start the server with HTTP transport:

```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar
```

Then configure Claude Desktop to connect via the `mcp-remote` bridge (requires Node.js 18+):

```json
{
  "mcpServers": {
    "hazelcast": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:3000/mcp"]
    }
  }
}
```

> **Why `mcp-remote`?** Claude Desktop communicates with MCP servers over stdio only.
> The `mcp-remote` package bridges stdio↔HTTP so Claude Desktop can reach network-based
> MCP servers. Any MCP client that natively supports Streamable HTTP can connect directly
> to `http://localhost:3000/mcp` without the bridge.

## Configuration Reference

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `HAZELCAST_MCP_CLUSTER_NAME` | Cluster name | `dev` |
| `HAZELCAST_MCP_CLUSTER_MEMBERS` | Comma-separated member addresses | `127.0.0.1:5701` |
| `HAZELCAST_MCP_SECURITY_USERNAME` | Authentication username | (empty) |
| `HAZELCAST_MCP_SECURITY_PASSWORD` | Authentication password | (empty) |
| `HAZELCAST_MCP_SECURITY_TOKEN` | Token-based auth | (empty) |
| `HAZELCAST_MCP_TRANSPORT` | Transport type (`stdio`, `sse`, or `sse-legacy`) | `stdio` |
| `HAZELCAST_MCP_ACCESS_MODE` | Access control mode (`all`, `allowlist`, `denylist`) | `all` |
| `ALPHA_VANTAGE_API_KEY` | Alpha Vantage API key for live stock data | (disabled) |
| `NEWS_API_KEY` | NewsAPI key for live financial news | (disabled) |

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
    │ MCP Protocol (stdio or Streamable HTTP)
    ▼
Hazelcast Client MCP Server
    ├── Tool Registry (map_*, sql_*, vector_*)
    ├── Resource Provider (cluster info, structures)
    ├── Prompt Templates (cache-lookup, data-exploration)
    ├── Access Controller (allowlist/denylist)
    ├── Transport Layer (stdio / Streamable HTTP / legacy SSE via Jetty 12)
    └── Hazelcast Client Adapter
            │ Hazelcast Binary Protocol
            ▼
    Hazelcast Cluster
```

## Technology Stack

- **Language**: Java 17+
- **MCP SDK**: [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) v1.0.0
- **Hazelcast Client**: Hazelcast 5.6+ (compatible with 5.5+)
- **Transport**: stdio (local), Streamable HTTP / legacy SSE via embedded Jetty 12 (Jakarta Servlet 6.1)
- **Build**: Maven, publishes fat JAR via `maven-shade-plugin`
- **Docker**: Multi-stage build (`eclipse-temurin:17`), Compose quickstart with demo data
- **Live Data**: CoinGecko, Alpha Vantage, NewsAPI integration with Hazelcast Jet

## License

Apache License 2.0
