package com.trading.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.models.AlpacaCredentials;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for all trading lambda functions
 */
public class TradingCommonUtils {
    
    // Shared clients
    private static final SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder().build();
    
    // HTTP client is now handled by AlpacaHttpClient
    
    
    /**
     * Check if a date is a market holiday using Finnhub API
     */
    public static boolean isMarketHoliday(LocalDate date, String finnhubApiKey) {
        try {
            // Use Finnhub calendar API to check for earnings/holidays
            String url = "https://finnhub.io/api/v1/calendar/earnings?from=" + date + "&to=" + date + "&token=" + finnhubApiKey;
            String responseBody = makeHttpRequest(url, "", "", "GET", null);
            JsonNode responseNode = JsonUtils.parseJson(responseBody);
            
            // If there are no earnings events, it might be a holiday
            // This is a simplified approach - in production you'd use a dedicated holiday API
            return !responseNode.has("earningsCalendar") || responseNode.get("earningsCalendar").size() == 0;
            
        } catch (Exception e) {
            // Conservative approach - assume not a holiday on error
            return false;
        }
    }
    
    /**
     * Get Alpaca API credentials from AWS Secrets Manager
     */
    public static AlpacaCredentials getAlpacaCredentials(String secretName) {
        return executeWithErrorHandling("getting API credentials", () -> {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();

            JsonNode secretNode = JsonUtils.parseJson(secretString);
            
            AlpacaCredentials credentials = new AlpacaCredentials();
            
            // Handle different secret formats
            if (secretNode.has("keyId")) {
                credentials.setKeyId(secretNode.get("keyId").asText());
            } else if (secretNode.has("apiKey")) {
                credentials.setApiKey(secretNode.get("apiKey").asText());
            }
            
            if (secretNode.has("secretKey")) {
                credentials.setSecretKey(secretNode.get("secretKey").asText());
            }
            
            if (secretNode.has("baseUrl")) {
                credentials.setBaseUrl(secretNode.get("baseUrl").asText());
            } else {
                credentials.setBaseUrl("https://paper-api.alpaca.markets/v2");
            }

            return credentials;
        });
    }
    
