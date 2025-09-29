package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
// Alpaca API imports
import com.example.AlpacaApiService.HistoricalBar;
import com.example.AlpacaApiService.LatestTrade;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AWS Lambda function for filtering stocks based on volume, volatility, and options data.
 * Processes tickers from EarningsTable and writes filtered results to FilteredTickersTable.
 * 
 * This implementation uses real Alpaca Markets API data for stock prices, volumes, and historical data.
 * Alpaca provides reliable, real-time market data with higher rate limits than Yahoo Finance.
 * Note: Options data (implied volatility) is approximated using historical volatility calculations
 * as Alpaca's free tier focuses on stock data rather than options data.
 */
public class StockFilterLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String EARNINGS_TABLE = System.getenv("EARNINGS_TABLE");
    private static final String FILTERED_TABLE = System.getenv("FILTERED_TABLE");
    
    // Filter thresholds
    private static final double VOLUME_THRESHOLD = Double.parseDouble(
        System.getenv().getOrDefault("VOLUME_THRESHOLD", "1000000"));
    private static final double RATIO_THRESHOLD = Double.parseDouble(
        System.getenv().getOrDefault("RATIO_THRESHOLD", "1.2"));
    private static final double SLOPE_THRESHOLD = Double.parseDouble(
        System.getenv().getOrDefault("SLOPE_THRESHOLD", "-0.00406")); // Negative threshold for backwardation (vol30 > vol60)
    
    // Earnings-specific filter thresholds
    private static final double VOLATILITY_CRUSH_THRESHOLD = Double.parseDouble(
        System.getenv().getOrDefault("VOLATILITY_CRUSH_THRESHOLD", "0.85")); // 15%+ crush required
    private static final double EARNINGS_STABILITY_THRESHOLD = Double.parseDouble(
        System.getenv().getOrDefault("EARNINGS_STABILITY_THRESHOLD", "0.05")); // 5% or less average move
    
    // Options liquidity thresholds
    private static final double MIN_STOCK_PRICE = Double.parseDouble(
        System.getenv().getOrDefault("MIN_STOCK_PRICE", "20.0")); // Minimum stock price for options liquidity (increased to avoid penny stocks)
    private static final double MAX_STOCK_PRICE = Double.parseDouble(
        System.getenv().getOrDefault("MAX_STOCK_PRICE", "1000.0")); // Maximum stock price for options liquidity (increased for very high-priced stocks)
    private static final double MIN_AVERAGE_VOLUME = Double.parseDouble(
        System.getenv().getOrDefault("MIN_AVERAGE_VOLUME", "500000")); // Use same threshold as VOLUME_THRESHOLD for consistency
    
    // Constants for volatility calculations
    private static final int TRADING_DAYS_PER_YEAR = 252;
    
    private final DynamoDbClient dynamoDbClient;
    private final ExecutorService executorService;
    
    private final AlpacaApiService alpacaApiService;
    
    public StockFilterLambda() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.executorService = Executors.newFixedThreadPool(10); // Limit concurrent API calls
        
        // Initialize Alpaca API service
        String apiKey = System.getenv("ALPACA_API_KEY");
        String secretKey = System.getenv("ALPACA_SECRET_KEY");
        boolean usePaperTrading = Boolean.parseBoolean(System.getenv().getOrDefault("ALPACA_PAPER_TRADING", "true"));
        
        this.alpacaApiService = new AlpacaApiService(apiKey, secretKey, usePaperTrading);
    }
    
    /**
     * Cleanup method to properly shutdown thread pool
     * This prevents idle threads from accumulating in AWS Lambda
     */
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting StockFilterLambda execution");
            
            // Extract scanDate from input, default to today
            String scanDate = extractScanDate(input);
            context.getLogger().log("Processing scanDate: " + scanDate);
            
            // Query tickers from EarningsTable
            List<String> tickers = queryTickersFromEarningsTable(scanDate, context);
            context.getLogger().log("Found " + tickers.size() + " tickers to process");
            
            if (tickers.isEmpty()) {
                return TradingErrorHandler.createSkippedResponse("No tickers found for scanDate: " + scanDate, Map.of("tickers_processed", 0));
            }
            
            // Process tickers in parallel with rate limiting
            List<CompletableFuture<Map<String, Object>>> futures = tickers.stream()
                .map(ticker -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return evaluateStockRecommendation(ticker, context);
                    } catch (Exception e) {
                        context.getLogger().log("ERROR processing ticker " + ticker + ": " + e.getMessage());
                        // Return error marker instead of null to track failures
                        Map<String, Object> errorResult = new HashMap<>();
                        errorResult.put("ticker", ticker);
                        errorResult.put("status", "ERROR");
                        errorResult.put("error", e.getMessage());
                        return errorResult;
                    }
                }, executorService))
                .collect(Collectors.toList());
            
            // Wait for all futures to complete and collect results
            List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            // Separate successful results from errors for better logging
            List<Map<String, Object>> successfulResults = results.stream()
                .filter(result -> !"ERROR".equals(result.get("status")))
                .collect(Collectors.toList());
            
            List<Map<String, Object>> errorResults = results.stream()
                .filter(result -> "ERROR".equals(result.get("status")))
                .collect(Collectors.toList());
            
            if (!errorResults.isEmpty()) {
                context.getLogger().log("ERRORS encountered for " + errorResults.size() + " tickers: " + 
                    errorResults.stream()
                        .map(r -> r.get("ticker") + " (" + r.get("error") + ")")
                        .collect(Collectors.joining(", ")));
            }
            
            // Write results to FilteredTickersTable (only successful results)
            int writtenCount = writeResultsToFilteredTable(scanDate, successfulResults, context);
            
            context.getLogger().log("Successfully processed " + successfulResults.size() + " tickers, wrote " + writtenCount + " to filtered table");
            
            return TradingErrorHandler.createSuccessResponse("Processing completed", Map.of(
                "tickers_processed", successfulResults.size(),
                "tickers_written", writtenCount,
                "errors_encountered", errorResults.size()
            ));
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "StockFilterLambda");
        } finally {
            // Cleanup thread pool to prevent idle threads from accumulating
            cleanup();
        }
    }
    
    private String extractScanDate(Map<String, Object> input) {
        Object scanDateObj = input.get("scanDate");
        if (scanDateObj != null) {
            return scanDateObj.toString();
        }
        // Default to today in America/New_York timezone
        return TradingCommonUtils.getCurrentDateString();
    }
    
    private List<String> queryTickersFromEarningsTable(String scanDate, Context context) {
        try {
            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(EARNINGS_TABLE)
                .keyConditionExpression("scanDate = :scanDate")
                .expressionAttributeValues(Map.of(":scanDate", AttributeValue.builder().s(scanDate).build()))
                .build();
            
            QueryResponse response = dynamoDbClient.query(queryRequest);
            
            return response.items().stream()
                .map(item -> item.get("ticker").s())
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            context.getLogger().log("Error querying EarningsTable: " + e.getMessage());
            throw new RuntimeException("Failed to query tickers from EarningsTable", e);
        }
    }
    
    private Map<String, Object> evaluateStockRecommendation(String ticker, Context context) {
        try {
            context.getLogger().log("Processing ticker: " + ticker);
            
            // Get real stock data from Alpaca API
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No data available for ticker: " + ticker);
                return null;
            }
            
            // Use the real data directly from the stock data object
            double avgVolume = stockData.getAverageVolume();
            double rv30 = stockData.getRv30();
            double iv30 = stockData.getIv30();
            double termSlope = stockData.getTermStructureSlope();
            
            // Calculate IV30/RV30 ratio
            double iv30Rv30Ratio = (rv30 > 0) ? iv30 / rv30 : 0.0;
            
            // Evaluate with filters
            Map<String, Object> recommendation = evaluateFilters(avgVolume, iv30Rv30Ratio, termSlope, ticker, context);
            
            if (recommendation != null) {
                recommendation.put("ticker", ticker);
                context.getLogger().log("Ticker " + ticker + " - Volume: " + avgVolume + 
                    ", IV30/RV30: " + iv30Rv30Ratio + ", Slope: " + termSlope + 
                    ", Status: " + recommendation.get("status"));
            }
            
            return recommendation;
            
        } catch (Exception e) {
            context.getLogger().log("Error computing recommendation for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    // ===== EARNINGS-SPECIFIC FILTER METHODS =====
    
    /**
     * Filter 1: Historical Earnings Volatility Crush Pattern
     * Checks if the stock historically experiences volatility crush after earnings
     */
    private boolean hasHistoricalVolatilityCrush(String ticker, Context context) {
        try {
            List<EarningsData> earningsData = getHistoricalEarningsData(ticker, context);
            if (earningsData.isEmpty()) {
                context.getLogger().log("No historical earnings data for " + ticker);
                return false;
            }
            
            int crushCount = 0;
            int totalEarnings = 0;
            
            for (EarningsData earning : earningsData) {
                try {
                    LocalDate earningsDate = earning.getEarningsDate();
                    
                    // Get IV30 for 5 days before earnings
                    double preEarningsIV30 = calculateIV30ForPeriod(ticker, earningsDate.minusDays(7), earningsDate.minusDays(1), context);
                    
                    // Get IV30 for 5 days after earnings
                    double postEarningsIV30 = calculateIV30ForPeriod(ticker, earningsDate.plusDays(1), earningsDate.plusDays(7), context);
                    
                    if (preEarningsIV30 > 0 && postEarningsIV30 > 0) {
                        double crushRatio = postEarningsIV30 / preEarningsIV30;
                        if (crushRatio < VOLATILITY_CRUSH_THRESHOLD) {
                            crushCount++;
                        }
                        totalEarnings++;
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            // PASS if at least 70% of earnings show volatility crush
            boolean hasCrush = totalEarnings > 0 && (double) crushCount / totalEarnings >= 0.70;
            context.getLogger().log(ticker + " volatility crush: " + crushCount + "/" + totalEarnings + " = " + hasCrush);
            return hasCrush;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking volatility crush for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Filter 2: Historical Earnings Stability
     * Checks if the stock historically has small moves after earnings
     */
    private boolean hasHistoricalEarningsStability(String ticker, Context context) {
        try {
            List<EarningsData> earningsData = getHistoricalEarningsData(ticker, context);
            if (earningsData.isEmpty()) {
                context.getLogger().log("No historical earnings data for " + ticker);
                return false;
            }
            
            List<Double> earningsMoves = new ArrayList<>();
            
            for (EarningsData earning : earningsData) {
                try {
                    LocalDate earningsDate = earning.getEarningsDate();
                    
                    // Get price data for earnings day
                    double earningsDayMove = calculateEarningsDayMove(ticker, earningsDate, context);
                    if (earningsDayMove >= 0) {
                        earningsMoves.add(earningsDayMove);
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error calculating earnings move for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            if (earningsMoves.isEmpty()) {
                return false;
            }
            
            // Calculate average earnings move
            double avgEarningsMove = earningsMoves.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            // PASS if average move is 5% or less
            boolean isStable = avgEarningsMove <= EARNINGS_STABILITY_THRESHOLD;
            context.getLogger().log(ticker + " earnings stability: avg move " + String.format("%.2f%%", avgEarningsMove * 100) + " = " + isStable);
            return isStable;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking earnings stability for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Filter 3: Options Liquidity
     * Checks if there's sufficient options liquidity for calendar spread trading
     */
    private boolean hasOptionsLiquidity(String ticker, Context context) {
        try {
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No stock data available for " + ticker);
                return false;
            }
            
            // Get current stock price
            double currentPrice = getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) {
                context.getLogger().log("Invalid stock price for " + ticker);
                return false;
            }
            
            // Check 1: Stock price range (optimal for options trading)
            boolean hasReasonablePrice = currentPrice >= MIN_STOCK_PRICE && currentPrice <= MAX_STOCK_PRICE;
            
            // Check 2: Average daily volume (proxy for overall liquidity)
            boolean hasSufficientVolume = stockData.getAverageVolume() >= MIN_AVERAGE_VOLUME;
            
            // Check 3: Options data availability (if we can get it)
            boolean hasOptionsData = checkOptionsDataAvailability(ticker, context);
            
            // Check 4: Market cap and institutional interest (proxy for options activity)
            boolean hasInstitutionalInterest = checkInstitutionalInterest(ticker, currentPrice, stockData.getAverageVolume(), context);
            
            // All checks must pass for options liquidity
            boolean hasLiquidity = hasReasonablePrice && hasSufficientVolume && (hasOptionsData || hasInstitutionalInterest);
            
            context.getLogger().log(ticker + " options liquidity check: " +
                "price=" + String.format("%.2f", currentPrice) + " (" + hasReasonablePrice + ") " +
                "volume=" + String.format("%.0f", stockData.getAverageVolume()) + " (" + hasSufficientVolume + ") " +
                "options=" + hasOptionsData + " institutional=" + hasInstitutionalInterest + 
                " = " + hasLiquidity);
            
            return hasLiquidity;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking options liquidity for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if options data is available for the stock
     * This is a simplified check - in production you'd query actual options data
     */
    private boolean checkOptionsDataAvailability(String ticker, Context context) {
        try {
            // For now, we'll use a heuristic based on stock characteristics
            // In a real implementation, you would:
            // 1. Query options chain data from Alpaca or another provider
            // 2. Check if options exist for the stock
            // 3. Verify there's recent options activity
            
            // Heuristic: Stocks with good volume and reasonable price typically have options
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) return false;
            
            double currentPrice = getCurrentStockPrice(ticker, context);
            if (currentPrice <= 0) return false;
            
            // Basic heuristics for options availability
            boolean hasGoodVolume = stockData.getAverageVolume() >= MIN_AVERAGE_VOLUME;
            boolean hasReasonablePrice = currentPrice >= MIN_STOCK_PRICE && currentPrice <= MAX_STOCK_PRICE;
            boolean isNotPennyStock = currentPrice >= 5.0; // Avoid penny stocks
            
            return hasGoodVolume && hasReasonablePrice && isNotPennyStock;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking options data availability for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if stock has institutional interest (proxy for options activity)
     */
    private boolean checkInstitutionalInterest(String ticker, double currentPrice, double averageVolume, Context context) {
        try {
            // Heuristic: Calculate market cap and volume-based metrics
            // This is a simplified approach - in production you'd use actual institutional data
            
            // Estimate market cap (this is rough - you'd need shares outstanding for accurate calculation)
            // For now, we'll use volume and price as proxies
            
            // High volume + reasonable price = likely institutional interest
            // For very high-priced stocks (>$500), lower volume requirement
            double volumeMultiplier = currentPrice > 500.0 ? 0.6 : 1.2; // 0.6x for high-priced, 1.2x for others (balanced)
            boolean hasHighVolume = averageVolume >= MIN_AVERAGE_VOLUME * volumeMultiplier;
            boolean hasReasonablePrice = currentPrice >= MIN_STOCK_PRICE && currentPrice <= MAX_STOCK_PRICE;
            
            // Additional check: Price stability (institutional stocks tend to be more stable)
            boolean hasPriceStability = checkPriceStability(ticker, currentPrice, context);
            
            return hasHighVolume && hasReasonablePrice && hasPriceStability;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking institutional interest for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check price stability (institutional stocks tend to be more stable)
     */
    private boolean checkPriceStability(String ticker, double currentPrice, Context context) {
        try {
            // Get recent price data to check stability
            List<HistoricalBar> recentBars = alpacaApiService.getHistoricalBars(ticker, 10);
            if (recentBars == null || recentBars.size() < 5) {
                return false;
            }
            
            // Calculate price volatility over last 10 days
            List<Double> dailyReturns = new ArrayList<>();
            for (int i = 1; i < recentBars.size(); i++) {
                double previousClose = recentBars.get(i-1).getClose();
                double currentClose = recentBars.get(i).getClose();
                
                if (previousClose > 0) {
                    double dailyReturn = Math.abs(Math.log(currentClose / previousClose));
                    dailyReturns.add(dailyReturn);
                }
            }
            
            if (dailyReturns.isEmpty()) {
                return false;
            }
            
            // Calculate average daily return
            double avgDailyReturn = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            // Convert to annualized volatility
            double annualizedVol = avgDailyReturn * Math.sqrt(252);
            
            // PASS if volatility is reasonable (not too high, not too low)
            // Too high = too risky, too low = no options activity
            // For high-priced stocks (>$200), lower volatility threshold as they still have good options liquidity
            double minVolThreshold = currentPrice > 200.0 ? 0.08 : 0.15; // 8% for high-priced stocks, 15% for others
            boolean isStable = annualizedVol >= minVolThreshold && annualizedVol <= 0.80; // 8%-80% or 15%-80% annualized vol
            
            context.getLogger().log(ticker + " price stability: annualized vol=" + 
                String.format("%.2f%%", annualizedVol * 100) + " = " + isStable);
            
            return isStable;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking price stability for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    // ===== HELPER METHODS FOR EARNINGS FILTERS =====
    
    /**
     * Get historical earnings data from Finnhub API using earnings calendar
     */
    private List<EarningsData> getHistoricalEarningsData(String ticker, Context context) {
        try {
            // Get Finnhub API key from environment or secrets manager
            String finnhubApiKey = System.getenv("FINNHUB_API_KEY");
            if (finnhubApiKey == null) {
                // Try to get from secrets manager
                try {
                    finnhubApiKey = TradingCommonUtils.getAlpacaCredentials("finnhub-secret").getApiKey();
                } catch (Exception e) {
                    context.getLogger().log("Could not retrieve Finnhub API key: " + e.getMessage());
                    return new ArrayList<>();
                }
            }
            
            // Get historical earnings data using the stock earnings endpoint
            String earningsUrl = "https://finnhub.io/api/v1/stock/earnings?symbol=" + ticker + 
                "&token=" + finnhubApiKey;
            
            context.getLogger().log("Fetching earnings data from: " + earningsUrl);
            
            String earningsResponse = TradingCommonUtils.makeHttpRequest(earningsUrl, "", "", "GET", null);
            JsonNode earningsArray = TradingCommonUtils.parseJson(earningsResponse);
            
            List<EarningsData> earningsList = new ArrayList<>();
            for (JsonNode earning : earningsArray) {
                try {
                    String period = earning.path("period").asText();
                    double actual = earning.path("actual").asDouble();
                    double estimate = earning.path("estimate").asDouble();
                    double surprise = earning.path("surprise").asDouble();
                    double surprisePercent = earning.path("surprisePercent").asDouble();
                    
                    if (!period.isEmpty() && actual != 0 && estimate != 0) {
                        // Parse the period date (format: "2024-12-31")
                        LocalDate earningsDate = LocalDate.parse(period);
                        
                        earningsList.add(new EarningsData(earningsDate, actual, estimate, surprise, surprisePercent));
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error parsing earnings data: " + e.getMessage());
                }
            }
            
            // Sort by date (most recent first)
            earningsList.sort((a, b) -> b.getEarningsDate().compareTo(a.getEarningsDate()));
            context.getLogger().log("Found " + earningsList.size() + " historical earnings records for " + ticker);
            return earningsList;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting historical earnings data for " + ticker + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Calculate IV30 for a specific period
     */
    private double calculateIV30ForPeriod(String ticker, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Calculate days between start and end date
            int daysBetween = (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
            if (daysBetween <= 0) {
                return 0.0;
            }
            
            // Get historical bars for the period (get more days to ensure we have data)
            List<HistoricalBar> allBars = alpacaApiService.getHistoricalBars(ticker, daysBetween + 10);
            if (allBars == null || allBars.isEmpty()) {
                return 0.0;
            }
            
            // Filter bars to the specific period
            List<HistoricalBar> periodBars = allBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10));
                        return !barDate.isBefore(startDate) && !barDate.isAfter(endDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
            
            if (periodBars.isEmpty()) {
                return 0.0;
            }
            
            // Calculate historical volatility for the period
            return calculateHistoricalVolatilityFromBars(periodBars);
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV30 for period " + startDate + " to " + endDate + ": " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate price move on earnings day
     */
    private double calculateEarningsDayMove(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Get historical bars for earnings day (get 5 days to ensure we have the earnings day)
            List<HistoricalBar> bars = alpacaApiService.getHistoricalBars(ticker, 5);
            if (bars == null || bars.isEmpty()) {
                return -1.0; // Indicate no data
            }
            
            // Find the bar for earnings day
            HistoricalBar earningsBar = bars.stream()
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
     * Get current stock price
     */
    private double getCurrentStockPrice(String ticker, Context context) {
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

    
    
    private Map<String, Object> evaluateFilters(double avgVolume, double iv30Rv30Ratio, double termSlope, String ticker, Context context) {
        // Original quality filters
        boolean volumeFilterPassed = avgVolume > VOLUME_THRESHOLD;
        boolean ratioFilterPassed = iv30Rv30Ratio > RATIO_THRESHOLD;
        boolean slopeFilterPassed = termSlope <= SLOPE_THRESHOLD; // Want negative slope (backwardation: vol30 > vol60)
        
        // New earnings-specific filters
        boolean volatilityCrushPassed = hasHistoricalVolatilityCrush(ticker, context);
        boolean earningsStabilityPassed = hasHistoricalEarningsStability(ticker, context);
        boolean optionsLiquidityPassed = hasOptionsLiquidity(ticker, context);
        
        // ALL 6 filters must pass for recommendation
        boolean allFiltersPassed = volumeFilterPassed && ratioFilterPassed && slopeFilterPassed &&
                                 volatilityCrushPassed && earningsStabilityPassed && optionsLiquidityPassed;
        
        if (!allFiltersPassed) {
            return null; // Not recommended
        }
        
        // Calculate recommendation score (all 6 filters passed)
        int recommendationScore = 6;
        String recommendationStatus = "Recommended";
        
        Map<String, Object> recommendationResult = new HashMap<>();
        recommendationResult.put("recommendationScore", recommendationScore);
        recommendationResult.put("status", recommendationStatus);
        recommendationResult.put("avgVolume", avgVolume);
        recommendationResult.put("iv30Rv30Ratio", iv30Rv30Ratio);
        recommendationResult.put("termSlope", termSlope);
        recommendationResult.put("volatilityCrushPassed", volatilityCrushPassed);
        recommendationResult.put("earningsStabilityPassed", earningsStabilityPassed);
        recommendationResult.put("optionsLiquidityPassed", optionsLiquidityPassed);
        
        return recommendationResult;
    }
    
    private int writeResultsToFilteredTable(String scanDate, List<Map<String, Object>> recommendationResults, Context context) {
        int successfullyWrittenCount = 0;
        
        for (Map<String, Object> recommendation : recommendationResults) {
            try {
                String recommendationStatus = (String) recommendation.get("status");
                
                // Only write stocks with "Recommended" status
                if (!"Recommended".equals(recommendationStatus)) {
                    continue;
                }
                
                String tickerSymbol = (String) recommendation.get("ticker");
                Integer recommendationScore = (Integer) recommendation.get("recommendationScore");
                
                Map<String, AttributeValue> dynamoDbItem = new HashMap<>();
                dynamoDbItem.put("scanDate", AttributeValue.builder().s(scanDate).build());
                dynamoDbItem.put("ticker", AttributeValue.builder().s(tickerSymbol).build());
                dynamoDbItem.put("recommendationScore", AttributeValue.builder().n(recommendationScore.toString()).build());
                
                PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(FILTERED_TABLE)
                    .item(dynamoDbItem)
                    .build();
                
                dynamoDbClient.putItem(putItemRequest);
                successfullyWrittenCount++;
                
            } catch (Exception e) {
                context.getLogger().log("Error writing result to FilteredTickersTable: " + e.getMessage());
            }
        }
        
        return successfullyWrittenCount;
    }
    
    
    // Real Alpaca API data methods
    private StockData getRealStockData(String ticker, Context context) {
        try {
            // Get latest trade for current price and volume
            LatestTrade latestTrade = alpacaApiService.getLatestTrade(ticker);
            if (latestTrade == null) {
                context.getLogger().log("No trade data available for: " + ticker);
                return null;
            }
            
            // Get historical data for calculations
            List<HistoricalBar> historicalBars = alpacaApiService.getHistoricalBars(ticker, 90); // 90 days
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical data available for: " + ticker);
                return null;
            }
            
            // Sort historical bars by timestamp to ensure chronological order (oldest first)
            // This is critical for correct "most recent 30 days" calculations
            historicalBars.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            
            // Log data order for debugging
            if (historicalBars.size() > 0) {
                context.getLogger().log("Data order verification for " + ticker + 
                    ": first=" + historicalBars.get(0).getTimestamp() + 
                    ", last=" + historicalBars.get(historicalBars.size()-1).getTimestamp());
            }
            
            // Calculate average volume from historical data
            double averageVolume = calculateAverageVolume(historicalBars);
            
            // Calculate RV30 using historical volatility
            double rv30 = calculateRV30(historicalBars, context);
            
            // Calculate IV30 (approximated as historical volatility)
            double iv30 = calculateIV30(historicalBars, context);
            
            // Calculate term structure slope
            double termSlope = calculateTermStructureSlope(historicalBars, context);
            
            return new StockData(ticker, averageVolume, rv30, iv30, termSlope);
            
        } catch (Exception e) {
            context.getLogger().log("Error getting real stock data for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    private double calculateAverageVolume(List<HistoricalBar> historicalBars) {
        if (historicalBars.isEmpty()) return 0.0;
        
        long totalVolume = historicalBars.stream()
            .mapToLong(HistoricalBar::getVolume)
            .sum();
        
        return (double) totalVolume / historicalBars.size();
    }
    
    private double calculateRV30(List<HistoricalBar> historicalBars, Context context) {
        try {
            // Get the most recent 30 days (equivalent to Python's [-30:])
            // Data is guaranteed to be in chronological order (oldest first) after sorting
            int startIndex = Math.max(historicalBars.size() - 30, 0);
            List<HistoricalBar> last30Days = historicalBars.subList(startIndex, historicalBars.size());
            
            if (last30Days.size() < 2) return 0.0;
            
            return calculateHistoricalVolatilityFromBars(last30Days);
        } catch (Exception e) {
            context.getLogger().log("Error calculating RV30: " + e.getMessage());
            return 0.0;
        }
    }
    
    private double calculateIV30(List<HistoricalBar> historicalBars, Context context) {
        try {
            // NOTE: This is currently approximated using historical volatility (RV30)
            // In production, this should use actual implied volatility from options data
            // For now, we're using historical volatility as a placeholder
            // TODO: Integrate real options IV data when available
            
            // Get the most recent 30 days (equivalent to Python's [-30:])
            // Data is guaranteed to be in chronological order (oldest first) after sorting
            int startIndex = Math.max(historicalBars.size() - 30, 0);
            List<HistoricalBar> last30Days = historicalBars.subList(startIndex, historicalBars.size());
            
            return calculateHistoricalVolatilityFromBars(last30Days);
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV30: " + e.getMessage());
            return 0.0;
        }
    }
    
    private double calculateTermStructureSlope(List<HistoricalBar> historicalBars, Context context) {
        try {
            // Get the most recent 30 and 60 days (equivalent to Python's [-30:] and [-60:])
            // Data is guaranteed to be in chronological order (oldest first) after sorting
            int startIndex30 = Math.max(historicalBars.size() - 30, 0);
            int startIndex60 = Math.max(historicalBars.size() - 60, 0);
            
            List<HistoricalBar> last30Days = historicalBars.subList(startIndex30, historicalBars.size());
            List<HistoricalBar> last60Days = historicalBars.subList(startIndex60, historicalBars.size());
            
            double vol30 = calculateHistoricalVolatilityFromBars(last30Days);
            double vol60 = calculateHistoricalVolatilityFromBars(last60Days);
            
            // Term structure slope: negative when shorter-term vol > longer-term vol (backwardation)
            // Positive when longer-term vol > shorter-term vol (contango)
            // Return the raw difference for meaningful comparison
            double termSlope = vol60 - vol30;
            
            context.getLogger().log("Term structure: vol30=" + String.format("%.4f", vol30) + 
                ", vol60=" + String.format("%.4f", vol60) + 
                ", slope=" + String.format("%.6f", termSlope) + 
                " (negative=backwardation, positive=contango)");
            
            return termSlope;
        } catch (Exception e) {
            context.getLogger().log("Error calculating term structure slope: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate historical volatility from HistoricalBar objects
     * This is the primary volatility calculation method used throughout the code
     * 
     * Formula: sqrt(variance of log returns) * sqrt(252)
     * Where log returns = ln(close_t / close_t-1)
     * 
     * @param historicalBars List of historical price bars
     * @return Annualized historical volatility
     */
    private double calculateHistoricalVolatilityFromBars(List<HistoricalBar> historicalBars) {
        if (historicalBars.size() < 2) return 0.0;
        
        List<Double> dailyReturns = new ArrayList<>();
        for (int i = 1; i < historicalBars.size(); i++) {
            double previousClose = historicalBars.get(i-1).getClose();
            double currentClose = historicalBars.get(i).getClose();
            
            if (previousClose > 0) {
                double dailyReturn = Math.log(currentClose / previousClose);
                dailyReturns.add(dailyReturn);
            }
        }
        
        if (dailyReturns.isEmpty()) return 0.0;
        
        // Calculate variance of daily returns
        double meanReturn = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = dailyReturns.stream()
            .mapToDouble(returnValue -> Math.pow(returnValue - meanReturn, 2))
            .average()
            .orElse(0.0);
        
        // Annualize by multiplying by sqrt(252 trading days per year)
        return Math.sqrt(variance) * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }
    
    // Data classes for stock information
    public static class StockData {
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
    
    
    public static class EarningsData {
        private final LocalDate earningsDate;
        private final double actual;
        private final double estimate;
        private final double surprise;
        private final double surprisePercent;
        
        public EarningsData(LocalDate earningsDate, double actual, double estimate, double surprise, double surprisePercent) {
            this.earningsDate = earningsDate;
            this.actual = actual;
            this.estimate = estimate;
            this.surprise = surprise;
            this.surprisePercent = surprisePercent;
        }
        
        public LocalDate getEarningsDate() { return earningsDate; }
        public double getActual() { return actual; }
        public double getEstimate() { return estimate; }
        public double getSurprise() { return surprise; }
        public double getSurprisePercent() { return surprisePercent; }
    }
}