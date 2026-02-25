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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Polls CoinGecko free API every 30 seconds for real-time cryptocurrency prices.
 * No API key required — works out of the box.
 *
 * <p>Stores data in the {@code crypto} IMap with keys like BTC, ETH, SOL etc.</p>
 */
public class CryptoFeed implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CryptoFeed.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String COINGECKO_URL =
            "https://api.coingecko.com/api/v3/simple/price"
            + "?ids=bitcoin,ethereum,solana,cardano,polkadot,chainlink,avalanche-2,polygon-pos,uniswap,litecoin"
            + "&vs_currencies=usd"
            + "&include_24hr_vol=true"
            + "&include_24hr_change=true"
            + "&include_market_cap=true";

    /** CoinGecko API id → ticker symbol */
    private static final Map<String, String> ID_TO_SYMBOL = new LinkedHashMap<>();
    static {
        ID_TO_SYMBOL.put("bitcoin", "BTC");
        ID_TO_SYMBOL.put("ethereum", "ETH");
        ID_TO_SYMBOL.put("solana", "SOL");
        ID_TO_SYMBOL.put("cardano", "ADA");
        ID_TO_SYMBOL.put("polkadot", "DOT");
        ID_TO_SYMBOL.put("chainlink", "LINK");
        ID_TO_SYMBOL.put("avalanche-2", "AVAX");
        ID_TO_SYMBOL.put("polygon-pos", "MATIC");
        ID_TO_SYMBOL.put("uniswap", "UNI");
        ID_TO_SYMBOL.put("litecoin", "LTC");
    }

    private final HazelcastInstance client;
    private final HttpClient httpClient;

    public CryptoFeed(HazelcastInstance client) {
        this.client = client;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void run() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COINGECKO_URL))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("CoinGecko returned HTTP {}", response.statusCode());
                return;
            }

            JsonNode root = mapper.readTree(response.body());
            IMap<String, HazelcastJsonValue> map = client.getMap("crypto");
            long now = System.currentTimeMillis();
            int count = 0;

            for (var entry : ID_TO_SYMBOL.entrySet()) {
                String coinId = entry.getKey();
                String symbol = entry.getValue();
                JsonNode coin = root.get(coinId);

                if (coin == null) continue;

                ObjectNode node = mapper.createObjectNode();
                node.put("symbol", symbol);
                node.put("coinId", coinId);
                node.put("price", round(coin.path("usd").asDouble()));
                node.put("volume24h", round(coin.path("usd_24h_vol").asDouble()));
                node.put("change24h", round(coin.path("usd_24h_change").asDouble()));
                node.put("marketCap", round(coin.path("usd_market_cap").asDouble()));
                node.put("timestamp", now);
                node.put("source", "coingecko");

                map.put(symbol, new HazelcastJsonValue(node.toString()));
                count++;
            }

            logger.debug("CryptoFeed: updated {} coins", count);

        } catch (Exception e) {
            logger.warn("CryptoFeed error: {}", e.getMessage());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
