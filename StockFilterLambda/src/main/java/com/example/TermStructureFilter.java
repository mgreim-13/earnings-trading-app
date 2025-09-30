package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.OptionSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking term structure backwardation
 * Compares IV across multiple expirations to ensure earnings week IV > longer-term IV
 */
public class TermStructureFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double SLOPE_THRESHOLD;
    private final double HV_SLOPE_THRESHOLD;
    
    public TermStructureFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.SLOPE_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("SLOPE_THRESHOLD", "0.01"));
        this.HV_SLOPE_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("HV_SLOPE_THRESHOLD", "-0.00406"));
    }
    
    /**
     * Check if stock has term structure backwardation
     */
    public boolean hasTermStructureBackwardation(String ticker, LocalDate earningsDate, Context context) {
        try {
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return false;
            
            double ivEarningsWeek = getIVForShortLeg(ticker, earningsDate, currentPrice, context);
            double iv30 = getIVForLongLeg(ticker, earningsDate, currentPrice, context);
            double iv60 = getIVForExpiration(ticker, earningsDate.plusDays(60), currentPrice, context);
            
            // If options data is available, use it
            if (ivEarningsWeek > 0 && iv30 > 0 && iv60 > 0) {
                double maxLongTermIV = Math.max(iv30, iv60);
                double difference = ivEarningsWeek - maxLongTermIV;
                boolean passes = difference >= SLOPE_THRESHOLD;
                
                context.getLogger().log(ticker + " term structure (options): IV_earnings=" + ivEarningsWeek + 
                    ", max(IV30,IV60)=" + maxLongTermIV + ", diff=" + String.format("%.3f", difference) + " (" + passes + ")");
                
                return passes;
            }
            
            // Fallback to HV-based slope (vol60 - vol30)
            context.getLogger().log("No options IV data for " + ticker + ", using HV fallback for term structure");
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No stock data available for HV fallback for " + ticker);
                return false;
            }
            
            double termSlope = stockData.getTermStructureSlope();
            boolean passes = termSlope <= HV_SLOPE_THRESHOLD;
            
            context.getLogger().log(ticker + " term structure (HV fallback): slope=" + 
                String.format("%.6f", termSlope) + " <= " + HV_SLOPE_THRESHOLD + " (" + passes + ")");
            
            return passes;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking term structure for " + ticker + ": " + e.getMessage());
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
