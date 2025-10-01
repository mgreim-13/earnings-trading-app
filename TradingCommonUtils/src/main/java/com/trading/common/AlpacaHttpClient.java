package com.trading.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.models.*;
import okhttp3.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unified HTTP client for all Alpaca API interactions
 * Replaces scattered HTTP client implementations across Lambda functions
 */
public class AlpacaHttpClient {
    
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    
    // Alpaca API URLs
    private static final String ALPACA_BASE_URL = "https://data.alpaca.markets/v2";
    private static final String ALPACA_OPTIONS_BASE_URL = "https://data.alpaca.markets/v1beta1";
    private static final String ALPACA_TRADING_URL = "https://paper-api.alpaca.markets/v2";
    
    // Constants
    private static final int BATCH_SIZE = 100;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    
    /**
     * Make HTTP request to Alpaca API with automatic retry on rate limiting
     */
    public static String makeAlpacaRequest(String url, String method, String body, AlpacaCredentials credentials) {
        return makeAlpacaRequest(url, method, body, credentials, 3); // 3 retry attempts
    }
    
    /**
     * Make HTTP request to Alpaca API with custom retry count
     */
    public static String makeAlpacaRequest(String url, String method, String body, AlpacaCredentials credentials, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                
                // Set method and body
                if ("GET".equals(method)) {
                    requestBuilder.get();
                } else if ("POST".equals(method)) {
                    RequestBody requestBody = RequestBody.create(body, MediaType.get("application/json"));
                    requestBuilder.post(requestBody);
                } else if ("PUT".equals(method)) {
                    RequestBody requestBody = RequestBody.create(body, MediaType.get("application/json"));
                    requestBuilder.put(requestBody);
                } else if ("DELETE".equals(method)) {
                    requestBuilder.delete();
                }
                
                Request request = requestBuilder
                        .addHeader("APCA-API-KEY-ID", credentials.getApiKeyId())
                        .addHeader("APCA-API-SECRET-KEY", credentials.getSecretKey())
                        .addHeader("accept", "application/json")
                        .addHeader("content-type", "application/json")
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        return response.body().string();
                    } else if (response.code() == 429 && attempt < maxRetries) {
                        // Rate limit - wait and retry
                        Thread.sleep(1000 * (attempt + 1)); // Exponential backoff
                        continue;
                    } else {
                        throw new RuntimeException("HTTP " + response.code() + ": " + response.body().string());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted", e);
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("HTTP request failed after " + (maxRetries + 1) + " attempts: " + e.getMessage(), e);
                }
                // Wait before retry
                try {
                    Thread.sleep(1000 * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Request interrupted during retry", ie);
                }
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }
    
    /**
     * Make request to Alpaca API with specified type and method
     */
    public static String makeAlpacaApiRequest(ApiType apiType, String endpoint, String method, String body, AlpacaCredentials credentials) {
        String baseUrl = switch (apiType) {
            case DATA -> ALPACA_BASE_URL;
            case OPTIONS -> ALPACA_OPTIONS_BASE_URL;
            case TRADING -> ALPACA_TRADING_URL;
        };
        String url = baseUrl + endpoint;
        return makeAlpacaRequest(url, method, body, credentials);
    }
    
    /**
     * Make GET request to Alpaca data API
     */
    public static String getAlpacaData(String endpoint, AlpacaCredentials credentials) {
        return makeAlpacaApiRequest(ApiType.DATA, endpoint, "GET", null, credentials);
    }
    
    /**
     * Make GET request to Alpaca options API
     */
    public static String getAlpacaOptions(String endpoint, AlpacaCredentials credentials) {
        return makeAlpacaApiRequest(ApiType.OPTIONS, endpoint, "GET", null, credentials);
    }
    
    /**
     * Make GET request to Alpaca trading API
     */
    public static String getAlpacaTrading(String endpoint, AlpacaCredentials credentials) {
        return makeAlpacaApiRequest(ApiType.TRADING, endpoint, "GET", null, credentials);
    }
    
    /**
     * Make POST request to Alpaca trading API
     */
    public static String postAlpacaTrading(String endpoint, String body, AlpacaCredentials credentials) {
        return makeAlpacaApiRequest(ApiType.TRADING, endpoint, "POST", body, credentials);
    }
    
