package com.trading.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.models.AlpacaCredentials;

import java.util.*;

/**
 * AWS Lambda function for converting exit orders to market orders.
 * Checks all open exit trades and converts them to market orders.
 */
public class ConvertExitOrdersToMarketLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final String ALPACA_SECRET = System.getenv("ALPACA_SECRET_NAME");
    
    public ConvertExitOrdersToMarketLambda() {
        // No initialization needed - using shared utilities
    }
    
    // Constructor for testing
    public ConvertExitOrdersToMarketLambda(Object testParam) {
        // No initialization needed - using shared utilities
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting ConvertExitOrdersToMarketLambda execution");
            
            // Use common setup method
            TradingCommonUtils.OrderMonitoringSetup setup = TradingCommonUtils.setupOrderMonitoring("exit", "convert", context);
            if (setup == null) {
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_converted", 0));
            }
            
            int ordersProcessed = 0;
            int ordersConverted = 0;
            
            // Process each exit order - no time logic, just convert all exit orders
            for (Map<String, Object> order : setup.filteredOrders) {
                try {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    
                    context.getLogger().log("Processing exit order " + orderId + " for " + symbol);
                    
                    ordersProcessed++;
                    
                    // Convert the exit order to market order
                    context.getLogger().log("Converting exit order " + orderId + " to market order");
                    
                    if (TradingCommonUtils.cancelAndResubmitAsMarketOrder(order, setup.credentials)) {
                        ordersConverted++;
                        TradingCommonUtils.logTradeSuccess(symbol, "market_order_converted", context);
                        context.getLogger().log("Successfully converted exit order " + orderId + " to market order");
                    } else {
                        TradingCommonUtils.logTradeFailure(symbol, "market_order_conversion_failed", context);
                        context.getLogger().log("Failed to convert exit order " + orderId + " to market order");
                    }
                    
                } catch (Exception e) {
                    String orderId = (String) order.get("orderId");
                    String symbol = (String) order.get("symbol");
                    context.getLogger().log("Error processing exit order " + orderId + ": " + e.getMessage());
                    TradingCommonUtils.logTradeFailure(symbol != null ? symbol : "unknown", "exit_order_conversion_error: " + e.getMessage(), context);
                    e.printStackTrace();
                }
            }
            
            String result = TradingErrorHandler.createSuccessResponse("Exit order conversions completed", Map.of(
                "orders_processed", ordersProcessed,
                "orders_converted", ordersConverted,
                "exit_orders_found", setup.filteredOrders.size()
            ));
            
            context.getLogger().log("ConvertExitOrdersToMarketLambda completed: " + result);
            return result;
            
        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "ConvertExitOrdersToMarketLambda");
        }
    }
}