    /**
     * Get Alpaca API credentials as Map for backward compatibility
     */
    public static Map<String, String> getAlpacaCredentialsAsMap(String secretName) {
        AlpacaCredentials credentials = getAlpacaCredentials(secretName);
        Map<String, String> result = new HashMap<>();
        result.put("apiKey", credentials.getApiKeyId());
        result.put("secretKey", credentials.getSecretKey());
        return result;
    }
    
    
    /**
     * Make HTTP request with API key and secret
     * @deprecated Use AlpacaHttpClient.makeAlpacaRequest() instead
     */
    @Deprecated
    public static String makeHttpRequest(String url, String apiKey, String secretKey, String method, String body) {
        try {
            AlpacaCredentials credentials = new AlpacaCredentials();
            credentials.setApiKey(apiKey);
            credentials.setSecretKey(secretKey);
            return AlpacaHttpClient.makeAlpacaRequest(url, method, body, credentials);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Log successful trade to CloudWatch
     */
    public static void logTradeSuccess(String ticker, String orderId, Context context) {
        context.getLogger().log(String.format(
            "SUCCESSFUL_TRADE: ticker=%s, orderId=%s, timestamp=%s", 
            ticker, 
            orderId, 
            LocalDateTime.now(CommonConstants.EST_ZONE)
        ));
    }

    /**
     * Log failed trade to CloudWatch
     */
    public static void logTradeFailure(String ticker, String reason, Context context) {
        context.getLogger().log(String.format(
            "FAILED_TRADE: ticker=%s, reason=%s, timestamp=%s", 
            ticker, 
            reason, 
            LocalDateTime.now(CommonConstants.EST_ZONE)
        ));
    }
    
    
    /**
     * Execute operation with error handling
     */
    public static <T> T executeWithErrorHandling(String operation, java.util.function.Supplier<T> operationSupplier) {
        try {
            return operationSupplier.get();
        } catch (Exception e) {
            throw new RuntimeException("Error " + operation + ": " + e.getMessage(), e);
        }
    }
    
    
    /**
     * Get current date string in EST
     */
    public static String getCurrentDateString() {
        return LocalDateTime.now(CommonConstants.EST_ZONE).toLocalDate().toString();
    }
    
    /**
     * Get current datetime string in EST
     */
    public static String getCurrentDateTimeString() {
        return LocalDateTime.now(CommonConstants.EST_ZONE).toString();
    }
    
    /**
     * Common setup method for order monitoring lambdas
     * Handles credentials, market check, and order filtering
     * 
     * @param orderType "entry" or "exit" to filter orders
     * @param operationName Name of the operation for logging (e.g., "cancel", "update", "convert")
     * @param context Lambda context for logging
     * @return OrderMonitoringSetup containing filtered orders and credentials, or null if market is closed
     */
    public static OrderMonitoringSetup setupOrderMonitoring(String orderType, String operationName, Context context) {
        try {
            // Get Alpaca API credentials
            AlpacaCredentials credentials = getAlpacaCredentials(System.getenv("ALPACA_SECRET_NAME"));
            
            // Check if market is open
            if (!AlpacaHttpClient.isMarketOpen(credentials)) {
                context.getLogger().log("Market is closed, skipping " + orderType + " order " + operationName + "s");
                return null; // Indicates market is closed
            }
            
            // Fetch open orders from Alpaca API
            List<Map<String, Object>> openOrders = getAllOpenOrders(credentials);
            context.getLogger().log("Found " + openOrders.size() + " open orders to check");
            
            // Filter for specific order type
            List<Map<String, Object>> filteredOrders = filterOrdersByType(openOrders, orderType);
            context.getLogger().log("Found " + filteredOrders.size() + " " + orderType + " orders to " + operationName);
            
            return new OrderMonitoringSetup(credentials, filteredOrders, openOrders.size());
            
        } catch (Exception e) {
            context.getLogger().log("Error in setupOrderMonitoring: " + e.getMessage());
            throw new RuntimeException("Failed to setup order monitoring", e);
        }
    }
    
    /**
     * Helper class to hold order monitoring setup data
     */
    public static class OrderMonitoringSetup {
        public final AlpacaCredentials credentials;
        public final List<Map<String, Object>> filteredOrders;
        public final int totalOrders;
        
        public OrderMonitoringSetup(AlpacaCredentials credentials, List<Map<String, Object>> filteredOrders, int totalOrders) {
            this.credentials = credentials;
            this.filteredOrders = filteredOrders;
            this.totalOrders = totalOrders;
        }
    }
    
    // Order monitoring constants
    private static final String ALPACA_URL = System.getenv("ALPACA_API_URL");
    private static final int DECIMAL_PLACES = 2;
    private static final double PRICE_CHANGE_THRESHOLD = 0.0005; // 0.05%
    private static final double EXIT_DISCOUNT = 0.97; // 3% below market price
    
    /**
     * Gets all open orders from Alpaca API
     */
    public static List<Map<String, Object>> getAllOpenOrders(AlpacaCredentials credentials) {
        try {
            String responseBody = AlpacaHttpClient.getAlpacaTrading("/orders?status=open&limit=100", credentials);
            JsonNode ordersNode = JsonUtils.parseJson(responseBody);
            
            List<Map<String, Object>> openOrders = new ArrayList<>();
            if (ordersNode.isArray()) {
                for (JsonNode orderNode : ordersNode) {
                    Map<String, Object> order = new HashMap<>();
                    order.put("orderId", orderNode.get("id").asText());
                    order.put("symbol", orderNode.get("symbol").asText());
                    order.put("status", orderNode.get("status").asText());
                    order.put("side", orderNode.get("side").asText());
                    order.put("order_type", orderNode.get("order_type").asText());
                    order.put("order_class", orderNode.get("order_class").asText());
                    order.put("qty", orderNode.get("qty").asText());
                    order.put("limit_price", orderNode.get("limit_price") != null ? orderNode.get("limit_price").asDouble() : null);
                    order.put("submitted_at", orderNode.get("submitted_at").asText());
                    
                    // Handle legs for multi-leg orders
                    if (orderNode.has("legs") && orderNode.get("legs").isArray()) {
                        List<Map<String, Object>> legs = new ArrayList<>();
                        for (JsonNode legNode : orderNode.get("legs")) {
                            Map<String, Object> leg = new HashMap<>();
                            leg.put("symbol", legNode.get("symbol").asText());
                            leg.put("side", legNode.get("side").asText());
                            leg.put("qty", legNode.get("qty").asText());
                            // Add additional fields that Alpaca provides
                            if (legNode.has("ratio_qty")) {
                                leg.put("ratio_qty", legNode.get("ratio_qty").asText());
                            }
                            if (legNode.has("position_intent")) {
                                leg.put("position_intent", legNode.get("position_intent").asText());
                            }
                            legs.add(leg);
                        }
                        order.put("legs", legs);
                    }
                    
                    openOrders.add(order);
                }
            }
            
            return openOrders;
            
        } catch (Exception e) {
            throw new RuntimeException("Error fetching open orders from Alpaca", e);
        }
    }
    
    /**
     * Determines trade type (entry/exit) from order legs
     */
    public static String determineTradeType(Map<String, Object> order) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) order.get("legs");
        
        if (legs == null || legs.size() < 2) {
            return null;
        }
        
        // For calendar spreads, determine entry/exit based on spread structure
        // Entry: Buy far leg (longer expiration), sell near leg (shorter expiration)
        // Exit: Sell far leg, buy near leg
        return determineCalendarSpreadType(legs);
    }
    
