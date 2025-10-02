package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingErrorHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.models.HistoricalBar;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.models.StockData;
import com.trading.common.OptionSelectionUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;

// Filter class imports
import com.example.LiquidityFilter;
import com.example.IVRatioFilter;
import com.example.TermStructureFilter;
import com.example.ExecutionSpreadFilter;
import com.example.FilterResult;

/**
 * AWS Lambda function for filtering stocks based on volume, volatility, and options data.
 * Refactored version with separated filter classes for better maintainability.
 */
public class StockFilterLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String EARNINGS_TABLE = System.getenv("EARNINGS_TABLE");
    private static final String FILTERED_TABLE = System.getenv("FILTERED_TABLE");

    // Services
    private final AlpacaCredentials credentials;
    private final StockFilterCommonUtils commonUtils;
    
    // Caching for efficiency
    private final CacheManager cacheManager;
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    
    public StockFilterLambda() {
        this.credentials = TradingCommonUtils.getAlpacaCredentials("trading/alpaca/credentials");
        this.commonUtils = new StockFilterCommonUtils(credentials);
        this.cacheManager = new CacheManager(credentials, commonUtils);
    }
    
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            final String scanDate = (String) input.getOrDefault("scanDate", LocalDate.now(ZoneId.of("America/New_York")).toString());
            
            context.getLogger().log("Starting stock filter scan for date: " + scanDate);
            
            // Query tickers from earnings table
            List<String> tickers = queryTickersFromEarningsTable(scanDate, context);
            if (tickers.isEmpty()) {
                context.getLogger().log("No tickers found for scan date: " + scanDate);
                return "No tickers to process";
            }
            
            context.getLogger().log("Found " + tickers.size() + " tickers to process");
            
            // Process tickers in parallel
            List<Map<String, Object>> recommendationResults = processTickersInParallel(tickers, scanDate, context);
            
            context.getLogger().log("Completed evaluation of " + tickers.size() + " tickers, " + 
                recommendationResults.size() + " recommendations");
            
            // Write results to filtered table
            int writtenCount = writeResultsToFilteredTable(scanDate, recommendationResults, context);
            
            // Log cache statistics
            cacheManager.logCacheStatistics(context);
            
            return "Successfully processed " + tickers.size() + " tickers, " + 
                recommendationResults.size() + " recommendations, " + writtenCount + " written to table";
            
        } catch (Exception e) {
            context.getLogger().log("Error in stock filter lambda: " + e.getMessage());
            TradingErrorHandler.handleError(e, context, "StockFilterLambda");
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Process tickers in parallel using thread pool
     */
    private List<Map<String, Object>> processTickersInParallel(List<String> tickers, String scanDate, Context context) {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            // First pass: Collect all TradeDecisions
            List<CompletableFuture<TradeDecision>> futures = tickers.stream()
                .map(ticker -> CompletableFuture.supplyAsync(() -> 
                    evaluateStockRecommendationAsDecision(ticker, scanDate, context), executor))
                .collect(Collectors.toList());
            
            // Wait for all evaluations to complete and collect approved decisions
            List<TradeDecision> approvedDecisions = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .filter(TradeDecision::isApproved)
                .collect(Collectors.toList());
            
            // Apply proportional scaling if needed
            approvedDecisions = scalePositionsProportionally(approvedDecisions, FilterThresholds.MAX_DAILY_PORTFOLIO_ALLOCATION);
            
            // Convert scaled decisions to result maps
            return approvedDecisions.stream()
                .map(decision -> convertDecisionToResultMap(decision, scanDate, context))
                .collect(Collectors.toList());
                
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Query tickers from earnings table for the given scan date
     */
    private List<String> queryTickersFromEarningsTable(String scanDate, Context context) {
        try {
            ScanResponse scanResponse = executeEarningsTableScan(dynamoDbClient, scanDate);
            return extractTickersFromScanResponse(scanResponse);
        } catch (Exception e) {
            context.getLogger().log("Error querying earnings table: " + e.getMessage());
            e.printStackTrace();
            // Return empty list but log the error for debugging
            return new ArrayList<>();
        }
    }
    
    /**
     * Execute scan request on earnings table
     */
    private ScanResponse executeEarningsTableScan(DynamoDbClient dynamoDbClient, String scanDate) {
            ScanRequest scanRequest = ScanRequest.builder()
                .tableName(EARNINGS_TABLE)
                .filterExpression("scanDate = :scanDate")
                .expressionAttributeValues(Map.of(
                    ":scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();
            
        return dynamoDbClient.scan(scanRequest);
    }
            
    /**
     * Extract ticker symbols from DynamoDB scan response
     */
    private List<String> extractTickersFromScanResponse(ScanResponse scanResponse) {
            return scanResponse.items().stream()
                .map(item -> item.get("ticker").s())
                .collect(Collectors.toList());
    }
    
    /**
     * Evaluate a single stock recommendation using gatekeeper system
     */
    private Map<String, Object> evaluateStockRecommendation(String ticker, String scanDate, Context context) {
        try {
            // Validate prerequisites
            LocalDate earningsDate = validateAndGetEarningsDate(ticker, scanDate, context);
            if (earningsDate == null) {
                return null;
            }
            
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null) {
                return null;
            }
            
            // Early exit: Quick volume check before expensive operations
            if (stockData.getAverageVolume() < FilterThresholds.VOLUME_THRESHOLD) {
                context.getLogger().log(ticker + " failed early volume check: " + 
                    String.format("%.0f", stockData.getAverageVolume()) + " < " + FilterThresholds.VOLUME_THRESHOLD);
                return null;
            }
            
            // Evaluate using gatekeeper system
            TradeDecision decision = evaluateTradeWithGatekeepers(ticker, earningsDate, context);
            
            if (!decision.isApproved()) {
                context.getLogger().log(ticker + " REJECTED: " + decision.getReason());
                return null;
            }
            
            // Build result with position sizing
            Map<String, Object> result = new HashMap<>();
            result.put("status", "Recommended");
            result.put("positionSizePercentage", decision.getPositionSizePercentage());
            result.put("reason", decision.getReason());
            result.put("earningsDate", earningsDate.toString());
            result.put("avgVolume", stockData.getAverageVolume());
            
            // Add filter results for transparency
            result.putAll(decision.getFilterResults());
            
            // Add metadata
            addMetadataToResults(result, ticker, scanDate);
            
            context.getLogger().log(ticker + " RECOMMENDED: " + decision.getReason() + 
                " (position size: " + String.format("%.1f%%", decision.getPositionSizePercentage() * 100) + ")");
            
            return result;
            
        } catch (Exception e) {
            context.getLogger().log("Error evaluating stock recommendation for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Validate and get earnings date for ticker
     */
    private LocalDate validateAndGetEarningsDate(String ticker, String scanDate, Context context) {
        LocalDate earningsDate = getEarningsDateForTicker(ticker, scanDate, context);
        if (earningsDate == null) {
            context.getLogger().log("No earnings date found for " + ticker);
        }
        return earningsDate;
    }
    
    /**
     * Add metadata (ticker and scan date) to results
     */
    private void addMetadataToResults(Map<String, Object> filterResults, String ticker, String scanDate) {
        filterResults.put("ticker", ticker);
        filterResults.put("scanDate", scanDate);
    }
    
    /**
     * Get earnings date for a ticker
     */
    private LocalDate getEarningsDateForTicker(String ticker, String scanDate, Context context) {
        try {
            GetItemResponse getItemResponse = executeEarningsTableGetItem(dynamoDbClient, ticker, scanDate);
            return extractEarningsDateFromResponse(getItemResponse);
        } catch (Exception e) {
            context.getLogger().log("Error getting earnings date for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Execute get item request on earnings table
     */
    private GetItemResponse executeEarningsTableGetItem(DynamoDbClient dynamoDbClient, String ticker, String scanDate) {
            GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(EARNINGS_TABLE)
                .key(Map.of(
                    "ticker", AttributeValue.builder().s(ticker).build(),
                    "scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();
            
        return dynamoDbClient.getItem(getItemRequest);
    }
            
    /**
     * Extract earnings date from DynamoDB get item response
     */
    private LocalDate extractEarningsDateFromResponse(GetItemResponse getItemResponse) {
            if (getItemResponse.item().containsKey("earningsDate")) {
                String earningsDateStr = getItemResponse.item().get("earningsDate").s();
                return LocalDate.parse(earningsDateStr);
            }
            return null;
    }
    
    // ===== GATEKEEPER SYSTEM METHODS =====
    
    /**
     * Evaluate trade using gatekeeper system (4 gatekeepers + 2 optional)
     */
    private TradeDecision evaluateTradeWithGatekeepers(String ticker, LocalDate earningsDate, Context context) {
        Map<String, Boolean> filterResults = new HashMap<>();
        
        // Gatekeeper 1: Liquidity
        boolean liquidityPassed = evaluateLiquidityGatekeeper(ticker, context);
        filterResults.put("liquidityPassed", liquidityPassed);
        if (!liquidityPassed) {
            return new TradeDecision(ticker, false, "Insufficient liquidity", 0.0, filterResults);
        }
        
        // Gatekeeper 2: IV Ratio
        boolean ivRatioPassed = evaluateIVRatioGatekeeper(ticker, earningsDate, context);
        filterResults.put("ivRatioPassed", ivRatioPassed);
        if (!ivRatioPassed) {
            return new TradeDecision(ticker, false, "No IV skew", 0.0, filterResults);
        }
        
        // Gatekeeper 3: Term Structure
        boolean termStructurePassed = evaluateTermStructureGatekeeper(ticker, earningsDate, context);
        filterResults.put("termStructurePassed", termStructurePassed);
        if (!termStructurePassed) {
            return new TradeDecision(ticker, false, "No term structure backwardation", 0.0, filterResults);
        }
        
        // Gatekeeper 4: Execution Spread
        boolean executionSpreadPassed = evaluateExecutionSpreadGatekeeper(ticker, earningsDate, context);
        filterResults.put("executionSpreadPassed", executionSpreadPassed);
        if (!executionSpreadPassed) {
            return new TradeDecision(ticker, false, "Poor execution spread", 0.0, filterResults);
        }
        
        // All gatekeepers passed - calculate position size
        double positionSize = calculatePositionSize(ticker, earningsDate, context, filterResults);
        
        return new TradeDecision(ticker, true, "All gatekeepers passed", positionSize, filterResults);
    }
    
    /**
     * Scale positions proportionally if total exceeds maximum allocation
     */
    private List<TradeDecision> scalePositionsProportionally(List<TradeDecision> decisions, double maxTotal) {
        double currentTotal = decisions.stream()
            .mapToDouble(TradeDecision::getPositionSizePercentage)
            .sum();
        
        if (currentTotal <= maxTotal) {
            return decisions; // No scaling needed
        }
        
        // Scale proportionally
        double scaleFactor = maxTotal / currentTotal;
        decisions.forEach(decision -> 
            decision.setPositionSizePercentage(decision.getPositionSizePercentage() * scaleFactor)
        );
        
        return decisions;
    }
    
    /**
     * Evaluate stock recommendation and return TradeDecision
     */
    private TradeDecision evaluateStockRecommendationAsDecision(String ticker, String scanDate, Context context) {
        try {
            // Validate prerequisites
            LocalDate earningsDate = validateAndGetEarningsDate(ticker, scanDate, context);
            if (earningsDate == null) {
                return null;
            }
            
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null) {
                return null;
            }
            
            // Early exit: Quick volume check before expensive operations
            if (stockData.getAverageVolume() < FilterThresholds.VOLUME_THRESHOLD) {
                context.getLogger().log(ticker + " failed early volume check: " + 
                    String.format("%.0f", stockData.getAverageVolume()) + " < " + FilterThresholds.VOLUME_THRESHOLD);
                return null;
            }
            
            // Evaluate using gatekeeper system
            return evaluateTradeWithGatekeepers(ticker, earningsDate, context);
            
        } catch (Exception e) {
            context.getLogger().log("Error evaluating " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Convert TradeDecision to result map for database storage
     */
    private Map<String, Object> convertDecisionToResultMap(TradeDecision decision, String scanDate, Context context) {
        Map<String, Object> result = new HashMap<>();
        result.put("ticker", decision.getTicker());
        result.put("scanDate", scanDate);
        result.put("approved", decision.isApproved());
        result.put("reason", decision.getReason());
        result.put("positionSizePercentage", decision.getPositionSizePercentage());
        result.put("filterResults", decision.getFilterResults());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
    
    /**
     * Calculate position size based on optional filters
     */
    private double calculatePositionSize(String ticker, LocalDate earningsDate, Context context, Map<String, Boolean> filterResults) {
        double baseInvestment = FilterThresholds.BASE_POSITION_SIZE; // 5% base
        double additionalInvestment = 0.0;
        
        // Optional filter 1: Volatility Crush History
        boolean volatilityCrushPassed = evaluateVolatilityCrushOptional(ticker, context);
        filterResults.put("volatilityCrushPassed", volatilityCrushPassed);
        if (volatilityCrushPassed) {
            additionalInvestment += FilterThresholds.OPTIONAL_FILTER_BONUS; // +1%
        }
        
        // Optional filter 2: Earnings Stability
        boolean earningsStabilityPassed = evaluateEarningsStabilityOptional(ticker, earningsDate, context);
        filterResults.put("earningsStabilityPassed", earningsStabilityPassed);
        if (earningsStabilityPassed) {
            additionalInvestment += FilterThresholds.OPTIONAL_FILTER_BONUS; // +1%
        }
        
        double totalPercentage = baseInvestment + additionalInvestment;
        
        
        context.getLogger().log(ticker + " position sizing: base=5%, additional=" + 
            String.format("%.1f%%", additionalInvestment * 100) + ", total=" + 
            String.format("%.1f%%", totalPercentage * 100));
        
        return totalPercentage;
    }
    
    // ===== GATEKEEPER HELPER METHODS =====
    
    private boolean evaluateLiquidityGatekeeper(String ticker, Context context) {
        try {
            // Get stock data for volume check
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null) {
                return false;
            }
            
            // Use the proper LiquidityFilter class
            LiquidityFilter liquidityFilter = new LiquidityFilter(credentials, commonUtils);
            return liquidityFilter.hasOptionsLiquidity(ticker, context);
        } catch (Exception e) {
            context.getLogger().log("Error in liquidity gatekeeper for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean evaluateIVRatioGatekeeper(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Use the proper IVRatioFilter class
            IVRatioFilter ivRatioFilter = new IVRatioFilter(credentials, commonUtils);
            return ivRatioFilter.hasIVRatio(ticker, earningsDate, context);
        } catch (Exception e) {
            context.getLogger().log("Error in IV ratio gatekeeper for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean evaluateTermStructureGatekeeper(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Use the proper TermStructureFilter class
            TermStructureFilter termStructureFilter = new TermStructureFilter(credentials, commonUtils);
            return termStructureFilter.hasTermStructureBackwardation(ticker, earningsDate, context);
        } catch (Exception e) {
            context.getLogger().log("Error in term structure gatekeeper for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean evaluateExecutionSpreadGatekeeper(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Use the proper ExecutionSpreadFilter class
            ExecutionSpreadFilter executionSpreadFilter = new ExecutionSpreadFilter(credentials, commonUtils);
            return executionSpreadFilter.hasExecutionSpreadFeasibility(ticker, earningsDate, context).isPassed();
        } catch (Exception e) {
            context.getLogger().log("Error in execution spread gatekeeper for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean evaluateVolatilityCrushOptional(String ticker, Context context) {
        try {
            // Reuse existing volatility crush filter logic
            return evaluateVolatilityCrushFilterWithCachedData(ticker, context).isPassed();
        } catch (Exception e) {
            context.getLogger().log("Error in volatility crush optional for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean evaluateEarningsStabilityOptional(String ticker, LocalDate earningsDate, Context context) {
        try {
            // Reuse existing earnings stability filter logic
            return evaluateEarningsStabilityFilterWithCachedData(ticker, earningsDate, null, null, context).isPassed();
        } catch (Exception e) {
            context.getLogger().log("Error in earnings stability optional for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Write results to filtered table
     */
    private int writeResultsToFilteredTable(String scanDate, List<Map<String, Object>> recommendationResults, Context context) {
        int successfullyWrittenCount = 0;
        
        for (Map<String, Object> recommendation : recommendationResults) {
            if (shouldWriteRecommendation(recommendation)) {
                try {
                    Map<String, AttributeValue> dynamoDbItem = buildDynamoDbItem(recommendation, scanDate);
                    writeItemToDynamoDb(dynamoDbItem);
                    successfullyWrittenCount++;
                } catch (Exception e) {
                    context.getLogger().log("Error writing result to FilteredTickersTable: " + e.getMessage());
                }
            }
        }
        
        return successfullyWrittenCount;
    }
    
    /**
     * Check if recommendation should be written to database
     */
    private boolean shouldWriteRecommendation(Map<String, Object> recommendation) {
        String recommendationStatus = (String) recommendation.get("status");
        return "Recommended".equals(recommendationStatus);
    }
    
    /**
     * Build DynamoDB item from recommendation data
     */
    private Map<String, AttributeValue> buildDynamoDbItem(Map<String, Object> recommendation, String scanDate) {
        Map<String, AttributeValue> dynamoDbItem = new HashMap<>();
        
        // Add basic fields
        addBasicFieldsToDynamoDbItem(dynamoDbItem, recommendation, scanDate);
        
        return dynamoDbItem;
    }
    
    /**
     * Add basic fields to DynamoDB item
     */
    private void addBasicFieldsToDynamoDbItem(Map<String, AttributeValue> dynamoDbItem, Map<String, Object> recommendation, String scanDate) {
        String tickerSymbol = (String) recommendation.get("ticker");
        Double positionSizePercentage = (Double) recommendation.get("positionSizePercentage");
        
        dynamoDbItem.put("ticker", AttributeValue.builder().s(tickerSymbol).build());
        dynamoDbItem.put("scanDate", AttributeValue.builder().s(scanDate).build());
        
        // Add position sizing data (only field used by downstream Lambdas)
        if (positionSizePercentage != null) {
            dynamoDbItem.put("positionSizePercentage", AttributeValue.builder().n(String.valueOf(positionSizePercentage)).build());
        }
    }
    
    /**
     * Write item to DynamoDB
     */
    private void writeItemToDynamoDb(Map<String, AttributeValue> dynamoDbItem) {
                PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(FILTERED_TABLE)
                    .item(dynamoDbItem)
                    .build();
                
                dynamoDbClient.putItem(putItemRequest);
    }
    
    
    /**
     * Evaluate liquidity filter with cached option data
     */
    private FilterResult evaluateLiquidityFilterWithCachedData(String ticker, double avgVolume, 
            Map<String, com.trading.common.models.OptionSnapshot> shortChain, 
            Map<String, com.trading.common.models.OptionSnapshot> longChain, Context context) {
        // Check volume threshold first
        boolean volumePassed = avgVolume >= FilterThresholds.VOLUME_THRESHOLD;
        
        // Check options liquidity using cached data
        boolean optionsLiquidityPassed = false;
        if (shortChain != null && longChain != null && !shortChain.isEmpty() && !longChain.isEmpty()) {
            // Use cached data to check options liquidity
            optionsLiquidityPassed = hasOptionsLiquidityWithCachedData(ticker, shortChain, longChain, context);
        }
        
        boolean liquidityPassed = volumePassed && optionsLiquidityPassed;
        context.getLogger().log(ticker + " Liquidity filter: volume=" + volumePassed + 
            ", options=" + optionsLiquidityPassed + " (passed: " + liquidityPassed + ")");
        return new FilterResult("Liquidity", liquidityPassed);
    }
    
    /**
     * Check options liquidity using cached option data
     */
    private boolean hasOptionsLiquidityWithCachedData(String ticker, 
            Map<String, com.trading.common.models.OptionSnapshot> shortChain, 
            Map<String, com.trading.common.models.OptionSnapshot> longChain, Context context) {
        try {
            // Check if we have sufficient option contracts
            if (shortChain.size() < 3 || longChain.size() < 3) {
                context.getLogger().log("Insufficient option contracts for " + ticker);
                return false;
            }
            
            // Check for tight spreads in short-term options
            boolean hasTightSpreads = shortChain.values().stream()
                .anyMatch(option -> {
                    double bid = option.getBid();
                    double ask = option.getAsk();
                    return bid > 0 && ask > 0 && (ask - bid) / ask < FilterThresholds.CACHED_DATA_SPREAD_THRESHOLD; // 5% spread threshold
                });
            
            // Check for reasonable volume in options (using bid/ask size as proxy for volume)
            boolean hasVolume = shortChain.values().stream()
                .anyMatch(option -> option.getTotalSize() > FilterThresholds.MIN_OPTION_SIZE);
            
            return hasTightSpreads && hasVolume;
        } catch (Exception e) {
            context.getLogger().log("Error checking options liquidity for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Evaluate IV ratio filter with cached option data
     */
    private FilterResult evaluateIVRatioFilterWithCachedData(String ticker, LocalDate earningsDate,
            Map<String, com.trading.common.models.OptionSnapshot> shortChain, 
            Map<String, com.trading.common.models.OptionSnapshot> longChain, Context context) {
        try {
            if (shortChain == null || longChain == null || shortChain.isEmpty() || longChain.isEmpty()) {
                context.getLogger().log("No option data available for IV ratio check for " + ticker);
                return new FilterResult("IV_Ratio", false);
            }
            
            // Get current stock price from cached stock data
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null || stockData.getCurrentPrice() <= 0) {
                context.getLogger().log("Invalid stock price for IV ratio check for " + ticker);
                return new FilterResult("IV_Ratio", false);
            }
            double currentPrice = stockData.getCurrentPrice();
            
            // Find best common strike for calendar spread
            double bestStrike = OptionSelectionUtils.findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
                shortChain, longChain, currentPrice);
            
            if (bestStrike < 0) {
                context.getLogger().log("No common strikes found for calendar spread for " + ticker);
                return new FilterResult("IV_Ratio", false);
            }
            
            // Calculate IV ratio using cached data
            double ivRatio = calculateIVRatioWithCachedData(shortChain, longChain, bestStrike, context);
            boolean passed = ivRatio > FilterThresholds.IV_RATIO_THRESHOLD; // Use configurable threshold
            
            context.getLogger().log(ticker + " IV ratio: " + String.format("%.2f", ivRatio) + 
                " (passed: " + passed + ")");
            return new FilterResult("IV_Ratio", passed);
            
        } catch (Exception e) {
            context.getLogger().log("Error in IV ratio filter for " + ticker + ": " + e.getMessage());
            return new FilterResult("IV_Ratio", false);
        }
    }
    
    /**
     * Calculate IV ratio using cached option data
     */
    private double calculateIVRatioWithCachedData(Map<String, com.trading.common.models.OptionSnapshot> shortChain,
            Map<String, com.trading.common.models.OptionSnapshot> longChain, double strike, Context context) {
        try {
            // Find options at the strike price
            com.trading.common.models.OptionSnapshot shortOption = shortChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - strike) < 0.01) // 1 cent tolerance for strike matching
                .findFirst().orElse(null);
                
            com.trading.common.models.OptionSnapshot longOption = longChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - strike) < 0.01) // 1 cent tolerance for strike matching
                .findFirst().orElse(null);
            
            if (shortOption == null || longOption == null) {
                return 0.0;
            }
            
            double shortIV = shortOption.getImpliedVol();
            double longIV = longOption.getImpliedVol();
            
            if (shortIV <= 0 || longIV <= 0) {
                return 0.0;
            }
            
            return shortIV / longIV;
        } catch (Exception e) {
            context.getLogger().log("Error calculating IV ratio: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Evaluate term structure filter with cached option data
     */
    private FilterResult evaluateTermStructureFilterWithCachedData(String ticker, LocalDate earningsDate,
            Map<String, com.trading.common.models.OptionSnapshot> shortChain, 
            Map<String, com.trading.common.models.OptionSnapshot> longChain, Context context) {
        try {
            if (shortChain == null || longChain == null || shortChain.isEmpty() || longChain.isEmpty()) {
                context.getLogger().log("No option data available for term structure check for " + ticker);
                return new FilterResult("Term_Structure", false);
            }
            
            // Get current stock price from cached stock data
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null || stockData.getCurrentPrice() <= 0) {
                context.getLogger().log("Invalid stock price for term structure check for " + ticker);
                return new FilterResult("Term_Structure", false);
            }
            double currentPrice = stockData.getCurrentPrice();
            
            // Find best common strike for calendar spread
            double bestStrike = OptionSelectionUtils.findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
                shortChain, longChain, currentPrice);
            
            if (bestStrike < 0) {
                context.getLogger().log("No common strikes found for term structure check for " + ticker);
                return new FilterResult("Term_Structure", false);
            }
            
            // Calculate term structure using cached data
            boolean hasBackwardation = calculateTermStructureWithCachedData(shortChain, longChain, bestStrike, context);
            
            context.getLogger().log(ticker + " Term structure backwardation: " + hasBackwardation);
            return new FilterResult("Term_Structure", hasBackwardation);
            
        } catch (Exception e) {
            context.getLogger().log("Error in term structure filter for " + ticker + ": " + e.getMessage());
            return new FilterResult("Term_Structure", false);
        }
    }
    
    /**
     * Calculate term structure backwardation using cached option data
     */
    private boolean calculateTermStructureWithCachedData(Map<String, com.trading.common.models.OptionSnapshot> shortChain,
            Map<String, com.trading.common.models.OptionSnapshot> longChain, double strike, Context context) {
        try {
            // Find options at the strike price
            com.trading.common.models.OptionSnapshot shortOption = shortChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - strike) < 0.01) // 1 cent tolerance for strike matching
                .findFirst().orElse(null);
                
            com.trading.common.models.OptionSnapshot longOption = longChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - strike) < 0.01) // 1 cent tolerance for strike matching
                .findFirst().orElse(null);
            
            if (shortOption == null || longOption == null) {
                return false;
            }
            
            double shortIV = shortOption.getImpliedVol();
            double longIV = longOption.getImpliedVol();
            
            if (shortIV <= 0 || longIV <= 0) {
                return false;
            }
            
            // Backwardation: short-term IV > long-term IV
            return shortIV > longIV;
        } catch (Exception e) {
            context.getLogger().log("Error calculating term structure: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Evaluate earnings stability filter with cached historical data
     */
    private FilterResult evaluateEarningsStabilityFilterWithCachedData(String ticker, LocalDate earningsDate,
            Map<String, com.trading.common.models.OptionSnapshot> shortCallChain, 
            Map<String, com.trading.common.models.OptionSnapshot> shortPutChain, Context context) {
        try {
            // Get cached historical data
            List<HistoricalBar> historicalBars = cacheManager.getCachedHistoricalData(ticker, context);
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical data available for earnings stability check for " + ticker);
                return new FilterResult("Earnings_Stability", false);
            }
            
            // Calculate earnings stability using cached data
            boolean hasEarningsStability = calculateEarningsStabilityWithCachedData(ticker, earningsDate, historicalBars, shortCallChain, shortPutChain, context);
            
            context.getLogger().log(ticker + " Earnings stability: " + hasEarningsStability);
            return new FilterResult("Earnings_Stability", hasEarningsStability);
            
        } catch (Exception e) {
            context.getLogger().log("Error in earnings stability filter for " + ticker + ": " + e.getMessage());
            return new FilterResult("Earnings_Stability", false);
        }
    }
    
    /**
     * Calculate earnings stability using cached historical data
     */
    private boolean calculateEarningsStabilityWithCachedData(String ticker, LocalDate earningsDate, 
            List<HistoricalBar> historicalBars, Map<String, com.trading.common.models.OptionSnapshot> shortCallChain,
            Map<String, com.trading.common.models.OptionSnapshot> shortPutChain, Context context) {
        try {
            // Get historical earnings data
            List<StockFilterCommonUtils.EarningsData> earningsData = commonUtils.getHistoricalEarningsData(ticker, context);
            if (earningsData.isEmpty()) {
                context.getLogger().log("No historical earnings data for " + ticker);
                return false;
            }
            
            // Calculate average historical move using cached data
            double averageHistoricalMove = calculateAverageHistoricalMoveWithCachedData(ticker, earningsData, historicalBars, context);
            if (averageHistoricalMove < 0) {
                context.getLogger().log("Could not calculate average historical move for " + ticker);
                return false;
            }
            
            // Check historical stability using cached data
            boolean historicalStable = checkHistoricalStabilityWithCachedData(ticker, earningsData, historicalBars, context);
            
            // Try to get current straddle-implied move (this still needs option data)
            double currentStraddleMove = getCurrentStraddleImpliedMoveWithCachedData(ticker, earningsDate, shortCallChain, shortPutChain, context);
            
            boolean straddleOverpriced = false;
            boolean usedStraddleData = false;
            
            if (currentStraddleMove > 0) {
                straddleOverpriced = currentStraddleMove > averageHistoricalMove * FilterThresholds.STRADDLE_HISTORICAL_MULTIPLIER; // 1.5x over historical
                usedStraddleData = true;
                context.getLogger().log(ticker + " straddle analysis: current=" + String.format("%.2f%%", currentStraddleMove * 100) + 
                    ", historical=" + String.format("%.2f%%", averageHistoricalMove * 100) + 
                    ", overpriced=" + straddleOverpriced);
            }
            
            // Determine if earnings are stable
            boolean isStable = historicalStable && (!usedStraddleData || straddleOverpriced);
            
            context.getLogger().log(ticker + " earnings stability: historical=" + historicalStable + 
                ", straddle_overpriced=" + straddleOverpriced + ", stable=" + isStable);
            
            return isStable;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating earnings stability for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate average historical move using cached historical data
     */
    private double calculateAverageHistoricalMoveWithCachedData(String ticker, List<StockFilterCommonUtils.EarningsData> earningsData, 
            List<HistoricalBar> historicalBars, Context context) {
        double totalWeightedMove = 0.0;
        double totalWeight = 0.0;
        int validMoves = 0;
        
        LocalDate cutoffDate = LocalDate.now(ZoneId.of("America/New_York")).minusYears(2);
        
        for (StockFilterCommonUtils.EarningsData earning : earningsData) {
            try {
                // Use cached data to calculate earnings day move
                double actualMove = calculateEarningsDayMoveWithCachedData(ticker, earning.getEarningsDate(), historicalBars, context);
                if (actualMove >= 0) {
                    validMoves++;
                    
                    // Calculate weight based on recency
                    double weight = commonUtils.calculateRecencyWeight(earning.getEarningsDate(), cutoffDate);
                    totalWeightedMove += actualMove * weight;
                    totalWeight += weight;
                }
            } catch (Exception e) {
                context.getLogger().log("Error calculating move for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
            }
        }
        
        if (validMoves == 0 || totalWeight == 0) {
            return -1.0;
        }
        
        double averageMove = totalWeightedMove / totalWeight;
        context.getLogger().log(ticker + " average historical move: " + String.format("%.2f%%", averageMove * 100) + 
            " (from " + validMoves + " earnings)");
        return averageMove;
    }
    
    /**
     * Calculate earnings day move using cached historical data
     */
    private double calculateEarningsDayMoveWithCachedData(String ticker, LocalDate earningsDate, 
            List<HistoricalBar> historicalBars, Context context) {
        try {
            // Find the earnings day bar
            HistoricalBar earningsBar = getHistoricalBarForDateFromCachedData(earningsDate, historicalBars, false, context);
            if (earningsBar == null) {
                return -1.0;
            }
            
            // Find the previous trading day's bar
            HistoricalBar previousBar = getHistoricalBarForDateFromCachedData(earningsDate, historicalBars, true, context);
            if (previousBar == null) {
                // Fallback to regular open-to-close calculation
                double openPrice = earningsBar.getOpen();
                double closePrice = earningsBar.getClose();
                if (openPrice <= 0) return -1.0;
                return Math.abs(closePrice - openPrice) / openPrice;
            }
            
            // Calculate move including overnight gap
            double previousClose = previousBar.getClose();
            double earningsOpen = earningsBar.getOpen();
            double earningsClose = earningsBar.getClose();
            
            if (previousClose <= 0 || earningsOpen <= 0) {
                return -1.0;
            }
            
            // Total move from previous close to earnings close
            double totalMove = Math.abs(earningsClose - previousClose) / previousClose;
            
            context.getLogger().log(ticker + " earnings move on " + earningsDate + ": " + 
                String.format("%.2f%%", totalMove * 100));
            
            return totalMove;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating earnings day move for " + ticker + " on " + earningsDate + ": " + e.getMessage());
            return -1.0;
        }
    }
    
    /**
     * Get historical bar for a specific date from cached data
     */
    private HistoricalBar getHistoricalBarForDateFromCachedData(LocalDate targetDate, List<HistoricalBar> historicalBars, 
            boolean getPrevious, Context context) {
        try {
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
                // Find the exact date bar
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
            context.getLogger().log("Error finding historical bar for " + targetDate + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check historical stability using cached data
     */
    private boolean checkHistoricalStabilityWithCachedData(String ticker, List<StockFilterCommonUtils.EarningsData> earningsData, 
            List<HistoricalBar> historicalBars, Context context) {
        try {
            int stableCount = 0;
            int totalEarnings = 0;
            
            for (StockFilterCommonUtils.EarningsData earning : earningsData) {
                try {
                    double move = calculateEarningsDayMoveWithCachedData(ticker, earning.getEarningsDate(), historicalBars, context);
                    if (move >= 0) {
                        totalEarnings++;
                        if (move < FilterThresholds.EARNINGS_MOVE_THRESHOLD) { // Less than 5% move
                            stableCount++;
                        }
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error checking stability for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            if (totalEarnings == 0) {
                return false;
            }
            
            double stabilityRatio = (double) stableCount / totalEarnings;
            boolean isStable = stabilityRatio >= FilterThresholds.STABILITY_THRESHOLD; // 70% of earnings were stable
            
            context.getLogger().log(ticker + " historical stability: " + stableCount + "/" + totalEarnings + 
                " = " + String.format("%.1f%%", stabilityRatio * 100) + " (stable: " + isStable + ")");
            
            return isStable;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking historical stability for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get current straddle-implied move using cached option data
     */
    private double getCurrentStraddleImpliedMoveWithCachedData(String ticker, LocalDate earningsDate,
            Map<String, com.trading.common.models.OptionSnapshot> shortCallChain, 
            Map<String, com.trading.common.models.OptionSnapshot> shortPutChain, Context context) {
        try {
            if (shortCallChain.isEmpty() || shortPutChain.isEmpty()) {
                return -1.0;
            }
            
            // Get current stock price from cached stock data
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null || stockData.getCurrentPrice() <= 0) {
                return -1.0;
            }
            double currentPrice = stockData.getCurrentPrice();
            
            // Find ATM options
            com.trading.common.models.OptionSnapshot atmCall = shortCallChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - currentPrice) / currentPrice < FilterThresholds.ATM_THRESHOLD)
                .min((a, b) -> Double.compare(Math.abs(a.getStrike() - currentPrice), Math.abs(b.getStrike() - currentPrice)))
                .orElse(null);
                
            com.trading.common.models.OptionSnapshot atmPut = shortPutChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - currentPrice) / currentPrice < FilterThresholds.ATM_THRESHOLD)
                .min((a, b) -> Double.compare(Math.abs(a.getStrike() - currentPrice), Math.abs(b.getStrike() - currentPrice)))
                .orElse(null);
            
            if (atmCall == null || atmPut == null) {
                return -1.0;
            }
            
            // Calculate straddle price
            double callPrice = (atmCall.getBid() + atmCall.getAsk()) / 2.0;
            double putPrice = (atmPut.getBid() + atmPut.getAsk()) / 2.0;
            double straddlePrice = callPrice + putPrice;
            
            if (straddlePrice <= 0 || currentPrice <= 0) {
                return -1.0;
            }
            
            // Convert to percentage move
            double impliedMove = straddlePrice / currentPrice;
            
            context.getLogger().log(ticker + " straddle implied move: " + String.format("%.2f%%", impliedMove * 100));
            
            return impliedMove;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating straddle implied move for " + ticker + ": " + e.getMessage());
            return -1.0;
        }
    }
    
    /**
     * Evaluate volatility crush filter with cached historical data
     */
    private FilterResult evaluateVolatilityCrushFilterWithCachedData(String ticker, Context context) {
        try {
            // Get cached historical data
            List<HistoricalBar> historicalBars = cacheManager.getCachedHistoricalData(ticker, context);
            if (historicalBars == null || historicalBars.isEmpty()) {
                context.getLogger().log("No historical data available for volatility crush check for " + ticker);
                return new FilterResult("Stock_Vol_Crush", false);
            }
            
            // Calculate volatility crush using cached data
            boolean hasVolatilityCrush = calculateVolatilityCrushWithCachedData(ticker, historicalBars, context);
            
            context.getLogger().log(ticker + " Volatility crush: " + hasVolatilityCrush);
            return new FilterResult("Stock_Vol_Crush", hasVolatilityCrush);
            
        } catch (Exception e) {
            context.getLogger().log("Error in volatility crush filter for " + ticker + ": " + e.getMessage());
            return new FilterResult("Stock_Vol_Crush", false);
        }
    }
    
    /**
     * Calculate volatility crush using cached historical data
     */
    private boolean calculateVolatilityCrushWithCachedData(String ticker, List<HistoricalBar> historicalBars, Context context) {
        try {
            // Get historical earnings data
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
                    
                    if (calculateVolatilityCrushForEarningsWithCachedData(ticker, earningsDate, historicalBars, context)) {
                        crushCount++;
                    }
                    totalEarnings++;
                } catch (Exception e) {
                    context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earning.getEarningsDate() + ": " + e.getMessage());
                }
            }
            
            boolean hasCrush = totalEarnings > 0 && (double) crushCount / totalEarnings >= FilterThresholds.CRUSH_PERCENTAGE; // 70% threshold
            context.getLogger().log(ticker + " stock volatility crush: " + crushCount + "/" + totalEarnings + 
                " = " + String.format("%.1f%%", (double) crushCount / totalEarnings * 100) + " (" + hasCrush + ")");
            return hasCrush;
            
        } catch (Exception e) {
            context.getLogger().log("Error calculating volatility crush for " + ticker + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate volatility crush for specific earnings using cached historical data
     */
    private boolean calculateVolatilityCrushForEarningsWithCachedData(String ticker, LocalDate earningsDate, 
            List<HistoricalBar> historicalBars, Context context) {
        try {
            // Calculate pre-earnings volatility (7 days before earnings)
            double preVol = calculateVolatilityForPeriod(historicalBars, earningsDate.minusDays(7), earningsDate.minusDays(1), context);
            
            // Calculate post-earnings volatility (7 days after earnings)
            double postVol = calculateVolatilityForPeriod(historicalBars, earningsDate.plusDays(1), earningsDate.plusDays(7), context);
            
            if (preVol > 0 && postVol > 0) {
                double crushRatio = postVol / preVol;
                boolean hasCrush = crushRatio < FilterThresholds.VOLATILITY_CRUSH_THRESHOLD; // 20% or more reduction
                context.getLogger().log(ticker + " volatility crush on " + earningsDate + ": " + 
                    String.format("%.3f", preVol) + " -> " + String.format("%.3f", postVol) + 
                    " (ratio: " + String.format("%.2f", crushRatio) + ", crush: " + hasCrush + ")");
                return hasCrush;
            }
            
            return false;
        } catch (Exception e) {
            context.getLogger().log("Error calculating volatility crush for " + ticker + " on " + earningsDate + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate volatility for a specific period using cached historical data
     */
    private double calculateVolatilityForPeriod(List<HistoricalBar> historicalBars, LocalDate startDate, LocalDate endDate, Context context) {
        try {
            // Filter bars for the specific period
            List<HistoricalBar> periodBars = historicalBars.stream()
                .filter(bar -> {
                    try {
                        LocalDate barDate = LocalDate.parse(bar.getTimestamp().substring(0, 10)); // Extract date part
                        return !barDate.isBefore(startDate) && !barDate.isAfter(endDate);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
            
            if (periodBars.size() < 2) {
                return 0.0;
            }
            
            // Calculate daily returns
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < periodBars.size(); i++) {
                double prevClose = periodBars.get(i - 1).getClose();
                double currentClose = periodBars.get(i).getClose();
                if (prevClose > 0) {
                    returns.add(Math.log(currentClose / prevClose));
                }
            }
            
            if (returns.isEmpty()) {
                return 0.0;
            }
            
            // Calculate standard deviation (volatility)
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0.0);
            
            return Math.sqrt(variance * 252); // Annualized volatility
        } catch (Exception e) {
            context.getLogger().log("Error calculating volatility for period: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Evaluate execution spread filter with cached option data
     */
    private FilterResult evaluateExecutionSpreadFilterWithCachedData(String ticker, LocalDate earningsDate,
            Map<String, com.trading.common.models.OptionSnapshot> shortCallChain, 
            Map<String, com.trading.common.models.OptionSnapshot> shortPutChain, Context context) {
        try {
            if (shortCallChain.isEmpty() || shortPutChain.isEmpty()) {
                context.getLogger().log("No option data available for execution spread check for " + ticker);
                return new FilterResult("Execution_Spread", false);
            }
            
            // Get current stock price from cached stock data
            StockData stockData = cacheManager.getCachedStockData(ticker, context);
            if (stockData == null || stockData.getCurrentPrice() <= 0) {
                context.getLogger().log("Invalid stock price for execution spread check for " + ticker);
                return new FilterResult("Execution_Spread", false);
            }
            double currentPrice = stockData.getCurrentPrice();
            
            // Calculate execution spread feasibility using cached data
            boolean hasFeasibleSpreads = calculateExecutionSpreadWithCachedData(ticker, currentPrice, 
                shortCallChain, shortPutChain, context);
            
            context.getLogger().log(ticker + " Execution spread feasibility: " + hasFeasibleSpreads);
            return new FilterResult("Execution_Spread", hasFeasibleSpreads);
            
        } catch (Exception e) {
            context.getLogger().log("Error in execution spread filter for " + ticker + ": " + e.getMessage());
            return new FilterResult("Execution_Spread", false);
        }
    }
    
    /**
     * Calculate execution spread feasibility using cached option data
     */
    private boolean calculateExecutionSpreadWithCachedData(String ticker, double currentPrice,
            Map<String, com.trading.common.models.OptionSnapshot> shortCallChain, 
            Map<String, com.trading.common.models.OptionSnapshot> shortPutChain, Context context) {
        try {
            // Find ATM options for both calls and puts
            com.trading.common.models.OptionSnapshot atmCall = shortCallChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - currentPrice) / currentPrice < FilterThresholds.ATM_THRESHOLD) // Within 2% of ATM
                .min((a, b) -> Double.compare(Math.abs(a.getStrike() - currentPrice), Math.abs(b.getStrike() - currentPrice)))
                .orElse(null);
                
            com.trading.common.models.OptionSnapshot atmPut = shortPutChain.values().stream()
                .filter(opt -> Math.abs(opt.getStrike() - currentPrice) / currentPrice < FilterThresholds.ATM_THRESHOLD) // Within 2% of ATM
                .min((a, b) -> Double.compare(Math.abs(a.getStrike() - currentPrice), Math.abs(b.getStrike() - currentPrice)))
                .orElse(null);
            
            if (atmCall == null || atmPut == null) {
                context.getLogger().log("No ATM options found for execution spread check for " + ticker);
                return false;
            }
            
            // Check if spreads are tight enough for execution
            double callSpread = atmCall.getBidAskSpreadPercent();
            double putSpread = atmPut.getBidAskSpreadPercent();
            
            // Require both spreads to be reasonable (less than 10%)
            boolean hasTightSpreads = callSpread < FilterThresholds.EXECUTION_SPREAD_CACHED_THRESHOLD && putSpread < FilterThresholds.EXECUTION_SPREAD_CACHED_THRESHOLD;
            
            // Check if there's reasonable liquidity (bid/ask sizes)
            boolean hasLiquidity = atmCall.getTotalSize() > FilterThresholds.MIN_OPTION_SIZE && atmPut.getTotalSize() > FilterThresholds.MIN_OPTION_SIZE;
            
            context.getLogger().log(ticker + " Call spread: " + String.format("%.1f%%", callSpread * 100) + 
                ", Put spread: " + String.format("%.1f%%", putSpread * 100) + 
                ", Liquidity: " + hasLiquidity);
            
            return hasTightSpreads && hasLiquidity;
        } catch (Exception e) {
            context.getLogger().log("Error calculating execution spread: " + e.getMessage());
            return false;
        }
    }
    
    
    
    
}
