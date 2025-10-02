package com.example;

/**
 * Central configuration file for all filter thresholds
 * Edit this file to modify all filter criteria in one place
 */
public class FilterThresholds {
    
    // ===== CORE THRESHOLDS =====
    public static final double VOLUME_THRESHOLD = 1500000.0;                    // Minimum average daily volume
    public static final double IV_RATIO_THRESHOLD = 1.20;                       // Minimum IV ratio (short/long)
    public static final double ATM_THRESHOLD = 0.02;                            // ATM options within 2% of stock price
    
    // ===== LIQUIDITY FILTER THRESHOLDS =====
    public static final double BID_ASK_THRESHOLD = 0.05;                        // Maximum bid-ask spread (5%)
    public static final long QUOTE_DEPTH_THRESHOLD = 200L;                      // Minimum quote depth (bid+ask size)
    public static final long MIN_DAILY_OPTION_TRADES = 300L;                    // Minimum daily option trades
    public static final double MIN_STOCK_PRICE = 30.0;                          // Minimum stock price
    public static final double MAX_STOCK_PRICE = 400.0;                         // Maximum stock price
    
    // ===== IV RATIO FILTER THRESHOLDS =====
    // Uses IV_RATIO_THRESHOLD from core thresholds
    
    // ===== TERM STRUCTURE FILTER THRESHOLDS =====
    public static final double SLOPE_THRESHOLD = 0.05;                          // Minimum IV difference (earnings week - long term)
    
    // ===== EXECUTION SPREAD FILTER THRESHOLDS =====
    public static final double MAX_DEBIT_TO_PRICE_RATIO = 0.04;                 // Maximum debit/price ratio (4%)
    
    // ===== EARNINGS STABILITY FILTER THRESHOLDS =====
    public static final double STABILITY_THRESHOLD = 0.70;                      // Minimum stability ratio (70%)
    public static final double EARNINGS_STABILITY_THRESHOLD = 0.05;             // Maximum earnings move (5%)
    public static final double STRADDLE_HISTORICAL_MULTIPLIER = 1.5;            // Straddle overpricing multiplier
    
    // ===== VOLATILITY CRUSH FILTER THRESHOLDS =====
    public static final double VOLATILITY_CRUSH_THRESHOLD = 0.80;               // Maximum post/pre volatility ratio
    public static final double CRUSH_PERCENTAGE = 0.70;                         // Minimum crush frequency (70%)
    public static final int VOLATILITY_LOOKBACK_DAYS = 365;                     // Historical data lookback
    
    // ===== HARDCODED THRESHOLDS (Previously Not Configurable) =====
    public static final double CACHED_DATA_SPREAD_THRESHOLD = 0.05;             // 5% spread threshold for cached data path
    public static final double EXECUTION_SPREAD_CACHED_THRESHOLD = 0.10;        // 10% spread threshold for ATM options in cached path
    public static final double VALIDATION_SPREAD_THRESHOLD = 0.10;              // 20% rejection threshold for validation
    public static final double BASE_POSITION_SIZE = 0.05;                       // 5% base position size
    public static final double OPTIONAL_FILTER_BONUS = 0.01;                    // 1% bonus per optional filter
    public static final long MIN_OPTION_SIZE = 50L;                             // Minimum option size for liquidity
    public static final double EARNINGS_MOVE_THRESHOLD = 0.05;                  // 5% earnings move threshold for stability
    
    // ===== POSITION SIZING =====
    public static final double MAX_POSITION_SIZE = BASE_POSITION_SIZE + (2 * OPTIONAL_FILTER_BONUS); // 7% maximum position size
    
    // ===== PORTFOLIO CONCENTRATION LIMITS =====
    public static final double MAX_DAILY_PORTFOLIO_ALLOCATION = 0.30; // 30% max per day
    
    // ===== CONVENIENCE METHODS =====
    
    /**
     * Get all core thresholds as a formatted string for logging
     */
    public static String getCoreThresholdsSummary() {
        return String.format("Core Thresholds - Volume: %.0f, IV Ratio: %.2f, ATM: %.2f%%", 
            VOLUME_THRESHOLD, IV_RATIO_THRESHOLD, ATM_THRESHOLD * 100);
    }
    
    /**
     * Get all liquidity thresholds as a formatted string for logging
     */
    public static String getLiquidityThresholdsSummary() {
        return String.format("Liquidity Thresholds - Spread: %.1f%%, Depth: %d, Trades: %d, Price: $%.0f-$%.0f", 
            BID_ASK_THRESHOLD * 100, QUOTE_DEPTH_THRESHOLD, MIN_DAILY_OPTION_TRADES, MIN_STOCK_PRICE, MAX_STOCK_PRICE);
    }
    
    /**
     * Get all filter thresholds as a formatted string for logging
     */
    public static String getAllThresholdsSummary() {
        return String.format("All Thresholds - Volume: %.0f, IV: %.2f, Spread: %.1f%%, Debit: %.1f%%, Stability: %.1f%%, Crush: %.1f%%", 
            VOLUME_THRESHOLD, IV_RATIO_THRESHOLD, BID_ASK_THRESHOLD * 100, 
            MAX_DEBIT_TO_PRICE_RATIO * 100, STABILITY_THRESHOLD * 100, CRUSH_PERCENTAGE * 100);
    }
}
