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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service class for interacting with Alpaca Markets API
 * Provides real-time and historical stock data
 */
public class AlpacaApiService {
    
    private static final String ALPACA_BASE_URL = "https://data.alpaca.markets/v2";
    private static final String ALPACA_PAPER_URL = "https://paper-api.alpaca.markets/v2";
    
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
        
        String url = ALPACA_BASE_URL + "/stocks/bars?" +
            "symbols=" + symbol +
            "&start=" + startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) +
            "&end=" + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE) +
            "&timeframe=1Day&limit=1000&feed=iex";
        
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
        String url = ALPACA_BASE_URL + "/stocks/" + symbol + "/trades/latest";
        
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
}
