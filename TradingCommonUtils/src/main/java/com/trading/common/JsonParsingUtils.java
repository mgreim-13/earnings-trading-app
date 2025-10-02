package com.trading.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.models.*;
import java.util.Map;

/**
 * Shared utilities for parsing JSON data from Alpaca API responses
 * Consolidates duplicate parsing logic across the project
 */
public class JsonParsingUtils {
    
    /**
     * Parse stock quote from JSON node
     */
    public static StockQuote parseStockQuote(JsonNode quoteNode, String symbol) {
        return new StockQuote(
            symbol,
            quoteNode.get("ap").asDouble(), // ask price
            quoteNode.get("bp").asDouble(), // bid price
            quoteNode.get("as").asLong(),   // ask size
            quoteNode.get("bs").asLong(),   // bid size
            quoteNode.get("t").asLong()     // timestamp
        );
    }
    
    /**
     * Parse historical bar from JSON node
     */
    public static HistoricalBar parseHistoricalBar(JsonNode barNode) {
        return new HistoricalBar(
            barNode.get("o").asDouble(), // open
            barNode.get("h").asDouble(), // high
            barNode.get("l").asDouble(), // low
            barNode.get("c").asDouble(), // close
            barNode.get("v").asLong(),   // volume
            barNode.get("t").asText()    // timestamp
        );
    }
    
    /**
     * Parse latest trade from JSON node
     */
    public static LatestTrade parseLatestTrade(JsonNode tradeNode, String symbol) {
        return new LatestTrade(
            symbol,
            tradeNode.get("p").asDouble(), // price
            tradeNode.get("s").asLong(),   // size
            parseTimestamp(tradeNode.get("t"))    // timestamp
        );
    }
    
    /**
     * Parse option snapshot from JSON node
     */
    public static OptionSnapshot parseOptionSnapshot(JsonNode node, String symbol) {
        // Extract strike, expiration, and type from the option symbol
        double strike = 0.0;
        String expiration = "";
        String type = "";
        
        try {
            Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(symbol);
            strike = (Double) parsed.get("strike");
            expiration = (String) parsed.get("expiration");
            type = (String) parsed.get("type");
        } catch (Exception e) {
            // If parsing fails, use defaults
            System.out.println("Warning: Failed to parse option symbol " + symbol + ": " + e.getMessage());
        }
        
        double impliedVol = 0.0;
        double delta = 0.0;
        double theta = 0.0;
        double gamma = 0.0;
        double vega = 0.0;
        
        if (node.has("greeks")) {
            JsonNode greeks = node.get("greeks");
            delta = greeks.has("delta") ? greeks.get("delta").asDouble() : 0.0;
            theta = greeks.has("theta") ? greeks.get("theta").asDouble() : 0.0;
            gamma = greeks.has("gamma") ? greeks.get("gamma").asDouble() : 0.0;
            vega = greeks.has("vega") ? greeks.get("vega").asDouble() : 0.0;
        }
        
        if (node.has("impliedVolatility")) {
            impliedVol = node.get("impliedVolatility").asDouble();
        }
        
        double bid = 0.0, ask = 0.0;
        long bidSize = 0, askSize = 0;
        
        if (node.has("latestQuote")) {
            JsonNode quote = node.get("latestQuote");
            bid = quote.has("bp") ? quote.get("bp").asDouble() : 0.0; // bid price
            ask = quote.has("ap") ? quote.get("ap").asDouble() : 0.0; // ask price
            bidSize = quote.has("bs") ? quote.get("bs").asLong() : 0; // bid size
            askSize = quote.has("as") ? quote.get("as").asLong() : 0; // ask size
        }
        
        return new OptionSnapshot(symbol, strike, expiration, type, 
                                impliedVol, delta, theta, gamma, vega,
                                bid, ask, bidSize, askSize);
    }
    
