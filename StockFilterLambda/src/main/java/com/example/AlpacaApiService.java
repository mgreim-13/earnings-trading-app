package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service class for interacting with Alpaca Markets API
 * Provides real-time and historical stock data, plus options market data
 */
public class AlpacaApiService {
    
    private static final String ALPACA_BASE_URL = "https://data.alpaca.markets/v2";

    private static final String ALPACA_OPTIONS_BASE_URL = "https://data.alpaca.markets/v1beta1";
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String secretKey;
    private final boolean usePaperTrading;
    
    public AlpacaApiService(String apiKey, String secretKey, boolean usePaperTrading) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.usePaperTrading = usePaperTrading;
        this.objectMapper = new ObjectMapper();
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    /**
     * Get current stock quote
     */
    public StockQuote getLatestQuote(String symbol) throws IOException {
        String url = ALPACA_BASE_URL + "/stocks/" + symbol + "/quotes/latest";
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            if (jsonNode.has("quote")) {
                JsonNode quoteNode = jsonNode.get("quote");
                return new StockQuote(
                    symbol,
                    quoteNode.get("ap").asDouble(), // ask price
                    quoteNode.get("bp").asDouble(), // bid price
                    quoteNode.get("as").asLong(),   // ask size
                    quoteNode.get("bs").asLong(),   // bid size
                    quoteNode.get("t").asLong()     // timestamp
                );
            }
            
            return null;
        }
    }
    
    /**
     * Get historical bars (OHLCV data) for a symbol
     */
    public List<HistoricalBar> getHistoricalBars(String symbol, int days) throws IOException {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        
        String url = "https://data.alpaca.markets/v2/stocks/bars?" +
            "symbols=" + symbol +
            "&start=" + startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) +
            "&end=" + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE) +
            "&timeframe=1Day&limit=1000&feed=sip&adjustment=all";
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            List<HistoricalBar> bars = new ArrayList<>();
            if (jsonNode.has("bars") && jsonNode.get("bars").has(symbol)) {
                JsonNode barsArray = jsonNode.get("bars").get(symbol);
                
                for (JsonNode barNode : barsArray) {
                    bars.add(new HistoricalBar(
                        barNode.get("o").asDouble(), // open
                        barNode.get("h").asDouble(), // high
                        barNode.get("l").asDouble(), // low
                        barNode.get("c").asDouble(), // close
                        barNode.get("v").asLong(),   // volume
                        barNode.get("t").asText()    // timestamp
                    ));
                }
            }
            
            return bars;
        }
    }
    
    /**
     * Get latest trade for a symbol
     */
    public LatestTrade getLatestTrade(String symbol) throws IOException {
        String url = "https://data.alpaca.markets/v2/stocks/" + symbol + "/trades/latest";
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            if (jsonNode.has("trade")) {
                JsonNode tradeNode = jsonNode.get("trade");
                return new LatestTrade(
                    symbol,
                    tradeNode.get("p").asDouble(), // price
                    tradeNode.get("s").asLong(),   // size
                    tradeNode.get("t").asLong()    // timestamp
                );
            }
            
            return null;
        }
    }
    
    // ===== NEW OPTIONS API METHODS =====
    
    /**
     * Get option chain for a stock with filtering by expiration and type
     * Uses the correct Alpaca API endpoint: /options/snapshots/{underlying_symbol}
     */
    public Map<String, OptionSnapshot> getOptionChain(String underlying, LocalDate expGte, LocalDate expLte, String type) throws IOException {
        StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/snapshots/" + underlying);
        urlBuilder.append("?expiration_date_gte=").append(expGte.format(DateTimeFormatter.ISO_LOCAL_DATE));
        urlBuilder.append("&expiration_date_lte=").append(expLte.format(DateTimeFormatter.ISO_LOCAL_DATE));
        urlBuilder.append("&type=").append(type);
        urlBuilder.append("&feed=opra"); // Use OPRA feed as per documentation
        urlBuilder.append("&limit=1000"); // Max limit per documentation
        
        Request request = new Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) {
                    // Rate limit - wait and retry
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for rate limit", e);
                    }
                    return getOptionChain(underlying, expGte, expLte, type);
                }
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            Map<String, OptionSnapshot> optionChain = new HashMap<>();
            if (jsonNode.has("snapshots")) {
                JsonNode snapshotsNode = jsonNode.get("snapshots");
                // Iterate through the snapshots object where keys are symbols
                snapshotsNode.fieldNames().forEachRemaining(symbol -> {
                    JsonNode snapshotNode = snapshotsNode.get(symbol);
                    OptionSnapshot snapshot = parseOptionSnapshot(snapshotNode);
                    // Set the symbol since it's not in the snapshot data
                    optionChain.put(symbol, snapshot);
                });
            }
            
            return optionChain;
        }
    }
    
    /**
     * Get option snapshots for specific symbols
     * Uses the correct Alpaca API endpoint: /options/snapshots
     */
    public Map<String, OptionSnapshot> getOptionSnapshots(List<String> symbols) throws IOException {
        if (symbols.isEmpty()) return new HashMap<>();
        
        Map<String, OptionSnapshot> snapshots = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            int endIndex = Math.min(i + 100, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            
            String symbolsParam = String.join(",", batch);
            StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/snapshots");
            urlBuilder.append("?symbols=").append(symbolsParam);
            urlBuilder.append("&feed=opra"); // Use OPRA feed as per documentation
            urlBuilder.append("&limit=1000"); // Max limit per documentation
            
            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for rate limit", e);
                        }
                        continue; // Skip this batch on rate limit
                    }
                    continue; // Skip on error
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                
                if (jsonNode.has("snapshots")) {
                    JsonNode snapshotsNode = jsonNode.get("snapshots");
                    // Iterate through the snapshots object where keys are symbols
                    snapshotsNode.fieldNames().forEachRemaining(symbol -> {
                        JsonNode snapshotNode = snapshotsNode.get(symbol);
                        OptionSnapshot snapshot = parseOptionSnapshot(snapshotNode);
                        // Set the symbol since it's not in the snapshot data
                        snapshots.put(symbol, snapshot);
                    });
                }
            }
        }
        
        return snapshots;
    }
    
    /**
     * Get historical option trades for trade count analysis
     * Uses the correct Alpaca API endpoint: /options/trades
     */
    public List<OptionTrade> getOptionHistoricalTrades(List<String> symbols, LocalDate start, LocalDate end) throws IOException {
        if (symbols.isEmpty()) return new ArrayList<>();
        
        // Batch symbols (max 100 per call as per documentation)
        List<OptionTrade> allTrades = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            int endIndex = Math.min(i + 100, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            
            String symbolsParam = String.join(",", batch);
            StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/trades");
            urlBuilder.append("?symbols=").append(symbolsParam);
            urlBuilder.append("&start=").append(start.format(DateTimeFormatter.ISO_LOCAL_DATE));
            urlBuilder.append("&end=").append(end.format(DateTimeFormatter.ISO_LOCAL_DATE));
            urlBuilder.append("&limit=10000"); // Max limit per documentation
            urlBuilder.append("&sort=asc"); // Sort ascending by timestamp
            
            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for rate limit", e);
                        }
                        continue; // Skip this batch on rate limit
                    }
                    continue; // Skip on error
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                
                if (jsonNode.has("trades")) {
                    JsonNode tradesNode = jsonNode.get("trades");
                    // Iterate through the trades object where keys are symbols
                    tradesNode.fieldNames().forEachRemaining(symbol -> {
                        JsonNode symbolTrades = tradesNode.get(symbol);
                        if (symbolTrades.isArray()) {
                            for (JsonNode tradeNode : symbolTrades) {
                                OptionTrade trade = parseOptionTrade(tradeNode, symbol);
                                allTrades.add(trade);
                            }
                        }
                    });
                }
            }
        }
        
        return allTrades;
    }
    
    /**
     * Get latest option trades for multiple symbols
     * Uses the correct Alpaca API endpoint: /options/trades/latest
     */
    public Map<String, OptionTrade> getLatestOptionTrades(List<String> symbols) throws IOException {
        if (symbols.isEmpty()) return new HashMap<>();
        
        Map<String, OptionTrade> latestTrades = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            int endIndex = Math.min(i + 100, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            
            String symbolsParam = String.join(",", batch);
            StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/trades/latest");
            urlBuilder.append("?symbols=").append(symbolsParam);
            urlBuilder.append("&feed=opra"); // Use OPRA feed as per documentation
            
            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for rate limit", e);
                        }
                        continue; // Skip this batch on rate limit
                    }
                    continue; // Skip on error
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                
                if (jsonNode.has("trades")) {
                    JsonNode tradesNode = jsonNode.get("trades");
                    for (String symbol : batch) {
                        if (tradesNode.has(symbol)) {
                            JsonNode tradeNode = tradesNode.get(symbol);
                            OptionTrade trade = parseOptionTrade(tradeNode, symbol);
                            latestTrades.put(symbol, trade);
                        }
                    }
                }
            }
        }
        
        return latestTrades;
    }
    
    /**
     * Get historical option bars for volume analysis
     * Uses the correct Alpaca API endpoint: /options/bars
     */
    public List<OptionBar> getOptionHistoricalBars(List<String> symbols, int days) throws IOException {
        if (symbols.isEmpty()) return new ArrayList<>();
        
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        
        List<OptionBar> allBars = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            int endIndex = Math.min(i + 100, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            
            String symbolsParam = String.join(",", batch);
            StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/bars");
            urlBuilder.append("?symbols=").append(symbolsParam);
            urlBuilder.append("&timeframe=1Day"); // Required parameter
            urlBuilder.append("&start=").append(startDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
            urlBuilder.append("&end=").append(endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
            urlBuilder.append("&limit=10000"); // Max limit per documentation
            urlBuilder.append("&sort=asc"); // Sort ascending by timestamp
            
            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for rate limit", e);
                        }
                        continue;
                    }
                    continue;
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                
                if (jsonNode.has("bars")) {
                    JsonNode barsNode = jsonNode.get("bars");
                    for (String symbol : batch) {
                        if (barsNode.has(symbol)) {
                            JsonNode symbolBars = barsNode.get(symbol);
                            for (JsonNode barNode : symbolBars) {
                                OptionBar bar = parseOptionBar(barNode, symbol);
                                allBars.add(bar);
                            }
                        }
                    }
                }
            }
        }
        
        return allBars;
    }
    
    /**
     * Get latest option quotes for bid/ask analysis
     * Uses the correct Alpaca API endpoint: /options/quotes/latest
     */
    public Map<String, OptionQuote> getLatestOptionQuotes(List<String> symbols) throws IOException {
        if (symbols.isEmpty()) return new HashMap<>();
        
        Map<String, OptionQuote> quotes = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            int endIndex = Math.min(i + 100, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            
            String symbolsParam = String.join(",", batch);
            StringBuilder urlBuilder = new StringBuilder(ALPACA_OPTIONS_BASE_URL + "/options/quotes/latest");
            urlBuilder.append("?symbols=").append(symbolsParam);
            urlBuilder.append("&feed=opra"); // Use OPRA feed as per documentation
            
            Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for rate limit", e);
                        }
                        continue;
                    }
                    continue;
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                
                if (jsonNode.has("quotes")) {
                    JsonNode quotesNode = jsonNode.get("quotes");
                    for (String symbol : batch) {
                        if (quotesNode.has(symbol)) {
                            JsonNode quoteNode = quotesNode.get(symbol);
                            OptionQuote quote = parseOptionQuote(quoteNode, symbol);
                            quotes.put(symbol, quote);
                        }
                    }
                }
            }
        }
        
        return quotes;
    }
    
    /**
     * Get condition codes mapping for option trades
     * Uses the correct Alpaca API endpoint: /options/meta/conditions/{ticktype}
     */
    public Map<String, String> getConditionCodes(String tickType) throws IOException {
        String url = ALPACA_OPTIONS_BASE_URL + "/options/meta/conditions/" + tickType;
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            Map<String, String> conditionCodes = new HashMap<>();
            jsonNode.fieldNames().forEachRemaining(code -> {
                conditionCodes.put(code, jsonNode.get(code).asText());
            });
            
            return conditionCodes;
        }
    }
    
    /**
     * Get exchange codes mapping for options
     * Uses the correct Alpaca API endpoint: /options/meta/exchanges
     */
    public Map<String, String> getExchangeCodes() throws IOException {
        String url = ALPACA_OPTIONS_BASE_URL + "/options/meta/exchanges";
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("APCA-API-KEY-ID", apiKey)
            .addHeader("APCA-API-SECRET-KEY", secretKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            Map<String, String> exchangeCodes = new HashMap<>();
            jsonNode.fieldNames().forEachRemaining(code -> {
                exchangeCodes.put(code, jsonNode.get(code).asText());
            });
            
            return exchangeCodes;
        }
    }
    
    // ===== PARSING HELPER METHODS =====
    
    private OptionSnapshot parseOptionSnapshot(JsonNode node) {
        // Note: Symbol, strike, expiration, and type are not in the snapshot response
        // They need to be extracted from the symbol key or passed separately
        String symbol = ""; // Will be set by caller
        double strike = 0.0; // Not available in snapshot response
        String expiration = ""; // Not available in snapshot response  
        String type = ""; // Not available in snapshot response
        
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
        
        return new OptionSnapshot(symbol, strike, expiration, type, impliedVol, delta, theta, gamma, vega, bid, ask, bidSize, askSize);
    }
    
    private OptionTrade parseOptionTrade(JsonNode node, String symbol) {
        double price = node.has("p") ? node.get("p").asDouble() : 0.0; // price
        long size = node.has("s") ? node.get("s").asLong() : 0; // size
        long timestamp = node.has("t") ? node.get("t").asLong() : 0; // timestamp
        
        return new OptionTrade(symbol, price, size, timestamp);
    }
    
    private OptionBar parseOptionBar(JsonNode node, String symbol) {
        double open = node.get("o").asDouble();
        double high = node.get("h").asDouble();
        double low = node.get("l").asDouble();
        double close = node.get("c").asDouble();
        long volume = node.get("v").asLong();
        String timestamp = node.get("t").asText();
        
        return new OptionBar(symbol, open, high, low, close, volume, timestamp);
    }
    
    private OptionQuote parseOptionQuote(JsonNode node, String symbol) {
        double bid = node.has("bp") ? node.get("bp").asDouble() : 0.0; // bid price
        double ask = node.has("ap") ? node.get("ap").asDouble() : 0.0; // ask price
        long bidSize = node.has("bs") ? node.get("bs").asLong() : 0; // bid size
        long askSize = node.has("as") ? node.get("as").asLong() : 0; // ask size
        long timestamp = node.has("t") ? node.get("t").asLong() : 0; // timestamp
        
        return new OptionQuote(symbol, bid, ask, bidSize, askSize, timestamp);
    }
    
    /**
     * Data classes for Alpaca API responses
     */
    public static class StockQuote {
        private final String symbol;
        private final double askPrice;
        private final double bidPrice;
        private final long askSize;
        private final long bidSize;
        private final long timestamp;
        
        public StockQuote(String symbol, double askPrice, double bidPrice, long askSize, long bidSize, long timestamp) {
            this.symbol = symbol;
            this.askPrice = askPrice;
            this.bidPrice = bidPrice;
            this.askSize = askSize;
            this.bidSize = bidSize;
            this.timestamp = timestamp;
        }
        
        public String getSymbol() { return symbol; }
        public double getAskPrice() { return askPrice; }
        public double getBidPrice() { return bidPrice; }
        public long getAskSize() { return askSize; }
        public long getBidSize() { return bidSize; }
        public long getTimestamp() { return timestamp; }
        public double getMidPrice() { return (askPrice + bidPrice) / 2.0; }
    }
    
    public static class HistoricalBar {
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final long volume;
        private final String timestamp;
        
        public HistoricalBar(double open, double high, double low, double close, long volume, String timestamp) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.timestamp = timestamp;
        }
        
        public double getOpen() { return open; }
        public double getHigh() { return high; }
        public double getLow() { return low; }
        public double getClose() { return close; }
        public long getVolume() { return volume; }
        public String getTimestamp() { return timestamp; }
    }
    
    public static class LatestTrade {
        private final String symbol;
        private final double price;
        private final long size;
        private final long timestamp;
        
        public LatestTrade(String symbol, double price, long size, long timestamp) {
            this.symbol = symbol;
            this.price = price;
            this.size = size;
            this.timestamp = timestamp;
        }
        
        public String getSymbol() { return symbol; }
        public double getPrice() { return price; }
        public long getSize() { return size; }
        public long getTimestamp() { return timestamp; }
    }
    
    // ===== NEW OPTIONS DATA CLASSES =====
    
    public static class OptionSnapshot {
        private final String symbol;
        private final double strike;
        private final String expiration;
        private final String type;
        private final double impliedVol;
        private final double delta;
        private final double theta;
        private final double gamma;
        private final double vega;
        private final double bid;
        private final double ask;
        private final long bidSize;
        private final long askSize;
        
        public OptionSnapshot(String symbol, double strike, String expiration, String type, 
                            double impliedVol, double delta, double theta, double gamma, double vega,
                            double bid, double ask, long bidSize, long askSize) {
            this.symbol = symbol;
            this.strike = strike;
            this.expiration = expiration;
            this.type = type;
            this.impliedVol = impliedVol;
            this.delta = delta;
            this.theta = theta;
            this.gamma = gamma;
            this.vega = vega;
            this.bid = bid;
            this.ask = ask;
            this.bidSize = bidSize;
            this.askSize = askSize;
        }
        
        public String getSymbol() { return symbol; }
        public double getStrike() { return strike; }
        public String getExpiration() { return expiration; }
        public String getType() { return type; }
        public double getImpliedVol() { return impliedVol; }
        public double getDelta() { return delta; }
        public double getTheta() { return theta; }
        public double getGamma() { return gamma; }
        public double getVega() { return vega; }
        public double getBid() { return bid; }
        public double getAsk() { return ask; }
        public long getBidSize() { return bidSize; }
        public long getAskSize() { return askSize; }
        public double getMidPrice() { return (bid + ask) / 2.0; }
        public double getBidAskSpread() { return ask - bid; }
        public double getBidAskSpreadPercent() { 
            double mid = getMidPrice();
            return mid > 0 ? getBidAskSpread() / mid : 0.0; 
        }
        public long getTotalSize() { return bidSize + askSize; }
    }
    
    public static class OptionTrade {
        private final String symbol;
        private final double price;
        private final long size;
        private final long timestamp;
        
        public OptionTrade(String symbol, double price, long size, long timestamp) {
            this.symbol = symbol;
            this.price = price;
            this.size = size;
            this.timestamp = timestamp;
        }
        
        public String getSymbol() { return symbol; }
        public double getPrice() { return price; }
        public long getSize() { return size; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class OptionBar {
        private final String symbol;
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final long volume;
        private final String timestamp;
        
        public OptionBar(String symbol, double open, double high, double low, double close, long volume, String timestamp) {
            this.symbol = symbol;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.timestamp = timestamp;
        }
        
        public String getSymbol() { return symbol; }
        public double getOpen() { return open; }
        public double getHigh() { return high; }
        public double getLow() { return low; }
        public double getClose() { return close; }
        public long getVolume() { return volume; }
        public String getTimestamp() { return timestamp; }
    }
    
    public static class OptionQuote {
        private final String symbol;
        private final double bid;
        private final double ask;
        private final long bidSize;
        private final long askSize;
        private final long timestamp;
        
        public OptionQuote(String symbol, double bid, double ask, long bidSize, long askSize, long timestamp) {
            this.symbol = symbol;
            this.bid = bid;
            this.ask = ask;
            this.bidSize = bidSize;
            this.askSize = askSize;
            this.timestamp = timestamp;
        }
        
        public String getSymbol() { return symbol; }
        public double getBid() { return bid; }
        public double getAsk() { return ask; }
        public long getBidSize() { return bidSize; }
        public long getAskSize() { return askSize; }
        public long getTimestamp() { return timestamp; }
        public double getMidPrice() { return (bid + ask) / 2.0; }
        public double getBidAskSpread() { return ask - bid; }
        public long getTotalSize() { return bidSize + askSize; }
    }
}
