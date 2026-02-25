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
import java.util.Set;

/**
 * Polls NewsAPI every 10 minutes for the latest financial/market news headlines.
 * Requires a free API key from <a href="https://newsapi.org/register">newsapi.org</a>.
 *
 * <p>Performs basic sentiment scoring on each article using keyword matching.
 * Stores data in the {@code news} IMap with URL-hash keys.</p>
 */
public class NewsFeed implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(NewsFeed.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String NEWS_URL =
            "https://newsapi.org/v2/everything"
            + "?q=stock+market+OR+cryptocurrency+OR+bitcoin"
            + "&language=en&sortBy=publishedAt&pageSize=20"
            + "&apiKey=%s";

    private static final Set<String> POSITIVE_WORDS = Set.of(
            "surge", "surges", "surging", "rally", "rallies", "rallying",
            "gain", "gains", "gaining", "bull", "bullish", "soar", "soars",
            "rise", "rises", "rising", "jump", "jumps", "jumping",
            "boom", "booms", "booming", "recover", "recovery", "upturn",
            "high", "record", "outperform", "breakout", "growth"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "crash", "crashes", "crashing", "drop", "drops", "dropping",
            "plunge", "plunges", "plunging", "bear", "bearish",
            "fall", "falls", "falling", "decline", "declines", "declining",
            "loss", "losses", "losing", "tank", "tanks", "tanking",
            "slump", "slumps", "bust", "downturn", "selloff", "sell-off",
            "low", "underperform", "collapse", "recession", "fear"
    );

    private final HazelcastInstance client;
    private final String apiKey;
    private final HttpClient httpClient;

    public NewsFeed(HazelcastInstance client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void run() {
        if (apiKey == null || apiKey.isBlank()) return;

        try {
            String url = String.format(NEWS_URL, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("NewsAPI returned HTTP {}", response.statusCode());
                return;
            }

            JsonNode root = mapper.readTree(response.body());

            if (!"ok".equals(root.path("status").asText())) {
                logger.warn("NewsAPI error: {}", root.path("message").asText("unknown"));
                return;
            }

            JsonNode articles = root.get("articles");
            if (articles == null || !articles.isArray()) return;

            IMap<String, HazelcastJsonValue> map = client.getMap("news");
            long now = System.currentTimeMillis();
            int count = 0;

            for (JsonNode article : articles) {
                String articleUrl = article.path("url").asText("");
                if (articleUrl.isBlank()) continue;

                String title = article.path("title").asText("");
                String description = article.path("description").asText("");
                String sourceName = article.path("source").path("name").asText("");
                String publishedAt = article.path("publishedAt").asText("");
                String author = article.path("author").asText("");

                // Basic sentiment scoring
                double sentiment = scoreSentiment(title + " " + description);

                ObjectNode node = mapper.createObjectNode();
                node.put("title", title);
                node.put("description", description);
                node.put("source", sourceName);
                node.put("author", author);
                node.put("url", articleUrl);
                node.put("publishedAt", publishedAt);
                node.put("sentiment", Math.round(sentiment * 100.0) / 100.0);
                node.put("sentimentLabel", sentiment > 0.1 ? "POSITIVE" : sentiment < -0.1 ? "NEGATIVE" : "NEUTRAL");
                node.put("fetchedAt", now);
                node.put("sourceApi", "newsapi");

                // Use hash of URL as key to deduplicate
                String key = "NEWS-" + Math.abs(articleUrl.hashCode());
                map.put(key, new HazelcastJsonValue(node.toString()));
                count++;
            }

            logger.debug("NewsFeed: stored {} articles", count);

        } catch (Exception e) {
            logger.warn("NewsFeed error: {}", e.getMessage());
        }
    }

    /**
     * Simple keyword-based sentiment scoring.
     * Returns a value from -1.0 (very negative) to 1.0 (very positive).
     */
    static double scoreSentiment(String text) {
        if (text == null || text.isBlank()) return 0.0;

        String lower = text.toLowerCase();
        String[] words = lower.split("[\\s,.;:!?()\\[\\]\"']+");

        int positive = 0;
        int negative = 0;

        for (String word : words) {
            if (POSITIVE_WORDS.contains(word)) positive++;
            if (NEGATIVE_WORDS.contains(word)) negative++;
        }

        int total = positive + negative;
        if (total == 0) return 0.0;

        return (double) (positive - negative) / total;
    }
}
