package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.models.AlpacaCredentials;

import java.util.*;

/**
 * AWS Lambda function for updating entry orders with new limit prices set to market price.
 * Checks all open entry trades and replaces them with new trades with new limit orders
 * set to current market price if the price has changed significantly.
 */
public class UpdateEntryOrdersAtMarketLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    
    public UpdateEntryOrdersAtMarketLambda() {
        // No initialization needed - using shared utilities
    }
    
    // Constructor for testing
    public UpdateEntryOrdersAtMarketLambda(Object testParam) {
        // No initialization needed - using shared utilities
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting UpdateEntryOrdersAtMarketLambda execution");
            
            // Use common setup method
            TradingCommonUtils.OrderMonitoringSetup setup = TradingCommonUtils.setupOrderMonitoring("entry", "update", context);
            if (setup == null) {
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_updated", 0));
            }
            
            int ordersProcessed = 0;
            int ordersUpdated = 0;
            
            // Process each entry order - no time logic, just update all entry orders
            for (Map<String, Object> order : setup.filteredOrders) {
                try {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    Double currentLimitPrice = (Double) order.get("limit_price");
                    
                    context.getLogger().log("Processing entry order " + orderId + " for " + symbol);
                    
                    ordersProcessed++;
                    
                    // Calculate current spread price
                    double currentSpreadPrice = TradingCommonUtils.calculateCurrentSpreadPrice(order, setup.credentials);
                    if (currentSpreadPrice > 0 && currentLimitPrice > 0) {
                        double priceChangePercent = Math.abs(currentSpreadPrice - currentLimitPrice) / currentLimitPrice;
                        double threshold = TradingCommonUtils.getPriceChangeThreshold();
                        
                        if (priceChangePercent > threshold) {
                            context.getLogger().log("Price changed by " + String.format("%.4f", priceChangePercent * 100) + 
                                "%, updating entry order " + orderId + " with new limit " + currentSpreadPrice);
                            
                            if (TradingCommonUtils.cancelAndResubmitOrder(order, currentSpreadPrice, setup.credentials)) {
                                ordersUpdated++;
                                context.getLogger().log("Successfully updated entry order " + orderId);
                            } else {
                                context.getLogger().log("Failed to update entry order " + orderId);
                            }
                        } else {
                            context.getLogger().log("Price change " + String.format("%.4f", priceChangePercent * 100) + 
                                "% below threshold, keeping current limit price for order " + orderId);
                        }
                    } else {
                        context.getLogger().log("Could not calculate spread price for order " + orderId + ", skipping");
                    }
                    
                } catch (Exception e) {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    context.getLogger().log("Error processing entry order " + orderId + ": " + e.getMessage());
                    TradingCommonUtils.logTradeFailure(symbol != null ? symbol : "unknown", "entry_order_update_error: " + e.getMessage(), context);
                    e.printStackTrace();
                }
            }
            
            String result = TradingErrorHandler.createSuccessResponse("Entry order updates completed", Map.of(
                "orders_processed", ordersProcessed,
                "orders_updated", ordersUpdated,
                "entry_orders_found", setup.filteredOrders.size()
            ));
            
            context.getLogger().log("UpdateEntryOrdersAtMarketLambda completed: " + result);
            return result;
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "UpdateEntryOrdersAtMarketLambda");
        }
    }
}
