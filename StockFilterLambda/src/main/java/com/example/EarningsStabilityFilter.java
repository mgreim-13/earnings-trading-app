package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.OptionSelectionUtils;
import com.trading.common.TradingCommonUtils;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking earnings stability
 * Compares current straddle-implied move vs average historical move
 * Falls back to simple threshold check when options data unavailable
 */
public class EarningsStabilityFilter {
    
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double STABILITY_THRESHOLD;
    private final double EARNINGS_STABILITY_THRESHOLD;
    private final double STRADDLE_HISTORICAL_MULTIPLIER;
    
    public EarningsStabilityFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.commonUtils = commonUtils;
        
        // Load thresholds from central configuration
        this.STABILITY_THRESHOLD = FilterThresholds.STABILITY_THRESHOLD;
        this.EARNINGS_STABILITY_THRESHOLD = FilterThresholds.EARNINGS_STABILITY_THRESHOLD;
        this.STRADDLE_HISTORICAL_MULTIPLIER = FilterThresholds.STRADDLE_HISTORICAL_MULTIPLIER;
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
                return new FilterResult("EarningsStability", false);
            }
            
            // Calculate average historical move
            double averageHistoricalMove = calculateAverageHistoricalMove(ticker, earningsData, context);
            if (averageHistoricalMove < 0) {
                context.getLogger().log("Could not calculate average historical move for " + ticker);
                return new FilterResult("EarningsStability", false);
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
            String reason;
            
            if (usedStraddleData) {
                // Both conditions must be met for full pass
                passed = historicalStable && straddleOverpriced;
                reason = passed ? "Both historical stability and straddle overpricing" : 
                    (!historicalStable ? "Historical stability failed" : "Straddle not overpriced");
            } else {
                // Fallback to historical stability only
                passed = historicalStable;
                reason = passed ? "Historical stability (straddle data unavailable)" : "Historical stability failed";
            }
            
            context.getLogger().log(ticker + " earnings stability result: " + reason + 
                " (historical_stable=" + historicalStable + 
                ", straddle_overpriced=" + straddleOverpriced + ", used_straddle=" + usedStraddleData + ")");
            
            return new FilterResult("EarningsStability", passed);
            
        } catch (Exception e) {
            context.getLogger().log("Error checking earnings stability for " + ticker + ": " + e.getMessage());
            return new FilterResult("EarningsStability", false);
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
        
        LocalDate cutoffDate = LocalDate.now(ZoneId.of("America/New_York")).minusYears(2);
        
        for (StockFilterCommonUtils.EarningsData earning : earningsData) {
            try {
                // Use enhanced method that includes overnight gaps
                double actualMove = commonUtils.calculateEarningsDayMoveWithGaps(ticker, earning.getEarningsDate(), context);
                if (actualMove >= 0) {
                    validMoves++;
                    
                    // Calculate weight based on recency
                    double weight = commonUtils.calculateRecencyWeight(earning.getEarningsDate(), cutoffDate);
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
     * Get current straddle-implied move for the short leg using same logic as InitiateTradesLambda
     */
    private double getCurrentStraddleImpliedMove(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get current stock price
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) {
                context.getLogger().log("No current stock price available for " + ticker);
                return 0.0;
            }
            
            // Get Alpaca credentials for API calls
            AlpacaCredentials credentials = TradingCommonUtils.getAlpacaCredentials("trading/alpaca/credentials");
            if (credentials == null) {
                context.getLogger().log("No Alpaca credentials available for " + ticker);
                return 0.0;
            }
            
            // Use same data source as InitiateTradesLambda for consistency
            LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
            
            // Fetch call and put option snapshots using shared method
            Map<String, List<Map<String, Object>>> callContracts = OptionSelectionUtils.fetchOptionSnapshots(
                ticker, credentials, "call", 60);
            Map<String, List<Map<String, Object>>> putContracts = OptionSelectionUtils.fetchOptionSnapshots(
                ticker, credentials, "put", 60);
            
            if (callContracts.isEmpty() && putContracts.isEmpty()) {
                context.getLogger().log("No options available for straddle calculation for " + ticker);
                return 0.0;
            }
            
            // Find target expiration using same logic as InitiateTradesLambda
            List<String> expirations = new java.util.ArrayList<>();
            expirations.addAll(callContracts.keySet());
            expirations.addAll(putContracts.keySet());
            expirations = expirations.stream().distinct().sorted().collect(java.util.ArrayList::new, java.util.ArrayList::add, java.util.ArrayList::addAll);
            
            LocalDate targetExpiration = OptionSelectionUtils.findShortLegExpiration(expirations, today);
            if (targetExpiration == null) {
                context.getLogger().log("No suitable short leg expiration found for " + ticker);
                return 0.0;
            }
            
            // For straddle calculation, we only need one expiration, so find best strike at target expiration
            List<Map<String, Object>> callContractsForExpiration = callContracts.get(targetExpiration.toString());
            List<Map<String, Object>> putContractsForExpiration = putContracts.get(targetExpiration.toString());
            
            if (callContractsForExpiration == null || callContractsForExpiration.isEmpty() ||
                putContractsForExpiration == null || putContractsForExpiration.isEmpty()) {
                context.getLogger().log("No options available for straddle calculation at " + targetExpiration + " for " + ticker);
                return 0.0;
            }
            
            // Find the best ATM strike ensuring it exists in both call and put contracts
            double bestStrike = OptionSelectionUtils.findATMStrikeForStraddle(
                callContractsForExpiration, putContractsForExpiration, currentPrice);
            if (bestStrike < 0) {
                context.getLogger().log("No suitable common ATM strike found for straddle calculation for " + ticker);
                return 0.0;
            }
            
            // Find call and put options for the common strike using shared method
            Map<String, Object>[] straddleOptions = OptionSelectionUtils.findStraddleOptionsForStrikeAndExpiration(
                callContracts, putContracts, bestStrike, targetExpiration);
            if (straddleOptions == null) {
                context.getLogger().log("No straddle options found for strike " + bestStrike + " at " + targetExpiration + " for " + ticker);
                return 0.0;
            }
            
            Map<String, Object> atmCallContract = straddleOptions[0];
            Map<String, Object> atmPutContract = straddleOptions[1];
            
            // Log expiration details for transparency
            if (atmCallContract != null) {
                LocalDate callExp = LocalDate.parse((String) atmCallContract.get("expiration"));
                long daysFromTarget = java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, callExp);
                context.getLogger().log(ticker + " selected call expiration: " + callExp + 
                    " (target: " + targetExpiration + ", days_diff: " + daysFromTarget + ")");
            }
            if (atmPutContract != null) {
                LocalDate putExp = LocalDate.parse((String) atmPutContract.get("expiration"));
                long daysFromTarget = java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, putExp);
                context.getLogger().log(ticker + " selected put expiration: " + putExp + 
                    " (target: " + targetExpiration + ", days_diff: " + daysFromTarget + ")");
            }
            
            // Calculate straddle price with flexible handling
            double straddlePrice;
            
            if (atmCallContract != null && atmPutContract != null) {
                // Full straddle: call + put with bid/ask sanity check
                double callMid = commonUtils.getMidPriceFromContract(atmCallContract);
                double putMid = commonUtils.getMidPriceFromContract(atmPutContract);
                
                if (callMid < 0 || putMid < 0) {
                    context.getLogger().log("Invalid bid/ask spreads for straddle calculation for " + ticker);
                    return 0.0;
                }
                
                straddlePrice = callMid + putMid;
                context.getLogger().log(ticker + " using full straddle: call=" + String.format("%.2f", callMid) + 
                    ", put=" + String.format("%.2f", putMid) + ", strike=" + String.format("%.2f", bestStrike));
            } else if (atmCallContract != null) {
                // Call-only straddle: double the call price as proxy with bid/ask sanity check
                double callMid = commonUtils.getMidPriceFromContract(atmCallContract);
                
                if (callMid < 0) {
                    context.getLogger().log("Invalid bid/ask spread for call-only straddle calculation for " + ticker);
                    return 0.0;
                }
                
                straddlePrice = callMid * 2.0;
                context.getLogger().log(ticker + " using call-only straddle: call=" + String.format("%.2f", callMid) + 
                    ", estimated_straddle=" + String.format("%.2f", straddlePrice) + ", strike=" + String.format("%.2f", bestStrike));
            } else if (atmPutContract != null) {
                // Put-only straddle: double the put price as proxy with bid/ask sanity check
                double putMid = commonUtils.getMidPriceFromContract(atmPutContract);
                
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
    
    
}
