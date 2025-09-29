package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AWS Lambda function for monitoring active trading orders.
 * Monitors entry and exit orders every 30 seconds for 15 minutes with time-based logic.
 */
public class MonitorTradesLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    private static final String ALPACA_URL = System.getenv("ALPACA_API_URL");
    private static final String ALPACA_DATA_URL = "https://data.alpaca.markets/v1beta1";
    private static final double PRICE_CHANGE_THRESHOLD = 0.0005; // 0.05%
    private static final double EXIT_DISCOUNT = 0.97; // 3% below market price
    private static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    private static final int FIRST_PHASE_MINUTES = 10;
    private static final int SECOND_PHASE_MINUTES = 13;
    private static final int CONTRACT_QUANTITY = 1;
    private static final int DECIMAL_PLACES = 2;
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_NO_CONTENT = 204;
    
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
            
            // Check if we're in a valid monitoring window
            if (!isInMonitoringWindow(dayType)) {
                context.getLogger().log("Not in monitoring window for " + dayType + " day, skipping execution");
                return TradingErrorHandler.createSkippedResponse("outside_monitoring_window", Map.of("orders_monitored", 0));
            }
            
            // Get Alpaca API credentials
            AlpacaCredentials credentials = TradingCommonUtils.getAlpacaCredentials(ALPACA_SECRET);
            
            // Check if market is open
            if (!TradingCommonUtils.isMarketOpen(credentials.getApiKeyId(), credentials.getSecretKey())) {
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
                    
                    // Parse submission time
                    ZonedDateTime submissionTime = ZonedDateTime.parse(submissionTimeStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    ZonedDateTime now = ZonedDateTime.now(EST_ZONE);
                    
                    // Calculate time elapsed
                    long minutesElapsed = java.time.Duration.between(submissionTime, now).toMinutes();
                    
                    ordersProcessed++;
                    
                    // Determine action based on time elapsed
                    if (minutesElapsed < FIRST_PHASE_MINUTES) {
                        // First phase: Update limit price if spread changed significantly
                        double currentSpreadPrice = calculateCurrentSpreadPrice(order, credentials);
                        if (currentSpreadPrice > 0) {
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
            String url = ALPACA_URL + "/v2/orders/" + orderId;
            TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "DELETE", null);
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
                // Entry: debit = far_ask - near_bid
                double farAsk = getAskPrice(farQuote);
                double nearBid = getBidPrice(nearQuote);
                spreadPrice = farAsk - nearBid;
            } else {
                // Exit: credit = far_bid - near_ask
                double farBid = getBidPrice(farQuote);
                double nearAsk = getAskPrice(nearQuote);
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
            String url = ALPACA_DATA_URL + "/options/quotes/latest?symbols=" + symbols + "&feed=indicative";
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
            
            JsonNode quotesNode = TradingCommonUtils.parseJson(responseBody).get("quotes");
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
    
    public double getBidPrice(JsonNode quote) {
        return getPrice(quote, "bp");
    }
    
    public double getAskPrice(JsonNode quote) {
        return getPrice(quote, "ap");
    }
    
    public double getPrice(JsonNode quote, String field) {
        return quote.has(field) ? quote.get(field).asDouble() : 0.0;
    }
    
    /**
     * Updates order limit price via Alpaca API
     */
    public boolean updateOrderLimit(String orderId, double newLimitPrice, AlpacaCredentials credentials) {
        try {
            String url = ALPACA_URL + "/v2/orders/" + orderId;
            Map<String, Object> updateData = Map.of("limit_price", newLimitPrice);
            String jsonBody = TradingCommonUtils.toJson(updateData);
            TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "PATCH", jsonBody);
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
            
            // Wait a moment for the cancellation to process
            Thread.sleep(1000);
            
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
     * Cancels an existing order and resubmits it as a market order
     */
    public boolean cancelAndResubmitAsMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
        try {
            String orderId = (String) originalOrder.get("orderId");
            
            // First, cancel the existing order
            if (!cancelOrder(orderId, credentials)) {
                throw new RuntimeException("Failed to cancel order " + orderId);
            }
            
            // Wait a moment for the cancellation to process
            Thread.sleep(1000);
            
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
            String url = ALPACA_URL + "/v2/orders";
            
            // Build the new order JSON with updated limit price
            Map<String, Object> newOrder = new HashMap<>();
            newOrder.put("symbol", originalOrder.get("symbol"));
            newOrder.put("qty", originalOrder.get("qty"));
            newOrder.put("type", "limit");
            newOrder.put("limit_price", newLimitPrice);
            newOrder.put("time_in_force", originalOrder.get("time_in_force"));
            newOrder.put("order_class", "mleg");
            newOrder.put("legs", originalOrder.get("legs"));
            
            String jsonBody = TradingCommonUtils.toJson(newOrder);
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "POST", jsonBody);
            
            JsonNode response = TradingCommonUtils.parseJson(responseBody);
            return response.get("id").asText();
        } catch (Exception e) {
            throw new RuntimeException("Error resubmitting order with new limit price", e);
        }
    }

    /**
     * Submits a new market order based on the original order
     */
    public String submitMarketOrder(Map<String, Object> originalOrder, AlpacaCredentials credentials) {
        try {
            String url = ALPACA_URL + "/v2/orders";
            
            // Build market order JSON
            Map<String, Object> marketOrder = new HashMap<>();
            marketOrder.put("symbol", originalOrder.get("symbol"));
            marketOrder.put("qty", originalOrder.get("qty"));
            marketOrder.put("type", "market");
            marketOrder.put("order_class", "mleg");
            marketOrder.put("legs", originalOrder.get("legs"));
            
            String jsonBody = TradingCommonUtils.toJson(marketOrder);
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "POST", jsonBody);
            
            JsonNode jsonNode = TradingCommonUtils.parseJson(responseBody);
            return jsonNode.get("id").asText();
            
        } catch (Exception e) {
            throw new RuntimeException("Error submitting market order", e);
        }
    }
    
    
    /**
     * Checks if we're in a valid monitoring window
     * Normal days: 9:45:30 AM - 10:00 AM EST and 3:45:30 PM - 4:00 PM EST
     * Early closure days: 9:45:30 AM - 10:00 AM EST and 12:45:30 PM - 1:00 PM EST
     */
    public boolean isInMonitoringWindow(String dayType) {
        try {
            LocalDateTime now = LocalDateTime.now(EST_ZONE);
            int hour = now.getHour();
            int minute = now.getMinute();
            
            // Morning window: 9:45:30 AM - 10:00 AM EST (same for both day types)
            boolean inMorningWindow = (hour == 9 && minute >= 45 && minute <= 59) || 
                                    (hour == 10 && minute == 0);
            
            // Afternoon window depends on day type
            boolean inAfternoonWindow;
            if ("early".equals(dayType)) {
                // Early closure: 12:45:30 PM - 1:00 PM EST
                inAfternoonWindow = (hour == 12 && minute >= 45 && minute <= 59) || 
                                  (hour == 13 && minute == 0);
            } else {
                // Normal day: 3:45:30 PM - 4:00 PM EST
                inAfternoonWindow = (hour == 15 && minute >= 45 && minute <= 59) || 
                                  (hour == 16 && minute == 0);
            }
            
            boolean inWindow = inMorningWindow || inAfternoonWindow;
            
            return inWindow;
        } catch (Exception e) {
            return false; // Conservative approach - don't monitor if we can't determine time
        }
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
        
        // Look for buy and sell legs
        boolean hasBuy = false;
        boolean hasSell = false;
        
        for (Map<String, Object> leg : legs) {
            String side = (String) leg.get("side");
            if ("buy".equals(side)) {
                hasBuy = true;
            } else if ("sell".equals(side)) {
                hasSell = true;
            }
        }
        
        // Entry trade: buy far leg, sell near leg
        // Exit trade: sell far leg, buy near leg
        // For now, we'll determine based on the first leg's side
        if (hasBuy && hasSell) {
            // This is a spread trade - determine if entry or exit based on leg order
            // For simplicity, we'll assume the first leg determines the trade type
            String firstLegSide = (String) legs.get(0).get("side");
            return "buy".equals(firstLegSide) ? "entry" : "exit";
        }
        
        return null;
    }
    
    /**
     * Gets all open orders from Alpaca API
     */
    public List<Map<String, Object>> getAllOpenOrders(AlpacaCredentials credentials) {
        try {
            String url = ALPACA_URL + "/v2/orders?status=open&limit=100";
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
            JsonNode ordersNode = TradingCommonUtils.parseJson(responseBody);
            
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
