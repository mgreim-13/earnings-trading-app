package com.trading.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.trading.common.models.AlpacaCredentials;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Portfolio equity validation - checks if there's enough buying power for a trade
 * while ensuring trades don't exceed 8% of total portfolio equity.
 */
public class PortfolioEquityValidator {
    
    private static final double MAX_POSITION_SIZE_PERCENT = 0.08; // 8% max per trade of portfolio equity
    
    /**
     * Check if there's enough buying power for a trade with position size limits.
     * Uses buying_power for availability check and equity for position size limits.
     * 
     * @param tradeValue The dollar amount needed for the trade
     * @param credentials Alpaca API credentials
     * @param context Lambda context for logging
     * @return true if enough buying power and within position size limits, false if not
     */
    public static boolean hasSufficientEquity(double tradeValue, AlpacaCredentials credentials, Context context) {
        try {
            // Get account information
            String responseBody = AlpacaHttpClient.getAlpacaTrading("/account", credentials);
            JsonNode accountNode = JsonUtils.parseJson(responseBody);
            
            // Get buying power (actual available funds for trading)
            double buyingPower = accountNode.get("buying_power").asDouble();
            
            // Get portfolio equity (total account value for position size limits)
            double portfolioEquity = accountNode.get("equity").asDouble();
            
            // Check if we have enough buying power
            boolean hasEnoughBuyingPower = tradeValue <= buyingPower;
            
            // Check position size limit (max 8% of portfolio equity per trade)
            double maxTradeValue = portfolioEquity * MAX_POSITION_SIZE_PERCENT;
            boolean withinPositionSizeLimit = tradeValue <= maxTradeValue;
            
            // Both conditions must be met
            boolean sufficient = hasEnoughBuyingPower && withinPositionSizeLimit;
            
            // Log detailed information if context is available
            if (context != null) {
                double cash = accountNode.get("cash").asDouble();
                context.getLogger().log("Trading validation: tradeValue=$" + String.format("%.2f", tradeValue) + 
                    ", buyingPower=$" + String.format("%.2f", buyingPower) + 
                    ", portfolioEquity=$" + String.format("%.2f", portfolioEquity) + 
                    ", cash=$" + String.format("%.2f", cash) + 
                    ", maxTradeValue=$" + String.format("%.2f", maxTradeValue) + 
                    ", hasEnoughBuyingPower=" + hasEnoughBuyingPower + 
                    ", withinPositionSizeLimit=" + withinPositionSizeLimit + 
                    ", sufficient=" + sufficient);
            }
            
            return sufficient;
            
        } catch (Exception e) {
            // Log if context is available
            if (context != null) {
                context.getLogger().log("Error checking buying power and equity: " + e.getMessage());
            }
            return false;
        }
    }
}
