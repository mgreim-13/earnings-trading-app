package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.OptionSnapshot;
import com.trading.common.OptionSelectionUtils;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Filter for checking execution spread feasibility
 * Checks if calendar spread is cost-effective and has positive theta
 */
public class ExecutionSpreadFilter {
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double MAX_DEBIT_TO_PRICE_RATIO;
    
    public ExecutionSpreadFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.MAX_DEBIT_TO_PRICE_RATIO = Double.parseDouble(System.getenv().getOrDefault("MAX_DEBIT_TO_PRICE_RATIO", "0.05"));
    }
    
    /**
     * Check if execution spread is feasible
     * Returns FilterResult for integration with scoring system
     */
    public FilterResult hasExecutionSpreadFeasibility(String ticker, LocalDate earningsDate, Context context) {
        try {
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) {
                return new FilterResult("ExecutionSpread", false, 0);
            }
            
            SpreadQuotes spreadQuotes = getSpreadQuotes(ticker, earningsDate, currentPrice, context);
            if (spreadQuotes == null) {
                return new FilterResult("ExecutionSpread", false, 0);
            }
            
            // Validate bid/ask spreads
            double shortMid = OptionSelectionUtils.validateBidAsk(spreadQuotes.shortBid, spreadQuotes.shortAsk);
            double longMid = OptionSelectionUtils.validateBidAsk(spreadQuotes.longBid, spreadQuotes.longAsk);
            if (shortMid < 0 || longMid < 0) {
                return new FilterResult("ExecutionSpread", false, 0);
            }
            
            // Note: Strike matching is guaranteed by the new common strike logic in getSpreadQuotes()
            
            double netDebit = spreadQuotes.longAsk - spreadQuotes.shortBid;
            
            // Dynamic debit threshold based on stock price
            double dynamicThreshold = Math.min(MAX_DEBIT_TO_PRICE_RATIO, 0.1 / currentPrice);
            double debitToPriceRatio = netDebit / currentPrice;
            boolean reasonableDebit = debitToPriceRatio <= dynamicThreshold;
            
            // For calendar spreads, check net theta (long theta - short theta) > 0
            // This ensures the long option has less negative theta (slower time decay) than the short option
            double netTheta = spreadQuotes.longTheta - spreadQuotes.shortTheta;
            boolean positiveNetTheta = netTheta > 0;
            boolean passes = reasonableDebit && positiveNetTheta;
            
            int score = passes ? 3 : 0;
            String reason = String.format("debit=%.2f, price=%.2f, debit/price=%.3f (threshold=%.3f), strike=%.2f, net_theta=%.3f (long=%.3f, short=%.3f)", 
                netDebit, currentPrice, debitToPriceRatio, dynamicThreshold, spreadQuotes.shortStrike, netTheta, spreadQuotes.longTheta, spreadQuotes.shortTheta);
            
            context.getLogger().log(ticker + " execution spread: " + reason + " (" + passes + ")");
            
            return new FilterResult("ExecutionSpread", passes, score);
            
        } catch (Exception e) {
            context.getLogger().log("Error checking execution spread for " + ticker + ": " + e.getMessage());
            return new FilterResult("ExecutionSpread", false, 0);
        }
    }
    
    /**
     * Get quotes for calendar spread legs using consistent option selection
     * Uses scan date as base date - all filters find the same options
     * This accounts for both BMO and AMC earnings calls since scan runs the day before earnings
     * Uses common strike logic to ensure both legs have the same strike price
     */
    private SpreadQuotes getSpreadQuotes(String ticker, LocalDate earningsDate, double currentPrice, Context context) {
        try {
            // Use scan date (current date) as base date - all filters find the same options
            // This works for both BMO (expires on earnings day) and AMC (expires day after earnings)
            LocalDate scanDate = LocalDate.now(ZoneId.of("America/New_York")); // Scan date = day before earnings
            
            // Get wide range of options (1-60 days) to find proper legs
            Map<String, OptionSnapshot> allOptions = commonUtils.getOptionChainForDateRange(
                ticker, scanDate, 1, 60, "call", credentials, context);
            
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
            
            // Filter to short leg options only
            Map<String, OptionSnapshot> shortChain = allOptions.entrySet().stream()
                .filter(entry -> entry.getKey().equals(shortExpiration.toString()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            if (shortChain.isEmpty()) {
                context.getLogger().log("No short leg options found for " + ticker + " at expiration " + shortExpiration);
                return null;
            }
            
            // Find long leg using reusable logic - closest to 30 days from scan date
            LocalDate longExpiration = com.trading.common.OptionSelectionUtils.findFarLegExpirationFromOptionChain(allOptions, scanDate, shortExpiration, 30);
            if (longExpiration == null) {
                context.getLogger().log("No suitable long leg expiration found for " + ticker);
                return null;
            }
            
            // Filter to long leg options only
            Map<String, OptionSnapshot> longChain = allOptions.entrySet().stream()
                .filter(entry -> entry.getKey().equals(longExpiration.toString()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            if (longChain.isEmpty()) {
                context.getLogger().log("No long leg options found for " + ticker + " at expiration " + longExpiration);
                return null;
            }
            
            // Find the best common strike between both chains
            double bestCommonStrike = OptionSelectionUtils.findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
                shortChain, longChain, currentPrice);
            
            if (bestCommonStrike < 0) {
                context.getLogger().log("No common strikes found between short and long leg chains for " + ticker);
                return null;
            }
            
            // Find options for the common strike
            OptionSnapshot shortOption = OptionSelectionUtils.findOptionForStrikeInOptionSnapshotChain(shortChain, bestCommonStrike);
            OptionSnapshot longOption = OptionSelectionUtils.findOptionForStrikeInOptionSnapshotChain(longChain, bestCommonStrike);
            
            if (shortOption == null) {
                context.getLogger().log("No short leg option found for strike " + bestCommonStrike + " for " + ticker);
                return null;
            }
            
            if (longOption == null) {
                context.getLogger().log("No long leg option found for strike " + bestCommonStrike + " for " + ticker);
                return null;
            }
            
            context.getLogger().log(ticker + " calendar spread: found common strike " + 
                String.format("%.2f", bestCommonStrike) + " for both legs");
            
            return new SpreadQuotes(
                shortOption.getBid(), shortOption.getAsk(), shortOption.getStrike(), shortOption.getTheta(),
                longOption.getBid(), longOption.getAsk(), longOption.getStrike(), longOption.getTheta()
            );
            
        } catch (Exception e) {
            context.getLogger().log("Error getting spread quotes for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    
    /**
     * Data class for spread quotes
     */
    private static class SpreadQuotes {
        public final double shortBid, shortAsk, shortStrike, shortTheta;
        public final double longBid, longAsk, longStrike, longTheta;
        
        public SpreadQuotes(double shortBid, double shortAsk, double shortStrike, double shortTheta,
                          double longBid, double longAsk, double longStrike, double longTheta) {
            this.shortBid = shortBid;
            this.shortAsk = shortAsk;
            this.shortStrike = shortStrike;
            this.shortTheta = shortTheta;
            this.longBid = longBid;
            this.longAsk = longAsk;
            this.longStrike = longStrike;
            this.longTheta = longTheta;
        }
    }
}