    /**
     * Parse option trade from JSON node
     */
    public static OptionTrade parseOptionTrade(JsonNode node, String symbol) {
        double price = node.has("p") ? node.get("p").asDouble() : 0.0; // price
        long size = node.has("s") ? node.get("s").asLong() : 0; // size
        long timestamp = parseTimestamp(node.get("t")); // timestamp
        
        return new OptionTrade(symbol, price, size, timestamp);
    }
    
    /**
     * Parse option bar from JSON node
     */
    public static OptionBar parseOptionBar(JsonNode node, String symbol) {
        double open = node.get("o").asDouble();
        double high = node.get("h").asDouble();
        double low = node.get("l").asDouble();
        double close = node.get("c").asDouble();
        long volume = node.get("v").asLong();
        String timestamp = node.get("t").asText();
        
        return new OptionBar(symbol, open, high, low, close, volume, timestamp);
    }
    
    /**
     * Parse option quote from JSON node
     */
    public static OptionQuote parseOptionQuote(JsonNode node, String symbol) {
        double bid = node.has("bp") ? node.get("bp").asDouble() : 0.0; // bid price
        double ask = node.has("ap") ? node.get("ap").asDouble() : 0.0; // ask price
        long bidSize = node.has("bs") ? node.get("bs").asLong() : 0; // bid size
        long askSize = node.has("as") ? node.get("as").asLong() : 0; // ask size
        long timestamp = parseTimestamp(node.get("t")); // timestamp
        
        return new OptionQuote(symbol, bid, ask, bidSize, askSize, timestamp);
    }
    
    /**
     * Extract bid price from quote JSON node
     */
    public static double getBidPrice(JsonNode quote) {
        return getPrice(quote, "bp");
    }
    
    /**
     * Extract ask price from quote JSON node
     */
    public static double getAskPrice(JsonNode quote) {
        return getPrice(quote, "ap");
    }
    
    /**
     * Extract mid price from quote JSON node
     */
    public static double getMidPrice(JsonNode quote) {
        double bid = getBidPrice(quote);
        double ask = getAskPrice(quote);
        if (bid > 0 && ask > 0) {
            return PriceUtils.calculateMidPrice(bid, ask);
        }
        return getPrice(quote, "last");
    }
    
    /**
     * Extract validated mid price from quote JSON node using OptionSelectionUtils validation
     * Returns -1 if bid/ask spread is invalid (e.g., > 20% spread ratio)
     */
    public static double getValidatedMidPrice(JsonNode quote) {
        double bid = getBidPrice(quote);
        double ask = getAskPrice(quote);
        return OptionSelectionUtils.validateBidAsk(bid, ask);
    }
    
    /**
     * Extract price from quote JSON node by field name
     */
    public static double getPrice(JsonNode quote, String field) {
        return quote.has(field) ? quote.get(field).asDouble() : 0.0;
    }
    
    /**
     * Check if quote has valid bid/ask data using OptionSelectionUtils validation
     */
    public static boolean hasValidBidAsk(JsonNode quote) {
        double bid = getBidPrice(quote);
        double ask = getAskPrice(quote);
        return OptionSelectionUtils.validateBidAsk(bid, ask) > 0;
    }
    
    /**
     * Parse timestamp from JSON node, handling both string and numeric formats
     */
    private static long parseTimestamp(JsonNode timestampNode) {
        if (timestampNode == null || timestampNode.isNull()) {
            return 0L;
        }
        
        if (timestampNode.isTextual()) {
            // Handle ISO 8601 string format like "2024-04-22T19:59:59.992734208Z"
            try {
                String timestampStr = timestampNode.asText();
                if (timestampStr.isEmpty()) {
                    return 0L;
                }
                // Convert ISO 8601 to Unix timestamp (seconds since epoch)
                return java.time.Instant.parse(timestampStr).getEpochSecond();
            } catch (Exception e) {
                System.out.println("Warning: Failed to parse timestamp: " + timestampNode.asText());
                return 0L;
            }
        } else if (timestampNode.isNumber()) {
            // Handle numeric timestamp (already in Unix format)
            return timestampNode.asLong();
        }
        
        return 0L;
    }
}
