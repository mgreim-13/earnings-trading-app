package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.OptionSnapshot;

import java.time.LocalDate;
import java.util.Map;

/**
 * Filter for checking execution spread feasibility
 * Checks if calendar spread is cost-effective and has positive theta
 */
public class ExecutionSpreadFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double MAX_DEBIT_TO_PRICE_RATIO;
    
    public ExecutionSpreadFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
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
            double shortMid = validateBidAsk(spreadQuotes.shortBid, spreadQuotes.shortAsk, ticker + " short call", context);
            double longMid = validateBidAsk(spreadQuotes.longBid, spreadQuotes.longAsk, ticker + " long call", context);
            if (shortMid < 0 || longMid < 0) {
                return new FilterResult("ExecutionSpread", false, 0);
            }
            
            double netDebit = spreadQuotes.longAsk - spreadQuotes.shortBid;
            
            // Dynamic debit threshold based on stock price
            double dynamicThreshold = Math.min(MAX_DEBIT_TO_PRICE_RATIO, 0.1 / currentPrice);
            double debitToPriceRatio = netDebit / currentPrice;
            boolean reasonableDebit = debitToPriceRatio <= dynamicThreshold;
            boolean positiveTheta = spreadQuotes.shortTheta > 0;
            boolean passes = reasonableDebit && positiveTheta;
            
            int score = passes ? 3 : 0;
            String reason = String.format("debit=%.2f, price=%.2f, debit/price=%.3f (threshold=%.3f), theta=%.3f", 
                netDebit, currentPrice, debitToPriceRatio, dynamicThreshold, spreadQuotes.shortTheta);
            
            context.getLogger().log(ticker + " execution spread: " + reason + " (" + passes + ")");
            
            return new FilterResult("ExecutionSpread", passes, score);
            
        } catch (Exception e) {
            context.getLogger().log("Error checking execution spread for " + ticker + ": " + e.getMessage());
            return new FilterResult("ExecutionSpread", false, 0);
        }
    }
    
    /**
     * Get quotes for calendar spread legs using actual earnings date with fallbacks
     */
    private SpreadQuotes getSpreadQuotes(String ticker, LocalDate earningsDate, double currentPrice, Context context) {
        try {
            // Get short leg quotes - find shortest available expiration between +1 and +7 days
            Map<String, OptionSnapshot> shortChain = alpacaApiService.getOptionChain(
                ticker, earningsDate.plusDays(1), earningsDate.plusDays(7), "call");
            OptionSnapshot shortOption = commonUtils.findShortestExpirationATMOption(shortChain, currentPrice);
            
            if (shortOption == null) {
                context.getLogger().log("No short leg option found for " + ticker + " between " + 
                    earningsDate.plusDays(1) + " and " + earningsDate.plusDays(7));
            } else {
                // Extract the actual expiration date from the option symbol or use the found option
                context.getLogger().log("Found short leg option for " + ticker + " at shortest available expiration");
            }
            
            if (shortOption == null) {
                context.getLogger().log("No short leg option found for " + ticker + " even with fallback");
                return null;
            }
            
            // Get long leg quotes - find shortest available expiration between +26 and +40 days
            Map<String, OptionSnapshot> longChain = alpacaApiService.getOptionChain(
                ticker, earningsDate.plusDays(26), earningsDate.plusDays(40), "call");
            OptionSnapshot longOption = commonUtils.findShortestExpirationATMOption(longChain, currentPrice);
            
            if (longOption == null) {
                context.getLogger().log("No long leg option found for " + ticker + " between " + 
                    earningsDate.plusDays(26) + " and " + earningsDate.plusDays(40));
            } else {
                context.getLogger().log("Found long leg option for " + ticker + " at shortest available expiration");
            }
            
            if (longOption == null) {
                context.getLogger().log("No long leg option found for " + ticker + " even with fallback");
                return null;
            }
            
            context.getLogger().log(ticker + " spread quotes: found both short and long leg options");
            
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
     * Validate bid/ask spreads similar to EarningsStabilityFilter
     */
    private double validateBidAsk(double bid, double ask, String optionName, Context context) {
        if (bid <= 0 || ask <= 0) {
            context.getLogger().log("Invalid " + optionName + " bid/ask: bid=" + bid + ", ask=" + ask);
            return -1;
        }
        
        if (ask < bid) {
            context.getLogger().log("Invalid " + optionName + " spread: ask=" + ask + " < bid=" + bid);
            return -1;
        }
        
        double mid = (bid + ask) / 2.0;
        double spread = ask - bid;
        double spreadPercent = (spread / mid) * 100;
        
        // Reject if spread is too wide (>20%) or mid price is too low (<$0.10)
        if (spreadPercent > 20.0 || mid < 0.10) {
            context.getLogger().log("Rejected " + optionName + ": spread=" + String.format("%.1f", spreadPercent) + 
                "%, mid=" + String.format("%.2f", mid));
            return -1;
        }
        
        return mid;
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
