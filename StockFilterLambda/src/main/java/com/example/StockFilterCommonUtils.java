package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.example.AlpacaApiService.HistoricalBar;
import com.example.AlpacaApiService.LatestTrade;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Common utilities for stock filtering operations
 * Contains shared methods used across multiple filter classes
 */
public class StockFilterCommonUtils {
    
    private final AlpacaApiService alpacaApiService;
    
    public StockFilterCommonUtils(AlpacaApiService alpacaApiService) {
        this.alpacaApiService = alpacaApiService;
    }
    
    /**
     * Get current stock price from latest trade
     */
    public double getCurrentStockPrice(String ticker, Context context) {
        try {
            LatestTrade latestTrade = alpacaApiService.getLatestTrade(ticker);
            if (latestTrade != null) {
                return latestTrade.getPrice();
            }
            return 0.0;
        } catch (Exception e) {
            context.getLogger().log("Error getting current price for " + ticker + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get historical earnings data from Finnhub API
     */
    public List<EarningsData> getHistoricalEarningsData(String ticker, Context context) {
        try {
            // Get Finnhub API key from environment or secrets manager
            String finnhubApiKey = System.getenv("FINNHUB_API_KEY");
            if (finnhubApiKey == null) {
                // Try to get from secrets manager
                try {
                    finnhubApiKey = com.trading.common.TradingCommonUtils.getAlpacaCredentials("finnhub-secret").getApiKey();
                } catch (Exception e) {
                    context.getLogger().log("Could not retrieve Finnhub API key: " + e.getMessage());
                    return new ArrayList<>();
                }
            }
            
            // Get historical earnings data using the stock earnings endpoint
            String earningsUrl = "https://finnhub.io/api/v1/stock/earnings?symbol=" + ticker + 
                "&token=" + finnhubApiKey;
            
            context.getLogger().log("Fetching earnings data from: " + earningsUrl);
            
            String earningsResponse = com.trading.common.TradingCommonUtils.makeHttpRequest(earningsUrl, "", "", "GET", null);
            JsonNode earningsArray = com.trading.common.TradingCommonUtils.parseJson(earningsResponse);
            
            List<EarningsData> earningsList = new ArrayList<>();
            if (earningsArray.isArray()) {
                for (JsonNode earning : earningsArray) {
                    try {
                        // Finnhub API returns "period" field, not "date"
                        String dateStr = earning.get("period").asText();
                        LocalDate earningsDate = LocalDate.parse(dateStr);
                        double actualEps = earning.has("actual") ? earning.get("actual").asDouble() : 0.0;
                        double estimateEps = earning.has("estimate") ? earning.get("estimate").asDouble() : 0.0;
                        
                        earningsList.add(new EarningsData(earningsDate, actualEps, estimateEps));
                    } catch (Exception e) {
                        context.getLogger().log("Error parsing earnings data: " + e.getMessage());
                    }
                }
            }
            
            context.getLogger().log("Retrieved " + earningsList.size() + " earnings records for " + ticker);
            return earningsList;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting historical earnings data for " + ticker + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Calculate earnings day move percentage
     */
    public double calculateEarningsDayMove(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get more historical data to cover older earnings dates
            List<HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 365);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return -1.0; // Indicate no data
            }
            
            // Sort by timestamp to ensure chronological order
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            // Find the bar for the earnings date
            HistoricalBar earningsBar = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return barDate.equals(earningsDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
            
            if (earningsBar == null) {
                return -1.0; // Indicate no data
            }
            
            // Calculate absolute move percentage
            double openPrice = earningsBar.getOpen();
            double closePrice = earningsBar.getClose();
            
            if (openPrice <= 0) {
                return -1.0; // Indicate invalid data
            }
            
            return Math.abs(closePrice - openPrice) / openPrice;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating earnings day move for " + ticker + " on " + earningsDate + ": " + e.getMessage());
            return -1.0;
        }
    }
    
    /**
     * Calculate earnings day move including overnight gaps (previous close to current close)
     * This captures the full move including after-hours earnings reactions
     */
    public double calculateEarningsDayMoveWithGaps(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get more historical data to cover older earnings dates
            List<HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 365);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return -1.0; // Indicate no data
            }
            
            // Sort by timestamp to ensure chronological order
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            // Find the bar for the earnings date
            HistoricalBar earningsBar = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return barDate.equals(earningsDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
            
            if (earningsBar == null) {
                return -1.0; // Indicate no data
            }
            
            // Find the previous trading day's bar
            HistoricalBar previousBar = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return barDate.isBefore(earningsDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .max((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .orElse(null);
            
            if (previousBar == null) {
                // Fallback to regular open-to-close calculation
                return calculateEarningsDayMove(ticker, earningsDate, context);
            }
            
            // Calculate total move including overnight gap
            double previousClose = previousBar.getClose();
            double currentClose = earningsBar.getClose();
            
            if (previousClose <= 0) {
                return -1.0; // Indicate invalid data
            }
            
            double totalMove = Math.abs(currentClose - previousClose) / previousClose;
            
            context.getLogger().log(ticker + " earnings move with gaps on " + earningsDate + 
                ": previous_close=" + String.format("%.2f", previousClose) + 
                ", current_close=" + String.format("%.2f", currentClose) + 
                ", total_move=" + String.format("%.2f", totalMove * 100) + "%");
            
            return totalMove;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating earnings day move with gaps for " + ticker + " on " + earningsDate + ": " + e.getMessage());
            return -1.0;
        }
    }
    
    /**
     * Find ATM option from option chain
     */
    public AlpacaApiService.OptionSnapshot findATMOption(Map<String, AlpacaApiService.OptionSnapshot> optionChain, double currentPrice) {
        if (optionChain.isEmpty()) return null;
        
        AlpacaApiService.OptionSnapshot closestOption = null;
        double minDifference = Double.MAX_VALUE;
        
        for (AlpacaApiService.OptionSnapshot option : optionChain.values()) {
            double strikeDifference = Math.abs(option.getStrike() - currentPrice);
            if (strikeDifference < minDifference) {
                minDifference = strikeDifference;
                closestOption = option;
            }
        }
        
        return closestOption;
    }
    
    /**
     * Find ATM option with shortest expiration from option chain
     * This method finds the option closest to current price that has the earliest expiration
     */
    public AlpacaApiService.OptionSnapshot findShortestExpirationATMOption(Map<String, AlpacaApiService.OptionSnapshot> optionChain, double currentPrice) {
        if (optionChain.isEmpty()) return null;
        
        AlpacaApiService.OptionSnapshot bestOption = null;
        double minStrikeDifference = Double.MAX_VALUE;
        LocalDate earliestExpiration = null;
        
        for (Map.Entry<String, AlpacaApiService.OptionSnapshot> entry : optionChain.entrySet()) {
            AlpacaApiService.OptionSnapshot option = entry.getValue();
            String symbol = entry.getKey();
            
            // Extract expiration date from option symbol (format: SYMBOL + YYMMDD + C/P + STRIKE)
            // For example: AAPL250103C00150000 -> 250103 (Jan 3, 2025)
            LocalDate expiration = parseExpirationFromSymbol(symbol);
            if (expiration == null) continue;
            
            double strikeDifference = Math.abs(option.getStrike() - currentPrice);
            
            // Prefer option with shorter expiration, or if same expiration, closer strike
            boolean isBetter = false;
            if (earliestExpiration == null) {
                isBetter = true;
            } else if (expiration.isBefore(earliestExpiration)) {
                isBetter = true;
            } else if (expiration.equals(earliestExpiration) && strikeDifference < minStrikeDifference) {
                isBetter = true;
            }
            
            if (isBetter) {
                minStrikeDifference = strikeDifference;
                bestOption = option;
                earliestExpiration = expiration;
            }
        }
        
        return bestOption;
    }
    
    /**
     * Parse expiration date from option symbol
     * Format: SYMBOL + YYMMDD + C/P + STRIKE
     * Example: AAPL250103C00150000 -> 2025-01-03
     */
    private LocalDate parseExpirationFromSymbol(String symbol) {
        try {
            // Find the pattern: 6 digits for YYMMDD
            String pattern = "\\d{6}";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(symbol);
            
            if (m.find()) {
                String dateStr = m.group();
                int year = 2000 + Integer.parseInt(dateStr.substring(0, 2));
                int month = Integer.parseInt(dateStr.substring(2, 4));
                int day = Integer.parseInt(dateStr.substring(4, 6));
                return LocalDate.of(year, month, day);
            }
        } catch (Exception e) {
            // If parsing fails, return null
        }
        return null;
    }
    
    /**
     * Calculate historical volatility from bars
     */
    public double calculateHistoricalVolatilityFromBars(List<HistoricalBar> bars) {
        if (bars.size() < 2) return 0.0;
        
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            double prevClose = bars.get(i - 1).getClose();
            double currentClose = bars.get(i).getClose();
            
            if (prevClose > 0) {
                double dailyReturn = Math.log(currentClose / prevClose);
                returns.add(dailyReturn);
            }
        }
        
        if (returns.isEmpty()) return 0.0;
        
        // Calculate mean return
        double meanReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Calculate variance
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - meanReturn, 2))
            .average()
            .orElse(0.0);
        
        // Annualize volatility (252 trading days)
        return Math.sqrt(variance * 252);
    }
    
    /**
     * Calculate average volume from historical bars
     */
    public double calculateAverageVolume(List<HistoricalBar> historicalBars) {
        if (historicalBars.isEmpty()) return 0.0;
        
        long totalVolume = historicalBars.stream()
            .mapToLong(HistoricalBar::getVolume)
            .sum();
        
        return (double) totalVolume / historicalBars.size();
    }
    
    /**
     * Calculate RV30 from historical bars
     */
    public double calculateRV30(List<HistoricalBar> historicalBars, Context context) {
        try {
            // Get the most recent 30 days
            int startIndex = Math.max(historicalBars.size() - 30, 0);
            List<HistoricalBar> last30Days = historicalBars.subList(startIndex, historicalBars.size());
            
            if (last30Days.size() < 2) return 0.0;
            
            return calculateHistoricalVolatilityFromBars(last30Days);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating RV30: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate IV30 from historical bars
     */
    public double calculateIV30(List<HistoricalBar> historicalBars, Context context) {
        try {
            // Get the most recent 30 days
            int startIndex = Math.max(historicalBars.size() - 30, 0);
            List<HistoricalBar> last30Days = historicalBars.subList(startIndex, historicalBars.size());
            
            if (last30Days.size() < 2) return 0.0;
            
            return calculateHistoricalVolatilityFromBars(last30Days);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV30: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate term structure slope from historical bars
     */
    public double calculateTermStructureSlope(List<HistoricalBar> historicalBars, Context context) {
        try {
            if (historicalBars.size() < 60) return 0.0;
            
            // Calculate 30-day volatility
            int start30 = Math.max(historicalBars.size() - 30, 0);
            List<HistoricalBar> last30Days = historicalBars.subList(start30, historicalBars.size());
            double vol30 = calculateHistoricalVolatilityFromBars(last30Days);
            
            // Calculate 60-day volatility
            int start60 = Math.max(historicalBars.size() - 60, 0);
            List<HistoricalBar> last60Days = historicalBars.subList(start60, historicalBars.size());
            double vol60 = calculateHistoricalVolatilityFromBars(last60Days);
            
            return vol60 - vol30;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating term structure slope: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Data class for earnings information
     */
    public static class EarningsData {
        private final LocalDate earningsDate;
        private final double actualEps;
        private final double estimateEps;
        
        public EarningsData(LocalDate earningsDate, double actualEps, double estimateEps) {
            this.earningsDate = earningsDate;
            this.actualEps = actualEps;
            this.estimateEps = estimateEps;
        }
        
        public LocalDate getEarningsDate() { return earningsDate; }
        public double getActualEps() { return actualEps; }
        public double getEstimateEps() { return estimateEps; }
    }
}
