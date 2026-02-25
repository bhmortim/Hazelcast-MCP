package com.hazelcast.mcp.demo;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.mcp.demo.feeds.CryptoFeed;
import com.hazelcast.mcp.demo.feeds.MarketCorrelationJob;
import com.hazelcast.mcp.demo.feeds.NewsFeed;
import com.hazelcast.mcp.demo.feeds.StockFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Central orchestrator for all live data feeds. Manages scheduled polling
 * of external APIs and periodic computation of market insights.
 *
 * <p>Feed activation follows a graceful degradation model:
 * <ul>
 *   <li><b>Crypto</b> (CoinGecko) — always active, no API key needed</li>
 *   <li><b>Stocks</b> (Alpha Vantage) — active only if {@code ALPHA_VANTAGE_API_KEY} is set</li>
 *   <li><b>News</b> (NewsAPI) — active only if {@code NEWS_API_KEY} is set</li>
 *   <li><b>Market Insights</b> — always active, correlates whatever data is available</li>
 * </ul>
 *
 * <p>Registers a JVM shutdown hook for graceful termination.</p>
 */
public class LiveDataFeedManager {

    private static final Logger logger = LoggerFactory.getLogger(LiveDataFeedManager.class);

    private final HazelcastInstance client;
    private final ScheduledExecutorService scheduler;

    private final String alphaVantageKey;
    private final String newsApiKey;

    public LiveDataFeedManager(HazelcastInstance client) {
        this.client = client;
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "live-feed-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        this.alphaVantageKey = env("ALPHA_VANTAGE_API_KEY", null);
        this.newsApiKey = env("NEWS_API_KEY", null);
    }

    /**
     * Starts all configured feeds and blocks until shutdown.
     */
    public void startAndBlock() {
        logger.info("=== Starting Live Data Feeds ===");

        // --- Crypto feed: always active ---
        CryptoFeed cryptoFeed = new CryptoFeed(client);
        scheduler.scheduleAtFixedRate(cryptoFeed, 0, 30, TimeUnit.SECONDS);
        logger.info("  ✓ CryptoFeed active (CoinGecko, 30s interval, no key required)");

        // --- Stock feed: requires API key ---
        if (alphaVantageKey != null && !alphaVantageKey.isBlank()) {
            StockFeed stockFeed = new StockFeed(client, alphaVantageKey);
            scheduler.scheduleAtFixedRate(stockFeed, 5, 300, TimeUnit.SECONDS);
            logger.info("  ✓ StockFeed active (Alpha Vantage, 5min interval)");
        } else {
            logger.info("  ○ StockFeed skipped (set ALPHA_VANTAGE_API_KEY to enable)");
        }

        // --- News feed: requires API key ---
        if (newsApiKey != null && !newsApiKey.isBlank()) {
            NewsFeed newsFeed = new NewsFeed(client, newsApiKey);
            scheduler.scheduleAtFixedRate(newsFeed, 10, 600, TimeUnit.SECONDS);
            logger.info("  ✓ NewsFeed active (NewsAPI, 10min interval)");
        } else {
            logger.info("  ○ NewsFeed skipped (set NEWS_API_KEY to enable)");
        }

        // --- Market insights: always runs, correlates whatever data is available ---
        MarketCorrelationJob correlationJob = new MarketCorrelationJob(client);
        scheduler.scheduleAtFixedRate(correlationJob, 60, 300, TimeUnit.SECONDS);
        logger.info("  ✓ MarketCorrelationJob active (5min interval)");

        logger.info("=== Live feeds running — press Ctrl+C to stop ===");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down live feeds...");
            stop();
        }, "feed-shutdown"));

        // Block forever — feeds run in background threads
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("Feed manager interrupted, shutting down");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the scheduler and all feeds.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Live feeds stopped");
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultVal;
    }
}
