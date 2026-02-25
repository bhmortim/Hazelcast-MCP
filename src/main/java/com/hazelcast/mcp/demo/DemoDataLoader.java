package com.hazelcast.mcp.demo;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.jet.JetService;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.map.IMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone demo data loader that populates a Hazelcast cluster with
 * realistic financial/trading data for showcasing the MCP server capabilities.
 *
 * <p>Creates four IMaps: trades, positions, market-data, risk-metrics
 * and submits two Jet batch jobs for P&L recalculation and risk aggregation.</p>
 *
 * <p>After loading seed data, starts {@link LiveDataFeedManager} to continuously
 * stream live crypto, stock, and news data from public APIs.</p>
 *
 * <p>Usage: java -cp hazelcast-mcp-server.jar com.hazelcast.mcp.demo.DemoDataLoader</p>
 */
public class DemoDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(DemoDataLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // --- Market universe ---
    private static final Map<String, double[]> SYMBOLS = new LinkedHashMap<>(); // symbol → [basePrice, volatility]
    static {
        SYMBOLS.put("AAPL",  new double[]{185.50, 0.02});
        SYMBOLS.put("GOOGL", new double[]{141.80, 0.025});
        SYMBOLS.put("MSFT",  new double[]{415.60, 0.018});
        SYMBOLS.put("AMZN",  new double[]{178.25, 0.022});
        SYMBOLS.put("TSLA",  new double[]{248.90, 0.045});
        SYMBOLS.put("JPM",   new double[]{198.40, 0.015});
        SYMBOLS.put("GS",    new double[]{465.20, 0.02});
        SYMBOLS.put("NVDA",  new double[]{875.30, 0.035});
        SYMBOLS.put("META",  new double[]{505.75, 0.028});
        SYMBOLS.put("NFLX",  new double[]{628.40, 0.03});
        SYMBOLS.put("BAC",   new double[]{37.85,  0.012});
        SYMBOLS.put("INTC",  new double[]{31.20,  0.025});
    }

    private static final String[] ACCOUNTS = {"ACCT-HF-001", "ACCT-HF-002", "ACCT-AM-003"};
    private static final String[] SIDES = {"BUY", "SELL"};
    private static final String[] STATUSES = {"FILLED", "FILLED", "FILLED", "PARTIALLY_FILLED"};

    public static void main(String[] args) {
        String clusterName = env("HAZELCAST_CLUSTER_NAME", "demo");
        String members = env("HAZELCAST_MEMBERS", "127.0.0.1:5701");

        logger.info("=== Hazelcast MCP Demo Data Loader ===");
        logger.info("Cluster: {}, Members: {}", clusterName, members);

        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName(clusterName);
        for (String member : members.split(",")) {
            clientConfig.getNetworkConfig().addAddress(member.trim());
        }
        clientConfig.getConnectionStrategyConfig()
                .getConnectionRetryConfig().setClusterConnectTimeoutMillis(60_000);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);
        logger.info("Connected to Hazelcast cluster");

        try {
            // Phase 1: Load static seed data for immediate querying
            loadMarketData(client);
            loadTrades(client);
            loadPositions(client);
            loadRiskMetrics(client);
            submitJetJobs(client);

            logger.info("=== Static demo data loaded ===");
            printSummary(client);

            // Signal readiness for Docker healthcheck
            try { new File("/tmp/feeds-ready").createNewFile(); } catch (Exception ignored) {}

            // Phase 2: Start live data feeds (blocks forever)
            logger.info("");
            LiveDataFeedManager feedManager = new LiveDataFeedManager(client);
            feedManager.startAndBlock();

        } catch (Exception e) {
            logger.error("Failed to load demo data: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            client.shutdown();
        }
    }

    // ---- Market Data ----

    private static void loadMarketData(HazelcastInstance client) {
        IMap<String, HazelcastJsonValue> map = client.getMap("market-data");
        map.clear();
        logger.info("Loading market data for {} symbols...", SYMBOLS.size());

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();

        for (var entry : SYMBOLS.entrySet()) {
            String symbol = entry.getKey();
            double basePrice = entry.getValue()[0];
            double vol = entry.getValue()[1];

            double change = basePrice * rng.nextDouble(-vol, vol);
            double last = round(basePrice + change);
            double spread = round(basePrice * 0.0003);

            ObjectNode node = mapper.createObjectNode();
            node.put("symbol", symbol);
            node.put("bid", round(last - spread));
            node.put("ask", round(last + spread));
            node.put("last", last);
            node.put("open", round(basePrice));
            node.put("high", round(last * (1 + rng.nextDouble(0, vol / 2))));
            node.put("low", round(last * (1 - rng.nextDouble(0, vol / 2))));
            node.put("volume", rng.nextLong(5_000_000, 80_000_000));
            node.put("change", round(change));
            node.put("changePct", round((change / basePrice) * 100));
            node.put("timestamp", now);
            node.put("exchange", symbol.length() <= 4 ? "NASDAQ" : "NYSE");

            map.put(symbol, new HazelcastJsonValue(node.toString()));
        }
        logger.info("  Loaded {} market-data entries", map.size());
    }

    // ---- Trades ----

    private static void loadTrades(HazelcastInstance client) {
        IMap<String, HazelcastJsonValue> map = client.getMap("trades");
        map.clear();
        logger.info("Loading trades...");

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<String> symbols = new ArrayList<>(SYMBOLS.keySet());
        Instant baseTime = Instant.now().minus(5, ChronoUnit.DAYS);

        for (int i = 1; i <= 80; i++) {
            String tradeId = String.format("TRD-%05d", i);
            String symbol = symbols.get(rng.nextInt(symbols.size()));
            String side = SIDES[rng.nextInt(SIDES.length)];
            String account = ACCOUNTS[rng.nextInt(ACCOUNTS.length)];
            String status = STATUSES[rng.nextInt(STATUSES.length)];

            double basePrice = SYMBOLS.get(symbol)[0];
            double vol = SYMBOLS.get(symbol)[1];
            double price = round(basePrice * (1 + rng.nextDouble(-vol * 2, vol * 2)));
            int quantity = rng.nextInt(1, 20) * 50; // 50-950 shares

            long timestamp = baseTime.plus(rng.nextLong(0, 5 * 24 * 60), ChronoUnit.MINUTES)
                    .toEpochMilli();

            ObjectNode node = mapper.createObjectNode();
            node.put("tradeId", tradeId);
            node.put("symbol", symbol);
            node.put("side", side);
            node.put("quantity", quantity);
            node.put("price", price);
            node.put("notional", round(price * quantity));
            node.put("timestamp", timestamp);
            node.put("account", account);
            node.put("status", status);
            node.put("exchange", "SMART");
            node.put("currency", "USD");

            map.put(tradeId, new HazelcastJsonValue(node.toString()));
        }
        logger.info("  Loaded {} trades across {} accounts", map.size(), ACCOUNTS.length);
    }

    // ---- Positions (aggregated from trades) ----

    private static void loadPositions(HazelcastInstance client) {
        IMap<String, HazelcastJsonValue> tradesMap = client.getMap("trades");
        IMap<String, HazelcastJsonValue> marketMap = client.getMap("market-data");
        IMap<String, HazelcastJsonValue> posMap = client.getMap("positions");
        posMap.clear();
        logger.info("Computing positions from trades...");

        // Aggregate trades into positions
        Map<String, long[]> posAgg = new LinkedHashMap<>(); // key → [netQty, totalCost*100]
        Map<String, double[]> costBasis = new LinkedHashMap<>();

        for (var entry : tradesMap.entrySet()) {
            try {
                var trade = mapper.readTree(entry.getValue().toString());
                String symbol = trade.get("symbol").asText();
                String account = trade.get("account").asText();
                int qty = trade.get("quantity").asInt();
                double price = trade.get("price").asDouble();
                String side = trade.get("side").asText();

                String key = account + "|" + symbol;
                int signedQty = "BUY".equals(side) ? qty : -qty;

                costBasis.merge(key, new double[]{signedQty, price * Math.abs(qty)},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
            } catch (Exception e) {
                logger.warn("Skipping trade {}: {}", entry.getKey(), e.getMessage());
            }
        }

        long now = System.currentTimeMillis();
        for (var entry : costBasis.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String account = parts[0];
            String symbol = parts[1];
            double netQty = entry.getValue()[0];
            double totalCost = entry.getValue()[1];

            if (Math.abs(netQty) < 1) continue; // flat position

            double avgCost = round(totalCost / Math.abs(netQty));

            // Get current market price
            double marketPrice = avgCost; // fallback
            HazelcastJsonValue mktJson = marketMap.get(symbol);
            if (mktJson != null) {
                try {
                    marketPrice = mapper.readTree(mktJson.toString()).get("last").asDouble();
                } catch (Exception ignored) {}
            }

            double marketValue = round(netQty * marketPrice);
            double unrealizedPnL = round((marketPrice - avgCost) * netQty);
            double pnlPct = round((unrealizedPnL / Math.abs(totalCost)) * 100);

            ObjectNode node = mapper.createObjectNode();
            node.put("symbol", symbol);
            node.put("account", account);
            node.put("quantity", (int) netQty);
            node.put("side", netQty > 0 ? "LONG" : "SHORT");
            node.put("avgCost", avgCost);
            node.put("marketPrice", round(marketPrice));
            node.put("marketValue", marketValue);
            node.put("unrealizedPnL", unrealizedPnL);
            node.put("unrealizedPnLPct", pnlPct);
            node.put("currency", "USD");
            node.put("lastUpdated", now);

            String posKey = account + "-" + symbol;
            posMap.put(posKey, new HazelcastJsonValue(node.toString()));
        }
        logger.info("  Computed {} positions", posMap.size());
    }

    // ---- Risk Metrics (per account) ----

    private static void loadRiskMetrics(HazelcastInstance client) {
        IMap<String, HazelcastJsonValue> posMap = client.getMap("positions");
        IMap<String, HazelcastJsonValue> riskMap = client.getMap("risk-metrics");
        riskMap.clear();
        logger.info("Computing risk metrics...");

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Map<String, double[]> accountAgg = new LinkedHashMap<>(); // acct → [totalMV, totalPnL, posCount]

        for (var entry : posMap.entrySet()) {
            try {
                var pos = mapper.readTree(entry.getValue().toString());
                String account = pos.get("account").asText();
                double mv = Math.abs(pos.get("marketValue").asDouble());
                double pnl = pos.get("unrealizedPnL").asDouble();

                accountAgg.merge(account, new double[]{mv, pnl, 1},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            } catch (Exception ignored) {}
        }

        long now = System.currentTimeMillis();
        for (var entry : accountAgg.entrySet()) {
            String account = entry.getKey();
            double totalExposure = round(entry.getValue()[0]);
            double totalPnL = round(entry.getValue()[1]);
            int positionCount = (int) entry.getValue()[2];

            double var95 = round(totalExposure * rng.nextDouble(0.08, 0.15));
            double var99 = round(var95 * 1.4);
            double sharpe = round(rng.nextDouble(0.5, 2.5));
            double maxDrawdown = round(rng.nextDouble(0.05, 0.20));
            double beta = round(rng.nextDouble(0.7, 1.4));

            ObjectNode node = mapper.createObjectNode();
            node.put("account", account);
            node.put("totalExposure", totalExposure);
            node.put("netExposure", round(totalExposure * rng.nextDouble(0.3, 0.8)));
            node.put("totalPnL", totalPnL);
            node.put("positionCount", positionCount);
            node.put("var95", var95);
            node.put("var99", var99);
            node.put("sharpeRatio", sharpe);
            node.put("maxDrawdown", maxDrawdown);
            node.put("beta", beta);
            node.put("currency", "USD");
            node.put("calculatedAt", now);

            riskMap.put(account, new HazelcastJsonValue(node.toString()));
        }
        logger.info("  Computed risk metrics for {} accounts", riskMap.size());
    }

    // ---- Jet Jobs ----
    // NOTE: Jet pipelines are serialized and shipped to the Hazelcast server.
    // All functions are defined in JetFunctions (a standalone Serializable class)
    // and uploaded via JobConfig.addClass() so the server can deserialize them.

    private static void submitJetJobs(HazelcastInstance client) {
        JetService jet = client.getJet();
        logger.info("Submitting Jet batch jobs...");

        // Upload JetFunctions class to the server so it can deserialize the lambdas
        com.hazelcast.jet.config.JobConfig jobConfig = new com.hazelcast.jet.config.JobConfig();
        jobConfig.addClass(JetFunctions.class);

        try {
            // Job 1: P&L Recalculation
            Pipeline pnlPipeline = Pipeline.create();
            pnlPipeline.readFrom(Sources.<String, HazelcastJsonValue>map("positions"))
                    .map(JetFunctions.RECALC_PNL)
                    .writeTo(Sinks.map("positions"));

            jet.newJob(pnlPipeline, jobConfig).join();
            logger.info("  Jet job 'pnl-recalculation' completed");

            // Job 2: Risk Aggregation
            Pipeline riskPipeline = Pipeline.create();
            riskPipeline.readFrom(Sources.<String, HazelcastJsonValue>map("positions"))
                    .map(JetFunctions.EXTRACT_FOR_RISK)
                    .groupingKey(JetFunctions.ACCOUNT_KEY)
                    .aggregate(JetFunctions.riskReducer())
                    .map(JetFunctions.FORMAT_RISK)
                    .writeTo(Sinks.map("risk-metrics"));

            jet.newJob(riskPipeline, jobConfig).join();
            logger.info("  Jet job 'risk-aggregation' completed");

        } catch (Exception e) {
            logger.warn("Jet jobs failed (Jet may not be enabled on server): {}", e.getMessage());
            logger.info("  Demo data is still loaded — Jet jobs are optional");
        }
    }

    // ---- Helpers ----

    private static void printSummary(HazelcastInstance client) {
        logger.info("--- Data Summary ---");
        logger.info("  market-data : {} symbols", client.getMap("market-data").size());
        logger.info("  trades      : {} trades", client.getMap("trades").size());
        logger.info("  positions   : {} positions", client.getMap("positions").size());
        logger.info("  risk-metrics: {} accounts", client.getMap("risk-metrics").size());
        logger.info("");
        logger.info("Try these MCP commands:");
        logger.info("  map/keys market-data         → list all ticker symbols");
        logger.info("  map/get  trades TRD-00001     → view a specific trade");
        logger.info("  map/get  market-data AAPL     → check Apple's market data");
        logger.info("  sql      SELECT * FROM trades WHERE side = 'BUY' AND symbol = 'TSLA'");
        logger.info("  map/keys risk-metrics         → list accounts with risk data");
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultVal;
    }
}
