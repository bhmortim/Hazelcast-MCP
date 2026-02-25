package com.hazelcast.mcp.demo;

import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.function.FunctionEx;
import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.aggregate.AggregateOperation1;

import java.io.Serializable;
import java.util.Map;

/**
 * Standalone serializable functions for Jet pipelines.
 *
 * <p>This class is uploaded to the Hazelcast server via {@code JobConfig.addClass()}
 * so all lambdas/functions must only reference classes available on the server classpath
 * (JDK + Hazelcast). No Jackson, SLF4J, or other external dependencies.</p>
 */
public final class JetFunctions implements Serializable {
    private static final long serialVersionUID = 1L;

    private JetFunctions() {}

    // ---- P&L Recalculation functions ----

    /** Reads a position JSON, applies a small random price movement, and writes it back. */
    public static final FunctionEx<Map.Entry<String, HazelcastJsonValue>,
            Map.Entry<String, HazelcastJsonValue>> RECALC_PNL = entry -> {
        String json = entry.getValue().toString();
        String symbol  = jsonString(json, "symbol");
        String account = jsonString(json, "account");
        int qty        = (int) jsonDouble(json, "quantity");
        double avgCost = jsonDouble(json, "avgCost");

        double currentPrice = avgCost * (1 + (Math.random() - 0.5) * 0.01);
        double marketValue  = Math.round(qty * currentPrice * 100.0) / 100.0;
        double pnl          = Math.round((currentPrice - avgCost) * qty * 100.0) / 100.0;
        double pnlPct       = Math.round((pnl / (Math.abs(qty) * avgCost)) * 10000.0) / 100.0;

        String updated = String.format(
                "{\"symbol\":\"%s\",\"account\":\"%s\",\"quantity\":%d,\"side\":\"%s\","
                + "\"avgCost\":%.2f,\"marketPrice\":%.2f,\"marketValue\":%.2f,"
                + "\"unrealizedPnL\":%.2f,\"unrealizedPnLPct\":%.2f,"
                + "\"currency\":\"USD\",\"lastUpdated\":%d}",
                symbol, account, qty, qty > 0 ? "LONG" : "SHORT",
                avgCost, Math.round(currentPrice * 100.0) / 100.0,
                marketValue, pnl, pnlPct, System.currentTimeMillis());

        return Map.entry(account + "-" + symbol, new HazelcastJsonValue(updated));
    };

    // ---- Risk Aggregation functions ----

    /** Extracts account + [marketValue, pnl, 1] from a position for grouping. */
    public static final FunctionEx<Map.Entry<String, HazelcastJsonValue>,
            Map.Entry<String, double[]>> EXTRACT_FOR_RISK = entry -> {
        String json = entry.getValue().toString();
        String account = jsonString(json, "account");
        double mv  = Math.abs(jsonDouble(json, "marketValue"));
        double pnl = jsonDouble(json, "unrealizedPnL");
        return Map.entry(account, new double[]{mv, pnl, 1});
    };

    /** Groups by account key. */
    public static final FunctionEx<Map.Entry<String, double[]>, String> ACCOUNT_KEY =
            entry -> entry.getKey();

    /** Reducing aggregate for risk metrics. */
    public static AggregateOperation1<Map.Entry<String, double[]>, ?, double[]> riskReducer() {
        return AggregateOperations.<Map.Entry<String, double[]>, double[]>reducing(
                new double[]{0, 0, 0},
                (Map.Entry<String, double[]> e) -> e.getValue(),
                (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]},
                (a, b) -> new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}
        );
    }

    /** Converts aggregated risk data to a HazelcastJsonValue. */
    public static final FunctionEx<Map.Entry<String, double[]>,
            Map.Entry<String, HazelcastJsonValue>> FORMAT_RISK = entry -> {
        String account = entry.getKey();
        double[] agg = entry.getValue();
        double totalExposure = Math.round(agg[0] * 100.0) / 100.0;
        double totalPnL      = Math.round(agg[1] * 100.0) / 100.0;
        int count            = (int) agg[2];

        String json = String.format(
                "{\"account\":\"%s\",\"totalExposure\":%.2f,\"netExposure\":%.2f,"
                + "\"totalPnL\":%.2f,\"positionCount\":%d,"
                + "\"var95\":%.2f,\"var99\":%.2f,"
                + "\"sharpeRatio\":%.2f,\"maxDrawdown\":%.2f,\"beta\":%.2f,"
                + "\"currency\":\"USD\",\"calculatedAt\":%d}",
                account, totalExposure,
                Math.round(totalExposure * 0.6 * 100.0) / 100.0,
                totalPnL, count,
                Math.round(totalExposure * 0.10 * 100.0) / 100.0,
                Math.round(totalExposure * 0.14 * 100.0) / 100.0,
                Math.round(Math.random() * 200.0) / 100.0,
                Math.round(Math.random() * 15.0 + 5.0) / 100.0,
                Math.round((0.7 + Math.random() * 0.7) * 100.0) / 100.0,
                System.currentTimeMillis());

        return Map.entry(account, new HazelcastJsonValue(json));
    };

    // ---- JDK-only JSON helpers ----

    /** Extract a string value from JSON by key. No external dependencies. */
    public static String jsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    /** Extract a numeric value from JSON by key. No external dependencies. */
    public static double jsonDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0.0;
        start += search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ' ') break;
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
