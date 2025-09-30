package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.OptionSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking options liquidity (bid-ask spreads, quote depth, trade activity)
 * Checks both short and long legs of the calendar spread
 */
public class LiquidityFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double VOLUME_THRESHOLD;
    private final double BID_ASK_THRESHOLD;
    private final long QUOTE_DEPTH_THRESHOLD;
    private final long MIN_DAILY_OPTION_TRADES;
    private final double MIN_STOCK_PRICE;
    private final double MAX_STOCK_PRICE;
    
    public LiquidityFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.VOLUME_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("VOLUME_THRESHOLD", "1000000"));
        this.BID_ASK_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("BID_ASK_THRESHOLD", "0.05"));
        this.QUOTE_DEPTH_THRESHOLD = Long.parseLong(System.getenv().getOrDefault("QUOTE_DEPTH_THRESHOLD", "50"));
        this.MIN_DAILY_OPTION_TRADES = Long.parseLong(System.getenv().getOrDefault("MIN_DAILY_OPTION_TRADES", "50"));
        this.MIN_STOCK_PRICE = Double.parseDouble(System.getenv().getOrDefault("MIN_STOCK_PRICE", "20.0"));
        this.MAX_STOCK_PRICE = Double.parseDouble(System.getenv().getOrDefault("MAX_STOCK_PRICE", "1000.0"));
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
     */
    private OptionsLiquidityData getOptionsLiquidityData(String ticker, double currentPrice, Context context) {
        try {
            LocalDate today = LocalDate.now();
            
            // Check short leg (earnings +1 day, with fallback to today +7 days)
            LocalDate shortExp = today.plusDays(1); // Default to tomorrow
            LocalDate shortStart = shortExp.minusDays(2);
            LocalDate shortEnd = shortExp.plusDays(2);
            
            Map<String, OptionSnapshot> shortChain = alpacaApiService.getOptionChain(
                ticker, shortStart, shortEnd, "call");
            
            // If no short leg options, try fallback to +7 days
            if (shortChain.isEmpty()) {
                shortExp = today.plusDays(7);
                shortStart = shortExp.minusDays(2);
                shortEnd = shortExp.plusDays(2);
                shortChain = alpacaApiService.getOptionChain(ticker, shortStart, shortEnd, "call");
                context.getLogger().log("No short leg options at +1 day, trying fallback at +7 days for " + ticker);
            }
            
            // Check long leg (~30 days)
            LocalDate longExp = today.plusDays(30);
            LocalDate longStart = longExp.minusDays(5);
            LocalDate longEnd = longExp.plusDays(5);
            
            Map<String, OptionSnapshot> longChain = alpacaApiService.getOptionChain(
                ticker, longStart, longEnd, "call");
            
            // If no long leg options, try fallback to +60 days
            if (longChain.isEmpty()) {
                longExp = today.plusDays(60);
                longStart = longExp.minusDays(5);
                longEnd = longExp.plusDays(5);
                longChain = alpacaApiService.getOptionChain(ticker, longStart, longEnd, "call");
                context.getLogger().log("No long leg options at +30 days, trying fallback at +60 days for " + ticker);
            }
            
            // Check if we have at least one leg
            if (shortChain.isEmpty() && longChain.isEmpty()) {
                context.getLogger().log("No options data available for either leg for " + ticker);
                return new OptionsLiquidityData(false, false, false);
            }
            
            // Evaluate short leg (if available)
            boolean shortTightSpreads = true, shortQuoteDepth = true, shortTradeActivity = true;
            if (!shortChain.isEmpty()) {
                OptionSnapshot shortOption = commonUtils.findATMOption(shortChain, currentPrice);
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
                OptionSnapshot longOption = commonUtils.findATMOption(longChain, currentPrice);
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
     * Check daily trade activity for an option
     */
    private boolean checkDailyTradeActivity(String ticker, String optionSymbol, Context context) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            
            List<AlpacaApiService.OptionTrade> trades = alpacaApiService.getOptionHistoricalTrades(
                List.of(optionSymbol), yesterday, today);
            
            long totalTrades = trades.stream()
                .mapToLong(AlpacaApiService.OptionTrade::getSize)
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
            AlpacaApiService.LatestTrade latestTrade = alpacaApiService.getLatestTrade(ticker);
            if (latestTrade == null) {
                context.getLogger().log("No trade data available for: " + ticker);
                return null;
            }
            
            // Get historical data for calculations
            List<AlpacaApiService.HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 90);
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
            
            return new StockData(ticker, averageVolume, rv30, iv30, termSlope);
            
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
    
    /**
     * Data class for stock information
     */
    private static class StockData {
        private final String ticker;
        private final double averageVolume;
        private final double rv30;
        private final double iv30;
        private final double termStructureSlope;
        
        public StockData(String ticker, double averageVolume, double rv30, double iv30, double termStructureSlope) {
            this.ticker = ticker;
            this.averageVolume = averageVolume;
            this.rv30 = rv30;
            this.iv30 = iv30;
            this.termStructureSlope = termStructureSlope;
        }
        
        public String getTicker() { return ticker; }
        public double getAverageVolume() { return averageVolume; }
        public double getRv30() { return rv30; }
        public double getIv30() { return iv30; }
        public double getTermStructureSlope() { return termStructureSlope; }
    }
}
