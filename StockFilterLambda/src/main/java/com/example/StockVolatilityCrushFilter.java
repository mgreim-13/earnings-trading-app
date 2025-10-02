package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.HistoricalBar;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.AlpacaHttpClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Filter for checking historical volatility crush using stock price data
 * Analyzes realized volatility patterns around earnings announcements
 * Uses historical stock price data instead of options data
 */
public class StockVolatilityCrushFilter {
    
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Thresholds
    private final double VOLATILITY_CRUSH_THRESHOLD;
    private final double CRUSH_PERCENTAGE;
    private final int VOLATILITY_LOOKBACK_DAYS;
    
    public StockVolatilityCrushFilter(AlpacaCredentials credentials, StockFilterCommonUtils commonUtils) {
        this.credentials = credentials;
        this.commonUtils = commonUtils;
        
        // Load thresholds from central configuration
        this.VOLATILITY_CRUSH_THRESHOLD = FilterThresholds.VOLATILITY_CRUSH_THRESHOLD;
        this.CRUSH_PERCENTAGE = FilterThresholds.CRUSH_PERCENTAGE;
        this.VOLATILITY_LOOKBACK_DAYS = FilterThresholds.VOLATILITY_LOOKBACK_DAYS;
    }
    
    /**
     * Check if stock has historical volatility crush pattern using stock price data
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
                    
                    if (calculateVolatilityCrushForEarnings(ticker, earningsDate, context)) {
                        crushCount++;
                    }
                    totalEarnings++;
                } catch (Exception e) {
                    context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            boolean hasCrush = totalEarnings > 0 && (double) crushCount / totalEarnings >= CRUSH_PERCENTAGE;
            context.getLogger().log(ticker + " stock volatility crush: " + crushCount + "/" + totalEarnings + 
                " = " + String.format("%.1f%%", (double) crushCount / totalEarnings * 100) + " (" + hasCrush + ")");
            return hasCrush;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking stock volatility crush for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate volatility crush for a specific earnings date using stock price data
     */
    private boolean calculateVolatilityCrushForEarnings(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get historical stock data for volatility calculation
            List<HistoricalBar> historicalBars = getHistoricalBarsForVolatility(ticker, earningsDate, context);
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical data available for " + ticker + " around " + earningsDate);
                return false;
            }
            
            // Calculate pre-earnings volatility (7 days before earnings)
            double preVol = calculateVolatilityForPeriod(historicalBars, earningsDate.minusDays(7), earningsDate.minusDays(1), context);
            
            // Calculate post-earnings volatility (7 days after earnings)
            double postVol = calculateVolatilityForPeriod(historicalBars, earningsDate.plusDays(1), earningsDate.plusDays(7), context);
            
            if (preVol > 0 && postVol > 0) {
                double crushRatio = postVol / preVol;
                boolean hasCrush = crushRatio < VOLATILITY_CRUSH_THRESHOLD;
                
                context.getLogger().log(ticker + " earnings " + earningsDate + ": preVol=" + 
                    String.format("%.3f", preVol) + ", postVol=" + String.format("%.3f", postVol) + 
                    ", ratio=" + String.format("%.3f", crushRatio) + ", hasCrush=" + hasCrush);
                
                return hasCrush;
            }
            
            context.getLogger().log(ticker + " earnings " + earningsDate + ": insufficient data (preVol=" + 
                String.format("%.3f", preVol) + ", postVol=" + String.format("%.3f", postVol) + ")");
            return false;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earningsDate + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get historical bars for volatility calculation around earnings date
     */
    private List<HistoricalBar> getHistoricalBarsForVolatility(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get enough historical data to cover the earnings period plus buffer
            int daysNeeded = VOLATILITY_LOOKBACK_DAYS;
            List<HistoricalBar> historicalBars = AlpacaHttpClient.getHistoricalBars(ticker, daysNeeded, credentials);
            
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical bars available for " + ticker);
                return null;
            }
            
            // Sort by timestamp to ensure chronological order
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            // Filter to include data around the earnings date
            LocalDate startDate = earningsDate.minusDays(30); // 30 days before earnings
            LocalDate endDate = earningsDate.plusDays(30);    // 30 days after earnings
            
            return historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return !barDate.isBefore(startDate) && !barDate.isAfter(endDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(java.util.stream.Collectors.toList());
                
        } catch (Exception e) {
            context.getLogger().log("Error getting historical bars for " + ticker + " around " + earningsDate + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate volatility for a specific period using stock price data
     * Reuses the existing calculateHistoricalVolatilityFromBars method
     */
    private double calculateVolatilityForPeriod(List<HistoricalBar> historicalBars, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Filter bars for the specific period
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
                context.getLogger().log("Insufficient data for period " + startDate + " to " + endDate + " (bars: " + periodBars.size() + ")");
                return 0.0;
            }
            
            // Use the existing volatility calculation method from StockFilterCommonUtils
            double volatility = commonUtils.calculateHistoricalVolatilityFromBars(periodBars);
            
            context.getLogger().log("Calculated volatility for period " + startDate + " to " + endDate + 
                ": " + String.format("%.3f", volatility) + " (bars: " + periodBars.size() + ")");
            
            return volatility;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating volatility for period " + startDate + " to " + endDate + ": " + e.getMessage());
            return 0.0;
        }
    }
}
