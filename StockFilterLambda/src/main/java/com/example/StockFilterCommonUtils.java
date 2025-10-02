package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.HistoricalBar;
import com.trading.common.models.LatestTrade;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.JsonUtils;
import com.trading.common.JsonParsingUtils;
import com.trading.common.PriceUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Common utilities for stock filtering operations
 * Contains shared methods used across multiple filter classes
 */
public class StockFilterCommonUtils {
    
    private final AlpacaCredentials credentials;
    
    public StockFilterCommonUtils(AlpacaCredentials credentials) {
        this.credentials = credentials;
    }
    
    /**
     * Get current stock price from latest trade
     */
    public double getCurrentStockPrice(String ticker, Context context) {
        try {
            LatestTrade latestTrade = AlpacaHttpClient.getLatestTrade(ticker, credentials);
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
                    finnhubApiKey = com.trading.common.TradingCommonUtils.getAlpacaCredentials("trading/finnhub/credentials").getApiKey();
                } catch (Exception e) {
                    context.getLogger().log("Could not retrieve Finnhub API key: " + e.getMessage());
                    return new ArrayList<>();
                }
            }
            
            // Get historical earnings data using the stock earnings endpoint
            String earningsUrl = "https://finnhub.io/api/v1/stock/earnings?symbol=" + ticker + 
                "&token=" + finnhubApiKey;
            
            context.getLogger().log("Fetching earnings data from: " + earningsUrl);
            
            String earningsResponse = AlpacaHttpClient.makeAlpacaRequest(earningsUrl, "GET", null, credentials);
            JsonNode earningsArray = JsonUtils.parseJson(earningsResponse);
            
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
            HistoricalBar earningsBar = getHistoricalBarForDate(ticker, earningsDate, context);
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
            HistoricalBar earningsBar = getHistoricalBarForDate(ticker, earningsDate, context);
            if (earningsBar == null) {
                return -1.0; // Indicate no data
            }
            
            // Find the previous trading day's bar
            HistoricalBar previousBar = getPreviousTradingDayBar(ticker, earningsDate, context);
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
    public com.trading.common.models.OptionSnapshot findATMOption(Map<String, com.trading.common.models.OptionSnapshot> optionChain, double currentPrice) {
        return findATMOption(optionChain, currentPrice, false);
    }
    
    /**
     * Find ATM option with shortest expiration from option chain
     * This method finds the option closest to current price that has the earliest expiration
     */
    public com.trading.common.models.OptionSnapshot findShortestExpirationATMOption(Map<String, com.trading.common.models.OptionSnapshot> optionChain, double currentPrice) {
        return findATMOption(optionChain, currentPrice, true);
    }
    
