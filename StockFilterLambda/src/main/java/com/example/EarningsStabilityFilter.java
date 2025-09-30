package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.OptionSnapshot;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filter for checking earnings stability
 * Compares current straddle-implied move vs average historical move
 * Falls back to simple threshold check when options data unavailable
 */
public class EarningsStabilityFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double STABILITY_THRESHOLD;
    private final double EARNINGS_STABILITY_THRESHOLD;
    private final double STRADDLE_HISTORICAL_MULTIPLIER;
    
    public EarningsStabilityFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.STABILITY_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("STABILITY_THRESHOLD", "0.60"));
        this.EARNINGS_STABILITY_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("EARNINGS_STABILITY_THRESHOLD", "0.05"));
        this.STRADDLE_HISTORICAL_MULTIPLIER = Double.parseDouble(System.getenv().getOrDefault("STRADDLE_HISTORICAL_MULTIPLIER", "1.5"));
    }
    
    /**
     * Check if stock has stable earnings moves with straddle-based overpricing check
     * Returns FilterResult with scoring: +2 for full pass, +1 for partial pass, +0 for fail
     */
    public FilterResult hasHistoricalEarningsStability(String ticker, LocalDate earningsDate, Context context) {
        try {
            context.getLogger().log("Checking earnings stability for " + ticker);
            
            // Get historical earnings data
            List<StockFilterCommonUtils.EarningsData> earningsData = commonUtils.getHistoricalEarningsData(ticker, context);
            if (earningsData.isEmpty()) {
                context.getLogger().log("No historical earnings data for " + ticker);
                return new FilterResult("EarningsStability", false, 0);
            }
            
            // Calculate average historical move
            double averageHistoricalMove = calculateAverageHistoricalMove(ticker, earningsData, context);
            if (averageHistoricalMove < 0) {
                context.getLogger().log("Could not calculate average historical move for " + ticker);
                return new FilterResult("EarningsStability", false, 0);
            }
            
            // Check historical stability (original logic)
            boolean historicalStable = checkHistoricalStability(ticker, earningsData, context);
            
            // Try to get current straddle-implied move
            double currentStraddleMove = getCurrentStraddleImpliedMove(ticker, earningsDate, context);
            
            boolean straddleOverpriced = false;
            boolean usedStraddleData = false;
            
            if (currentStraddleMove > 0) {
                // Check if current straddle is overpriced compared to historical average
                straddleOverpriced = currentStraddleMove >= (averageHistoricalMove * STRADDLE_HISTORICAL_MULTIPLIER);
                usedStraddleData = true;
                
                context.getLogger().log(ticker + " straddle analysis: current=" + 
                    String.format("%.2f", currentStraddleMove * 100) + "%, historical_avg=" + 
                    String.format("%.2f", averageHistoricalMove * 100) + "%, multiplier=" + STRADDLE_HISTORICAL_MULTIPLIER + 
                    ", overpriced=" + straddleOverpriced);
            } else {
                context.getLogger().log("Straddle data unavailable for " + ticker + ", using fallback to historical stability only");
            }
            
            // Determine result based on available data
            boolean passed;
            int score;
            String reason;
            
            if (usedStraddleData) {
                // Both conditions must be met for full pass
                passed = historicalStable && straddleOverpriced;
                score = passed ? 2 : 0;
                reason = passed ? "Both historical stability and straddle overpricing" : 
                    (!historicalStable ? "Historical stability failed" : "Straddle not overpriced");
            } else {
                // Fallback to historical stability only
                passed = historicalStable;
                score = passed ? 1 : 0; // Partial score for fallback
                reason = passed ? "Historical stability (straddle data unavailable)" : "Historical stability failed";
            }
            
            context.getLogger().log(ticker + " earnings stability result: " + reason + 
                " (score=" + score + ", historical_stable=" + historicalStable + 
                ", straddle_overpriced=" + straddleOverpriced + ", used_straddle=" + usedStraddleData + ")");
            
            return new FilterResult("EarningsStability", passed, score);
            
        } catch (Exception e) {
            context.getLogger().log("Error checking earnings stability for " + ticker + ": " + e.getMessage());
            return new FilterResult("EarningsStability", false, 0);
        }
    }
    
    /**
     * Calculate weighted average historical earnings move with recency bias
     * Recent earnings (last 2 years) get higher weight
     */
    private double calculateAverageHistoricalMove(String ticker, List<StockFilterCommonUtils.EarningsData> earningsData, Context context) {
        double totalWeightedMove = 0.0;
        double totalWeight = 0.0;
        int validMoves = 0;
        
        LocalDate cutoffDate = LocalDate.now().minusYears(2);
        
        for (StockFilterCommonUtils.EarningsData earning : earningsData) {
            try {
                // Use enhanced method that includes overnight gaps
                double actualMove = commonUtils.calculateEarningsDayMoveWithGaps(ticker, earning.getEarningsDate(), context);
                if (actualMove >= 0) {
                    validMoves++;
                    
                    // Calculate weight based on recency
                    double weight = calculateRecencyWeight(earning.getEarningsDate(), cutoffDate);
                    totalWeightedMove += actualMove * weight;
                    totalWeight += weight;
                    
                    context.getLogger().log(ticker + " earnings on " + earning.getEarningsDate() + 
                        ": move=" + String.format("%.2f", actualMove * 100) + "%, weight=" + String.format("%.2f", weight));
                }
            } catch (Exception e) {
                context.getLogger().log("Error calculating move for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
            }
        }
        
        if (validMoves == 0 || totalWeight == 0) {
            return -1.0; // No valid moves
        }
        
        double weightedAverageMove = totalWeightedMove / totalWeight;
        context.getLogger().log(ticker + " weighted average historical move: " + 
            String.format("%.2f", weightedAverageMove * 100) + "% (" + validMoves + " earnings, total_weight=" + 
            String.format("%.2f", totalWeight) + ")");
        
        return weightedAverageMove;
    }
    
    /**
     * Calculate recency weight for earnings data
     * Recent earnings (last 2 years) get weight 2.0, older get weight 1.0
     */
    private double calculateRecencyWeight(LocalDate earningsDate, LocalDate cutoffDate) {
        if (earningsDate.isAfter(cutoffDate)) {
            return 2.0; // Recent earnings get double weight
        } else {
            return 1.0; // Older earnings get normal weight
        }
    }
    
    /**
     * Check historical stability using original threshold logic
     */
    private boolean checkHistoricalStability(String ticker, List<StockFilterCommonUtils.EarningsData> earningsData, Context context) {
        int stableCount = 0;
        int totalEarnings = 0;
        
        for (StockFilterCommonUtils.EarningsData earning : earningsData) {
            try {
                // Use enhanced method that includes overnight gaps
                double actualMove = commonUtils.calculateEarningsDayMoveWithGaps(ticker, earning.getEarningsDate(), context);
                if (actualMove < 0) continue; // Skip if no data available
                
                totalEarnings++;
                
                // Check if move was within threshold (stable)
                if (actualMove <= EARNINGS_STABILITY_THRESHOLD) {
                    stableCount++;
                }
                
            } catch (Exception e) {
                context.getLogger().log("Error calculating earnings stability for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
            }
        }
        
        if (totalEarnings == 0) {
            return false;
        }
        
        double stabilityRatio = (double) stableCount / totalEarnings;
        boolean isStable = stabilityRatio >= STABILITY_THRESHOLD;
        
        context.getLogger().log(ticker + " historical stability: " + stableCount + "/" + totalEarnings + 
            " stable = " + String.format("%.1f%%", stabilityRatio * 100) + " (" + isStable + ")");
        
        return isStable;
    }
    
    /**
     * Get current straddle-implied move for the short leg (earnings +1 day)
     */
    private double getCurrentStraddleImpliedMove(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get current stock price
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) {
                context.getLogger().log("No current stock price available for " + ticker);
                return 0.0;
            }
            
            // Look for options expiring ±1 day around earnings +1 day, but cap at +7 days max
            LocalDate targetExpiration = earningsDate.plusDays(1);
            LocalDate expirationStart = targetExpiration.minusDays(1);
            LocalDate expirationEnd = targetExpiration.plusDays(7); // Cap at +7 days maximum
            
            // Get option chains for both calls and puts
            Map<String, OptionSnapshot> callChain = alpacaApiService.getOptionChain(
                ticker, expirationStart, expirationEnd, "call");
            Map<String, OptionSnapshot> putChain = alpacaApiService.getOptionChain(
                ticker, expirationStart, expirationEnd, "put");
            
            if (callChain.isEmpty() && putChain.isEmpty()) {
                context.getLogger().log("No options available for straddle calculation for " + ticker + 
                    " expiring between " + expirationStart + " and " + expirationEnd);
                return 0.0;
            }
            
            // Find the best ATM strike using both calls and puts
            double bestStrike = findBestATMStrike(callChain, putChain, currentPrice, targetExpiration);
            if (bestStrike < 0) {
                context.getLogger().log("No suitable ATM strike found for straddle calculation for " + ticker);
                return 0.0;
            }
            
            // Find call and put options for the best strike
            OptionSnapshot atmCall = findOptionForStrike(callChain, bestStrike, targetExpiration);
            OptionSnapshot atmPut = findOptionForStrike(putChain, bestStrike, targetExpiration);
            
            // Log expiration details for transparency
            if (atmCall != null) {
                LocalDate callExp = LocalDate.parse(atmCall.getExpiration());
                long daysFromTarget = java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, callExp);
                context.getLogger().log(ticker + " selected call expiration: " + callExp + 
                    " (target: " + targetExpiration + ", days_diff: " + daysFromTarget + ")");
            }
            if (atmPut != null) {
                LocalDate putExp = LocalDate.parse(atmPut.getExpiration());
                long daysFromTarget = java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, putExp);
                context.getLogger().log(ticker + " selected put expiration: " + putExp + 
                    " (target: " + targetExpiration + ", days_diff: " + daysFromTarget + ")");
            }
            
            // Calculate straddle price with flexible handling
            double straddlePrice;
            
            if (atmCall != null && atmPut != null) {
                // Full straddle: call + put with bid/ask sanity check
                double callMid = validateBidAsk(atmCall.getBid(), atmCall.getAsk(), ticker + " call", context);
                double putMid = validateBidAsk(atmPut.getBid(), atmPut.getAsk(), ticker + " put", context);
                
                if (callMid < 0 || putMid < 0) {
                    context.getLogger().log("Invalid bid/ask spreads for straddle calculation for " + ticker);
                    return 0.0;
                }
                
                straddlePrice = callMid + putMid;
                context.getLogger().log(ticker + " using full straddle: call=" + String.format("%.2f", callMid) + 
                    ", put=" + String.format("%.2f", putMid) + ", strike=" + String.format("%.2f", bestStrike));
            } else if (atmCall != null) {
                // Call-only straddle: double the call price as proxy with bid/ask sanity check
                double callMid = validateBidAsk(atmCall.getBid(), atmCall.getAsk(), ticker + " call", context);
                
                if (callMid < 0) {
                    context.getLogger().log("Invalid bid/ask spread for call-only straddle calculation for " + ticker);
                    return 0.0;
                }
                
                straddlePrice = callMid * 2.0;
                context.getLogger().log(ticker + " using call-only straddle: call=" + String.format("%.2f", callMid) + 
                    ", estimated_straddle=" + String.format("%.2f", straddlePrice) + ", strike=" + String.format("%.2f", bestStrike));
            } else if (atmPut != null) {
                // Put-only straddle: double the put price as proxy with bid/ask sanity check
                double putMid = validateBidAsk(atmPut.getBid(), atmPut.getAsk(), ticker + " put", context);
                
                if (putMid < 0) {
                    context.getLogger().log("Invalid bid/ask spread for put-only straddle calculation for " + ticker);
                    return 0.0;
                }
                
                straddlePrice = putMid * 2.0;
                context.getLogger().log(ticker + " using put-only straddle: put=" + String.format("%.2f", putMid) + 
                    ", estimated_straddle=" + String.format("%.2f", straddlePrice) + ", strike=" + String.format("%.2f", bestStrike));
            } else {
                context.getLogger().log("No options found for strike " + String.format("%.2f", bestStrike) + " for " + ticker);
                return 0.0;
            }
            
            // Calculate implied move as percentage of stock price
            double impliedMove = straddlePrice / currentPrice;
            
            context.getLogger().log(ticker + " current straddle: straddle_price=" + String.format("%.2f", straddlePrice) + 
                ", implied_move=" + String.format("%.2f", impliedMove * 100) + "%");
            
            return impliedMove;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting current straddle implied move for " + ticker + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Find the best ATM strike using both calls and puts
     * Returns the strike closest to current price that has options available
     */
    private double findBestATMStrike(Map<String, OptionSnapshot> callChain, Map<String, OptionSnapshot> putChain, 
                                   double currentPrice, LocalDate targetExpiration) {
        // Collect all unique strikes from both chains
        Set<Double> allStrikes = new HashSet<>();
        callChain.values().forEach(option -> allStrikes.add(option.getStrike()));
        putChain.values().forEach(option -> allStrikes.add(option.getStrike()));
        
        if (allStrikes.isEmpty()) {
            return -1.0;
        }
        
        // Find strike closest to current price
        return allStrikes.stream()
            .min((a, b) -> Double.compare(Math.abs(a - currentPrice), Math.abs(b - currentPrice)))
            .orElse(-1.0);
    }
    
    /**
     * Find option for specific strike and expiration with tolerance limit
     */
    private OptionSnapshot findOptionForStrike(Map<String, OptionSnapshot> optionChain, double strike, LocalDate targetExpiration) {
        if (optionChain.isEmpty()) {
            return null;
        }
        
        // First try exact expiration match
        OptionSnapshot exactMatch = optionChain.values().stream()
            .filter(option -> option.getStrike() == strike && 
                             option.getExpiration().equals(targetExpiration.toString()))
            .findFirst()
            .orElse(null);
        
        if (exactMatch != null) {
            return exactMatch;
        }
        
        // If no exact match, find closest expiration for this strike within tolerance
        LocalDate maxExpiration = targetExpiration.plusDays(7); // Cap at +7 days
        
        return optionChain.values().stream()
            .filter(option -> {
                if (option.getStrike() != strike) return false;
                
                LocalDate expDate = LocalDate.parse(option.getExpiration());
                // Only consider options within 7 days of target AND after the target expiration
                return !expDate.isAfter(maxExpiration) && !expDate.isBefore(targetExpiration);
            })
            .min((a, b) -> {
                LocalDate expA = LocalDate.parse(a.getExpiration());
                LocalDate expB = LocalDate.parse(b.getExpiration());
                long daysDiffA = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, expA));
                long daysDiffB = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, expB));
                return Long.compare(daysDiffA, daysDiffB);
            })
            .orElse(null);
    }
    
    /**
     * Validate bid/ask prices and calculate mid price with sanity checks
     * Returns -1 if bid/ask is invalid (zero mid or >50% spread)
     */
    private double validateBidAsk(double bid, double ask, String optionType, Context context) {
        double mid = (bid + ask) / 2.0;
        
        // Check for invalid mid price
        if (mid <= 0) {
            context.getLogger().log("Invalid " + optionType + " mid price: bid=" + bid + ", ask=" + ask);
            return -1.0;
        }
        
        // Check for excessive spread (>50%)
        double spreadRatio = (ask - bid) / Math.max(1e-6, mid);
        if (spreadRatio > 0.5) {
            context.getLogger().log("Excessive " + optionType + " spread: " + String.format("%.1f%%", spreadRatio * 100) + 
                " (bid=" + bid + ", ask=" + ask + ", mid=" + String.format("%.2f", mid) + ")");
            return -1.0;
        }
        
        return mid;
    }
    
}
