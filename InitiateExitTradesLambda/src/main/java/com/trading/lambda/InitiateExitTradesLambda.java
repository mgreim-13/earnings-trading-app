package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.JsonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.JsonParsingUtils;
import com.trading.common.OptionSymbolUtils;
import com.trading.common.ExitOrderUtils;
import com.trading.common.models.AlpacaCredentials;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AWS Lambda function for exiting multi-leg option positions.
 * Queries Alpaca API to fetch all currently held positions, groups them into multi-leg strategies,
 * evaluates exit criteria, and submits exit orders.
 */
public class InitiateExitTradesLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InitiateExitTradesLambda.class);

    private static final String SECRET_NAME = System.getenv("ALPACA_SECRET_NAME");
    private static final String PAPER_TRADING = System.getenv("PAPER_TRADING");
    
    private static volatile ExecutorService executorService;
    
    private AlpacaCredentials credentials;
    
    private static ExecutorService getExecutorService() {
        if (executorService == null || executorService.isShutdown()) {
            synchronized (InitiateExitTradesLambda.class) {
                if (executorService == null || executorService.isShutdown()) {
                    executorService = Executors.newFixedThreadPool(10);
                }
            }
        }
        return executorService;
    }
    
    // Time window restrictions removed - EventBridge controls when this runs

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        log.info("InitiateExitTradesLambda execution started. Request ID: {}", context.getAwsRequestId());
        
        try {
            initializeClients();
            
            // Check if market is open
            if (!isMarketOpen()) {
                log.info("Market is closed. Exiting without processing positions.");
                return createSuccessResponse("Market is closed. No positions processed.");
            }
            
            // Time window restrictions removed - EventBridge controls when this runs
            
            // Use existing position fetching logic
            List<Position> allPositions = fetchHeldPositions();
            log.info("Fetched {} total positions from Alpaca", allPositions.size());
            
            // Filter for option positions using existing logic
            List<Position> optionPositions = allPositions.stream()
                    .filter(pos -> "option".equalsIgnoreCase(pos.getAssetClass()) || 
                                 "us_option".equalsIgnoreCase(pos.getAssetClass()))
                    .collect(Collectors.toList());
            log.info("Found {} option positions", optionPositions.size());
            
            if (optionPositions.isEmpty()) {
                log.info("No option positions found. Exiting.");
                return createSuccessResponse("No option positions found.");
            }
            
            // Group positions into calendar spreads using existing logic
            Map<String, List<Position>> calendarSpreadGroups = groupPositionsIntoMleg(optionPositions);
            log.info("Grouped positions into {} calendar spread groups", calendarSpreadGroups.size());
            
            if (calendarSpreadGroups.isEmpty()) {
                log.info("No calendar spreads found. Exiting.");
                return createSuccessResponse("No calendar spreads found.");
            }
            
            // Process each calendar spread - ALL calendar spreads should be closed at 9:45 AM EST
            List<CompletableFuture<Void>> exitFutures = new ArrayList<>();
            int processedGroups = 0;
            final AtomicInteger successfulExits = new AtomicInteger(0);
            final AtomicInteger failedExits = new AtomicInteger(0);
            
            for (Map.Entry<String, List<Position>> entry : calendarSpreadGroups.entrySet()) {
                String groupKey = entry.getKey();
                List<Position> positions = entry.getValue();
                
                CompletableFuture<Void> exitFuture = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Closing calendar spread: {} at 9:45 AM EST", groupKey);
                        
                        if (submitExitOrder(positions)) {
                            log.info("Successfully submitted exit order for calendar spread: {}", groupKey);
                            successfulExits.incrementAndGet();
                        } else {
                            log.error("Failed to submit exit order for calendar spread: {}", groupKey);
                            failedExits.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Error processing calendar spread {}: {}", groupKey, e.getMessage(), e);
                        failedExits.incrementAndGet();
                    }
                }, getExecutorService());
                
                exitFutures.add(exitFuture);
                processedGroups++;
            }
            
            // Wait for all exit operations to complete
            CompletableFuture.allOf(exitFutures.toArray(new CompletableFuture[0])).join();
            
            log.info("InitiateExitTradesLambda execution completed. Processed {} calendar spreads, " +
                "successful: {}, failed: {}", processedGroups, successfulExits.get(), failedExits.get());
            
            return createSuccessResponse(String.format("Processed %d calendar spreads - successful: %d, failed: %d", 
                processedGroups, successfulExits.get(), failedExits.get()));
            
        } catch (Exception e) {
            log.error("Fatal error in InitiateExitTradesLambda: {}", e.getMessage(), e);
            return createErrorResponse("Internal server error: " + e.getMessage());
        } finally {
            // Shutdown executor service to prevent resource leaks
            ExecutorService exec = executorService;
            if (exec != null && !exec.isShutdown()) {
                exec.shutdown();
                try {
                    if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                        exec.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    exec.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
    
    /**
     * Initialize AWS and HTTP clients
     */
    private void initializeClients() {
        try {
            // HTTP client is now handled by AlpacaHttpClient
            
            // Get Alpaca API credentials
            credentials = TradingCommonUtils.getAlpacaCredentials(SECRET_NAME);
            
            log.info("Successfully initialized clients for {} trading", 
                    "true".equalsIgnoreCase(PAPER_TRADING) ? "paper" : "live");
            
        } catch (Exception e) {
            log.error("Failed to initialize clients: {}", e.getMessage(), e);
            throw new RuntimeException("Client initialization failed", e);
        }
    }
    
    /**
     * Fetch all held positions from Alpaca API
     */
    private List<Position> fetchHeldPositions() {
        try {
            String responseBody = AlpacaHttpClient.getAlpacaTrading("/positions", credentials);
            JsonNode positionsArray = JsonUtils.parseJson(responseBody);
            
            List<Position> positions = new ArrayList<>();
            for (JsonNode positionNode : positionsArray) {
                Position position = new Position();
                position.setSymbol(positionNode.get("symbol").asText());
                position.setQty(new BigDecimal(positionNode.get("qty").asText()));
                position.setCostBasis(positionNode.get("cost_basis").asText());
                position.setUnrealizedPl(positionNode.get("unrealized_pl").asText());
                position.setSide(positionNode.get("side").asText());
                position.setAssetClass(positionNode.get("asset_class").asText());
                positions.add(position);
            }
            
            log.info("Successfully fetched {} positions from Alpaca", positions.size());
            return positions;
        } catch (Exception e) {
            log.error("Failed to fetch positions from Alpaca API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch positions", e);
        }
    }
    
    /**
     * Group option positions into calendar spreads only
     * Calendar spreads have exactly 2 positions with different expiration dates
     */
    private Map<String, List<Position>> groupPositionsIntoMleg(List<Position> optionPositions) {
        Map<String, List<Position>> groups = new HashMap<>();
        
        // First, group by underlying symbol
        Map<String, List<Position>> underlyingGroups = new HashMap<>();
        for (Position position : optionPositions) {
            String underlying = extractUnderlyingSymbol(position.getSymbol());
            underlyingGroups.computeIfAbsent(underlying, k -> new ArrayList<>()).add(position);
        }
        
        // Then, for each underlying, check if it's a calendar spread
        for (Map.Entry<String, List<Position>> entry : underlyingGroups.entrySet()) {
            String underlying = entry.getKey();
            List<Position> positions = entry.getValue();
            
            if (isCalendarSpread(positions)) {
                String groupKey = String.format("%s_CALENDAR_SPREAD", underlying);
                groups.put(groupKey, positions);
                log.info("Grouped {} positions for {} calendar spread", positions.size(), underlying);
            } else {
                log.info("Skipping {} positions for {} - not a calendar spread", positions.size(), underlying);
            }
        }
        
        return groups;
    }
    
    /**
     * Extract underlying symbol from option symbol using OptionSymbolUtils
     */
    String extractUnderlyingSymbol(String optionSymbol) {
        try {
            Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(optionSymbol);
            return (String) parsed.get("underlying");
        } catch (Exception e) {
            log.warn("Failed to parse option symbol: " + optionSymbol + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract expiration date from option symbol using OptionSymbolUtils
     */
    String extractExpirationDate(String optionSymbol) {
        try {
            Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(optionSymbol);
            return (String) parsed.get("expiration"); // Returns YYYY-MM-DD format
        } catch (Exception e) {
            log.warn("Failed to parse option symbol: " + optionSymbol + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if positions form a calendar spread
     * Calendar spreads have exactly 2 positions with different expiration dates
     */
    private boolean isCalendarSpread(List<Position> positions) {
        // Must have exactly 2 positions
        if (positions.size() != 2) {
            return false;
        }
        
        // Check if they have different expiration dates
        String exp1 = extractExpirationDate(positions.get(0).getSymbol());
        String exp2 = extractExpirationDate(positions.get(1).getSymbol());
        
        // If parsing failed, skip this group
        if (exp1 == null || exp2 == null) {
            log.warn("Failed to parse expiration dates for positions - skipping group");
            return false;
        }
        
        if (exp1.equals(exp2)) {
            return false; // Same expiration = vertical spread, not calendar
        }
        
        // Additional validation: check if they have opposite sides
        String side1 = positions.get(0).getSide();
        String side2 = positions.get(1).getSide();
        
        if (side1.equals(side2)) {
            log.warn("Positions have same side - may not be a valid calendar spread");
            return false;
        }
        
        // Additional validation: check if quantities are equal in absolute value
        int qty1 = Math.abs(positions.get(0).getQty().intValue());
        int qty2 = Math.abs(positions.get(1).getQty().intValue());
        
        if (qty1 != qty2) {
            log.warn("Position quantities don't match - may not be a valid calendar spread");
            return false;
        }
        
        return true;
    }
    
    /**
     * Calculate the credit for exiting a calendar spread
     * Calendar spread exit: farBid - nearAsk (opposite of entry: nearBid - farAsk)
     */
    private double calculateExitCredit(List<Position> positions) {
        return executeWithErrorHandling("calculating calendar spread exit credit", () -> {
            if (!isCalendarSpread(positions)) {
                log.warn("Positions do not form a valid calendar spread");
                return 0.0;
            }
            
            // Determine which is near-term and which is far-term based on expiration
            String nearSymbol, farSymbol;
            String nearExp = extractExpirationDate(positions.get(0).getSymbol());
            String farExp = extractExpirationDate(positions.get(1).getSymbol());
            
            if (nearExp.compareTo(farExp) < 0) {
                // positions[0] is near-term, positions[1] is far-term
                nearSymbol = positions.get(0).getSymbol();
                farSymbol = positions.get(1).getSymbol();
            } else {
                // positions[1] is near-term, positions[0] is far-term
                nearSymbol = positions.get(1).getSymbol();
                farSymbol = positions.get(0).getSymbol();
            }
            
            log.info("Calendar spread: near-term={}, far-term={}", nearSymbol, farSymbol);
            
            // Use same API endpoint and parsing as InitiateTradesLambda
            String endpoint = "/options/quotes/latest?symbols=" + nearSymbol + "," + farSymbol + "&feed=opra";
            String responseBody = AlpacaHttpClient.getAlpacaOptions(endpoint, credentials);
            JsonNode quotes = parseJson(responseBody).get("quotes");
            
            if (quotes == null || !quotes.isObject()) {
                log.warn("No quotes data received from Alpaca");
                return 0.0;
            }
            
            JsonNode nearQuote = quotes.get(nearSymbol);
            JsonNode farQuote = quotes.get(farSymbol);
            if (nearQuote == null || farQuote == null) {
                log.warn("Missing quote data for calendar spread symbols");
                return 0.0;
            }
            
            // Use same price extraction methods as InitiateTradesLambda
            double farBid = JsonParsingUtils.getBidPrice(farQuote);
            double nearAsk = JsonParsingUtils.getAskPrice(nearQuote);
            
            // Calculate credit: farBid - nearAsk (opposite of entry debit: nearBid - farAsk)
            double credit = Math.max(0.0, farBid - nearAsk);
            
            log.info("Calendar spread exit credit calculation: farBid={}, nearAsk={}, credit={}", 
                    farBid, nearAsk, credit);
            
            return credit;
        });
    }
    
    
    /**
     * Submit exit order for a multi-leg position group using Alpaca's MLeg order format
     * Logic mirrors InitiateTradesLambda but for exiting positions (opposite calculation)
     */
    private boolean submitExitOrder(List<Position> positions) {
        try {
            if (positions.isEmpty()) {
                log.warn("No positions to exit");
                return false;
            }
            
            // Convert Position objects to Map format for reusable utility
            List<Map<String, Object>> positionMaps = new ArrayList<>();
            for (Position position : positions) {
                Map<String, Object> positionMap = new HashMap<>();
                positionMap.put("symbol", position.getSymbol());
                positionMap.put("qty", position.getQty().toString());
                positionMap.put("side", position.getSide());
                positionMaps.add(positionMap);
            }
            
            // Use reusable utility to create and submit market order
            String exitOrderJson = ExitOrderUtils.createCalendarSpreadExitOrder(positionMaps, "market");
            log.info("Created exit order JSON: {}", exitOrderJson);
            
            // Use existing submitOrder pattern from InitiateTradesLambda
            Map<String, Object> orderResult = ExitOrderUtils.submitExitOrder(exitOrderJson, credentials);
            String orderId = (String) orderResult.get("orderId");
            String status = (String) orderResult.get("status");
            
            if (orderId != null && !orderId.isEmpty()) {
                log.info("Successfully submitted multi-leg exit order with ID: {} (status: {})", orderId, status);
                return true;
            } else {
                log.error("Failed to submit exit order - no order ID returned");
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error submitting multi-leg exit order: {}", e.getMessage(), e);
            logToCloudWatch("MLEG_ORDER_ERROR", 
                    String.format("Error: %s", e.getMessage()));
            return false;
        }
    }
    
    /**
     * Calculate Greatest Common Divisor for ratio_qty values
     */
    private int calculateGCD(int[] numbers) {
        if (numbers.length == 0) return 1;
        
        int gcd = numbers[0];
        for (int i = 1; i < numbers.length; i++) {
            gcd = calculateGCD(gcd, numbers[i]);
        }
        return gcd;
    }
    
    /**
     * Calculate GCD of two numbers
     */
    private int calculateGCD(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return Math.abs(a);
    }
    
    /**
     * Check for uncovered short legs in the multi-leg order
     * Alpaca requires all short legs to be covered within the same order
     */
    private boolean checkForUncoveredShorts(List<Map<String, Object>> legs) {
        // Group legs by underlying symbol and expiration
        Map<String, List<Map<String, Object>>> legsByUnderlying = new HashMap<>();
        
        for (Map<String, Object> leg : legs) {
            String symbol = (String) leg.get("symbol");
            String underlying = extractUnderlyingSymbol(symbol);
            String expiration = extractExpirationDate(symbol);
            String key = underlying + "_" + expiration;
            
            legsByUnderlying.computeIfAbsent(key, k -> new ArrayList<>()).add(leg);
        }
        
        // Check each underlying group for uncovered shorts
        for (Map.Entry<String, List<Map<String, Object>>> entry : legsByUnderlying.entrySet()) {
            List<Map<String, Object>> groupLegs = entry.getValue();
            
            // Count long and short positions
            int longCount = 0;
            int shortCount = 0;
            
            for (Map<String, Object> leg : groupLegs) {
                String side = (String) leg.get("side");
                int ratioQty = (Integer) leg.get("ratio_qty");
                
                if ("buy".equals(side)) {
                    longCount += ratioQty;
                } else {
                    shortCount += ratioQty;
                }
            }
            
            // If there are more shorts than longs, we have uncovered shorts
            if (shortCount > longCount) {
                log.warn("Uncovered shorts detected in group {}: {} shorts vs {} longs", 
                        entry.getKey(), shortCount, longCount);
                return true;
            }
        }
        
        return false;
    }
    
    // Note: getCurrentMarketData method removed - now using InitiateTradesLambda approach with direct API calls
    
    
    /**
     * Check if market is currently open
     */
    private boolean isMarketOpen() {
        return AlpacaHttpClient.isMarketOpen(credentials);
    }
    
    
    /**
     * Execute operation with error handling (mirrors InitiateTradesLambda pattern)
     */
    private <T> T executeWithErrorHandling(String operation, java.util.function.Supplier<T> operationSupplier) {
        try {
            return operationSupplier.get();
        } catch (Exception e) {
            throw new RuntimeException("Error " + operation + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse JSON string (mirrors InitiateTradesLambda)
     */
    private JsonNode parseJson(String json) {
        try {
            return JsonUtils.parseJson(json);
        } catch (Exception e) {
            throw new RuntimeException("JSON parsing failed: " + e.getMessage(), e);
        }
    }
    
    
    
    /**
     * Log error details to CloudWatch
     */
    private void logToCloudWatch(String errorType, String details) {
        Map<String, Object> errorLog = new HashMap<>();
        errorLog.put("timestamp", LocalDateTime.now(ZoneId.of("America/New_York")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorLog.put("errorType", errorType);
        errorLog.put("details", details);
        errorLog.put("lambdaFunction", "InitiateExitTradesLambda");
        
        try {
            String errorJson = JsonUtils.toJson(errorLog);
            log.error("CLOUDWATCH_ERROR_LOG: {}", errorJson);
        } catch (Exception e) {
            log.error("Failed to create error log JSON: {}", e.getMessage());
        }
    }
    
    /**
     * Create success response
     */
    private APIGatewayProxyResponseEvent createSuccessResponse(String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(TradingErrorHandler.createSuccessResponse(message));
        return response;
    }
    
    /**
     * Create error response
     */
    private APIGatewayProxyResponseEvent createErrorResponse(String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(500);
        response.setBody(TradingErrorHandler.createErrorResponse(message, 500));
        return response;
    }
    
    /**
     * Position model class
     */
    public static class Position {
        private String symbol;
        private BigDecimal qty;
        private String costBasis;
        private String unrealizedPl;
        private String side;
        private String assetClass;
        
        // Getters and setters
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public BigDecimal getQty() { return qty; }
        public void setQty(BigDecimal qty) { this.qty = qty; }
        
        public String getCostBasis() { return costBasis; }
        public void setCostBasis(String costBasis) { this.costBasis = costBasis; }
        
        public String getUnrealizedPl() { return unrealizedPl; }
        public void setUnrealizedPl(String unrealizedPl) { this.unrealizedPl = unrealizedPl; }
        
        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }
        
        public String getAssetClass() { return assetClass; }
        public void setAssetClass(String assetClass) { this.assetClass = assetClass; }
    }
    
    /**
     * Quote model class
     */
    public static class Quote {
        private String bidPrice;
        private String askPrice;
        
        // Getters and setters
        public String getBidPrice() { return bidPrice; }
        public void setBidPrice(String bidPrice) { this.bidPrice = bidPrice; }
        
        public String getAskPrice() { return askPrice; }
        public void setAskPrice(String askPrice) { this.askPrice = askPrice; }
    }
    
    /**
     * Clock model class
     */
    public static class Clock {
        private boolean isOpen;
        
        public boolean getIsOpen() { return isOpen; }
        public void setIsOpen(boolean isOpen) { this.isOpen = isOpen; }
    }
    
}