    /**
     * Find ATM option from option chain with optional expiration preference
     * @param optionChain Map of option symbols to OptionSnapshot objects
     * @param currentPrice Current stock price for ATM calculation
     * @param preferShortestExpiration If true, prefer options with shorter expiration dates
     * @return The best matching option, or null if none found
     */
    private com.trading.common.models.OptionSnapshot findATMOption(Map<String, com.trading.common.models.OptionSnapshot> optionChain, 
                                                                  double currentPrice, 
                                                                  boolean preferShortestExpiration) {
        if (optionChain.isEmpty()) return null;
        
        com.trading.common.models.OptionSnapshot bestOption = null;
        double minStrikeDifference = Double.MAX_VALUE;
        LocalDate earliestExpiration = null;
        
        for (Map.Entry<String, com.trading.common.models.OptionSnapshot> entry : optionChain.entrySet()) {
            com.trading.common.models.OptionSnapshot option = entry.getValue();
            double strikeDifference = Math.abs(option.getStrike() - currentPrice);
            
            boolean isBetter = false;
            
            if (preferShortestExpiration) {
                // Extract expiration date from option symbol
                String symbol = entry.getKey();
                LocalDate expiration = parseExpirationFromSymbol(symbol);
                if (expiration == null) continue;
                
                // Prefer option with shorter expiration, or if same expiration, closer strike
                if (earliestExpiration == null) {
                    isBetter = true;
                } else if (expiration.isBefore(earliestExpiration)) {
                    isBetter = true;
                } else if (expiration.equals(earliestExpiration) && strikeDifference < minStrikeDifference) {
                    isBetter = true;
                }
            } else {
                // Simple closest strike selection
                if (strikeDifference < minStrikeDifference) {
                    isBetter = true;
                }
            }
            
            if (isBetter) {
                minStrikeDifference = strikeDifference;
                bestOption = option;
                if (preferShortestExpiration) {
                    String symbol = entry.getKey();
                    earliestExpiration = parseExpirationFromSymbol(symbol);
                }
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
        return calculateVolatilityForPeriod(historicalBars, 30, "RV30", context);
    }
    
    /**
     * Calculate IV30 from historical bars
     */
    public double calculateIV30(List<HistoricalBar> historicalBars, Context context) {
        return calculateVolatilityForPeriod(historicalBars, 30, "IV30", context);
    }
    
    /**
     * Calculate term structure slope from historical bars
     */
    public double calculateTermStructureSlope(List<HistoricalBar> historicalBars, Context context) {
        try {
            if (historicalBars.size() < 60) return 0.0;
            
            // Calculate 30-day and 60-day volatility
            double vol30 = calculateVolatilityForPeriod(historicalBars, 30, "TermStructure30", context);
            double vol60 = calculateVolatilityForPeriod(historicalBars, 60, "TermStructure60", context);
            
            return vol60 - vol30;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating term structure slope: " + e.getMessage());
            return 0.0;
        }
    }
    
    // ===== HELPER METHODS FOR CODE REUSE =====
    
    /**
     * Get historical bar for a specific date
     * Helper method to eliminate duplication in earnings move calculations
     */
    private HistoricalBar getHistoricalBarForDate(String ticker, LocalDate targetDate, Context context) {
        return getHistoricalBar(ticker, targetDate, false, context);
    }
    
    /**
     * Get previous trading day bar before a specific date
     * Helper method for earnings move with gaps calculation
     */
    private HistoricalBar getPreviousTradingDayBar(String ticker, LocalDate targetDate, Context context) {
        return getHistoricalBar(ticker, targetDate, true, context);
    }
    
    /**
     * Get historical bar for a specific date or the previous trading day
     * @param ticker Stock ticker symbol
     * @param targetDate Target date for the bar
     * @param getPrevious If true, get the previous trading day before targetDate; if false, get the bar for targetDate
     * @param context Lambda context for logging
     * @return HistoricalBar or null if not found
     */
    private HistoricalBar getHistoricalBar(String ticker, LocalDate targetDate, boolean getPrevious, Context context) {
        try {
            // Get more historical data to cover older earnings dates
            List<HistoricalBar> historicalBars = AlpacaHttpClient.getHistoricalBars(ticker, 365, credentials);
            if (historicalBars == null || historicalBars.isEmpty()) {
                return null;
            }
            
            // Sort by timestamp to ensure chronological order
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            if (getPrevious) {
                // Find the previous trading day's bar
                return historicalBars.stream()
                    .filter(bar -> {
                        try {
                            LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                            return barDate.isBefore(targetDate);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .max((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                    .orElse(null);
            } else {
                // Find the bar for the target date
                return historicalBars.stream()
                    .filter(bar -> {
                        try {
                            LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                            return barDate.equals(targetDate);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
            }
                
        } catch (Exception e) {
            String operation = getPrevious ? "previous trading day bar" : "historical bar";
            context.getLogger().log("Error getting " + operation + " for " + ticker + " on " + targetDate + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate volatility for a specific period from historical bars
     * Helper method to eliminate duplication in volatility calculations
     */
    private double calculateVolatilityForPeriod(List<HistoricalBar> historicalBars, int days, String periodName, Context context) {
        try {
            // Get the most recent N days
            List<HistoricalBar> periodBars = getLastNDays(historicalBars, days);
            
            if (periodBars.size() < 2) return 0.0;
            
            return calculateHistoricalVolatilityFromBars(periodBars);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating " + periodName + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get the last N days from historical bars
     * Helper method to eliminate duplication in period extraction
     */
    private List<HistoricalBar> getLastNDays(List<HistoricalBar> historicalBars, int days) {
        int startIndex = Math.max(historicalBars.size() - days, 0);
        return historicalBars.subList(startIndex, historicalBars.size());
    }
    
    // ===== CONSOLIDATED HELPER METHODS FOR FILTER REUSE =====
    
    /**
     * Get option chain for a specific date range
     * Consolidated method used by multiple filters
     */
    public Map<String, com.trading.common.models.OptionSnapshot> getOptionChainForDateRange(String ticker, LocalDate baseDate, 
                                                                                          int minDays, int maxDays, String optionType, 
                                                                                          com.trading.common.models.AlpacaCredentials credentials, Context context) {
        try {
            LocalDate startDate = baseDate.plusDays(minDays);
            LocalDate endDate = baseDate.plusDays(maxDays);
            context.getLogger().log("DEBUG: Fetching options for " + ticker + " from " + startDate + " to " + endDate + " (" + optionType + ")");
            Map<String, com.trading.common.models.OptionSnapshot> result = com.trading.common.AlpacaHttpClient.getOptionChain(ticker, startDate, endDate, optionType, credentials);
            context.getLogger().log("DEBUG: Options API returned " + (result != null ? result.size() : "null") + " options for " + ticker);
            // Ensure we never return null - return empty map if no data
            return result != null ? result : new java.util.HashMap<>();
        } catch (Exception e) {
            context.getLogger().log("Error getting option chain for " + ticker + " (" + minDays + "-" + maxDays + " days): " + e.getMessage());
            return new java.util.HashMap<>();
        }
    }
    
    /**
     * Get IV for a specific strike from an option chain
     * Consolidated method used by multiple filters
     */
    public double getIVForStrikeFromChain(Map<String, com.trading.common.models.OptionSnapshot> optionChain, double targetStrike, 
                                        String legName, Context context) {
        try {
            if (optionChain.isEmpty()) {
                context.getLogger().log("No " + legName + " options available");
                return 0.0;
            }
            
            // Find option with the target strike
            com.trading.common.models.OptionSnapshot option = com.trading.common.OptionSelectionUtils.findOptionForStrikeInOptionSnapshotChain(optionChain, targetStrike);
            if (option == null) {
                context.getLogger().log("No " + legName + " option found for strike " + targetStrike);
                return 0.0;
            }
            
            double impliedVol = option.getImpliedVol();
            if (!isValidImpliedVolatility(impliedVol, legName, context)) {
                return 0.0;
            }
            
            context.getLogger().log("Found " + legName + " IV: " + String.format("%.3f", impliedVol) + " at strike " + targetStrike);
            return impliedVol;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting IV for " + legName + " strike " + targetStrike + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Find the strike closest to current price
     * Consolidated method used by multiple filters
     */
    public double findClosestStrikeToPrice(Set<Double> strikes, double currentPrice) {
        if (strikes == null || strikes.isEmpty() || currentPrice <= 0) {
            return -1.0;
        }
        
        return strikes.stream()
            .min(java.util.Comparator.comparing(strike -> Math.abs(strike - currentPrice)))
            .orElse(-1.0);
    }
    
    /**
     * Collect all strikes from multiple option chains
     * Consolidated method used by multiple filters
     */
    @SafeVarargs
    public final Set<Double> collectAllStrikesFromChains(Map<String, com.trading.common.models.OptionSnapshot>... chains) {
        Set<Double> allStrikes = new java.util.HashSet<>();
        
        for (Map<String, com.trading.common.models.OptionSnapshot> chain : chains) {
            if (chain != null && !chain.isEmpty()) {
                chain.values().forEach(option -> allStrikes.add(option.getStrike()));
            }
        }
        
        return allStrikes;
    }
    
    /**
     * Validate implied volatility value
     * Consolidated method used by multiple filters
     */
    public boolean isValidImpliedVolatility(double impliedVol, String legName, Context context) {
        if (impliedVol <= 0) {
            context.getLogger().log("Invalid implied volatility for " + legName + " option");
            return false;
        }
        return true;
    }
    
    /**
     * Calculate mid date between start and end dates
     * Consolidated method used by multiple filters
     */
    public LocalDate calculateMidDate(LocalDate startDate, LocalDate endDate) {
        return startDate.plusDays((int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) / 2);
    }
    
    /**
     * Calculate recency weight for earnings data
     * Recent earnings (last 2 years) get weight 2.0, older get weight 1.0
     * Consolidated method used by multiple filters
     */
    public double calculateRecencyWeight(LocalDate earningsDate, LocalDate cutoffDate) {
        if (earningsDate.isAfter(cutoffDate)) {
            return 2.0; // Recent earnings get double weight
        } else {
            return 1.0; // Older earnings get normal weight
        }
    }
    
    /**
     * Get mid price from contract data with bid/ask validation
     * Consolidated method used by multiple filters
     */
    public double getMidPriceFromContract(Map<String, Object> contract) {
        try {
            JsonNode snapshot = (JsonNode) contract.get("snapshot");
            if (snapshot == null || !snapshot.has("quote")) {
                return -1.0;
            }
            
            JsonNode quote = snapshot.get("quote");
            return JsonParsingUtils.getValidatedMidPrice(quote);
        } catch (Exception e) {
            return -1.0;
        }
    }
    
    /**
     * Check if bid/ask spread is valid for trading
     * Consolidated method used by multiple filters
     */
    public boolean isValidBidAskSpread(double bid, double ask, double maxSpreadRatio) {
        return PriceUtils.isValidBidAsk(bid, ask) && PriceUtils.isSpreadAcceptable(bid, ask, maxSpreadRatio);
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
