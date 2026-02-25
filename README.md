# Hazelcast Client MCP Server

An open-source [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that wraps the Hazelcast Java Client, enabling AI agents (Claude, Cursor, VS Code Copilot, custom LangChain agents) to interact with Hazelcast clusters via natural language tools.

> **Verified working** with MCP SDK 1.0.0, Hazelcast 5.6, Claude Desktop + `mcp-remote 0.1.37`, and MCP protocol 2025-06-18 (Streamable HTTP).

## Docker Quickstart

The fastest way to try the MCP server — zero setup, includes demo data:

```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse docker compose up --build

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; docker compose up --build
```

This spins up:

1. **Hazelcast** — Single node cluster with Jet enabled
2. **Demo Data Loader + Live Feeds** — Seeds financial data, then streams live crypto/stock/news
3. **MCP Server** — Connected to Hazelcast, Streamable HTTP endpoint at `http://localhost:3000/mcp`

> **Tip:** Use `HAZELCAST_MCP_TRANSPORT=sse` to start the HTTP transport. Without it, the server defaults to stdio (only useful for `docker exec` scenarios).

### Connect Claude Desktop

Claude Desktop communicates with MCP servers over stdio, so we use [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) as a stdio-to-HTTP bridge (requires [Node.js](https://nodejs.org/) 18+).

**Step 1:** Add to your Claude Desktop config:

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

> `mcp-remote` is auto-installed on first run via `npx -y`. If you already have other entries in `mcpServers`, merge the `"hazelcast"` key into the existing object.

**Step 2:** Restart Claude Desktop (fully quit and reopen). Look for the hammer icon in the chat input — click it to verify 57 Hazelcast tools are listed.

**Troubleshooting:**

- **No hammer icon?** Make sure Docker is running and the MCP server container is healthy (`docker compose ps`). The HTTP endpoint must be available _before_ Claude Desktop starts.
- **JSON parse error on launch?** Validate your config at [jsonlint.com](https://jsonlint.com/). A common mistake is a missing comma between `"preferences"` and `"mcpServers"` blocks.
- **Windows `HAZELCAST_MCP_TRANSPORT=sse` not recognized?** PowerShell requires the `$env:VAR="value"; command` syntax shown above. The Unix `VAR=value command` form only works in bash/zsh.

### What's in the demo data?

The loader populates four IMaps with a financial trading dataset, then starts live streaming feeds.

#### Static seed data (loaded at startup)

| IMap | Keys | Contents | Records |
|------|------|----------|---------|
| `market-data` | Ticker symbols (`AAPL`, `GOOGL`, `MSFT`, ...) | Bid, ask, last, open, high, low, volume, change, changePct, exchange, timestamp | 12 symbols |
| `trades` | Trade IDs (`TRD-00001` through `TRD-00080`) | Symbol, side (BUY/SELL), quantity, price, notional, account, status, timestamp | ~80 trades |
| `positions` | Account-symbol (`ACCT-HF-001-AAPL`, ...) | Net quantity, side (LONG/SHORT), avgCost, marketPrice, unrealizedPnL | ~15 positions |
| `risk-metrics` | Account IDs (`ACCT-HF-001`, `ACCT-HF-002`, `ACCT-AM-003`) | TotalExposure, netExposure, totalPnL, VaR95, VaR99, sharpeRatio, maxDrawdown, beta | 3 accounts |

**Symbols:** AAPL, GOOGL, MSFT, AMZN, TSLA, JPM, GS, NVDA, META, NFLX, BAC, INTC

**Accounts:** ACCT-HF-001, ACCT-HF-002 (hedge funds), ACCT-AM-003 (asset management)

#### Live data feeds (streaming continuously)

| IMap | Keys | API | Key Required? | Polling Interval |
|------|------|-----|---------------|-----------------|
| `crypto` | Ticker symbols (`BTC`, `ETH`, `SOL`, ...) | [CoinGecko](https://www.coingecko.com/en/api) | No | 30 seconds |
| `stocks` | Ticker symbols (`AAPL`, `MSFT`, ...) | [Alpha Vantage](https://www.alphavantage.co/support/#api-key) | Yes (free) | 5 minutes |
| `news` | URL hashes (`NEWS-{hash}`) | [NewsAPI](https://newsapi.org/register) | Yes (free) | 10 minutes |
| `market-insights` | Insight types (`correlation`, `sentiment`, `top-movers`, `summary`) | Jet pipeline (internal) | No | 5 minutes |

**Crypto works out of the box** — no API keys needed. Stocks and news are optional enrichments.

To enable all feeds:

```bash
ALPHA_VANTAGE_API_KEY=your_key NEWS_API_KEY=your_key HAZELCAST_MCP_TRANSPORT=sse docker compose up --build
```

**Get free API keys:**

- Alpha Vantage: [alphavantage.co/support/#api-key](https://www.alphavantage.co/support/#api-key) (25 requests/day)
- NewsAPI: [newsapi.org/register](https://newsapi.org/register) (100 requests/day)

### Verified prompts to try

These prompts have been tested end-to-end with Claude Desktop and return real results from the demo data. Claude will translate them into the appropriate MCP tool calls automatically.

> **Important:** Claude doesn't know the map names upfront. For best results, tell it the map names in your first message (e.g., "The Hazelcast cluster has maps: market-data, trades, positions, risk-metrics, crypto"). After that, Claude can query them by name.

#### Market data (12 stock symbols)

```
"List all the keys in the market-data map"
"Get the current market data for all 12 symbols in market-data"
"What's Apple's current stock price?"
"Show me NVIDIA's full market data"
"Which stocks have the highest trading volume?"
```

#### Trades (~80 trade records)

```
"How many entries are in the trades map?"
"Show me all keys in the trades map"
"Get the details of trade TRD-00001"
"Find all Tesla trades using SQL: SELECT * FROM trades WHERE symbol='TSLA'"
"Show me the top 5 trades by notional value: SELECT * FROM trades ORDER BY notional DESC LIMIT 5"
"How many buy vs sell trades? SELECT side, COUNT(*) FROM trades GROUP BY side"
```

#### Positions (portfolio holdings)

```
"List all keys in the positions map"
"Get position ACCT-HF-001-AAPL"
"Show me all LONG positions using SQL"
"Which positions have the biggest unrealized P&L?"
```

#### Risk metrics (3 accounts)

```
"Get risk metrics for account ACCT-HF-001"
"Show me risk metrics for all three accounts"
"Which account has the highest Value at Risk?"
"Compare Sharpe ratios across all accounts"
```

#### Crypto (live — no API key needed)

```
"What's Bitcoin trading at right now?"
"Show me all crypto prices"
"Which crypto has the biggest 24h change?"
"Compare BTC and ETH market caps"
```

#### Cross-asset and market insights

```
"Give me a full market briefing from market-insights"
"How is the market sentiment today?"
"Who are the biggest movers right now?"
"Compare crypto vs stock performance"
```

#### SQL queries (advanced)

```
"SELECT symbol, SUM(quantity * price) as total_notional FROM trades GROUP BY symbol ORDER BY total_notional DESC"
"SELECT account, COUNT(*) as trade_count, SUM(notional) as total_notional FROM trades GROUP BY account"
"SELECT * FROM trades WHERE account='ACCT-HF-001' AND side='BUY' ORDER BY notional DESC LIMIT 10"
```

### Alternate transport: Docker + stdio

If you prefer stdio transport without HTTP, use `docker exec`:

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

### Streamable HTTP for other MCP clients

The Streamable HTTP endpoint at `http://localhost:3000/mcp` implements MCP protocol 2025-06-18. Any MCP client that natively supports Streamable HTTP can connect directly without the `mcp-remote` bridge.

> **Legacy SSE:** If you need the older SSE transport (protocol 2024-11-05), set `transport: "sse-legacy"` in the YAML config. The SSE endpoint uses `/sse` with message endpoint at `/mcp/message`.

## Features

**Tools** (57 operations AI agents can invoke):
- **Cluster Discovery**: `list_structures` — List all distributed objects in the cluster (solves the "what maps exist?" problem)
- **IMap** (14 tools): `map_get`, `map_put`, `map_delete`, `map_get_all`, `map_put_all`, `map_size`, `map_keys`, `map_values`, `map_contains_key`, `map_clear`, `map_put_if_absent`, `map_replace`, `map_entry_set`
- **SQL** (4 tools): `sql_execute`, `sql_create_mapping`, `sql_drop_mapping`, `sql_show_mappings`
- **VectorCollection** (4 tools): `vector_search`, `vector_put`, `vector_get`, `vector_delete`
- **IQueue** (6 tools): `queue_offer`, `queue_poll`, `queue_peek`, `queue_size`, `queue_drain`, `queue_clear`
- **IList** (5 tools): `list_add`, `list_get`, `list_remove`, `list_size`, `list_sublist`
- **ISet** (5 tools): `set_add`, `set_remove`, `set_contains`, `set_size`, `set_get_all`
- **MultiMap** (7 tools): `multimap_put`, `multimap_get`, `multimap_remove`, `multimap_keys`, `multimap_values`, `multimap_size`, `multimap_value_count`
- **AtomicLong** (5 tools): `atomic_get`, `atomic_set`, `atomic_increment`, `atomic_decrement`, `atomic_compare_and_set` (requires CP Subsystem)
- **ITopic** (2 tools): `topic_publish`, `topic_info`
- **Ringbuffer** (5 tools): `ringbuffer_add`, `ringbuffer_read`, `ringbuffer_read_many`, `ringbuffer_size`, `ringbuffer_capacity`

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

### Run with Claude Desktop (stdio)

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

### Run with Claude Desktop (Streamable HTTP)

Start the server with HTTP transport, then use `mcp-remote` as a stdio-to-HTTP bridge (requires Node.js 18+):

```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar
```

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
    queues: ["task-queue"]
    lists: []       # empty = allow all lists
    sets: []        # empty = allow all sets
    multimaps: []
    topics: []
    ringbuffers: []
    atomics: []
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
    ├── Tool Registry (map_*, sql_*, vector_*, queue_*, list_*, set_*, multimap_*, atomic_*, topic_*, ringbuffer_*)
    ├── Resource Provider (cluster info, structures)
    ├── Prompt Templates (cache-lookup, data-exploration)
    ├── Access Controller (allowlist/denylist per data structure type)
    ├── Transport Layer (stdio / Streamable HTTP / legacy SSE via Jetty 12)
    └── Hazelcast Client Adapter
            │ Hazelcast Binary Protocol
            ▼
    Hazelcast Cluster
```

## Technology Stack

- **Language**: Java 17+
- **MCP SDK**: [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) v1.0.0 GA (with Jackson 2.x — jackson3 excluded to prevent classpath conflicts)
- **MCP Protocol**: 2025-06-18 (Streamable HTTP), 2024-11-05 (legacy SSE)
- **Hazelcast Client**: Hazelcast 5.6 (compatible with 5.5+)
- **Transport**: stdio (local), Streamable HTTP / legacy SSE via embedded Jetty 12 (Jakarta Servlet 6.1)
- **Build**: Maven, publishes fat JAR via `maven-shade-plugin`
- **Docker**: Multi-stage build (`eclipse-temurin:17`), Compose quickstart with demo data
- **Live Data**: CoinGecko, Alpha Vantage, NewsAPI integration with Hazelcast Jet
- **Tested With**: Claude Desktop + `mcp-remote` 0.1.37 on Windows and macOS

## Known Issues

- **Claude can now discover data structures.** Use the `list_structures` tool to see all maps, queues, lists, sets, topics, and other distributed objects in the cluster. You can also use the `hazelcast://structures/list` resource if your MCP client supports resources.
- **Jackson 3.x classpath conflict.** MCP SDK 1.0.0 transitively includes `mcp-json-jackson3` which brings Jackson 3.x onto the classpath, causing `NoSuchFieldError: POJO` at runtime. This project explicitly excludes `mcp-json-jackson3` in `pom.xml` and uses `mcp-json-jackson2` only. If you fork or upgrade, make sure this exclusion stays in place.

## License

Apache License 2.0
