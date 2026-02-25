package com.hazelcast.mcp.demo.feeds;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Computes cross-asset market insights by reading from the crypto, stocks,
 * and news IMaps. Runs every 5 minutes as a scheduled task.
 *
 * <p>Writes computed insights to the {@code market-insights} IMap with keys:
 * {@code correlation}, {@code sentiment}, {@code top-movers}, {@code summary}.</p>
 */
public class MarketCorrelationJob implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MarketCorrelationJob.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HazelcastInstance client;

    public MarketCorrelationJob(HazelcastInstance client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            IMap<String, HazelcastJsonValue> cryptoMap = client.getMap("crypto");
            IMap<String, HazelcastJsonValue> stocksMap = client.getMap("stocks");
            IMap<String, HazelcastJsonValue> newsMap = client.getMap("news");
            IMap<String, HazelcastJsonValue> insightsMap = client.getMap("market-insights");

            long now = System.currentTimeMillis();

            // --- Collect all asset changes ---
            List<AssetChange> allChanges = new ArrayList<>();

            // Crypto changes
            for (var entry : cryptoMap.entrySet()) {
                try {
                    JsonNode node = mapper.readTree(entry.getValue().toString());
                    double change = node.path("change24h").asDouble(0);
                    double price = node.path("price").asDouble(0);
                    allChanges.add(new AssetChange(entry.getKey(), "CRYPTO", change, price));
                } catch (Exception ignored) {}
            }

            // Stock changes
            for (var entry : stocksMap.entrySet()) {
                try {
                    JsonNode node = mapper.readTree(entry.getValue().toString());
                    double change = node.path("changePct").asDouble(0);
                    double price = node.path("price").asDouble(0);
                    allChanges.add(new AssetChange(entry.getKey(), "STOCK", change, price));
                } catch (Exception ignored) {}
            }

            // --- 1) Crypto vs Stocks Correlation ---
            computeCorrelation(allChanges, insightsMap, now);

            // --- 2) News Sentiment ---
            computeSentiment(newsMap, insightsMap, now);

            // --- 3) Top Movers ---
            computeTopMovers(allChanges, insightsMap, now);

            // --- 4) Market Summary ---
            computeSummary(allChanges, newsMap, insightsMap, now);

            logger.debug("MarketCorrelationJob: insights updated");

        } catch (Exception e) {
            logger.warn("MarketCorrelationJob error: {}", e.getMessage());
        }
    }

    private void computeCorrelation(List<AssetChange> changes,
                                     IMap<String, HazelcastJsonValue> insightsMap, long now) {
        double cryptoAvgChange = changes.stream()
                .filter(c -> "CRYPTO".equals(c.assetType))
                .mapToDouble(c -> c.changePct)
                .average().orElse(0);

        double stockAvgChange = changes.stream()
                .filter(c -> "STOCK".equals(c.assetType))
                .mapToDouble(c -> c.changePct)
                .average().orElse(0);

        long cryptoCount = changes.stream().filter(c -> "CRYPTO".equals(c.assetType)).count();
        long stockCount = changes.stream().filter(c -> "STOCK".equals(c.assetType)).count();

        // Simple directional correlation
        String direction;
        if (cryptoCount == 0 || stockCount == 0) {
            direction = "INSUFFICIENT_DATA";
        } else if (Math.signum(cryptoAvgChange) == Math.signum(stockAvgChange)) {
            direction = "CORRELATED";
        } else {
            direction = "DIVERGENT";
        }

        ObjectNode node = mapper.createObjectNode();
        node.put("cryptoAvgChange", round(cryptoAvgChange));
        node.put("stockAvgChange", round(stockAvgChange));
        node.put("cryptoAssetCount", cryptoCount);
        node.put("stockAssetCount", stockCount);
        node.put("direction", direction);
        node.put("description", String.format(
                "Crypto avg 24h change: %.2f%%, Stock avg change: %.2f%% — markets are %s",
                cryptoAvgChange, stockAvgChange, direction.toLowerCase()));
        node.put("timestamp", now);

        insightsMap.put("correlation", new HazelcastJsonValue(node.toString()));
    }

    private void computeSentiment(IMap<String, HazelcastJsonValue> newsMap,
                                   IMap<String, HazelcastJsonValue> insightsMap, long now) {
        double totalSentiment = 0;
        int positive = 0, negative = 0, neutral = 0;
        int count = 0;

        for (var entry : newsMap.entrySet()) {
            try {
                JsonNode article = mapper.readTree(entry.getValue().toString());
                double sentiment = article.path("sentiment").asDouble(0);
                totalSentiment += sentiment;
                count++;

                if (sentiment > 0.1) positive++;
                else if (sentiment < -0.1) negative++;
                else neutral++;
            } catch (Exception ignored) {}
        }

        double avgSentiment = count > 0 ? totalSentiment / count : 0;
        String label;
        if (count == 0) label = "NO_DATA";
        else if (avgSentiment > 0.1) label = "BULLISH";
        else if (avgSentiment < -0.1) label = "BEARISH";
        else label = "NEUTRAL";

        ObjectNode node = mapper.createObjectNode();
        node.put("averageSentiment", round(avgSentiment));
        node.put("label", label);
        node.put("articleCount", count);
        node.put("positiveCount", positive);
        node.put("negativeCount", negative);
        node.put("neutralCount", neutral);
        node.put("description", String.format(
                "Market sentiment: %s (avg score: %.2f from %d articles — %d positive, %d negative, %d neutral)",
                label, avgSentiment, count, positive, negative, neutral));
        node.put("timestamp", now);

        insightsMap.put("sentiment", new HazelcastJsonValue(node.toString()));
    }

    private void computeTopMovers(List<AssetChange> changes,
                                   IMap<String, HazelcastJsonValue> insightsMap, long now) {
        // Sort by absolute change percentage descending
        changes.sort((a, b) -> Double.compare(Math.abs(b.changePct), Math.abs(a.changePct)));

        ArrayNode moversArray = mapper.createArrayNode();
        int limit = Math.min(5, changes.size());

        for (int i = 0; i < limit; i++) {
            AssetChange ac = changes.get(i);
            ObjectNode mover = mapper.createObjectNode();
            mover.put("symbol", ac.symbol);
            mover.put("assetType", ac.assetType);
            mover.put("changePct", round(ac.changePct));
            mover.put("price", round(ac.price));
            mover.put("direction", ac.changePct >= 0 ? "UP" : "DOWN");
            moversArray.add(mover);
        }

        ObjectNode node = mapper.createObjectNode();
        node.set("topMovers", moversArray);
        node.put("totalAssetsTracked", changes.size());
        node.put("timestamp", now);

        insightsMap.put("top-movers", new HazelcastJsonValue(node.toString()));
    }

    private void computeSummary(List<AssetChange> changes,
                                 IMap<String, HazelcastJsonValue> newsMap,
                                 IMap<String, HazelcastJsonValue> insightsMap, long now) {
        double overallAvg = changes.stream()
                .mapToDouble(c -> c.changePct)
                .average().orElse(0);

        long gainers = changes.stream().filter(c -> c.changePct > 0).count();
        long losers = changes.stream().filter(c -> c.changePct < 0).count();
        long unchanged = changes.stream().filter(c -> c.changePct == 0).count();

        String marketDirection;
        if (overallAvg > 0.5) marketDirection = "STRONGLY_BULLISH";
        else if (overallAvg > 0) marketDirection = "SLIGHTLY_BULLISH";
        else if (overallAvg > -0.5) marketDirection = "SLIGHTLY_BEARISH";
        else marketDirection = "STRONGLY_BEARISH";

        // Top 3 movers for summary
        changes.sort((a, b) -> Double.compare(Math.abs(b.changePct), Math.abs(a.changePct)));
        ArrayNode top3 = mapper.createArrayNode();
        for (int i = 0; i < Math.min(3, changes.size()); i++) {
            AssetChange ac = changes.get(i);
            top3.add(String.format("%s %s %.2f%%", ac.symbol,
                    ac.changePct >= 0 ? "▲" : "▼", ac.changePct));
        }

        ObjectNode node = mapper.createObjectNode();
        node.put("marketDirection", marketDirection);
        node.put("overallAvgChange", round(overallAvg));
        node.put("totalAssets", changes.size());
        node.put("gainers", gainers);
        node.put("losers", losers);
        node.put("unchanged", unchanged);
        node.set("topMovers", top3);
        node.put("newsArticleCount", newsMap.size());
        node.put("timestamp", now);
        node.put("description", String.format(
                "Market is %s (avg change: %.2f%%). %d gainers, %d losers across %d assets. Top mover: %s",
                marketDirection.toLowerCase().replace('_', ' '),
                overallAvg, gainers, losers, changes.size(),
                changes.isEmpty() ? "N/A" : changes.get(0).symbol + " " + round(changes.get(0).changePct) + "%"));

        insightsMap.put("summary", new HazelcastJsonValue(node.toString()));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Simple data holder for asset price changes. */
    private static class AssetChange {
        final String symbol;
        final String assetType;
        final double changePct;
        final double price;

        AssetChange(String symbol, String assetType, double changePct, double price) {
            this.symbol = symbol;
            this.assetType = assetType;
            this.changePct = changePct;
            this.price = price;
        }
    }
}
