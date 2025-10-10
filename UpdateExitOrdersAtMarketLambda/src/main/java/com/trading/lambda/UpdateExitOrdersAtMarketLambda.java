package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.models.AlpacaCredentials;

import java.util.*;

/**
 * AWS Lambda function for updating exit orders with new limit prices set to market price.
 * Checks all open exit trades and replaces them with new trades with new limit orders
 * set to current market price.
 */
public class UpdateExitOrdersAtMarketLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    
    public UpdateExitOrdersAtMarketLambda() {
        // No initialization needed - using shared utilities
    }
    
    // Constructor for testing
    public UpdateExitOrdersAtMarketLambda(Object testParam) {
        // No initialization needed - using shared utilities
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting UpdateExitOrdersAtMarketLambda execution");
            
            // Get Alpaca API credentials
            AlpacaCredentials credentials = TradingCommonUtils.getAlpacaCredentials(ALPACA_SECRET);
            
            // Check if market is open
            if (!AlpacaHttpClient.isMarketOpen(credentials)) {
                context.getLogger().log("Market is closed, skipping exit order updates");
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_updated", 0));
            }
            
            // Fetch open orders from Alpaca API
            List<Map<String, Object>> openOrders = TradingCommonUtils.getAllOpenOrders(credentials);
            context.getLogger().log("Found " + openOrders.size() + " open orders to check");
            
            // Filter for exit orders only
            List<Map<String, Object>> exitOrders = TradingCommonUtils.filterOrdersByType(openOrders, "exit");
            context.getLogger().log("Found " + exitOrders.size() + " exit orders to update");
            
            int ordersProcessed = 0;
            int ordersUpdated = 0;
            
            // Process each exit order - no time logic, just update all exit orders
            for (Map<String, Object> order : exitOrders) {
                try {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    
                    context.getLogger().log("Processing exit order " + orderId + " for " + symbol);
                    
                    ordersProcessed++;
                    
                    // Calculate current spread price and set to market price
                    double currentSpreadPrice = TradingCommonUtils.calculateCurrentSpreadPrice(order, credentials);
                    if (currentSpreadPrice > 0) {
                        double newLimitPrice = currentSpreadPrice; // Use market price directly
                        context.getLogger().log("Updating exit order " + orderId + " to market price: " + newLimitPrice);
                        
                        if (TradingCommonUtils.cancelAndResubmitOrder(order, newLimitPrice, credentials)) {
                            ordersUpdated++;
                            context.getLogger().log("Successfully updated exit order " + orderId);
                        } else {
                            context.getLogger().log("Failed to update exit order " + orderId);
                        }
                    } else {
                        context.getLogger().log("Could not calculate spread price for exit order " + orderId + ", skipping");
                    }
                    
                } catch (Exception e) {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    context.getLogger().log("Error processing exit order " + orderId + ": " + e.getMessage());
                    TradingCommonUtils.logTradeFailure(symbol != null ? symbol : "unknown", "exit_order_update_error: " + e.getMessage(), context);
                    e.printStackTrace();
                }
            }
            
            String result = TradingErrorHandler.createSuccessResponse("Exit order updates completed", Map.of(
                "orders_processed", ordersProcessed,
                "orders_updated", ordersUpdated,
                "exit_orders_found", exitOrders.size()
            ));
            
            context.getLogger().log("UpdateExitOrdersAtMarketLambda completed: " + result);
            return result;
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "UpdateExitOrdersAtMarketLambda");
        }
    }
}