    /**
     * Determine calendar spread type by analyzing expiration dates and sides
     */
    private static String determineCalendarSpreadType(List<Map<String, Object>> legs) {
        Map<String, Object> farLeg = null;
        Map<String, Object> nearLeg = null;
        
        // Find far and near legs by analyzing symbols for expiration dates
        for (Map<String, Object> leg : legs) {
            String symbol = (String) leg.get("symbol");
            if (symbol != null) {
                try {
                    // Parse option symbol to get expiration
                    Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(symbol);
                    String expiration = (String) parsed.get("expiration");
                    LocalDate expDate = LocalDate.parse(expiration);
                    
                    if (farLeg == null || nearLeg == null) {
                        if (farLeg == null) {
                            farLeg = leg;
                        } else {
                            nearLeg = leg;
                        }
                    } else {
                        // Determine which is far/near based on expiration
                        LocalDate farExp = LocalDate.parse((String) OptionSymbolUtils.parseOptionSymbol((String) farLeg.get("symbol")).get("expiration"));
                        LocalDate nearExp = LocalDate.parse((String) OptionSymbolUtils.parseOptionSymbol((String) nearLeg.get("symbol")).get("expiration"));
                        
                        if (expDate.isAfter(farExp)) {
                            nearLeg = farLeg;
                            farLeg = leg;
                        } else if (expDate.isBefore(nearExp)) {
                            nearLeg = leg;
                        }
                    }
                } catch (Exception e) {
                    // Skip invalid symbols
                    continue;
                }
            }
        }
        
        if (farLeg == null || nearLeg == null) {
            return null;
        }
        
        // Determine trade type based on far leg side
        // Entry: Buy far leg (longer expiration)
        // Exit: Sell far leg (longer expiration)
        String farLegSide = (String) farLeg.get("side");
        return "buy".equals(farLegSide) ? "entry" : "exit";
    }
    
    /**
     * Calculates current spread price based on market quotes
     */
    public static double calculateCurrentSpreadPrice(Map<String, Object> order, AlpacaCredentials credentials) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> legs = (List<Map<String, Object>>) order.get("legs");
            String tradeType = (String) order.get("tradeType");
            
            if (legs == null || legs.size() < 2) {
                throw new RuntimeException("Invalid legs configuration");
            }
            
            // Extract far and near symbols
            String farSymbol = null;
            String nearSymbol = null;
            
            for (Map<String, Object> leg : legs) {
                String side = (String) leg.get("side");
                if ("buy".equals(side)) {
                    farSymbol = (String) leg.get("symbol");
                } else if ("sell".equals(side)) {
                    nearSymbol = (String) leg.get("symbol");
                }
            }
            
            if (farSymbol == null || nearSymbol == null) {
                throw new RuntimeException("Could not determine far and near symbols from legs");
            }
            
            // Get current quotes
            Map<String, JsonNode> quotes = getOptionQuotes(farSymbol, nearSymbol, credentials);
            JsonNode farQuote = quotes.get(farSymbol);
            JsonNode nearQuote = quotes.get(nearSymbol);
            
            if (farQuote == null || nearQuote == null) {
                throw new RuntimeException("Could not get quotes for symbols: " + farSymbol + ", " + nearSymbol);
            }
            
            double spreadPrice;
            if ("entry".equals(tradeType)) {
                // Entry: debit = farAsk - nearBid
                // (You pay the ask for the long leg, receive the bid for the short leg)
                double nearBid = JsonParsingUtils.getBidPrice(nearQuote);
                double farAsk = JsonParsingUtils.getAskPrice(farQuote);
                spreadPrice = farAsk - nearBid;
            } else {
                // Exit: credit = far_bid - near_ask
                // (You receive the bid for the far leg you're selling, pay the ask for the near leg you're buying back)
                double farBid = JsonParsingUtils.getBidPrice(farQuote);
                double nearAsk = JsonParsingUtils.getAskPrice(nearQuote);
                spreadPrice = farBid - nearAsk;
            }
            
