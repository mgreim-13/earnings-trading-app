package com.trading.common;

/**
 * Utility class for price calculations and validations
 * Centralizes common price-related logic used across data models
 */
public class PriceUtils {
    
    /**
     * Calculate mid price from bid and ask prices
     * Handles infinite values by returning NaN
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @return Mid price or NaN if inputs are infinite
     */
    public static double calculateMidPrice(double bid, double ask) {
        if (Double.isInfinite(bid) || Double.isInfinite(ask) || 
            Double.isInfinite(bid + ask)) {
            return Double.NaN; // Return NaN for infinite values
        }
        return (bid + ask) / 2.0;
    }
    
    /**
     * Calculate bid-ask spread
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @return Spread (ask - bid)
     */
    public static double calculateSpread(double bid, double ask) {
        return ask - bid;
    }
    
    /**
     * Calculate spread ratio as percentage of mid price
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @return Spread ratio (0.0 to 1.0+)
     */
    public static double calculateSpreadRatio(double bid, double ask) {
        double mid = calculateMidPrice(bid, ask);
        if (Double.isNaN(mid) || mid <= 0) {
            return Double.POSITIVE_INFINITY; // Invalid spread
        }
        return calculateSpread(bid, ask) / mid;
    }
    
    /**
     * Check if bid/ask prices are valid for trading
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @return true if valid, false otherwise
     */
    public static boolean isValidBidAsk(double bid, double ask) {
        return bid > 0 && ask > 0 && ask >= bid && !Double.isInfinite(bid) && !Double.isInfinite(ask);
    }
    
    /**
     * Check if spread ratio is within acceptable limits
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @param maxSpreadRatio Maximum acceptable spread ratio (e.g., 0.10 for 10%)
     * @return true if spread is acceptable, false otherwise
     */
    public static boolean isSpreadAcceptable(double bid, double ask, double maxSpreadRatio) {
        if (!isValidBidAsk(bid, ask)) {
            return false;
        }
        double spreadRatio = calculateSpreadRatio(bid, ask);
        return !Double.isInfinite(spreadRatio) && spreadRatio <= maxSpreadRatio;
    }
    
    // Private constructor to prevent instantiation
    private PriceUtils() {}
}
