package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.OptionSnapshot;
import com.trading.common.OptionSelectionUtils;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Filter for checking IV ratio (short-term vs long-term implied volatility)
 * Compares short leg (earnings +1 day) vs long leg (~30 days post-earnings)
 */
public class IVRatioFilter {
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double IV_RATIO_THRESHOLD;
    
    public IVRatioFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.IV_RATIO_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("IV_RATIO_THRESHOLD", "1.15"));
    }
    
    /**
     * Check if stock has sufficient IV ratio for debit calendar spread around earnings
     * Compares short-term IV (earliest expiration after scan date) vs long-term IV (closest to 30 days from scan date)
     * Higher short-term IV indicates potential volatility crush opportunity
     * Uses scan date as base date - all filters find the same options
     */
    public boolean hasIVRatio(String ticker, LocalDate earningsDate, Context context) {
        try {
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return false;
            
            LocalDate scanDate = LocalDate.now(ZoneId.of("America/New_York")); // Scan date = day before earnings
            
            // Get wide range of options (1-60 days) to find proper legs
            Map<String, OptionSnapshot> allOptions = getOptionChainForLeg(ticker, scanDate, 1, 60, context);
            
            if (allOptions.isEmpty()) {
                context.getLogger().log("No options data available for " + ticker + ", skipping IV ratio check");
                return false;
            }
            
            // Find short leg using reusable logic - earliest expiration after scan date
            LocalDate shortExpiration = com.trading.common.OptionSelectionUtils.findShortLegExpirationFromOptionChain(allOptions, scanDate);
            if (shortExpiration == null) {
                context.getLogger().log("No suitable short leg expiration found for " + ticker);
                return false;
            }
            
            // Filter to short leg options only - filter by expiration date from option symbols
            Map<String, OptionSnapshot> shortChain = allOptions.entrySet().stream()
                .filter(entry -> {
                    try {
                        Map<String, Object> parsed = com.trading.common.OptionSymbolUtils.parseOptionSymbol(entry.getKey());
                        String expiration = (String) parsed.get("expiration");
                        return shortExpiration.toString().equals(expiration);
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            // Find long leg using reusable logic - closest to 30 days from scan date
            LocalDate longExpiration = com.trading.common.OptionSelectionUtils.findFarLegExpirationFromOptionChain(allOptions, scanDate, shortExpiration, 30);
            if (longExpiration == null) {
                context.getLogger().log("No suitable long leg expiration found for " + ticker);
                return false;
            }
            
            // Filter to long leg options only - filter by expiration date from option symbols
            Map<String, OptionSnapshot> longChain = allOptions.entrySet().stream()
                .filter(entry -> {
                    try {
                        Map<String, Object> parsed = com.trading.common.OptionSymbolUtils.parseOptionSymbol(entry.getKey());
                        String expiration = (String) parsed.get("expiration");
                        return longExpiration.toString().equals(expiration);
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            if (shortChain.isEmpty() || longChain.isEmpty()) {
                context.getLogger().log("No options data available for " + ticker + ", skipping IV ratio check");
                return false;
            }
            
            // Find the best common strike for calendar spread
            double bestStrike = OptionSelectionUtils.findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
                shortChain, longChain, currentPrice);
            
            if (bestStrike < 0) {
                context.getLogger().log("No common strikes found for calendar spread for " + ticker);
                return false;
            }
            
            // Get IV for both legs at the common strike
            double ivShort = getIVForStrike(shortChain, bestStrike, context);
            double ivLong = getIVForStrike(longChain, bestStrike, context);
            
            if (ivShort <= 0 || ivLong <= 0) {
                context.getLogger().log("Invalid IV data for " + ticker + " at strike " + bestStrike);
                return false;
            }
            
            double ivRatio = ivShort / ivLong;
            boolean passes = ivRatio >= IV_RATIO_THRESHOLD;
            
            context.getLogger().log(ticker + " Calendar spread IV ratio: " + 
                String.format("%.3f", ivShort) + "/" + String.format("%.3f", ivLong) + " = " + 
                String.format("%.3f", ivRatio) + " (strike: " + String.format("%.2f", bestStrike) + 
                ", threshold: " + String.format("%.2f", IV_RATIO_THRESHOLD) + ", passes: " + passes + ")");
            
            return passes;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking IV ratio for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get option chain for a specific leg with flexible date range
     * @param ticker Stock ticker
     * @param earningsDate Earnings date
     * @param minDays Minimum days from earnings
     * @param maxDays Maximum days from earnings
     * @param context Lambda context
     * @return Map of option snapshots by expiration
     */
    private Map<String, OptionSnapshot> getOptionChainForLeg(String ticker, LocalDate earningsDate, 
                                                           int minDays, int maxDays, Context context) {
        return commonUtils.getOptionChainForDateRange(ticker, earningsDate, minDays, maxDays, "call", credentials, context);
    }
    
    /**
     * Get IV for a specific strike from an option chain
     * @param optionChain Map of option snapshots by expiration
     * @param strike Target strike price
     * @param context Lambda context
     * @return Implied volatility for the strike, or 0 if not found
     */
    private double getIVForStrike(Map<String, OptionSnapshot> optionChain, double strike, Context context) {
        return commonUtils.getIVForStrikeFromChain(optionChain, strike, "option", context);
    }
    
}
