# Hazelcast Client MCP Server

An open-source [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that gives AI agents full access to your Hazelcast cluster. Claude, Cursor, VS Code Copilot, and custom LangChain agents can query, write, and manage **every major Hazelcast data structure** through natural language.

> **57 tools** covering IMap, IQueue, IList, ISet, MultiMap, AtomicLong, ITopic, Ringbuffer, SQL, and VectorCollection — plus cluster discovery, access control, and three transport options.

## Docker Quickstart

Zero setup, includes demo data:

```bash
# Linux / macOS
HAZELCAST_MCP_TRANSPORT=sse docker compose up --build

# Windows PowerShell
$env:HAZELCAST_MCP_TRANSPORT="sse"; docker compose up --build
```

This spins up a Hazelcast node with Jet enabled, seeds financial demo data with live streaming feeds, and starts the MCP server at `http://localhost:3000/mcp`.

### Connect Claude Desktop

Claude Desktop uses stdio, so we bridge with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) (requires [Node.js](https://nodejs.org/) 18+).

Add to your Claude Desktop config:

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

Restart Claude Desktop. Click the hammer icon to verify 57 Hazelcast tools appear.

**Troubleshooting:**
- **No hammer icon?** Make sure Docker is running and the MCP server is healthy (`docker compose ps`).
- **JSON parse error?** Validate at [jsonlint.com](https://jsonlint.com/). Common mistake: missing comma between config blocks.
- **Windows `HAZELCAST_MCP_TRANSPORT=sse` not recognized?** Use the PowerShell `$env:` syntax shown above.

## Features

### Tools (57 operations)

#### Cluster Discovery

| Tool | Description |
|------|-------------|
| `list_structures` | List every distributed object in the cluster — maps, queues, lists, sets, topics, and more. This is the "what's in my cluster?" tool. |

#### IMap (14 tools)

Full CRUD plus advanced operations for Hazelcast's core key-value store.

| Tool | Description |
|------|-------------|
| `map_get` | Get a single entry by key |
| `map_put` | Put a key-value pair (supports JSON values) |
| `map_delete` | Remove an entry by key |
| `map_get_all` | Batch get multiple keys at once |
| `map_put_all` | Batch put multiple entries |
| `map_size` | Get the number of entries |
| `map_keys` | List all keys (with optional limit) |
| `map_values` | List all values (with optional limit) |
| `map_contains_key` | Check if a key exists |
| `map_clear` | Remove all entries (requires `clear: true` in config) |
| `map_put_if_absent` | Atomic insert — only writes if key doesn't already exist |
| `map_replace` | Atomic replace with optional compare-and-set (old value check) |
| `map_entry_set` | Bulk key-value retrieval with optional server-side SQL predicate filtering |

#### SQL (4 tools)

| Tool | Description |
|------|-------------|
| `sql_execute` | Run SELECT, INSERT, UPDATE, DELETE with parameterized queries and pagination |
| `sql_create_mapping` | Create a SQL mapping for an IMap (enables SQL queries against it) |
| `sql_drop_mapping` | Remove a SQL mapping |
| `sql_show_mappings` | List all active SQL mappings |

#### IQueue (6 tools)

Distributed FIFO queue for task processing and work distribution.

| Tool | Description |
|------|-------------|
| `queue_offer` | Add an item to the tail of the queue |
| `queue_poll` | Remove and return the head item |
| `queue_peek` | View the head item without removing it |
| `queue_size` | Get the number of items in the queue |
| `queue_drain` | Remove multiple items at once (batch dequeue) |
| `queue_clear` | Remove all items (requires `clear: true`) |

#### IList (5 tools)

Distributed ordered list with index-based access.

| Tool | Description |
|------|-------------|
| `list_add` | Add an item (optionally at a specific index) |
| `list_get` | Get an item by index |
| `list_remove` | Remove an item by index |
| `list_size` | Get the list length |
| `list_sublist` | Get a range of items by index (from/to) |

#### ISet (5 tools)

Distributed set with uniqueness guarantees.

| Tool | Description |
|------|-------------|
| `set_add` | Add an item (no-op if already present) |
| `set_remove` | Remove an item |
| `set_contains` | Check if an item exists |
| `set_size` | Get the set size |
| `set_get_all` | Get all items |

#### MultiMap (7 tools)

Map that supports multiple values per key — ideal for tags, categories, and one-to-many relationships.

| Tool | Description |
|------|-------------|
| `multimap_put` | Add a value for a key (allows multiple values per key) |
| `multimap_get` | Get all values for a key |
| `multimap_remove` | Remove a specific value or all values for a key |
| `multimap_keys` | List all keys |
| `multimap_values` | List all values |
| `multimap_size` | Total entry count across all keys |
| `multimap_value_count` | Count values for a specific key |

#### AtomicLong (5 tools)

Distributed atomic counter via the CP Subsystem — linearizable, consistent across the cluster.

| Tool | Description |
|------|-------------|
| `atomic_get` | Get the current value |
| `atomic_set` | Set to a specific value |
| `atomic_increment` | Atomically increment by 1 and return new value |
| `atomic_decrement` | Atomically decrement by 1 and return new value |
| `atomic_compare_and_set` | CAS operation — set new value only if current matches expected |

> **Note:** AtomicLong requires the [CP Subsystem](https://docs.hazelcast.com/hazelcast/latest/cp-subsystem/configuration) to be enabled on your Hazelcast cluster. The tools gracefully report an error if CP is not configured.

#### ITopic (2 tools)

Distributed pub/sub messaging.

| Tool | Description |
|------|-------------|
| `topic_publish` | Publish a message (JSON or plain text) to all subscribers |
| `topic_info` | Get topic statistics |

#### Ringbuffer (5 tools)

Fixed-capacity circular buffer — ideal for audit logs, event streams, and recent-history queries.

| Tool | Description |
|------|-------------|
| `ringbuffer_add` | Append an item (overwrites oldest when full) |
| `ringbuffer_read` | Read a single item by sequence number |
| `ringbuffer_read_many` | Read a batch of items from a starting sequence |
| `ringbuffer_size` | Current number of items stored |
| `ringbuffer_capacity` | Maximum capacity of the ringbuffer |

#### VectorCollection (4 tools)

Semantic similarity search (requires Hazelcast 5.7+/6.0+ with the vector module).

| Tool | Description |
|------|-------------|
| `vector_search` | Find nearest neighbors by vector similarity |
| `vector_put` | Store a document with its vector embedding |
| `vector_get` | Retrieve a document by key |
| `vector_delete` | Remove a document |

### Resources (read-only cluster context)

- `hazelcast://cluster/info` — Cluster name, version, member list
- `hazelcast://cluster/health` — Connection status and latency
- `hazelcast://structures/list` — All distributed objects with type and size

### Prompts (reusable interaction patterns)

- `cache-lookup` — Check cache hit/miss with explanation
- `data-exploration` — Discover and sample cluster data
- `vector-search` — Semantic similarity search workflow

### Transports

- **stdio** — Standard MCP transport for local AI clients (Claude Desktop, Cursor)
- **Streamable HTTP** — Recommended HTTP transport for network-accessible MCP (protocol 2025-06-18)
- **SSE (legacy)** — Older HTTP Server-Sent Events transport (protocol 2024-11-05)

## Example Prompts

These prompts work with Claude Desktop (or any MCP client) and showcase the full range of tools. Claude translates natural language into the right tool calls automatically.

### Cluster discovery

```
"What data structures are in my Hazelcast cluster?"
"Show me everything in the cluster — maps, queues, lists, all of it"
```

### Maps and SQL (works with demo data)

```
"Get AAPL from the market-data map"
"Show me all keys in the trades map"
"Find Tesla trades: SELECT * FROM trades WHERE symbol='TSLA'"
"What are the top 5 trades by notional value?"
"How many buy vs sell trades? Group by side."
"Compare risk metrics for all three accounts"
```

### Queues

```
"Create a queue called 'work-items' and add three tasks to it"
"How many items are in the work-items queue?"
"Poll the next item from work-items"
"Peek at the head of the queue without removing it"
"Drain all remaining items"
```

### Lists and Sets

```
"Create a list called 'watchlist' with AAPL, GOOGL, TSLA, NVDA"
"What's the third item in the watchlist?"
"Get items 0 through 3 from the watchlist"
"Create a set called 'active-symbols' and add AAPL, MSFT, AAPL — verify AAPL isn't duplicated"
"Is AMZN in the active-symbols set?"
```

### MultiMap

```
"Create a multimap called 'user-roles' — give user 'brian' the roles admin, editor, and viewer"
"What roles does brian have?"
"How many roles does brian have vs user 'guest'?"
"Remove the viewer role from brian"
"List all keys and values in user-roles"
```

### Atomic counters

```
"Create an atomic counter called 'request-count' starting at 0"
"Increment request-count 5 times and show the final value"
"Use compare-and-set: if request-count is 5, set it to 1000"
```

### Topics and Ringbuffers

```
"Publish an alert to the 'notifications' topic: {\"type\": \"warning\", \"message\": \"High CPU usage\"}"
"Add 5 log entries to a ringbuffer called 'audit-log'"
"Read the last 3 entries from audit-log"
"What's the capacity of the audit-log ringbuffer?"
```

### SQL DDL

```
"Show all SQL mappings in the cluster"
"Create a SQL mapping for the trades map with varchar keys and json values"
"Now query it: SELECT * FROM trades LIMIT 5"
"Drop the trades mapping"
```

### End-to-end workflows

```
"Build me a job processing system: create a 'jobs' queue with 5 tasks, a 'completed' set to
track finished work, and a 'jobs-processed' counter. Then process 2 jobs — poll from the queue,
add to the completed set, and increment the counter each time."

"Set up a market alert system: create a 'price-alerts' multimap where each symbol can have
multiple alert thresholds. Add alerts for AAPL at 150 and 200, TSLA at 250. Then check what
alerts exist for AAPL."

"Audit everything: list all structures in the cluster, check the sizes of each map, show me the
queue depths, and give me a summary of what's in the cluster."
```

## Demo Data

The Docker quickstart includes a financial trading dataset with live streaming feeds.

### Static seed data

| IMap | Keys | Contents | Records |
|------|------|----------|---------|
| `market-data` | Ticker symbols (`AAPL`, `GOOGL`, `MSFT`, ...) | Bid, ask, last, open, high, low, volume, change, changePct | 12 symbols |
| `trades` | Trade IDs (`TRD-00001` – `TRD-00080`) | Symbol, side, quantity, price, notional, account, status | ~80 trades |
| `positions` | Account-symbol keys | Net quantity, side, avgCost, marketPrice, unrealizedPnL | ~15 positions |
| `risk-metrics` | Account IDs (`ACCT-HF-001`, `ACCT-HF-002`, `ACCT-AM-003`) | TotalExposure, VaR95, VaR99, sharpeRatio, maxDrawdown, beta | 3 accounts |

**Symbols:** AAPL, GOOGL, MSFT, AMZN, TSLA, JPM, GS, NVDA, META, NFLX, BAC, INTC

### Live data feeds

| IMap | Source | API Key? | Interval |
|------|--------|----------|----------|
| `crypto` | [CoinGecko](https://www.coingecko.com/en/api) | No | 30s |
| `stocks` | [Alpha Vantage](https://www.alphavantage.co/support/#api-key) | Yes (free) | 5m |
| `news` | [NewsAPI](https://newsapi.org/register) | Yes (free) | 10m |
| `market-insights` | Jet pipeline (internal) | No | 5m |

**Crypto works out of the box.** Stocks and news are optional:

```bash
ALPHA_VANTAGE_API_KEY=your_key NEWS_API_KEY=your_key HAZELCAST_MCP_TRANSPORT=sse docker compose up --build
```

Free API keys: [Alpha Vantage](https://www.alphavantage.co/support/#api-key) (25 req/day) · [NewsAPI](https://newsapi.org/register) (100 req/day)

## Quick Start (without Docker)

### Prerequisites

- Java 17+
- A running Hazelcast cluster (5.5+ / 5.6 recommended)

### Build

```bash
./mvnw clean package -DskipTests
```

### Configure

Create `hazelcast-mcp.yaml`:

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
    clear: false
```

### Run with Claude Desktop (stdio)

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

```bash
# Start server
HAZELCAST_MCP_TRANSPORT=sse java -jar hazelcast-mcp-server-1.0.0-SNAPSHOT.jar
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

### Docker + stdio (alternative)

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

## Configuration Reference

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `HAZELCAST_MCP_CLUSTER_NAME` | Cluster name | `dev` |
| `HAZELCAST_MCP_CLUSTER_MEMBERS` | Comma-separated member addresses | `127.0.0.1:5701` |
| `HAZELCAST_MCP_SECURITY_USERNAME` | Authentication username | (empty) |
| `HAZELCAST_MCP_SECURITY_PASSWORD` | Authentication password | (empty) |
| `HAZELCAST_MCP_SECURITY_TOKEN` | Token-based auth | (empty) |
| `HAZELCAST_MCP_TRANSPORT` | Transport: `stdio`, `sse`, or `sse-legacy` | `stdio` |
| `HAZELCAST_MCP_ACCESS_MODE` | Access control: `all`, `allowlist`, `denylist` | `all` |
| `ALPHA_VANTAGE_API_KEY` | Alpha Vantage API key for live stock data | (disabled) |
| `NEWS_API_KEY` | NewsAPI key for live financial news | (disabled) |

### Access Control

Restrict which data structures and operations are exposed to AI agents:

```yaml
access:
  mode: "allowlist"          # "all" (default), "allowlist", or "denylist"
  allowlist:
    maps: ["session-cache", "user-profiles"]
    vectors: ["document-embeddings"]
    queues: ["task-queue"]
    lists: []                # empty = allow all
    sets: []
    multimaps: []
    topics: []
    ringbuffers: []
    atomics: []
  denylist:                  # used when mode is "denylist"
    maps: ["internal-config"]
    queues: ["system-queue"]
  operations:
    sql: true
    write: false             # read-only mode
    clear: false             # destructive ops require explicit enable
```

## Architecture

```
AI Agent (Claude, Cursor, etc.)
    │ MCP Protocol (stdio or Streamable HTTP)
    ▼
Hazelcast Client MCP Server
    ├── MapTools         (14)  — IMap CRUD + atomic ops + predicate queries
    ├── SqlTools          (4)  — SQL DML/DDL with parameterized queries
    ├── QueueTools        (6)  — IQueue offer/poll/drain/clear
    ├── CollectionTools  (10)  — IList + ISet operations
    ├── MultiMapTools     (7)  — Multi-value map operations
    ├── AtomicTools       (5)  — CP Subsystem atomic counters
    ├── TopicTools        (2)  — Pub/sub messaging
    ├── RingbufferTools   (5)  — Circular buffer read/write
    ├── VectorTools       (4)  — Semantic similarity search
    ├── ClusterResources  (3)  — Read-only cluster context
    ├── AccessController       — Per-structure allowlist/denylist + operation gating
    └── Transport Layer        — stdio / Streamable HTTP / legacy SSE (Jetty 12)
            │ Hazelcast Binary Protocol
            ▼
    Hazelcast Cluster (5.5+)
```

## Technology Stack

- **Language**: Java 17+
- **MCP SDK**: [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) v1.0.0 GA (Jackson 2.x)
- **MCP Protocol**: 2025-06-18 (Streamable HTTP), 2024-11-05 (legacy SSE)
- **Hazelcast Client**: 5.6 (compatible with 5.5+)
- **Transport**: stdio (local), Streamable HTTP / legacy SSE via embedded Jetty 12
- **Build**: Maven with fat JAR via `maven-shade-plugin`
- **Docker**: Multi-stage build (`eclipse-temurin:17`), Compose quickstart
- **Live Data**: CoinGecko, Alpha Vantage, NewsAPI with Hazelcast Jet
- **Tested With**: Claude Desktop + `mcp-remote` 0.1.37 (Windows & macOS)

## Known Issues

- **Jackson 3.x classpath conflict.** MCP SDK 1.0.0 transitively includes `mcp-json-jackson3` which brings Jackson 3.x, causing `NoSuchFieldError: POJO` at runtime. This project excludes `mcp-json-jackson3` in `pom.xml` and uses `mcp-json-jackson2` only. Keep this exclusion if you fork or upgrade.
- **AtomicLong requires CP Subsystem.** If CP is not enabled on the cluster, the 5 atomic tools will return a descriptive error message rather than failing silently.

## License

Apache License 2.0
