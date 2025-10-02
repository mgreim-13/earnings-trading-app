package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.OptionSnapshot;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Filter for checking term structure backwardation
 * Compares IV across multiple expirations to ensure earnings week IV > longer-term IV
 */
public class TermStructureFilter {
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double SLOPE_THRESHOLD;
    
    public TermStructureFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.SLOPE_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("SLOPE_THRESHOLD", "0.01"));
    }
    
    /**
     * Check if stock has term structure backwardation using options data only
     * Uses reusable option selection logic to find closest strikes and expirations
     */
    public boolean hasTermStructureBackwardation(String ticker, LocalDate earningsDate, Context context) {
        try {
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return false;
            
            // Get options data for term structure analysis
            TermStructureData termData = getTermStructureData(ticker, earningsDate, currentPrice, context);
            if (termData == null) {
                context.getLogger().log("No options data available for term structure analysis for " + ticker);
                return false;
            }
            
            // Calculate term structure backwardation
            TermStructureResult result = calculateTermStructureBackwardation(termData);
            
            // Log the results
            logTermStructureResults(ticker, termData, result, context);
            
            return result.isPasses();
            
        } catch (Exception e) {
            context.getLogger().log("Error checking term structure for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get comprehensive term structure data using reusable option selection logic
     * Uses scan date as base date - all filters find the same options
     * - Short leg: Earliest expiration after scan date (consistent across all filters)
     * - Medium leg: Closest to 30 days from scan date (monthly options)
     * - Long leg: Closest to 60 days from scan date (quarterly options)
     * Since scan runs day before earnings, next trading day after earnings = next trading day after scan
     */
    private TermStructureData getTermStructureData(String ticker, LocalDate earningsDate, double currentPrice, Context context) {
        try {
            LocalDate scanDate = LocalDate.now(ZoneId.of("America/New_York")); // Scan date = day before earnings
            
            // Get wide range of options (1-60 days) to find proper legs
            Map<String, OptionSnapshot> allOptions = getOptionChainForLeg(ticker, scanDate, 1, 60, context);
            
            if (allOptions.isEmpty()) {
                context.getLogger().log("No options data available for " + ticker);
                return null;
            }
            
            // Find short leg using reusable logic - earliest expiration after scan date
            LocalDate shortExpiration = com.trading.common.OptionSelectionUtils.findShortLegExpirationFromOptionChain(allOptions, scanDate);
            if (shortExpiration == null) {
                context.getLogger().log("No suitable short leg expiration found for " + ticker);
                return null;
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
            
            // Find medium leg using reusable logic - closest to 30 days from scan date
            LocalDate mediumExpiration = com.trading.common.OptionSelectionUtils.findFarLegExpirationFromOptionChain(allOptions, scanDate, shortExpiration, 30);
            if (mediumExpiration == null) {
                context.getLogger().log("No suitable medium leg expiration found for " + ticker);
                return null;
            }
            
            // Filter to medium leg options only - filter by expiration date from option symbols
            Map<String, OptionSnapshot> mediumChain = allOptions.entrySet().stream()
                .filter(entry -> {
                    try {
                        Map<String, Object> parsed = com.trading.common.OptionSymbolUtils.parseOptionSymbol(entry.getKey());
                        String expiration = (String) parsed.get("expiration");
                        return mediumExpiration.toString().equals(expiration);
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            // Find long leg using reusable logic - closest to 60 days from scan date
            LocalDate longExpiration = com.trading.common.OptionSelectionUtils.findFarLegExpirationFromOptionChain(allOptions, scanDate, shortExpiration, 60);
            if (longExpiration == null) {
                context.getLogger().log("No suitable long leg expiration found for " + ticker);
                return null;
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
            
            // Check if we have at least one leg
            if (shortChain.isEmpty() && mediumChain.isEmpty() && longChain.isEmpty()) {
                context.getLogger().log("No options data available for any leg for " + ticker);
                return null;
            }
            
            // Find the best common strike across all available legs
            double bestCommonStrike = findBestCommonStrike(shortChain, mediumChain, longChain, currentPrice);
            if (bestCommonStrike < 0) {
                context.getLogger().log("No common strikes found across option chains for " + ticker);
                return null;
            }
            
            // Extract IV for each leg using the common strike
            double ivEarningsWeek = getIVForStrike(shortChain, bestCommonStrike, "earnings week", context);
            double iv30 = getIVForStrike(mediumChain, bestCommonStrike, "30-day", context);
            double iv60 = getIVForStrike(longChain, bestCommonStrike, "60-day", context);
            
            // Check if we have at least the earnings week and one long-term leg
            if (ivEarningsWeek <= 0 || (iv30 <= 0 && iv60 <= 0)) {
                context.getLogger().log("Insufficient IV data for term structure analysis for " + ticker);
                return null;
            }
            
            return new TermStructureData(ivEarningsWeek, iv30, iv60);
            
        } catch (Exception e) {
            context.getLogger().log("Error getting term structure data for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get option chain for a specific leg using reusable logic
     * Uses realistic windows based on standard options expiration cycles:
     * - Short leg: 1-7 days (weekly options)
     * - Medium leg: 30-37 days (monthly options) 
     * - Long leg: 60-75 days (quarterly options)
     */
    private Map<String, OptionSnapshot> getOptionChainForLeg(String ticker, LocalDate baseDate, int minDays, int maxDays, Context context) {
        return commonUtils.getOptionChainForDateRange(ticker, baseDate, minDays, maxDays, "call", credentials, context);
    }
    
    /**
     * Find the best common strike across multiple option chains
     * Uses hybrid approach: try to find common strikes across all legs, fall back to best available
     */
    private double findBestCommonStrike(Map<String, OptionSnapshot> shortChain, 
                                      Map<String, OptionSnapshot> mediumChain, 
                                      Map<String, OptionSnapshot> longChain, 
                                      double currentPrice) {
        try {
            // First try to find common strikes across all 3 legs
            Set<Double> commonStrikes = findCommonStrikesAcrossAllChains(shortChain, mediumChain, longChain);
            
            if (!commonStrikes.isEmpty()) {
                // Use common strikes approach (most reliable)
                return commonUtils.findClosestStrikeToPrice(commonStrikes, currentPrice);
            }
            
            // Fallback: try common strikes between short and each long leg
            Set<Double> shortMediumCommon = findCommonStrikesBetweenChains(shortChain, mediumChain);
            Set<Double> shortLongCommon = findCommonStrikesBetweenChains(shortChain, longChain);
            
            if (!shortMediumCommon.isEmpty() || !shortLongCommon.isEmpty()) {
                // Use the best available common strikes
                Set<Double> bestCommon = new HashSet<>();
                if (!shortMediumCommon.isEmpty()) bestCommon.addAll(shortMediumCommon);
                if (!shortLongCommon.isEmpty()) bestCommon.addAll(shortLongCommon);
                return commonUtils.findClosestStrikeToPrice(bestCommon, currentPrice);
            }
            
            // Last resort: use all strikes (original approach)
            Set<Double> allStrikes = commonUtils.collectAllStrikesFromChains(shortChain, mediumChain, longChain);
            return commonUtils.findClosestStrikeToPrice(allStrikes, currentPrice);
                
        } catch (Exception e) {
            return -1.0;
        }
    }
    
    /**
     * Find common strikes across all 3 chains
     */
    private Set<Double> findCommonStrikesAcrossAllChains(Map<String, OptionSnapshot> shortChain, 
                                                        Map<String, OptionSnapshot> mediumChain, 
                                                        Map<String, OptionSnapshot> longChain) {
        Set<Double> shortStrikes = extractStrikesFromChain(shortChain);
        Set<Double> mediumStrikes = extractStrikesFromChain(mediumChain);
        Set<Double> longStrikes = extractStrikesFromChain(longChain);
        
        Set<Double> common = new HashSet<>(shortStrikes);
        common.retainAll(mediumStrikes);
        common.retainAll(longStrikes);
        
        return common;
    }
    
    /**
     * Find common strikes between two chains
     */
    private Set<Double> findCommonStrikesBetweenChains(Map<String, OptionSnapshot> chain1, 
                                                      Map<String, OptionSnapshot> chain2) {
        Set<Double> strikes1 = extractStrikesFromChain(chain1);
        Set<Double> strikes2 = extractStrikesFromChain(chain2);
        
        Set<Double> common = new HashSet<>(strikes1);
        common.retainAll(strikes2);
        
        return common;
    }
    
    /**
     * Extract strikes from a single chain
     */
    private Set<Double> extractStrikesFromChain(Map<String, OptionSnapshot> chain) {
        Set<Double> strikes = new HashSet<>();
        if (chain != null && !chain.isEmpty()) {
            chain.values().forEach(option -> strikes.add(option.getStrike()));
        }
        return strikes;
    }
    
    /**
     * Get IV for a specific strike in an option chain
     */
    private double getIVForStrike(Map<String, OptionSnapshot> optionChain, double targetStrike, String legName, Context context) {
        return commonUtils.getIVForStrikeFromChain(optionChain, targetStrike, legName, context);
    }
    
    // ===== HELPER METHODS FOR CODE REUSE =====
    
    /**
     * Calculate term structure backwardation from IV data
     */
    private TermStructureResult calculateTermStructureBackwardation(TermStructureData termData) {
        double maxLongTermIV = Math.max(termData.getIv30(), termData.getIv60());
        double difference = termData.getIvEarningsWeek() - maxLongTermIV;
        boolean passes = difference >= SLOPE_THRESHOLD;
        
        return new TermStructureResult(maxLongTermIV, difference, passes);
    }
    
    /**
     * Log term structure analysis results
     */
    private void logTermStructureResults(String ticker, TermStructureData termData, TermStructureResult result, Context context) {
        String logMessage = String.format("%s term structure: IV_earnings=%.3f, IV30=%.3f, IV60=%.3f, max_long=%.3f, diff=%.3f (%s)",
            ticker, termData.getIvEarningsWeek(), termData.getIv30(), termData.getIv60(), 
            result.getMaxLongTermIV(), result.getDifference(), result.isPasses());
        
        context.getLogger().log(logMessage);
    }
    
    
    /**
     * Data class for term structure information
     */
    private static class TermStructureData {
        private final double ivEarningsWeek;
        private final double iv30;
        private final double iv60;
        
        public TermStructureData(double ivEarningsWeek, double iv30, double iv60) {
            this.ivEarningsWeek = ivEarningsWeek;
            this.iv30 = iv30;
            this.iv60 = iv60;
        }
        
        public double getIvEarningsWeek() { return ivEarningsWeek; }
        public double getIv30() { return iv30; }
        public double getIv60() { return iv60; }
    }
    
    /**
     * Data class for term structure calculation results
     */
    private static class TermStructureResult {
        private final double maxLongTermIV;
        private final double difference;
        private final boolean passes;
        
        public TermStructureResult(double maxLongTermIV, double difference, boolean passes) {
            this.maxLongTermIV = maxLongTermIV;
            this.difference = difference;
            this.passes = passes;
        }
        
        public double getMaxLongTermIV() { return maxLongTermIV; }
        public double getDifference() { return difference; }
        public boolean isPasses() { return passes; }
    }
}
