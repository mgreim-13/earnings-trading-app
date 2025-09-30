package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.HistoricalBar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Filter for checking historical volatility crush
 * Checks if post-earnings volatility is consistently lower than pre-earnings
 */
public class VolatilityCrushFilter {
    
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double VOLATILITY_CRUSH_THRESHOLD;
    private final double CRUSH_PERCENTAGE;
    
    public VolatilityCrushFilter(AlpacaApiService alpacaApiService, StockFilterCommonUtils commonUtils) {
        this.alpacaApiService = alpacaApiService;
        this.commonUtils = commonUtils;
        
        // Load thresholds from environment
        this.VOLATILITY_CRUSH_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("VOLATILITY_CRUSH_THRESHOLD", "0.85"));
        this.CRUSH_PERCENTAGE = Double.parseDouble(System.getenv().getOrDefault("CRUSH_PERCENTAGE", "0.60"));
    }
    
    /**
     * Check if stock has historical volatility crush pattern
     */
    public boolean hasHistoricalVolatilityCrush(String ticker, Context context) {
        try {
            List<StockFilterCommonUtils.EarningsData> earningsData = commonUtils.getHistoricalEarningsData(ticker, context);
            if (earningsData.isEmpty()) {
                context.getLogger().log("No historical earnings data for " + ticker);
                return false;
            }
            
            int crushCount = 0;
            int totalEarnings = 0;
            
            for (StockFilterCommonUtils.EarningsData earning : earningsData) {
                try {
                    LocalDate earningsDate = earning.getEarningsDate();
                    
                    double preIV = getIVForPeriod(ticker, earningsDate.minusDays(7), earningsDate.minusDays(1), context);
                    double postIV = getIVForPeriod(ticker, earningsDate.plusDays(1), earningsDate.plusDays(7), context);
                    
                    if (preIV <= 0 || postIV <= 0) {
                        preIV = calculateIV30ForPeriod(ticker, earningsDate.minusDays(7), earningsDate.minusDays(1), context);
                        postIV = calculateIV30ForPeriod(ticker, earningsDate.plusDays(1), earningsDate.plusDays(7), context);
                    }
                    
                    if (preIV > 0 && postIV > 0) {
                        double crushRatio = postIV / preIV;
                        if (crushRatio < VOLATILITY_CRUSH_THRESHOLD) {
                            crushCount++;
                        }
                        totalEarnings++;
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            boolean hasCrush = totalEarnings > 0 && (double) crushCount / totalEarnings >= CRUSH_PERCENTAGE;
            context.getLogger().log(ticker + " volatility crush: " + crushCount + "/" + totalEarnings + 
                " = " + String.format("%.1f%%", (double) crushCount / totalEarnings * 100) + " (" + hasCrush + ")");
            return hasCrush;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking volatility crush for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get IV for a period using options data or Black-Scholes estimation
     */
    private double getIVForPeriod(String ticker, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Check if we have recent options data (within 7 days)
            LocalDate today = LocalDate.now();
            if (endDate.isAfter(today.minusDays(7))) {
                // Try to get real IV from options data
                double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
                if (currentPrice > 0) {
                    // Get IV for the period using options chain
                    LocalDate midDate = startDate.plusDays((int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) / 2);
                    return getIVForExpiration(ticker, midDate, currentPrice, context);
                }
            }
            
            // For older data, try to estimate IV using Black-Scholes if we have option prices
            return calculateHistoricalIV(ticker, startDate, endDate, context);
            
        } catch (Exception e) {
            context.getLogger().log("Error getting real IV for period " + startDate + " to " + endDate + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate historical IV using Black-Scholes and historical option data
     */
    private double calculateHistoricalIV(String ticker, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Check if this is recent data (within 7 days) where we might have option prices
            LocalDate today = LocalDate.now();
            if (endDate.isAfter(today.minusDays(7))) {
                // Try to get historical option data for Black-Scholes estimation
                double estimatedIV = calculateIVFromHistoricalOptions(ticker, startDate, endDate, context);
                if (estimatedIV > 0) {
                    context.getLogger().log("Using Black-Scholes estimated IV for " + ticker + ": " + estimatedIV);
                    return estimatedIV;
                }
            }
            
            // Fallback to historical volatility from stock bars
            List<HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 90);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return 0.0;
            }
            
            // Filter bars for the period
            List<HistoricalBar> periodBars = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return !barDate.isBefore(startDate) && !barDate.isAfter(endDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toList());
            
            if (periodBars.size() < 2) {
                return 0.0;
            }
            
            return commonUtils.calculateHistoricalVolatilityFromBars(periodBars);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating historical IV for " + ticker + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate IV from historical option data using Black-Scholes
     */
    private double calculateIVFromHistoricalOptions(String ticker, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Get current stock price for the period
            double currentPrice = commonUtils.getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return 0.0;
            
            // Get historical option trades for the period
            List<AlpacaApiService.OptionTrade> trades = alpacaApiService.getOptionHistoricalTrades(
                List.of(ticker + "*"), startDate, endDate);
            
            if (trades.isEmpty()) {
                return 0.0;
            }
            
            // Find ATM call trades
            // For now, use all trades since OptionTrade doesn't contain strike information
            // In a real implementation, you'd need to get this from the option symbol or use OptionSnapshot
            List<AlpacaApiService.OptionTrade> atmTrades = trades;
            
            if (atmTrades.isEmpty()) {
                return 0.0;
            }
            
            // Use average price for Black-Scholes estimation
            double avgPrice = atmTrades.stream()
                .mapToDouble(AlpacaApiService.OptionTrade::getPrice)
                .average()
                .orElse(0.0);
            
            if (avgPrice <= 0) return 0.0;
            
            // Simple Black-Scholes IV estimation (simplified)
            // This is a placeholder - in practice, you'd implement proper Black-Scholes
            double timeToExpiry = 0.25; // Assume 3 months
            double riskFreeRate = 0.05; // Assume 5% risk-free rate
            
            // Simplified IV calculation (not accurate Black-Scholes)
            double moneyness = currentPrice / avgPrice;
            double estimatedIV = Math.log(moneyness) / Math.sqrt(timeToExpiry);
            
            return Math.abs(estimatedIV); // Return absolute value
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV from historical options for " + ticker + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate IV30 for a specific period using historical bars
     */
    private double calculateIV30ForPeriod(String ticker, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            List<HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 90);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return 0.0;
            }
            
            // Filter bars for the period
            List<HistoricalBar> periodBars = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return !barDate.isBefore(startDate) && !barDate.isAfter(endDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toList());
            
            if (periodBars.size() < 2) {
                return 0.0;
            }
            
            return commonUtils.calculateHistoricalVolatilityFromBars(periodBars);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV30 for period for " + ticker + ": " + e.getMessage());
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
            
            Map<String, AlpacaApiService.OptionSnapshot> optionChain = alpacaApiService.getOptionChain(
                ticker, startDate, endDate, "call");
            
            if (optionChain.isEmpty()) {
                return 0.0;
            }
            
            // Find ATM call option
            AlpacaApiService.OptionSnapshot atmOption = commonUtils.findATMOption(optionChain, currentPrice);
            if (atmOption == null) {
                return 0.0;
            }
            
            return atmOption.getImpliedVol();
            
        } catch (Exception e) {
            context.getLogger().log("Error getting IV for expiration " + expiration + " for " + ticker + ": " + e.getMessage());
            return 0.0;
        }
    }
}
