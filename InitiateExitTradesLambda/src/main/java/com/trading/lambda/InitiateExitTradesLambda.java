package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.models.AlpacaCredentials;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String REGION = System.getenv("AWS_REGION");
    
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);
    
    private OkHttpClient httpClient;
    private AlpacaCredentials credentials;
    
    // Exit criteria - ALL positions should be closed at 9:45 AM EST
    private static final int EXIT_HOUR = 9; // 9 AM EST
    private static final int EXIT_MINUTE = 45; // 45 minutes

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
            
            // Check if it's time to exit (9:45 AM EST)
            if (!isTimeToExit()) {
                log.info("Not yet time to exit positions. Current time is not 9:45 AM EST.");
                return createSuccessResponse("Not yet time to exit positions.");
            }
            
            // Fetch all held positions
            List<Position> allPositions = fetchHeldPositions();
            log.info("Fetched {} total positions from Alpaca", allPositions.size());
            
            // Filter for option positions
            List<Position> optionPositions = allPositions.stream()
                    .filter(pos -> "option".equalsIgnoreCase(pos.getAssetClass()) || 
                                 "us_option".equalsIgnoreCase(pos.getAssetClass()))
                    .collect(Collectors.toList());
            log.info("Found {} option positions", optionPositions.size());
            
            if (optionPositions.isEmpty()) {
                log.info("No option positions found. Exiting.");
                return createSuccessResponse("No option positions found.");
            }
            
            // Group positions into calendar spreads only
            Map<String, List<Position>> calendarSpreadGroups = groupPositionsIntoMleg(optionPositions);
            log.info("Grouped positions into {} calendar spread groups", calendarSpreadGroups.size());
            
            if (calendarSpreadGroups.isEmpty()) {
                log.info("No calendar spreads found. Exiting.");
                return createSuccessResponse("No calendar spreads found.");
            }
            
            // Process each calendar spread - ALL calendar spreads should be closed at 9:45 AM EST
            List<CompletableFuture<Void>> exitFutures = new ArrayList<>();
            int processedGroups = 0;
            
            for (Map.Entry<String, List<Position>> entry : calendarSpreadGroups.entrySet()) {
                String groupKey = entry.getKey();
                List<Position> positions = entry.getValue();
                
                CompletableFuture<Void> exitFuture = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Closing calendar spread: {} at 9:45 AM EST", groupKey);
                        if (submitExitOrder(positions)) {
                            log.info("Successfully submitted exit order for calendar spread: {}", groupKey);
                        } else {
                            log.error("Failed to submit exit order for calendar spread: {}", groupKey);
                        }
                    } catch (Exception e) {
                        log.error("Error processing calendar spread {}: {}", groupKey, e.getMessage(), e);
                    }
                }, executorService);
                
                exitFutures.add(exitFuture);
                processedGroups++;
            }
            
            // Wait for all exit operations to complete
            CompletableFuture.allOf(exitFutures.toArray(new CompletableFuture[0])).join();
            
            log.info("InitiateExitTradesLambda execution completed. Processed {} calendar spreads", processedGroups);
            return createSuccessResponse(String.format("Processed %d calendar spreads", processedGroups));
            
        } catch (Exception e) {
            log.error("Fatal error in InitiateExitTradesLambda: {}", e.getMessage(), e);
            return createErrorResponse("Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Initialize AWS and HTTP clients
     */
    private void initializeClients() {
        try {
            // Initialize HTTP client
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            
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
            Request request = new Request.Builder()
                    .url(credentials.getBaseUrl() + "/v2/positions")
                    .addHeader("APCA-API-KEY-ID", credentials.getKeyId())
                    .addHeader("APCA-API-SECRET-KEY", credentials.getSecretKey())
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Failed to fetch positions: " + response.code() + " " + response.message());
                }
                
                String responseBody = response.body().string();
                JsonNode positionsArray = TradingCommonUtils.parseJson(responseBody);
                
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
            }
        } catch (IOException e) {
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
     * Extract underlying symbol from option symbol (e.g., "AAPL240315C00150000" -> "AAPL")
     */
    String extractUnderlyingSymbol(String optionSymbol) {
        // Simple extraction - assumes standard option symbol format
        int i = 0;
        while (i < optionSymbol.length() && Character.isLetter(optionSymbol.charAt(i))) {
            i++;
        }
        return optionSymbol.substring(0, i);
    }
    
    /**
     * Extract expiration date from option symbol
     */
    String extractExpirationDate(String optionSymbol) {
        // This is a simplified extraction - in production, use a proper option symbol parser
        String underlying = extractUnderlyingSymbol(optionSymbol);
        return optionSymbol.substring(underlying.length(), underlying.length() + 6); // YYMMDD
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
     * Calendar spread exit: nearBid - farAsk (opposite of entry: farAsk - nearBid)
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
            String url = "https://data.alpaca.markets/v1beta1/options/quotes/latest?symbols=" + nearSymbol + "," + farSymbol + "&feed=indicative";
            String responseBody = makeHttpRequest(url, credentials.getKeyId(), credentials.getSecretKey(), "GET", null);
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
            double nearBid = getBidPrice(nearQuote);
            double farAsk = getAskPrice(farQuote);
            
            // Calculate credit: nearBid - farAsk (opposite of entry debit: farAsk - nearBid)
            double credit = Math.max(0.0, nearBid - farAsk);
            
            log.info("Calendar spread exit credit calculation: nearBid={}, farAsk={}, credit={}", 
                    nearBid, farAsk, credit);
            
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
            
            // Calculate the credit for exiting the calendar spread (opposite of InitiateTradesLambda debit calculation)
            double credit = calculateExitCredit(positions);
            if (credit <= 0) {
                log.warn("Invalid credit calculation for exit order: {}", credit);
                return false;
            }
            
            // Round to 2 decimal places (same as InitiateTradesLambda)
            double limitPrice = Math.round(credit * 100.0) / 100.0;
            
            // Build legs array for multi-leg order
            List<Map<String, Object>> legs = new ArrayList<>();
            int totalQuantity = 0;
            
            for (Position position : positions) {
                // Determine opposite side and position intent for exit
                String exitSide = position.getSide().equals("long") ? "sell" : "buy";
                String positionIntent = position.getSide().equals("long") ? "sell_to_close" : "buy_to_close";
                
                // Build leg for multi-leg order
                Map<String, Object> leg = new HashMap<>();
                leg.put("symbol", position.getSymbol());
                leg.put("ratio_qty", Math.abs(position.getQty().intValue()));
                leg.put("side", exitSide);
                leg.put("position_intent", positionIntent);
                
                legs.add(leg);
                totalQuantity = Math.max(totalQuantity, Math.abs(position.getQty().intValue()));
            }
            
            if (legs.isEmpty()) {
                log.warn("No valid legs generated for multi-leg order");
                return false;
            }
            
            // Ensure GCD of ratio_qty values is 1 (Alpaca requirement)
            int[] ratioQtys = legs.stream()
                    .mapToInt(leg -> (Integer) leg.get("ratio_qty"))
                    .toArray();
            
            int gcd = calculateGCD(ratioQtys);
            
            if (gcd > 1) {
                // Simplify ratios by dividing by GCD
                for (Map<String, Object> leg : legs) {
                    int ratioQty = (Integer) leg.get("ratio_qty");
                    leg.put("ratio_qty", ratioQty / gcd);
                }
                totalQuantity = totalQuantity / gcd;
                log.info("Simplified ratio quantities by GCD of {}", gcd);
            }
            
            // Validate that we have at least 2 legs for a multi-leg order
            if (legs.size() < 2) {
                log.warn("Multi-leg order requires at least 2 legs, found {}", legs.size());
                return false;
            }
            
            // Check for uncovered short legs (Alpaca restriction for Options Level 3)
            boolean hasUncoveredShorts = checkForUncoveredShorts(legs);
            if (hasUncoveredShorts) {
                log.warn("Order contains uncovered short legs, which is not allowed for Options Level 3");
                logToCloudWatch("UNCOVERED_SHORTS_ERROR", 
                        "Order rejected due to uncovered short legs in multi-leg order");
                return false;
            }
            
            // Build multi-leg order request (same structure as InitiateTradesLambda)
            Map<String, Object> mlegOrder = new HashMap<>();
            mlegOrder.put("order_class", "mleg");
            mlegOrder.put("type", "limit");
            mlegOrder.put("time_in_force", "day");
            mlegOrder.put("limit_price", limitPrice);
            mlegOrder.put("qty", totalQuantity);
            mlegOrder.put("legs", legs);
            
            // Submit multi-leg order
            String orderJson = TradingCommonUtils.toJson(mlegOrder);
            RequestBody body = RequestBody.create(orderJson, MediaType.get("application/json"));
            
            Request request = new Request.Builder()
                    .url(credentials.getBaseUrl() + "/v2/orders")
                    .addHeader("APCA-API-KEY-ID", credentials.getKeyId())
                    .addHeader("APCA-API-SECRET-KEY", credentials.getSecretKey())
                    .addHeader("accept", "application/json")
                    .addHeader("content-type", "application/json")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    log.info("Successfully submitted multi-leg exit order: {}", responseBody);
                    return true;
                } else {
                    String errorBody = response.body().string();
                    log.error("Failed to submit multi-leg order: {} {}", response.code(), errorBody);
                    logToCloudWatch("MLEG_ORDER_SUBMISSION_FAILED", 
                            String.format("Error: %s %s", response.code(), errorBody));
                    return false;
                }
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
     * Check if it's time to exit positions (9:45 AM EST)
     */
    private boolean isTimeToExit() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("America/New_York"));
            int currentHour = now.getHour();
            int currentMinute = now.getMinute();
            
            // Check if it's 9:45 AM EST (within a 5-minute window for safety)
            boolean isExitTime = (currentHour == EXIT_HOUR && currentMinute >= EXIT_MINUTE && currentMinute <= EXIT_MINUTE + 5);
            
            log.info("Current time: {}:{} EST, Exit time: {}:{} EST, Is exit time: {}", 
                    currentHour, String.format("%02d", currentMinute), 
                    EXIT_HOUR, String.format("%02d", EXIT_MINUTE), isExitTime);
            
            return isExitTime;
        } catch (Exception e) {
            log.error("Error checking exit time: {}", e.getMessage(), e);
            return false; // Conservative approach
        }
    }
    
    /**
     * Check if market is currently open
     */
    private boolean isMarketOpen() {
        return TradingCommonUtils.isMarketOpen(credentials.getKeyId(), credentials.getSecretKey());
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
            return TradingCommonUtils.parseJson(json);
        } catch (Exception e) {
            throw new RuntimeException("JSON parsing failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Make HTTP request (mirrors InitiateTradesLambda)
     */
    private String makeHttpRequest(String url, String apiKey, String secretKey, String method, String body) {
        try {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            
            if ("GET".equals(method)) {
                requestBuilder.get();
            } else if ("POST".equals(method)) {
                RequestBody requestBody = RequestBody.create(body, MediaType.get("application/json"));
                requestBuilder.post(requestBody);
            }
            
            Request request = requestBuilder
                    .addHeader("APCA-API-KEY-ID", apiKey)
                    .addHeader("APCA-API-SECRET-KEY", secretKey)
                    .addHeader("accept", "application/json")
                    .addHeader("content-type", "application/json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("HTTP " + response.code() + ": " + response.body().string());
                }
                return response.body().string();
            }
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get price from quote node (mirrors InitiateTradesLambda)
     */
    private double getPrice(JsonNode quote, String field) {
        return quote.has(field) ? quote.get(field).asDouble() : 0.0;
    }
    
    /**
     * Get bid price (mirrors InitiateTradesLambda)
     */
    private double getBidPrice(JsonNode quote) {
        return getPrice(quote, "bp");
    }
    
    /**
     * Get ask price (mirrors InitiateTradesLambda)
     */
    private double getAskPrice(JsonNode quote) {
        return getPrice(quote, "ap");
    }
    
    /**
     * Log error details to CloudWatch
     */
    private void logToCloudWatch(String errorType, String details) {
        Map<String, Object> errorLog = new HashMap<>();
        errorLog.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorLog.put("errorType", errorType);
        errorLog.put("details", details);
        errorLog.put("lambdaFunction", "InitiateExitTradesLambda");
        
        try {
            String errorJson = TradingCommonUtils.toJson(errorLog);
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