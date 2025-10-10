package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.models.AlpacaCredentials;

import java.util.*;

/**
 * AWS Lambda function for canceling entry orders.
 * Checks all open entry trades and cancels them.
 */
public class CancelEntryOrdersLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    
    public CancelEntryOrdersLambda() {
        // No initialization needed - using shared utilities
    }
    
    // Constructor for testing
    public CancelEntryOrdersLambda(Object testParam) {
        // No initialization needed - using shared utilities
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting CancelEntryOrdersLambda execution");
            
            // Use common setup method
            TradingCommonUtils.OrderMonitoringSetup setup = TradingCommonUtils.setupOrderMonitoring("entry", "cancel", context);
            if (setup == null) {
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_canceled", 0));
            }
            
            int ordersProcessed = 0;
            int ordersCanceled = 0;
            
            // Process each entry order - no time logic, just cancel all entry orders
            for (Map<String, Object> order : setup.filteredOrders) {
                try {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    
                    context.getLogger().log("Processing entry order " + orderId + " for " + symbol);
                    
                    ordersProcessed++;
                    
                    // Cancel the entry order
                    context.getLogger().log("Canceling entry order " + orderId);
                    
                    if (TradingCommonUtils.cancelOrder(orderId, setup.credentials)) {
                        ordersCanceled++;
                        context.getLogger().log("Successfully canceled entry order " + orderId);
                    } else {
                        context.getLogger().log("Failed to cancel entry order " + orderId);
                    }
                    
                } catch (Exception e) {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    context.getLogger().log("Error processing entry order " + orderId + ": " + e.getMessage());
                    TradingCommonUtils.logTradeFailure(symbol != null ? symbol : "unknown", "entry_order_cancel_error: " + e.getMessage(), context);
                    e.printStackTrace();
                }
            }
            
            String result = TradingErrorHandler.createSuccessResponse("Entry order cancellations completed", Map.of(
                "orders_processed", ordersProcessed,
                "orders_canceled", ordersCanceled,
                "entry_orders_found", setup.filteredOrders.size()
            ));
            
            context.getLogger().log("CancelEntryOrdersLambda completed: " + result);
            return result;
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "CancelEntryOrdersLambda");
        }
    }
}
