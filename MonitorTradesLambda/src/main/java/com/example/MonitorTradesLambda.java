package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.JsonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.JsonParsingUtils;
import com.trading.common.PortfolioEquityValidator;
import com.trading.common.OptionSymbolUtils;
import com.trading.common.ExitOrderUtils;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AWS Lambda function for monitoring active trading orders.
 * Monitors entry and exit orders every 30 seconds for 15 minutes with time-based logic.
 * 
 * Phase 2 (minutes 10-13): Entry orders are canceled, exit orders are updated to 3% below market.
 * Phase 3 (minutes 13+): Exit orders are converted to market orders.
 */
public class MonitorTradesLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    private static final String ALPACA_URL = System.getenv("ALPACA_API_URL");
    private static final double PRICE_CHANGE_THRESHOLD = 0.0005; // 0.05%
    private static final double EXIT_DISCOUNT = 0.97; // 3% below market price
    private static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    private static final int FIRST_PHASE_MINUTES = 10;
    private static final int SECOND_PHASE_MINUTES = 13;
    private static final int DECIMAL_PLACES = 2;
    
    public MonitorTradesLambda() {
        // No initialization needed - using shared utilities
    }
    
    // Constructor for testing
    public MonitorTradesLambda(Object testParam) {
        // No initialization needed - using shared utilities
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting MonitorTradesLambda execution");
            
            // Extract day type from input
            String dayType = (String) input.get("dayType");
            if (dayType == null) {
                dayType = "normal"; // Default to normal day
            }
            
            // Time window restrictions removed - EventBridge controls when this runs
            
            // Get Alpaca API credentials
            AlpacaCredentials credentials = TradingCommonUtils.getAlpacaCredentials(ALPACA_SECRET);
            
            // Check if market is open
            if (!AlpacaHttpClient.isMarketOpen(credentials)) {
                context.getLogger().log("Market is closed, skipping monitoring");
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_monitored", 0));
            }
            
            // Fetch open orders from Alpaca API
            List<Map<String, Object>> openOrders = getAllOpenOrders(credentials);
            context.getLogger().log("Found " + openOrders.size() + " open orders to monitor");
            
            int ordersProcessed = 0;
            int ordersUpdated = 0;
            int ordersCanceled = 0;
            int ordersConverted = 0;
            
            // Process each open order
            for (Map<String, Object> order : openOrders) {
                try {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    String orderClass = (String) order.get("order_class");
                    String submissionTimeStr = (String) order.get("submitted_at");
                    Double currentLimitPrice = (Double) order.get("limit_price");
                    
                    // Skip non-multi-leg orders
                    if (!"mleg".equals(orderClass)) {
                        context.getLogger().log("Skipping non-multi-leg order " + orderId);
                        continue;
                    }
                    
                    // Determine trade type from order legs
                    String tradeType = determineTradeType(order);
                    if (tradeType == null) {
                        context.getLogger().log("Could not determine trade type for order " + orderId);
                        continue;
                    }
                    
                    context.getLogger().log("Processing order " + orderId + " for " + symbol + " (" + tradeType + ")");
                    
                    // Parse submission time and convert to EST
                    ZonedDateTime submissionTime = ZonedDateTime.parse(submissionTimeStr, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                        .withZoneSameInstant(EST_ZONE);
                    ZonedDateTime now = ZonedDateTime.now(EST_ZONE);
                    
                    // Calculate time elapsed
                    long minutesElapsed = java.time.Duration.between(submissionTime, now).toMinutes();
                    
                    ordersProcessed++;
                    
                    // Determine action based on time elapsed
                    if (minutesElapsed < FIRST_PHASE_MINUTES) {
                        // First phase: Update limit price if spread changed significantly
                        double currentSpreadPrice = calculateCurrentSpreadPrice(order, credentials);
                        if (currentSpreadPrice > 0 && currentLimitPrice > 0) {
                            double priceChangePercent = Math.abs(currentSpreadPrice - currentLimitPrice) / currentLimitPrice;
                            
                            if (priceChangePercent > PRICE_CHANGE_THRESHOLD) {
                                context.getLogger().log("Price changed by " + String.format("%.4f", priceChangePercent * 100) + 
                                    "%, canceling and resubmitting order " + orderId + " with new limit " + currentSpreadPrice);
                                
                                if (cancelAndResubmitOrder(order, currentSpreadPrice, credentials)) {
                                    ordersUpdated++;
                                }
                            }
                        }
                    } else if (minutesElapsed < SECOND_PHASE_MINUTES) {
                        // Second phase: Cancel entry orders, update exit orders to 3% below market
                        if ("entry".equals(tradeType)) {
                            context.getLogger().log("Canceling entry order " + orderId + " after 10 minutes");
                            if (cancelOrder(orderId, credentials)) {
                                ordersCanceled++;
                            }
                        } else if ("exit".equals(tradeType)) {
                            double currentSpreadPrice = calculateCurrentSpreadPrice(order, credentials);
                            if (currentSpreadPrice > 0) {
                                double newLimitPrice = currentSpreadPrice * EXIT_DISCOUNT;
                                context.getLogger().log("Canceling and resubmitting exit order " + orderId + " to 3% below market: " + newLimitPrice);
                                
                                if (cancelAndResubmitOrder(order, newLimitPrice, credentials)) {
                                    ordersUpdated++;
                                }
                            }
                        }
                    } else {
                        // Third phase: Convert exit orders to market orders
                        if ("exit".equals(tradeType)) {
                            context.getLogger().log("Converting exit order " + orderId + " to market order after 13 minutes");
                            
                            // Cancel and resubmit as market order
                            if (cancelAndResubmitAsMarketOrder(order, credentials)) {
                                ordersConverted++;
                                TradingCommonUtils.logTradeSuccess(symbol, "market_order_converted", context);
                                context.getLogger().log("Successfully converted order " + orderId + " to market order");
                            } else {
                                TradingCommonUtils.logTradeFailure(symbol, "market_order_conversion_failed", context);
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    context.getLogger().log("Error processing order " + orderId + ": " + e.getMessage());
                    TradingCommonUtils.logTradeFailure(symbol != null ? symbol : "unknown", "processing_error: " + e.getMessage(), context);
                    e.printStackTrace();
                }
            }
            
            String result = TradingErrorHandler.createSuccessResponse("Monitoring completed", Map.of(
                "orders_processed", ordersProcessed,
                "orders_updated", ordersUpdated,
                "orders_canceled", ordersCanceled,
                "orders_converted", ordersConverted,
                "orders_monitored", openOrders.size()
            ));
            
            context.getLogger().log("MonitorTradesLambda completed: " + result);
            return result;
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "MonitorTradesLambda");
        }
    }
    
    
    
    /**
     * Cancels an order via Alpaca API
     */
    public boolean cancelOrder(String orderId, AlpacaCredentials credentials) {
        try {
            AlpacaHttpClient.makeAlpacaRequest(ALPACA_URL + "/v2/orders/" + orderId, "DELETE", null, credentials);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error canceling order " + orderId, e);
        }
    }
    
    /**
     * Calculates current spread price based on market quotes
     */
    public double calculateCurrentSpreadPrice(Map<String, Object> order, AlpacaCredentials credentials) {
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
                // Entry: debit = near_bid - far_ask
                // (You receive the bid for the short leg, pay the ask for the long leg)
                double nearBid = JsonParsingUtils.getBidPrice(nearQuote);
                double farAsk = JsonParsingUtils.getAskPrice(farQuote);
                spreadPrice = nearBid - farAsk;
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
    private Map<String, JsonNode> getOptionQuotes(String farSymbol, String nearSymbol, AlpacaCredentials credentials) {
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
     * Updates order limit price via Alpaca API
     */
    public boolean updateOrderLimit(String orderId, double newLimitPrice, AlpacaCredentials credentials) {
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
     * Cancels an existing order and resubmits it with a new limit price
     * This is required for mleg orders as they cannot be modified directly
     */
    public boolean cancelAndResubmitOrder(Map<String, Object> originalOrder, double newLimitPrice, AlpacaCredentials credentials) {
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
     * Wait for order cancellation with status verification
     */
    private boolean waitForOrderCancellation(String orderId, AlpacaCredentials credentials) {
        int maxAttempts = 10;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                // Remove blocking sleep - rely on API response timing
                
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
     * Cancels an existing order and resubmits it as a market order
     */
    public boolean cancelAndResubmitAsMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
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
     * Resubmits an order with a new limit price
     */
    private String resubmitOrderWithNewLimit(Map<String, Object> originalOrder, double newLimitPrice, AlpacaCredentials credentials) {
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
     * Submits a new market order based on the original order
     */
    public String submitMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
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
    private List<Map<String, Object>> convertOrderLegsToPositions(Map<String, Object> originalOrder) {
        List<Map<String, Object>> positions = new ArrayList<>();
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
     * Determines trade type (entry/exit) from order legs
     */
    public String determineTradeType(Map<String, Object> order) {
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
    private String determineCalendarSpreadType(List<Map<String, Object>> legs) {
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
     * Gets all open orders from Alpaca API
     */
    public List<Map<String, Object>> getAllOpenOrders(AlpacaCredentials credentials) {
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
    
}