            // Round to 2 decimal places
            return Math.round(spreadPrice * Math.pow(10, DECIMAL_PLACES)) / Math.pow(10, DECIMAL_PLACES);
            
        } catch (Exception e) {
            throw new RuntimeException("Error calculating spread price", e);
        }
    }
    
    /**
     * Gets option quotes from Alpaca API
     */
    private static Map<String, JsonNode> getOptionQuotes(String farSymbol, String nearSymbol, AlpacaCredentials credentials) {
        try {
            String symbols = farSymbol + "," + nearSymbol;
            String endpoint = "/options/quotes/latest?symbols=" + symbols + "&feed=opra";
            String responseBody = AlpacaHttpClient.getAlpacaOptions(endpoint, credentials);
            
            JsonNode quotesNode = JsonUtils.parseJson(responseBody).get("quotes");
            Map<String, JsonNode> quotes = new HashMap<>();
            
            if (quotesNode != null && quotesNode.isObject()) {
                quotesNode.fields().forEachRemaining(entry -> {
                    quotes.put(entry.getKey(), entry.getValue());
                });
            }
            
            return quotes;
            
        } catch (Exception e) {
            throw new RuntimeException("Error getting option quotes", e);
        }
    }
    
    /**
     * Cancels an order via Alpaca API
     */
    public static boolean cancelOrder(String orderId, AlpacaCredentials credentials) {
        try {
            AlpacaHttpClient.makeAlpacaRequest(ALPACA_URL + "/v2/orders/" + orderId, "DELETE", null, credentials);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error canceling order " + orderId, e);
        }
    }
    
    /**
     * Wait for order cancellation with status verification
     */
    public static boolean waitForOrderCancellation(String orderId, AlpacaCredentials credentials) {
        int maxAttempts = 10;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                // Check if order is actually cancelled
                String responseBody = AlpacaHttpClient.getAlpacaTrading("/orders/" + orderId, credentials);
                JsonNode orderNode = JsonUtils.parseJson(responseBody);
                String status = orderNode.get("status").asText();
                
                if ("canceled".equals(status) || "rejected".equals(status)) {
                    return true;
                }
                
                attempt++;
            } catch (Exception e) {
                // If we can't check status, assume it's cancelled after timeout
                if (attempt >= maxAttempts - 1) {
                    return true;
                }
                attempt++;
            }
        }
        
        return false;
    }
    
    /**
     * Cancels an existing order and resubmits it with a new limit price
     * This is required for mleg orders as they cannot be modified directly
     */
    public static boolean cancelAndResubmitOrder(Map<String, Object> originalOrder, double newLimitPrice, AlpacaCredentials credentials) {
        try {
            String orderId = (String) originalOrder.get("orderId");
            
            // First, cancel the existing order
            if (!cancelOrder(orderId, credentials)) {
                throw new RuntimeException("Failed to cancel order " + orderId);
            }
            
            // Wait for cancellation to process with status verification
            if (!waitForOrderCancellation(orderId, credentials)) {
                throw new RuntimeException("Order " + orderId + " was not cancelled within timeout");
            }
            
            // Resubmit with new limit price
            String newOrderId = resubmitOrderWithNewLimit(originalOrder, newLimitPrice, credentials);
            if (newOrderId != null) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Error canceling and resubmitting order", e);
        }
    }
    
    /**
     * Resubmits an order with a new limit price
     */
    private static String resubmitOrderWithNewLimit(Map<String, Object> originalOrder, double newLimitPrice, AlpacaCredentials credentials) {
        try {
            // Calculate trade value for equity check using existing pattern
            int qty = Integer.parseInt(originalOrder.get("qty").toString());
            double tradeValue = Math.abs(newLimitPrice * qty * 100); // 100 is contract multiplier
            
            // Check if we have sufficient equity for this trade
            if (!PortfolioEquityValidator.hasSufficientEquity(tradeValue, credentials, null)) {
                throw new RuntimeException("Insufficient equity for order resubmission: $" + String.format("%.2f", tradeValue));
            }
            
            // Convert order to position format for reusable utility
            List<Map<String, Object>> positions = convertOrderLegsToPositions(originalOrder);
            
            // Use reusable utility to create limit order
            String exitOrderJson = ExitOrderUtils.createCalendarSpreadExitOrderWithLimit(positions, newLimitPrice);
            
            // Use existing submitOrder pattern
            Map<String, Object> orderResult = ExitOrderUtils.submitExitOrder(exitOrderJson, credentials);
            return (String) orderResult.get("orderId");
            
        } catch (Exception e) {
            throw new RuntimeException("Error resubmitting order with new limit price", e);
        }
    }
    
    /**
     * Cancels an existing order and resubmits it as a market order
     */
    public static boolean cancelAndResubmitAsMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
        try {
            String orderId = (String) originalOrder.get("orderId");
            
            // First, cancel the existing order
            if (!cancelOrder(orderId, credentials)) {
                throw new RuntimeException("Failed to cancel order " + orderId);
            }
            
            // Wait for cancellation to process with status verification
            if (!waitForOrderCancellation(orderId, credentials)) {
                throw new RuntimeException("Order " + orderId + " was not cancelled within timeout");
            }
            
            // Resubmit as market order
            String newOrderId = submitMarketOrder(originalOrder, credentials);
            if (newOrderId != null) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Error canceling and resubmitting as market order", e);
        }
    }
    
    /**
     * Submits a new market order based on the original order
     */
    public static String submitMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
        try {
            // For market orders, we can't calculate exact trade value since we don't know the execution price
            // We'll use the original limit price as a conservative estimate for equity validation
            Double originalLimitPrice = (Double) originalOrder.get("limit_price");
            if (originalLimitPrice != null) {
                int qty = Integer.parseInt(originalOrder.get("qty").toString());
                double estimatedTradeValue = Math.abs(originalLimitPrice * qty * 100); // 100 is contract multiplier
                
                // Check if we have sufficient equity for this trade
                if (!PortfolioEquityValidator.hasSufficientEquity(estimatedTradeValue, credentials, null)) {
                    throw new RuntimeException("Insufficient equity for market order: $" + String.format("%.2f", estimatedTradeValue));
                }
            }
            
            // Convert order to position format for reusable utility
            List<Map<String, Object>> positions = convertOrderLegsToPositions(originalOrder);
            
            // Use reusable utility to create market order
            String exitOrderJson = ExitOrderUtils.createCalendarSpreadExitOrder(positions, "market");
            
            // Use existing submitOrder pattern
            Map<String, Object> orderResult = ExitOrderUtils.submitExitOrder(exitOrderJson, credentials);
            return (String) orderResult.get("orderId");
            
        } catch (Exception e) {
            throw new RuntimeException("Error submitting market order", e);
        }
    }
    
    /**
     * Helper method to convert order legs to position format for reusable utilities
     * Reuses existing conversion logic
     */
    private static List<Map<String, Object>> convertOrderLegsToPositions(Map<String, Object> originalOrder) {
        List<Map<String, Object>> positions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) originalOrder.get("legs");
        
        for (Map<String, Object> leg : legs) {
            Map<String, Object> position = new HashMap<>();
            position.put("symbol", leg.get("symbol"));
            position.put("qty", leg.get("qty"));
            // Determine side based on position_intent
            String positionIntent = (String) leg.get("position_intent");
            if ("buy_to_open".equals(positionIntent) || "buy_to_close".equals(positionIntent)) {
                position.put("side", "long");
            } else {
                position.put("side", "short");
            }
            positions.add(position);
        }
        
        return positions;
    }
    
    /**
     * Filters orders by trade type (entry/exit)
     */
    public static List<Map<String, Object>> filterOrdersByType(List<Map<String, Object>> orders, String targetType) {
        List<Map<String, Object>> filteredOrders = new ArrayList<>();
        
        for (Map<String, Object> order : orders) {
            String orderClass = (String) order.get("order_class");
            
            // Skip non-multi-leg orders
            if (!"mleg".equals(orderClass)) {
                continue;
            }
            
            // Determine trade type from order legs
            String tradeType = determineTradeType(order);
            if (targetType.equals(tradeType)) {
                filteredOrders.add(order);
            }
        }
        
        return filteredOrders;
    }

    /**
     * Updates order limit price via Alpaca API
     */
    public static boolean updateOrderLimit(String orderId, double newLimitPrice, AlpacaCredentials credentials) {
        try {
            Map<String, Object> updateData = Map.of("limit_price", newLimitPrice);
            String jsonBody = JsonUtils.toJson(updateData);
            AlpacaHttpClient.makeAlpacaRequest(ALPACA_URL + "/v2/orders/" + orderId, "PATCH", jsonBody, credentials);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error updating order limit for " + orderId, e);
        }
    }

    /**
     * Gets the price change threshold constant
     */
    public static double getPriceChangeThreshold() {
        return PRICE_CHANGE_THRESHOLD;
    }

    /**
     * Gets the exit discount constant
     */
    public static double getExitDiscount() {
        return EXIT_DISCOUNT;
    }
    
    // Private helper methods
    
}
