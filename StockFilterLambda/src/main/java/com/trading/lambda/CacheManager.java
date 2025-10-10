package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.StockData;
import com.trading.common.models.HistoricalBar;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.AlpacaHttpClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized cache manager for all data types
 * Provides reusable caching functionality with TTL and size management
 */
public class CacheManager {
    
    // Cache maps
    private final Map<String, CachedStockData> stockDataCache = new ConcurrentHashMap<>();
    private final Map<String, CachedOptionData> optionDataCache = new ConcurrentHashMap<>();
    private final Map<String, CachedHistoricalData> historicalDataCache = new ConcurrentHashMap<>();
    
    // Cache configuration
    private static final int CACHE_TTL_MINUTES = 5;
    private static final int MAX_CACHE_SIZE = 200;
    
    // Cache statistics - using AtomicInteger for thread safety
    private final java.util.concurrent.atomic.AtomicInteger stockCacheHits = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger stockCacheMisses = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger optionCacheHits = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger optionCacheMisses = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger historicalCacheHits = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger historicalCacheMisses = new java.util.concurrent.atomic.AtomicInteger(0);
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    public CacheManager(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
    }
    
    /**
     * Get cached stock data or fetch if not available/expired
     */
    public StockData getCachedStockData(String ticker, Context context) {
        CachedStockData cached = stockDataCache.get(ticker);
        if (cached != null && !cached.isExpired()) {
            stockCacheHits.incrementAndGet();
            context.getLogger().log("Using cached stock data for " + ticker);
            return cached.getStockData();
        }
        
        stockCacheMisses.incrementAndGet();
        StockData stockData = fetchStockData(ticker, context);
        if (stockData != null) {
            manageCacheSize();
            stockDataCache.put(ticker, new CachedStockData(stockData));
        }
        return stockData;
    }
    
    /**
     * Get cached option data or fetch if not available/expired
     */
    public Map<String, com.trading.common.models.OptionSnapshot> getCachedOptionData(String ticker, 
            LocalDateTime baseDate, int minDays, int maxDays, String optionType, Context context) {
        String cacheKey = ticker + "_" + baseDate.toLocalDate() + "_" + minDays + "_" + maxDays + "_" + optionType;
        CachedOptionData cached = optionDataCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            optionCacheHits.incrementAndGet();
            context.getLogger().log("Using cached option data for " + ticker + " (" + optionType + ")");
            return cached.getOptionChain();
        }
        
        optionCacheMisses.incrementAndGet();
        Map<String, com.trading.common.models.OptionSnapshot> optionChain = 
            commonUtils.getOptionChainForDateRange(ticker, baseDate.toLocalDate(), minDays, maxDays, optionType, credentials, context);
        
