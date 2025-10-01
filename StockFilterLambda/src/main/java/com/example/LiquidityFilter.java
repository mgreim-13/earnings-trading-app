package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.OptionSnapshot;
import com.trading.common.models.OptionTrade;
import com.trading.common.models.LatestTrade;
import com.trading.common.models.HistoricalBar;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.models.StockData;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.OptionSelectionUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking options liquidity (bid-ask spreads, quote depth, trade activity)
 * Checks both short and long legs of the calendar spread
 */
public class LiquidityFilter {
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double VOLUME_THRESHOLD;
    private final double BID_ASK_THRESHOLD;
    private final long QUOTE_DEPTH_THRESHOLD;
    private final long MIN_DAILY_OPTION_TRADES;
    private final double MIN_STOCK_PRICE;
    private final double MAX_STOCK_PRICE;
    
    public LiquidityFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment - Updated for earnings calendar spreads
        this.VOLUME_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("VOLUME_THRESHOLD", "2000000")); // Increased for better liquidity
        this.BID_ASK_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("BID_ASK_THRESHOLD", "0.08")); // 60% wider tolerance for earnings volatility
        this.QUOTE_DEPTH_THRESHOLD = Long.parseLong(System.getenv().getOrDefault("QUOTE_DEPTH_THRESHOLD", "100")); // 2x higher for better fills during volatility
        this.MIN_DAILY_OPTION_TRADES = Long.parseLong(System.getenv().getOrDefault("MIN_DAILY_OPTION_TRADES", "500")); // 10x higher for earnings options activity
        this.MIN_STOCK_PRICE = Double.parseDouble(System.getenv().getOrDefault("MIN_STOCK_PRICE", "25.0")); // Tighter range for better liquidity
        this.MAX_STOCK_PRICE = Double.parseDouble(System.getenv().getOrDefault("MAX_STOCK_PRICE", "500.0")); // Tighter range for better liquidity
    }
    
    /**
     * Check if stock has sufficient options liquidity
     */
    public boolean hasOptionsLiquidity(String ticker, Context context) {
        try {
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No stock data available for " + ticker);
                return false;
            }
            
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) {
                context.getLogger().log("Invalid stock price for " + ticker);
                return false;
            }
            
            boolean hasSufficientVolume = stockData.getAverageVolume() >= VOLUME_THRESHOLD;
            boolean hasReasonablePrice = currentPrice >= MIN_STOCK_PRICE && currentPrice <= MAX_STOCK_PRICE;
            
            OptionsLiquidityData optionsData = getOptionsLiquidityData(ticker, currentPrice, context);
            boolean hasTightSpreads = optionsData.hasTightSpreads;
            boolean hasQuoteDepth = optionsData.hasQuoteDepth;
            boolean hasTradeActivity = optionsData.hasTradeActivity;
            
            boolean hasLiquidity = hasSufficientVolume && hasReasonablePrice && 
                                 hasTightSpreads && hasQuoteDepth && hasTradeActivity;
            
            context.getLogger().log(ticker + " options liquidity check: " +
                "volume=" + String.format("%.0f", stockData.getAverageVolume()) + " (" + hasSufficientVolume + ") " +
                "price=" + String.format("%.2f", currentPrice) + " (" + hasReasonablePrice + ") " +
                "spreads=" + hasTightSpreads + " depth=" + hasQuoteDepth + " trades=" + hasTradeActivity + 
                " = " + hasLiquidity);
            
            return hasLiquidity;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking options liquidity for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get comprehensive options liquidity data for both short and long legs
     * Uses scan date as base date - all filters find the same options
     */
    private OptionsLiquidityData getOptionsLiquidityData(String ticker, double currentPrice, Context context) {
        try {
            LocalDate scanDate = LocalDate.now(ZoneId.of("America/New_York")); // Scan date = day before earnings
            
            // Get wide range of options (1-60 days) to find proper legs
            Map<String, OptionSnapshot> allOptions = getOptionChainForLeg(ticker, scanDate, 1, 60, context);
            
            if (allOptions.isEmpty()) {
                context.getLogger().log("No options data available for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Find short leg using reusable logic - earliest expiration after scan date
            LocalDate shortExpiration = com.trading.common.OptionSelectionUtils.findShortLegExpirationFromOptionChain(allOptions, scanDate);
            if (shortExpiration == null) {
                context.getLogger().log("No suitable short leg expiration found for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Filter to short leg options only
            Map<String, OptionSnapshot> shortChain = allOptions.entrySet().stream()
                .filter(entry -> entry.getKey().equals(shortExpiration.toString()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            // Find long leg using reusable logic - closest to 30 days from scan date
            LocalDate longExpiration = com.trading.common.OptionSelectionUtils.findFarLegExpirationFromOptionChain(allOptions, scanDate, shortExpiration, 30);
            if (longExpiration == null) {
                context.getLogger().log("No suitable long leg expiration found for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Filter to long leg options only
            Map<String, OptionSnapshot> longChain = allOptions.entrySet().stream()
                .filter(entry -> entry.getKey().equals(longExpiration.toString()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            // Check if we have at least one leg
            if (shortChain.isEmpty() && longChain.isEmpty()) {
                context.getLogger().log("No options data available for either leg for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Find the best common strike for calendar spread
            double bestCommonStrike = OptionSelectionUtils.findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
                shortChain, longChain, currentPrice);
            
            if (bestCommonStrike < 0) {
                context.getLogger().log("No common strikes found between short and long leg chains for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Evaluate short leg (if available)
            boolean shortTightSpreads = true, shortQuoteDepth = true, shortTradeActivity = true;
            if (!shortChain.isEmpty()) {
                OptionSnapshot shortOption = OptionSelectionUtils.findOptionForStrikeInOptionSnapshotChain(shortChain, bestCommonStrike);
                if (shortOption != null) {
                    shortTightSpreads = shortOption.getBidAskSpreadPercent() <= BID_ASK_THRESHOLD;
                    shortQuoteDepth = shortOption.getTotalSize() >= QUOTE_DEPTH_THRESHOLD;
                    shortTradeActivity = checkDailyTradeActivity(ticker, shortOption.getSymbol(), context);
                } else {
                    shortTightSpreads = shortQuoteDepth = shortTradeActivity = false;
                }
            }
            
            // Evaluate long leg (if available)
            boolean longTightSpreads = true, longQuoteDepth = true, longTradeActivity = true;
            if (!longChain.isEmpty()) {
                OptionSnapshot longOption = OptionSelectionUtils.findOptionForStrikeInOptionSnapshotChain(longChain, bestCommonStrike);
                if (longOption != null) {
                    longTightSpreads = longOption.getBidAskSpreadPercent() <= BID_ASK_THRESHOLD;
                    longQuoteDepth = longOption.getTotalSize() >= QUOTE_DEPTH_THRESHOLD;
                    longTradeActivity = checkDailyTradeActivity(ticker, longOption.getSymbol(), context);
                } else {
                    longTightSpreads = longQuoteDepth = longTradeActivity = false;
                }
            }
            
            // Both legs must pass if both are available, otherwise use available leg
            boolean hasTightSpreads = shortChain.isEmpty() ? longTightSpreads : 
                                    longChain.isEmpty() ? shortTightSpreads : 
                                    (shortTightSpreads && longTightSpreads);
            
            boolean hasQuoteDepth = shortChain.isEmpty() ? longQuoteDepth : 
                                  longChain.isEmpty() ? shortQuoteDepth : 
                                  (shortQuoteDepth && longQuoteDepth);
            
            boolean hasTradeActivity = shortChain.isEmpty() ? longTradeActivity : 
                                     longChain.isEmpty() ? shortTradeActivity : 
                                     (shortTradeActivity && longTradeActivity);
            
            context.getLogger().log(ticker + " liquidity check: short=" + !shortChain.isEmpty() + 
                " long=" + !longChain.isEmpty() + " spreads=" + hasTightSpreads + 
                " depth=" + hasQuoteDepth + " trades=" + hasTradeActivity);
            
            return new OptionsLiquidityData(hasTightSpreads, hasQuoteDepth, hasTradeActivity);
            
        } catch (Exception e) {
            context.getLogger().log("Error getting options liquidity data for " + ticker + ": " + e.getMessage());
            return new OptionsLiquidityData(false, false, false);
        }
    }
    
    /**
     * Get option chain for a specific leg with flexible date range
     * @param ticker Stock ticker
     * @param baseDate Base date for calculation
     * @param minDays Minimum days from base date
     * @param maxDays Maximum days from base date
     * @param context Lambda context
     * @return Map of option snapshots by expiration
     */
    private Map<String, OptionSnapshot> getOptionChainForLeg(String ticker, LocalDate baseDate, 
                                                           int minDays, int maxDays, Context context) {
        return commonUtils.getOptionChainForDateRange(ticker, baseDate, minDays, maxDays, "call", credentials, context);
    }
    
    /**
     * Check daily trade activity for an option
     */
    private boolean checkDailyTradeActivity(String ticker, String optionSymbol, Context context) {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
            LocalDate yesterday = today.minusDays(1);
            
            List<OptionTrade> trades = AlpacaHttpClient.getOptionHistoricalTrades(
                List.of(optionSymbol), yesterday, today, credentials);
            
            long totalTrades = trades.stream()
                .mapToLong(OptionTrade::getSize)
                .sum();
            
            boolean hasActivity = totalTrades >= MIN_DAILY_OPTION_TRADES;
            context.getLogger().log("Trade activity for " + optionSymbol + ": " + totalTrades + " trades = " + hasActivity);
            
            return hasActivity;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking trade activity for " + optionSymbol + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get real stock data with simplified calculations
     */
    private StockData getRealStockData(String ticker, Context context) {
        try {
            // Get latest trade for current price and volume
            LatestTrade latestTrade = AlpacaHttpClient.getLatestTrade(ticker, credentials);
            if (latestTrade == null) {
                context.getLogger().log("No trade data available for: " + ticker);
                return null;
            }
            
            // Get historical data for calculations
            List<HistoricalBar> historicalBars = AlpacaHttpClient.getHistoricalBars(ticker, 90, credentials);
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical data available for: " + ticker);
                return null;
            }
            
            // Sort historical bars by timestamp to ensure chronological order
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            // Calculate average volume from historical data
            double averageVolume = commonUtils.calculateAverageVolume(historicalBars);
            
            // Calculate RV30 (realized volatility)
            double rv30 = commonUtils.calculateRV30(historicalBars, context);
            
            // Calculate IV30 (implied volatility - using HV as proxy)
            double iv30 = commonUtils.calculateIV30(historicalBars, context);
            
            // Calculate term structure slope
            double termSlope = commonUtils.calculateTermStructureSlope(historicalBars, context);
            
            return new StockData(ticker, latestTrade.getPrice(), averageVolume, rv30, iv30, termSlope);
            
        } catch (Exception e) {
            context.getLogger().log("Error getting real stock data for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Data class for options liquidity information
     */
    private static class OptionsLiquidityData {
        public final boolean hasTightSpreads;
        public final boolean hasQuoteDepth;
        public final boolean hasTradeActivity;
        
        public OptionsLiquidityData(boolean hasTightSpreads, boolean hasQuoteDepth, boolean hasTradeActivity) {
            this.hasTightSpreads = hasTightSpreads;
            this.hasQuoteDepth = hasQuoteDepth;
            this.hasTradeActivity = hasTradeActivity;
        }
    }
    
}
