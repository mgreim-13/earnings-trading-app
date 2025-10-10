package com.trading.common;

import com.trading.common.models.AlpacaCredentials;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Utility class for creating exit orders for multi-leg option positions.
 * Reuses existing patterns from InitiateTradesLambda and InitiateExitTradesLambda.
 */
public class ExitOrderUtils {
    
    /**
     * Creates a multi-leg exit order for calendar spreads using existing patterns
     * Reuses the same structure as InitiateTradesLambda.buildEntryOrderJson()
     */
    public static String createCalendarSpreadExitOrder(List<Map<String, Object>> positions, String orderType) {
        return TradingCommonUtils.executeWithErrorHandling("building exit order JSON", () -> {
            System.out.println("DEBUG: ExitOrderUtils.createCalendarSpreadExitOrder called with " + positions.size() + " positions");
            System.out.println("DEBUG: Input positions: " + positions);
            
            Map<String, Object> order = new HashMap<>();
            order.put("order_class", "mleg");
            order.put("type", orderType);
            order.put("time_in_force", "day");
            
            System.out.println("DEBUG: Base order created: " + order);
            
            // Add limit price only for limit orders
            if ("limit".equals(orderType)) {
                // This will be set by the caller for limit orders
                // order.put("limit_price", limitPrice);
            }
            
            List<Map<String, Object>> legs = new ArrayList<>();
            // Track leg quantities to compute parent qty as required by Alpaca (top-level qty)
            List<Integer> legQuantities = new ArrayList<>();
            
            for (Map<String, Object> position : positions) {
                System.out.println("DEBUG: Processing position: " + position);
                
                Map<String, Object> leg = new HashMap<>();
                leg.put("symbol", position.get("symbol"));
                
                // Determine side and position intent based on current position
                String currentSide = (String) position.get("side");
                String qty = (String) position.get("qty");
                
                System.out.println("DEBUG: Position side: " + currentSide + ", qty: " + qty);
                
                // For closing positions, we need to do the opposite of the current position
                if ("long".equals(currentSide)) {
                    leg.put("side", "sell");
                    leg.put("position_intent", "sell_to_close");
                } else {
                    leg.put("side", "buy");
                    leg.put("position_intent", "buy_to_close");
                }
                
                // Per Alpaca MLeg docs: legs use ratio_qty only; parent order uses top-level qty
                // Accumulate absolute position quantity to derive parent qty
                int absQty = Math.abs(Integer.parseInt(qty));
                legQuantities.add(absQty);
                
                // Use simplified ratio for calendar spreads (1:1)
                leg.put("ratio_qty", "1");
                
                System.out.println("DEBUG: Created leg: " + leg);
                legs.add(leg);
            }
            
            System.out.println("DEBUG: All legs created: " + legs);
            System.out.println("DEBUG: Leg quantities: " + legQuantities);
            
            // Compute parent quantity as the minimum across leg quantities (given 1:1 ratio)
            int parentQty = legQuantities.stream().mapToInt(Integer::intValue).min().orElse(0);
            System.out.println("DEBUG: Computed parent quantity: " + parentQty);
            
            // Alpaca requires top-level qty > 0
            if (parentQty <= 0) {
                throw new RuntimeException("Computed parent quantity is not > 0 for exit MLeg order");
            }
            // Use string values consistent with Alpaca examples
            order.put("qty", String.valueOf(parentQty));

            order.put("legs", legs);
            
            System.out.println("DEBUG: Final order before JSON conversion: " + order);
            
            String jsonResult = JsonUtils.toJson(order);
            System.out.println("DEBUG: Final JSON result: " + jsonResult);
            
            return jsonResult;
        });
    }
    
    /**
     * Creates a multi-leg exit order with limit price (for order monitoring lambdas)
     * Reuses the same pattern as InitiateTradesLambda.buildEntryOrderJson()
     */
    public static String createCalendarSpreadExitOrderWithLimit(List<Map<String, Object>> positions, double limitPrice) {
        String baseOrderJson = createCalendarSpreadExitOrder(positions, "limit");
        
        // Parse and add limit price
        JsonNode orderNode = JsonUtils.parseJson(baseOrderJson);
        Map<String, Object> orderMap = JsonUtils.parseJsonToMap(baseOrderJson);
        orderMap.put("limit_price", limitPrice);
        
        return JsonUtils.toJson(orderMap);
    }
    
    /**
     * Submits an exit order using existing submitOrder pattern from InitiateTradesLambda
     */
    public static Map<String, Object> submitExitOrder(String orderJson, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("submitting exit order", () -> {
            String responseBody = AlpacaHttpClient.postAlpacaTrading("/orders", orderJson, credentials);
            JsonNode orderNode = JsonUtils.parseJson(responseBody);
            return Map.of(
                "orderId", orderNode.get("id").asText(),
                "status", orderNode.get("status").asText()
            );
        });
    }
    
    /**
     * Gets all positions from Alpaca API using existing pattern
     */
    public static List<Map<String, Object>> getAllPositions(AlpacaCredentials credentials) throws Exception {
        String responseBody = AlpacaHttpClient.getAlpacaTrading("/positions", credentials);
        JsonNode positionsNode = JsonUtils.parseJson(responseBody);
        
        List<Map<String, Object>> positions = new ArrayList<>();
        if (positionsNode.isArray()) {
            for (JsonNode posNode : positionsNode) {
                Map<String, Object> position = new HashMap<>();
                position.put("symbol", posNode.get("symbol").asText());
                position.put("asset_class", posNode.get("asset_class").asText());
                position.put("qty", posNode.get("qty").asText());
                position.put("side", posNode.get("side").asText());
                position.put("market_value", posNode.get("market_value").asText());
                position.put("cost_basis", posNode.get("cost_basis").asText());
                positions.add(position);
            }
        }
        
        return positions;
    }
    
    /**
     * Filters option positions from all positions using existing pattern
     */
    public static List<Map<String, Object>> filterOptionPositions(List<Map<String, Object>> allPositions) {
        return allPositions.stream()
            .filter(pos -> "option".equalsIgnoreCase((String) pos.get("asset_class")) || 
                         "us_option".equalsIgnoreCase((String) pos.get("asset_class")))
            .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Groups option positions into calendar spreads using existing pattern from InitiateExitTradesLambda
     */
    public static Map<String, List<Map<String, Object>>> groupPositionsIntoCalendarSpreads(List<Map<String, Object>> optionPositions) {
        Map<String, List<Map<String, Object>>> groups = new HashMap<>();
        
        for (Map<String, Object> position : optionPositions) {
            String symbol = (String) position.get("symbol");
            
            try {
                Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(symbol);
                String underlying = (String) parsed.get("underlying");
                String type = (String) parsed.get("type");
                String strike = parsed.get("strike").toString();
                
                // Group by underlying + type + strike (for calendar spreads)
                String groupKey = underlying + "_" + type + "_" + strike;
                
                groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(position);
            } catch (Exception e) {
                // Skip positions that can't be parsed
                continue;
            }
        }
        
        // Filter to only groups with exactly 2 positions (calendar spreads)
        Map<String, List<Map<String, Object>>> calendarSpreads = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            if (entry.getValue().size() == 2) {
                calendarSpreads.put(entry.getKey(), entry.getValue());
            }
        }
        
        return calendarSpreads;
    }
}