        if (optionChain != null && !optionChain.isEmpty()) {
            manageCacheSize();
            optionDataCache.put(cacheKey, new CachedOptionData(optionChain));
        }
        return optionChain != null ? optionChain : new HashMap<>();
    }
    
    /**
     * Get cached historical data or fetch if not available/expired
     */
    public List<HistoricalBar> getCachedHistoricalData(String ticker, Context context) {
        CachedHistoricalData cached = historicalDataCache.get(ticker);
        if (cached != null && !cached.isExpired()) {
            historicalCacheHits.incrementAndGet();
            context.getLogger().log("Using cached historical data for " + ticker);
            return cached.getHistoricalBars();
        }
        
        historicalCacheMisses.incrementAndGet();
        List<HistoricalBar> historicalBars;
        try {
            historicalBars = AlpacaHttpClient.getHistoricalBars(ticker, 90, credentials);
        } catch (Exception e) {
            context.getLogger().log("Error getting historical bars for " + ticker + ": " + e.getMessage());
            return new ArrayList<>();
        }
        
        if (historicalBars != null && !historicalBars.isEmpty()) {
            manageCacheSize();
            historicalDataCache.put(ticker, new CachedHistoricalData(historicalBars));
        }
        return historicalBars != null ? historicalBars : new ArrayList<>();
    }
    
    /**
     * Manage cache size by removing expired entries
     */
    private void manageCacheSize() {
        // Clean up expired entries
        stockDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        optionDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        historicalDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // If still too large, remove oldest entries (LRU-style eviction)
        int totalSize = stockDataCache.size() + optionDataCache.size() + historicalDataCache.size();
        if (totalSize > MAX_CACHE_SIZE) {
            // Remove oldest entries from each cache proportionally
            int targetSize = MAX_CACHE_SIZE * 2 / 3; // Keep 2/3 of max size
            evictOldestEntries(stockDataCache, targetSize / 3);
            evictOldestEntries(optionDataCache, targetSize / 3);
            evictOldestEntries(historicalDataCache, targetSize / 3);
        }
    }
    
    /**
     * Evict oldest entries from cache to maintain size limit
     */
    private void evictOldestEntries(Map<String, ?> cache, int targetSize) {
        if (cache.size() <= targetSize) return;
        
        int entriesToRemove = cache.size() - targetSize;
        cache.entrySet().stream()
            .sorted((e1, e2) -> {
                // Sort by timestamp (oldest first)
                if (e1.getValue() instanceof CachedStockData) {
                    return ((CachedStockData) e1.getValue()).timestamp.compareTo(((CachedStockData) e2.getValue()).timestamp);
                } else if (e1.getValue() instanceof CachedOptionData) {
                    return ((CachedOptionData) e1.getValue()).timestamp.compareTo(((CachedOptionData) e2.getValue()).timestamp);
                } else if (e1.getValue() instanceof CachedHistoricalData) {
                    return ((CachedHistoricalData) e1.getValue()).timestamp.compareTo(((CachedHistoricalData) e2.getValue()).timestamp);
                }
                return 0;
            })
            .limit(entriesToRemove)
            .forEach(entry -> cache.remove(entry.getKey()));
    }
    
    /**
     * Log cache statistics
     */
    public void logCacheStatistics(Context context) {
        int totalHits = stockCacheHits.get() + optionCacheHits.get() + historicalCacheHits.get();
        int totalMisses = stockCacheMisses.get() + optionCacheMisses.get() + historicalCacheMisses.get();
        int totalRequests = totalHits + totalMisses;
        
        if (totalRequests > 0) {
            double hitRate = (double) totalHits / totalRequests * 100;
            context.getLogger().log(String.format("Cache Statistics: %d hits, %d misses, %.1f%% hit rate (Stock: %d/%d, Option: %d/%d, Historical: %d/%d)", 
                totalHits, totalMisses, hitRate, 
                stockCacheHits.get(), stockCacheMisses.get(), 
                optionCacheHits.get(), optionCacheMisses.get(),
                historicalCacheHits.get(), historicalCacheMisses.get()));
        }
    }
    
    /**
     * Fetch stock data from API
     */
    private StockData fetchStockData(String ticker, Context context) {
        try {
            // Get latest trade
            com.trading.common.models.LatestTrade latestTrade = AlpacaHttpClient.getLatestTrade(ticker, credentials);
            if (latestTrade == null) {
                return null;
            }
            
            // Get historical data
            List<HistoricalBar> historicalBars = AlpacaHttpClient.getHistoricalBars(ticker, 90, credentials);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return null;
            }
            
            // Calculate metrics
            double averageVolume = commonUtils.calculateAverageVolume(historicalBars);
            double rv30 = commonUtils.calculateRV30(historicalBars, context);
            double iv30 = commonUtils.calculateIV30(historicalBars, context);
            double termSlope = commonUtils.calculateTermStructureSlope(historicalBars, context);
            
            return new StockData(ticker, latestTrade.getPrice(), averageVolume, rv30, iv30, termSlope);
            
        } catch (Exception e) {
            context.getLogger().log("Error fetching stock data for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    // Cached data classes
    private static class CachedStockData {
        private final StockData stockData;
        private final LocalDateTime timestamp;
        
        public CachedStockData(StockData stockData) {
            this.stockData = stockData;
            this.timestamp = LocalDateTime.now(ZoneId.of("America/New_York"));
        }
        
        public StockData getStockData() { return stockData; }
        public boolean isExpired() { return LocalDateTime.now(ZoneId.of("America/New_York")).isAfter(timestamp.plusMinutes(CACHE_TTL_MINUTES)); }
    }
    
    private static class CachedOptionData {
        private final Map<String, com.trading.common.models.OptionSnapshot> optionChain;
        private final LocalDateTime timestamp;
        
        public CachedOptionData(Map<String, com.trading.common.models.OptionSnapshot> optionChain) {
            this.optionChain = optionChain;
            this.timestamp = LocalDateTime.now(ZoneId.of("America/New_York"));
        }
        
        public Map<String, com.trading.common.models.OptionSnapshot> getOptionChain() { return optionChain; }
        public boolean isExpired() { return LocalDateTime.now(ZoneId.of("America/New_York")).isAfter(timestamp.plusMinutes(CACHE_TTL_MINUTES)); }
    }
    
    private static class CachedHistoricalData {
        private final List<HistoricalBar> historicalBars;
        private final LocalDateTime timestamp;
        
        public CachedHistoricalData(List<HistoricalBar> historicalBars) {
            this.historicalBars = historicalBars;
            this.timestamp = LocalDateTime.now(ZoneId.of("America/New_York"));
        }
        
        public List<HistoricalBar> getHistoricalBars() { return historicalBars; }
        public boolean isExpired() { return LocalDateTime.now(ZoneId.of("America/New_York")).isAfter(timestamp.plusMinutes(CACHE_TTL_MINUTES)); }
    }
}