    /**
     * API types for Alpaca endpoints
     */
    public enum ApiType {
        DATA, OPTIONS, TRADING
    }
    
    /**
     * Parse JSON response
     */
    public static JsonNode parseJson(String json) {
        try {
            return CommonConstants.OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("JSON parsing failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert object to JSON
     */
    public static String toJson(Object object) {
        try {
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if market is open using Alpaca API
     */
    public static boolean isMarketOpen(AlpacaCredentials credentials) {
        try {
            String responseBody = getAlpacaTrading("/clock", credentials);
            JsonNode clockNode = parseJson(responseBody);
            return clockNode.get("is_open").asBoolean();
        } catch (Exception e) {
            return false; // Conservative approach
        }
    }
    
    // ===== HELPER METHODS FOR CODE REUSE =====
    
    /**
     * Process symbols in batches to avoid API limits
     */
    private static <T> List<T> processSymbolsInBatches(List<String> symbols, 
                                                      BatchProcessor<T> processor, 
                                                      AlpacaCredentials credentials) throws IOException {
        if (symbols.isEmpty()) return new ArrayList<>();
        
        List<T> results = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            results.addAll(processor.processBatch(batch, credentials));
        }
        return results;
    }
    
    /**
     * Process symbols in batches and return as Map
     */
    private static <T> Map<String, T> processSymbolsInBatchesAsMap(List<String> symbols, 
                                                                  BatchProcessorMap<T> processor, 
                                                                  AlpacaCredentials credentials) throws IOException {
        if (symbols.isEmpty()) return new HashMap<>();
        
        Map<String, T> results = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, symbols.size());
            List<String> batch = symbols.subList(i, endIndex);
            results.putAll(processor.processBatch(batch, credentials));
        }
        return results;
    }
    
    /**
     * Parse JSON response and extract field data using a processor
     */
    private static <T> Map<String, T> parseJsonFieldData(JsonNode jsonNode, String fieldName, 
                                                        FieldProcessor<T> processor) {
        Map<String, T> results = new HashMap<>();
        if (jsonNode.has(fieldName)) {
            JsonNode fieldNode = jsonNode.get(fieldName);
            fieldNode.fieldNames().forEachRemaining(symbol -> {
                JsonNode itemNode = fieldNode.get(symbol);
                T item = processor.process(symbol, itemNode);
                if (item != null) {
                    results.put(symbol, item);
                }
            });
        }
        return results;
    }
    
    /**
     * Parse JSON response and extract array data using a processor
     */
    private static <T> List<T> parseJsonArrayData(JsonNode jsonNode, String fieldName, 
                                                 ArrayProcessor<T> processor) {
        List<T> results = new ArrayList<>();
        if (jsonNode.has(fieldName)) {
            JsonNode fieldNode = jsonNode.get(fieldName);
            fieldNode.fieldNames().forEachRemaining(symbol -> {
                JsonNode symbolData = fieldNode.get(symbol);
                if (symbolData.isArray()) {
                    for (JsonNode itemNode : symbolData) {
                        T item = processor.process(symbol, itemNode);
                        if (item != null) {
                            results.add(item);
                        }
                    }
                }
            });
        }
        return results;
    }
    
    /**
     * Format date for API calls
     */
    private static String formatDate(LocalDate date) {
        return date.format(DATE_FORMATTER);
    }
    
    /**
     * Create symbols parameter for API calls
     */
    private static String createSymbolsParam(List<String> symbols) {
        return String.join(",", symbols);
    }
    
    // ===== FUNCTIONAL INTERFACES FOR PROCESSORS =====
    
    @FunctionalInterface
    private interface BatchProcessor<T> {
        List<T> processBatch(List<String> symbols, AlpacaCredentials credentials) throws IOException;
    }
    
    @FunctionalInterface
    private interface BatchProcessorMap<T> {
        Map<String, T> processBatch(List<String> symbols, AlpacaCredentials credentials) throws IOException;
    }
    
    @FunctionalInterface
    private interface FieldProcessor<T> {
        T process(String symbol, JsonNode node);
    }
    
    @FunctionalInterface
    private interface ArrayProcessor<T> {
        T process(String symbol, JsonNode node);
    }
    
    // ===== HIGH-LEVEL API METHODS (from AlpacaApiService) =====
    
    /**
     * Get current stock quote
     */
    public static StockQuote getLatestQuote(String symbol, AlpacaCredentials credentials) throws IOException {
        try {
            String responseBody = getAlpacaData("/stocks/" + symbol + "/quotes/latest", credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            if (jsonNode.has("quote")) {
                JsonNode quoteNode = jsonNode.get("quote");
                return JsonParsingUtils.parseStockQuote(quoteNode, symbol);
            }
            
            return null;
        } catch (Exception e) {
            throw new IOException("Failed to get latest quote for " + symbol, e);
        }
    }
    
    /**
     * Get historical bars (OHLCV data) for a symbol
     */
    public static List<HistoricalBar> getHistoricalBars(String symbol, int days, AlpacaCredentials credentials) throws IOException {
        try {
            LocalDate endDate = LocalDate.now(ZoneId.of("America/New_York"));
            LocalDate startDate = endDate.minusDays(days);
            
            String endpoint = "/stocks/bars?" +
                "symbols=" + symbol +
                "&start=" + formatDate(startDate) +
                "&end=" + formatDate(endDate) +
                "&timeframe=1Day&limit=1000&feed=opra&adjustment=all";
            
            String responseBody = getAlpacaData(endpoint, credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            List<HistoricalBar> bars = new ArrayList<>();
            if (jsonNode.has("bars") && jsonNode.get("bars").has(symbol)) {
                JsonNode barsArray = jsonNode.get("bars").get(symbol);
                
                for (JsonNode barNode : barsArray) {
                    bars.add(JsonParsingUtils.parseHistoricalBar(barNode));
                }
            }
            
            return bars;
        } catch (Exception e) {
            throw new IOException("Failed to get historical bars for " + symbol, e);
        }
    }
    
    /**
     * Get latest trade for a symbol
     */
    public static LatestTrade getLatestTrade(String symbol, AlpacaCredentials credentials) throws IOException {
        try {
            String responseBody = getAlpacaData("/stocks/" + symbol + "/trades/latest", credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            if (jsonNode.has("trade")) {
                JsonNode tradeNode = jsonNode.get("trade");
                return JsonParsingUtils.parseLatestTrade(tradeNode, symbol);
            }
            
            return null;
        } catch (Exception e) {
            throw new IOException("Failed to get latest trade for " + symbol, e);
        }
    }
    
    /**
     * Get option chain for a stock with filtering by expiration and type
     */
    public static Map<String, OptionSnapshot> getOptionChain(String underlying, LocalDate expGte, LocalDate expLte, String type, AlpacaCredentials credentials) throws IOException {
        try {
            StringBuilder endpointBuilder = new StringBuilder("/options/snapshots/" + underlying);
            endpointBuilder.append("?expiration_date_gte=").append(formatDate(expGte));
            endpointBuilder.append("&expiration_date_lte=").append(formatDate(expLte));
            endpointBuilder.append("&type=").append(type);
            endpointBuilder.append("&feed=opra");
            endpointBuilder.append("&limit=1000");
            
            String responseBody = getAlpacaOptions(endpointBuilder.toString(), credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            return parseJsonFieldData(jsonNode, "snapshots", 
                (symbol, node) -> JsonParsingUtils.parseOptionSnapshot(node, symbol));
        } catch (Exception e) {
            throw new IOException("Failed to get option chain for " + underlying, e);
        }
    }
    
    /**
     * Get option snapshots for specific symbols
     */
    public static Map<String, OptionSnapshot> getOptionSnapshots(List<String> symbols, AlpacaCredentials credentials) throws IOException {
        return fetchOptionData(symbols, "/options/snapshots", 
            (symbol, node) -> JsonParsingUtils.parseOptionSnapshot(node, symbol), credentials);
    }
    
    /**
     * Get historical option trades for trade count analysis
     */
    public static List<OptionTrade> getOptionHistoricalTrades(List<String> symbols, LocalDate start, LocalDate end, AlpacaCredentials credentials) throws IOException {
        String endpoint = "/options/trades?start=" + formatDate(start) + "&end=" + formatDate(end);
        return fetchOptionHistoricalData(symbols, endpoint, 
            (symbol, node) -> JsonParsingUtils.parseOptionTrade(node, symbol), credentials);
    }
    
    /**
     * Get latest option trades for multiple symbols
     */
    public static Map<String, OptionTrade> getLatestOptionTrades(List<String> symbols, AlpacaCredentials credentials) throws IOException {
        return fetchOptionData(symbols, "/options/trades/latest", 
            (symbol, node) -> JsonParsingUtils.parseOptionTrade(node, symbol), credentials);
    }
    
    /**
     * Get historical option bars for volume analysis
     */
    public static List<OptionBar> getOptionHistoricalBars(List<String> symbols, int days, AlpacaCredentials credentials) throws IOException {
        LocalDate endDate = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate startDate = endDate.minusDays(days);
        String endpoint = "/options/bars?timeframe=1Day&start=" + formatDate(startDate) + "&end=" + formatDate(endDate);
        
        return fetchOptionHistoricalData(symbols, endpoint, 
            (symbol, node) -> JsonParsingUtils.parseOptionBar(node, symbol), credentials);
    }
    
    /**
     * Get latest option quotes for bid/ask analysis
     */
    public static Map<String, OptionQuote> getLatestOptionQuotes(List<String> symbols, AlpacaCredentials credentials) throws IOException {
        return fetchOptionData(symbols, "/options/quotes/latest", 
            (symbol, node) -> JsonParsingUtils.parseOptionQuote(node, symbol), credentials);
    }
    
    /**
     * Get condition codes mapping for option trades
     */
    public static Map<String, String> getConditionCodes(String tickType, AlpacaCredentials credentials) throws IOException {
        try {
            String endpoint = "/options/meta/conditions/" + tickType;
            String responseBody = getAlpacaOptions(endpoint, credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            return parseStringMapFromJson(jsonNode);
        } catch (Exception e) {
            throw new IOException("Failed to get condition codes for " + tickType, e);
        }
    }
    
    /**
     * Get exchange codes mapping for options
     */
    public static Map<String, String> getExchangeCodes(AlpacaCredentials credentials) throws IOException {
        try {
            String endpoint = "/options/meta/exchanges";
            String responseBody = getAlpacaOptions(endpoint, credentials);
            JsonNode jsonNode = parseJson(responseBody);
            
            return parseStringMapFromJson(jsonNode);
        } catch (Exception e) {
            throw new IOException("Failed to get exchange codes", e);
        }
    }
    
    /**
     * Parse a JSON object into a Map<String, String>
     */
    private static Map<String, String> parseStringMapFromJson(JsonNode jsonNode) {
        Map<String, String> result = new HashMap<>();
        jsonNode.fieldNames().forEachRemaining(code -> {
            result.put(code, jsonNode.get(code).asText());
        });
        return result;
    }
    
    /**
     * Generic method to fetch option data with different endpoints
     */
    private static <T> Map<String, T> fetchOptionData(List<String> symbols, String endpoint, 
                                                     FieldProcessor<T> processor, 
                                                     AlpacaCredentials credentials) throws IOException {
        return processSymbolsInBatchesAsMap(symbols, (batch, creds) -> {
            String symbolsParam = createSymbolsParam(batch);
            String fullEndpoint = endpoint + "?symbols=" + symbolsParam + "&feed=opra&limit=1000";
            
            String responseBody = getAlpacaOptions(fullEndpoint, creds);
            JsonNode jsonNode = parseJson(responseBody);
            
            return parseJsonFieldData(jsonNode, endpoint.contains("trades") ? "trades" : 
                endpoint.contains("quotes") ? "quotes" : "snapshots", processor);
        }, credentials);
    }
    
    /**
     * Generic method to fetch option historical data
     */
    private static <T> List<T> fetchOptionHistoricalData(List<String> symbols, String endpoint, 
                                                        ArrayProcessor<T> processor,
                                                        AlpacaCredentials credentials) throws IOException {
        return processSymbolsInBatches(symbols, (batch, creds) -> {
            String symbolsParam = createSymbolsParam(batch);
            String fullEndpoint = endpoint + "?symbols=" + symbolsParam + "&feed=opra&limit=10000&sort=asc";
            
            String responseBody = getAlpacaOptions(fullEndpoint, creds);
            JsonNode jsonNode = parseJson(responseBody);
            
            return parseJsonArrayData(jsonNode, endpoint.contains("trades") ? "trades" : "bars", processor);
        }, credentials);
    }
}
