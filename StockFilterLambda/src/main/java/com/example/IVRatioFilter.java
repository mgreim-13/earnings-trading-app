package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.OptionSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking IV ratio (short-term vs long-term implied volatility)
 * Compares short leg (earnings +1 day) vs long leg (~30 days post-earnings)
 */
public class IVRatioFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double IV_RATIO_THRESHOLD;
    
    public IVRatioFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.IV_RATIO_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("IV_RATIO_THRESHOLD", "1.2"));
    }
    
    /**
     * Check if stock has sufficient IV ratio for calendar spread
     */
    public boolean hasIVRatio(String ticker, LocalDate earningsDate, Context context) {
        try {
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return false;
            
            double ivShort = getIVForShortLeg(ticker, earningsDate, currentPrice, context);
            double ivLong = getIVForLongLeg(ticker, earningsDate, currentPrice, context);
            
            // If options data is available, use it
            if (ivShort > 0 && ivLong > 0) {
                double ivRatio = ivShort / ivLong;
                boolean passes = ivRatio >= IV_RATIO_THRESHOLD;
                
                context.getLogger().log(ticker + " IV ratio (options): " + ivShort + "/" + ivLong + " = " + 
                    String.format("%.3f", ivRatio) + " (" + passes + ")");
                
                return passes;
            }
            
            // Fallback to HV-based IV30/RV30 ratio
            context.getLogger().log("No options IV data for " + ticker + ", using HV fallback");
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No stock data available for HV fallback for " + ticker);
                return false;
            }
            
            double iv30 = stockData.getIv30();
            double rv30 = stockData.getRv30();
            
            if (iv30 <= 0 || rv30 <= 0) {
                context.getLogger().log("No HV data available for " + ticker + ", failing filter");
                return false;
            }
            
            double hvRatio = iv30 / rv30;
            boolean passes = hvRatio >= IV_RATIO_THRESHOLD;
            
            context.getLogger().log(ticker + " IV ratio (HV fallback): " + iv30 + "/" + rv30 + " = " + 
                String.format("%.3f", hvRatio) + " (" + passes + ")");
            
            return passes;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking IV ratio for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get IV for short leg (earnings + 1 day)
     */
    private double getIVForShortLeg(String ticker, LocalDate earningsDate, double currentPrice, Context context) {
        return getIVForLeg(ticker, earningsDate, 1, currentPrice, context);
    }
    
    /**
     * Get IV for long leg (~30 days post-earnings)
     */
    private double getIVForLongLeg(String ticker, LocalDate earningsDate, double currentPrice, Context context) {
        return getIVForLeg(ticker, earningsDate, 30, currentPrice, context);
    }
    
    /**
     * Get IV for specific leg based on earnings date and offset
     */
    private double getIVForLeg(String ticker, LocalDate earningsDate, int daysOffset, double currentPrice, Context context) {
        try {
            LocalDate expiration = earningsDate.plusDays(daysOffset);
            return getIVForExpiration(ticker, expiration, currentPrice, context);
        } catch (Exception e) {
            context.getLogger().log("Error getting IV for leg (+" + daysOffset + " days): " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get IV for specific expiration date
     */
    private double getIVForExpiration(String ticker, LocalDate expiration, double currentPrice, Context context) {
        try {
            // Get options chain for the expiration date
            LocalDate startDate = expiration.minusDays(2);
            LocalDate endDate = expiration.plusDays(2);
            
            Map<String, OptionSnapshot> optionChain = alpacaApiService.getOptionChain(
                ticker, startDate, endDate, "call");
            
            if (optionChain.isEmpty()) {
                context.getLogger().log("No options chain available for " + ticker + " at " + expiration);
                return 0.0;
            }
            
            // Find ATM call option
            OptionSnapshot atmOption = commonUtils.findATMOption(optionChain, currentPrice);
            if (atmOption == null) {
                context.getLogger().log("No ATM option found for " + ticker + " at " + expiration);
                return 0.0;
            }
            
            double impliedVol = atmOption.getImpliedVol();
            if (impliedVol <= 0) {
                context.getLogger().log("Invalid implied volatility for " + ticker + " at " + expiration);
                return 0.0;
            }
            
            context.getLogger().log("Found IV for " + ticker + " at " + expiration + ": " + impliedVol);
            return impliedVol;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting IV for expiration " + expiration + " for " + ticker + ": " + e.getMessage());
            return 0.0;
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
