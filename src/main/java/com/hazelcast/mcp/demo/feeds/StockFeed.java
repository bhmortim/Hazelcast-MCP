package com.hazelcast.mcp.demo.feeds;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Polls Alpha Vantage GLOBAL_QUOTE API every 5 minutes for real-time stock prices.
 * Requires a free API key from <a href="https://www.alphavantage.co/support/#api-key">alphavantage.co</a>.
 *
 * <p>Rotates through 8 stock symbols per cycle. The free tier allows 25 requests/day,
 * so with 8 symbols per cycle we get ~3 full update cycles per day.</p>
 *
 * <p>Stores data in the {@code stocks} IMap with keys like AAPL, MSFT, GOOGL etc.</p>
 */
public class StockFeed implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StockFeed.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String[] SYMBOLS = {
        "AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "NVDA", "JPM", "GS"
    };

    private static final String BASE_URL = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=%s&apikey=%s";

    private final HazelcastInstance client;
    private final String apiKey;
    private final HttpClient httpClient;

    public StockFeed(HazelcastInstance client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void run() {
        if (apiKey == null || apiKey.isBlank()) return;

        IMap<String, HazelcastJsonValue> map = client.getMap("stocks");
        long now = System.currentTimeMillis();
        int count = 0;

        for (String symbol : SYMBOLS) {
            try {
                String url = String.format(BASE_URL, symbol, apiKey);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    logger.warn("Alpha Vantage returned HTTP {} for {}", response.statusCode(), symbol);
                    continue;
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode quote = root.get("Global Quote");

                if (quote == null || quote.isEmpty()) {
                    // May be rate-limited or invalid response
                    if (root.has("Note") || root.has("Information")) {
                        logger.warn("Alpha Vantage rate limit hit, stopping cycle");
                        break;
                    }
                    continue;
                }

                ObjectNode node = mapper.createObjectNode();
                node.put("symbol", symbol);
                node.put("price", parseDouble(quote, "05. price"));
                node.put("open", parseDouble(quote, "02. open"));
                node.put("high", parseDouble(quote, "03. high"));
                node.put("low", parseDouble(quote, "04. low"));
                node.put("volume", parseLong(quote, "06. volume"));
                node.put("previousClose", parseDouble(quote, "08. previous close"));
                node.put("change", parseDouble(quote, "09. change"));
                node.put("changePct", parseChangePct(quote));
                node.put("latestTradingDay", quote.path("07. latest trading day").asText(""));
                node.put("timestamp", now);
                node.put("source", "alphavantage");

                map.put(symbol, new HazelcastJsonValue(node.toString()));
                count++;

                // Be respectful of rate limits — small pause between calls
                Thread.sleep(1500);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warn("StockFeed error for {}: {}", symbol, e.getMessage());
            }
        }

        if (count > 0) {
            logger.debug("StockFeed: updated {} stocks", count);
        }
    }

    private static double parseDouble(JsonNode quote, String field) {
        try {
            return Math.round(Double.parseDouble(quote.path(field).asText("0")) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static long parseLong(JsonNode quote, String field) {
        try {
            return Long.parseLong(quote.path(field).asText("0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseChangePct(JsonNode quote) {
        try {
            String raw = quote.path("10. change percent").asText("0%");
            return Math.round(Double.parseDouble(raw.replace("%", "")) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
