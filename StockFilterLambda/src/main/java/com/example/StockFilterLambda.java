package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.time.LocalDate;

/**
 * AWS Lambda function for filtering stocks based on volume, volatility, and options data.
 * Refactored version with separated filter classes for better maintainability.
 */
public class StockFilterLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String EARNINGS_TABLE = System.getenv("EARNINGS_TABLE");
    private static final String FILTERED_TABLE = System.getenv("FILTERED_TABLE");
    
    // Thresholds
    private static final double VOLUME_THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("VOLUME_THRESHOLD", "1000000"));
    private static final int MIN_SCORE_THRESHOLD = Integer.parseInt(System.getenv().getOrDefault("MIN_SCORE_THRESHOLD", "7"));
    
    // Services
    private final AlpacaApiService alpacaApiService;
    private final StockFilterCommonUtils commonUtils;
    private final LiquidityFilter liquidityFilter;
    private final IVRatioFilter ivRatioFilter;
    private final TermStructureFilter termStructureFilter;
    private final VolatilityCrushFilter volatilityCrushFilter;
    private final EarningsStabilityFilter earningsStabilityFilter;
    private final ExecutionSpreadFilter executionSpreadFilter;
    
    public StockFilterLambda() {
        String apiKey = System.getenv("ALPACA_API_KEY");
        String secretKey = System.getenv("ALPACA_SECRET_KEY");
        boolean usePaperTrading = Boolean.parseBoolean(System.getenv().getOrDefault("ALPACA_PAPER_TRADING", "true"));
        
        this.alpacaApiService = new AlpacaApiService(apiKey, secretKey, usePaperTrading);
        this.commonUtils = new StockFilterCommonUtils(alpacaApiService);
        this.liquidityFilter = new LiquidityFilter(alpacaApiService, commonUtils);
        this.ivRatioFilter = new IVRatioFilter(alpacaApiService, commonUtils);
        this.termStructureFilter = new TermStructureFilter(alpacaApiService, commonUtils);
        this.volatilityCrushFilter = new VolatilityCrushFilter(alpacaApiService, commonUtils);
        this.earningsStabilityFilter = new EarningsStabilityFilter(alpacaApiService, commonUtils);
        this.executionSpreadFilter = new ExecutionSpreadFilter(alpacaApiService, commonUtils);
    }
    
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            final String scanDate = (String) input.getOrDefault("scanDate", LocalDate.now().toString());
            
            context.getLogger().log("Starting stock filter scan for date: " + scanDate);
            
            // Query tickers from earnings table
            List<String> tickers = queryTickersFromEarningsTable(scanDate, context);
            if (tickers.isEmpty()) {
                context.getLogger().log("No tickers found for scan date: " + scanDate);
                return "No tickers to process";
            }
            
            context.getLogger().log("Found " + tickers.size() + " tickers to process");
            
            // Process tickers in parallel
            ExecutorService executor = Executors.newFixedThreadPool(10);
            List<CompletableFuture<Map<String, Object>>> futures = tickers.stream()
                .map(ticker -> CompletableFuture.supplyAsync(() -> 
                    evaluateStockRecommendation(ticker, scanDate, context), executor))
                .collect(Collectors.toList());
            
            // Wait for all evaluations to complete
            List<Map<String, Object>> recommendationResults = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            executor.shutdown();
            
            context.getLogger().log("Completed evaluation of " + tickers.size() + " tickers, " + 
                recommendationResults.size() + " recommendations");
            
            // Write results to filtered table
            int writtenCount = writeResultsToFilteredTable(scanDate, recommendationResults, context);
            
            return "Successfully processed " + tickers.size() + " tickers, " + 
                recommendationResults.size() + " recommendations, " + writtenCount + " written to table";
            
        } catch (Exception e) {
            context.getLogger().log("Error in stock filter lambda: " + e.getMessage());
            TradingErrorHandler.handleError(e, context, "StockFilterLambda");
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Query tickers from earnings table for the given scan date
     */
    private List<String> queryTickersFromEarningsTable(String scanDate, Context context) {
        try {
            DynamoDbClient dynamoDbClient = DynamoDbClient.create();
            
            ScanRequest scanRequest = ScanRequest.builder()
                .tableName(EARNINGS_TABLE)
                .filterExpression("scanDate = :scanDate")
                .expressionAttributeValues(Map.of(
                    ":scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();
            
            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            
            return scanResponse.items().stream()
                .map(item -> item.get("ticker").s())
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            context.getLogger().log("Error querying earnings table: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Evaluate a single stock recommendation using all filters
     */
    private Map<String, Object> evaluateStockRecommendation(String ticker, String scanDate, Context context) {
        try {
            // Get earnings date for the ticker
            LocalDate earningsDate = getEarningsDateForTicker(ticker, scanDate, context);
            if (earningsDate == null) {
                context.getLogger().log("No earnings date found for " + ticker);
                return null;
            }
            
            // Get stock data for volume check
            StockData stockData = getRealStockData(ticker, context);
            if (stockData == null) {
                context.getLogger().log("No stock data available for " + ticker);
                return null;
            }
            
            double avgVolume = stockData.getAverageVolume();
            
            // Evaluate all filters
            Map<String, Object> filterResults = evaluateFilters(avgVolume, ticker, earningsDate, context);
            if (filterResults == null) {
                return null;
            }
            
            // Add ticker and scan date to results
            filterResults.put("ticker", ticker);
            filterResults.put("scanDate", scanDate);
            
            return filterResults;
            
        } catch (Exception e) {
            context.getLogger().log("Error evaluating stock recommendation for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get earnings date for a ticker
     */
    private LocalDate getEarningsDateForTicker(String ticker, String scanDate, Context context) {
        try {
            DynamoDbClient dynamoDbClient = DynamoDbClient.create();
            
            GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(EARNINGS_TABLE)
                .key(Map.of(
                    "ticker", AttributeValue.builder().s(ticker).build(),
                    "scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();
            
            GetItemResponse getItemResponse = dynamoDbClient.getItem(getItemRequest);
            
            if (getItemResponse.item().containsKey("earningsDate")) {
                String earningsDateStr = getItemResponse.item().get("earningsDate").s();
                return LocalDate.parse(earningsDateStr);
            }
            
            return null;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting earnings date for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Evaluate all filters with weighted scoring system
     */
    private Map<String, Object> evaluateFilters(double avgVolume, String ticker, LocalDate earningsDate, Context context) {
        // Define filters with their weights
        FilterResult[] filters = {
            evaluateLiquidityFilter(ticker, avgVolume, context),
            evaluateFilter("IV_Ratio", ivRatioFilter.hasIVRatio(ticker, earningsDate, context), 2, context, ticker),
            evaluateFilter("Term_Structure", termStructureFilter.hasTermStructureBackwardation(ticker, earningsDate, context), 1, context, ticker),
            evaluateFilter("Vol_Crush", volatilityCrushFilter.hasHistoricalVolatilityCrush(ticker, context), 1, context, ticker),
            earningsStabilityFilter.hasHistoricalEarningsStability(ticker, earningsDate, context),
            evaluateFilter("Execution_Spread", executionSpreadFilter.hasExecutionSpreadFeasibility(ticker, earningsDate, context), 3, context, ticker)
        };
        
        // Calculate total score
        int totalScore = Arrays.stream(filters).mapToInt(FilterResult::getScore).sum();
        
        // Check if passes threshold
        if (totalScore < MIN_SCORE_THRESHOLD) {
            context.getLogger().log(ticker + " failed scoring: " + totalScore + "/" + MIN_SCORE_THRESHOLD);
            return null;
        }
        
        // Build result
        Map<String, Object> result = new HashMap<>();
        result.put("recommendationScore", totalScore);
        result.put("status", "Recommended");
        result.put("avgVolume", avgVolume);
        result.put("earningsDate", earningsDate.toString());
        
        // Add filter results
        for (FilterResult filter : filters) {
            result.put(filter.getName().toLowerCase() + "Score", filter.getScore());
            result.put(filter.getName().toLowerCase() + "Passed", filter.isPassed());
        }
        
        context.getLogger().log(ticker + " passed scoring: " + totalScore + "/" + MIN_SCORE_THRESHOLD);
        return result;
    }
    
    /**
     * Special handling for liquidity filter (partial credit)
     */
    private FilterResult evaluateLiquidityFilter(String ticker, double avgVolume, Context context) {
        boolean volumePassed = avgVolume >= VOLUME_THRESHOLD;
        boolean optionsLiquidityPassed = liquidityFilter.hasOptionsLiquidity(ticker, context);
        int score = optionsLiquidityPassed ? 2 : (volumePassed ? 1 : 0);
        context.getLogger().log(ticker + " Liquidity filter: volume=" + volumePassed + 
            ", options=" + optionsLiquidityPassed + " (score: " + score + ")");
        return new FilterResult("Liquidity", score > 0, score);
    }
    
    /**
     * Evaluate a filter and return standardized result
     */
    private FilterResult evaluateFilter(String name, boolean passed, int weight, Context context, String ticker) {
        int score = passed ? weight : 0;
        return new FilterResult(name, passed, score);
    }
    
    /**
     * Write results to filtered table
     */
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
                Double avgVolume = (Double) recommendation.get("avgVolume");
                String earningsDate = (String) recommendation.get("earningsDate");
                
                // Build DynamoDB item
                Map<String, AttributeValue> dynamoDbItem = new HashMap<>();
                dynamoDbItem.put("ticker", AttributeValue.builder().s(tickerSymbol).build());
                dynamoDbItem.put("scanDate", AttributeValue.builder().s(scanDate).build());
                dynamoDbItem.put("recommendationScore", AttributeValue.builder().n(String.valueOf(recommendationScore)).build());
                dynamoDbItem.put("status", AttributeValue.builder().s(recommendationStatus).build());
                dynamoDbItem.put("avgVolume", AttributeValue.builder().n(String.valueOf(avgVolume)).build());
                dynamoDbItem.put("earningsDate", AttributeValue.builder().s(earningsDate).build());
                
                // Add filter results
                for (String key : recommendation.keySet()) {
                    if (key.endsWith("Score") || key.endsWith("Passed")) {
                        Object value = recommendation.get(key);
                        if (value instanceof Integer) {
                            dynamoDbItem.put(key, AttributeValue.builder().n(String.valueOf(value)).build());
                        } else if (value instanceof Boolean) {
                            dynamoDbItem.put(key, AttributeValue.builder().bool((Boolean) value).build());
                        }
                    }
                }
                
                // Write to DynamoDB
                DynamoDbClient dynamoDbClient = DynamoDbClient.create();
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
