package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.JsonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.OptionSelectionUtils;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.JsonParsingUtils;
import com.trading.common.OptionSymbolUtils;
import com.trading.common.PortfolioEquityValidator;
import com.trading.common.models.AlpacaCredentials;
import com.trading.common.models.OptionSnapshot;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * AWS Lambda function for initiating calendar spread trades.
 * Processes filtered tickers from DynamoDB and submits calendar spread orders to Alpaca.
 */
public class InitiateTradesLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String FILTERED_TABLE = System.getenv().getOrDefault("FILTERED_TABLE", "filtered-tickers-table");
    private static final String ALPACA_SECRET_NAME = System.getenv().getOrDefault("ALPACA_SECRET_NAME", "trading/alpaca/credentials");
    // No default position size - only trade tickers with valid position size data
    private static final int CONTRACT_MULTIPLIER = 100;
    private final DynamoDbClient dynamoDbClient;
    // Note: OptionSelectionUtils is in StockFilterLambda module, so we keep original logic here

    public InitiateTradesLambda() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
    }

    // Constructor for testing
    public InitiateTradesLambda(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            String scanDate = (String) input.getOrDefault("scanDate", TradingCommonUtils.getCurrentDateString());
            AlpacaCredentials credentials = TradingCommonUtils.getAlpacaCredentials(ALPACA_SECRET_NAME);

            if (!AlpacaHttpClient.isMarketOpen(credentials)) {
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_submitted", 0));
            }

            double accountEquity = getAccountEquity(credentials);
            Map<String, Double> tickerPositionSizes = fetchFilteredTickersWithPositionSizes(scanDate);
            int ordersSubmitted = 0;
            int ordersRejected = 0;

            if (tickerPositionSizes.isEmpty()) {
                context.getLogger().log("No valid tickers found with position size data - no trades will be executed");
                return TradingErrorHandler.createSkippedResponse("no_valid_tickers", Map.of("orders_submitted", 0));
            }

            for (Map.Entry<String, Double> entry : tickerPositionSizes.entrySet()) {
                String ticker = entry.getKey();
                double positionSizePercentage = entry.getValue();
                
                double totalDebitTarget = accountEquity * positionSizePercentage;
                
                // Check if we have sufficient equity for this trade
                if (!PortfolioEquityValidator.hasSufficientEquity(totalDebitTarget, credentials, context)) {
                    context.getLogger().log("Insufficient equity for " + ticker + " trade: $" + 
                        String.format("%.2f", totalDebitTarget));
                    ordersRejected++;
                    continue;
                }
                
                context.getLogger().log("Processing " + ticker + " with position size: " + 
                    String.format("%.1f%%", positionSizePercentage * 100) + " (debit target: $" + 
                    String.format("%.2f", totalDebitTarget) + ")");
                
                try {
                    Map<String, Object> optionContracts = selectOptionContracts(ticker, credentials);
                    if (optionContracts == null || optionContracts.isEmpty()) continue;

                    String nearSymbol = (String) optionContracts.get("near_symbol");
                    String farSymbol = (String) optionContracts.get("far_symbol");
                    double debit = calculateDebit(nearSymbol, farSymbol, credentials);
                    if (debit <= 0) continue;

                    int quantity = calculateQuantity(debit, totalDebitTarget);
                    if (quantity < 1) continue;

                    double limitPrice = Math.round(debit * 100.0) / 100.0;
                    String orderJson = buildEntryOrderJson(ticker, nearSymbol, farSymbol, quantity, limitPrice);
                    Map<String, Object> orderResult = submitOrder(orderJson, credentials);
                    String orderId = (String) orderResult.get("orderId");

                    if (orderId != null && !orderId.isEmpty()) {
                        TradingCommonUtils.logTradeSuccess(ticker, orderId, context);
                        ordersSubmitted++;
                        
                        // Check buying power after successful order to prevent overextension
                        try {
                            String responseBody = AlpacaHttpClient.getAlpacaTrading("/account", credentials);
                            JsonNode accountNode = JsonUtils.parseJson(responseBody);
                            double currentBuyingPower = accountNode.get("buying_power").asDouble();
                            
                            if (currentBuyingPower <= 0) {
                                context.getLogger().log("Buying power exhausted after " + ticker + " order - stopping processing");
                                break; // Stop processing remaining tickers
                            }
                        } catch (Exception e) {
                            context.getLogger().log("Warning: Could not check buying power after order: " + e.getMessage());
                            // Continue processing but log the warning
                        }
                    } else {
                        TradingCommonUtils.logTradeFailure(ticker, "order_submission_failed", context);
                    }
                } catch (Exception e) {
                    // Check if this is an insufficient funds error
                    if (e.getMessage() != null && e.getMessage().contains("INSUFFICIENT_FUNDS")) {
                        context.getLogger().log("Insufficient funds detected - stopping order processing");
                        TradingCommonUtils.logTradeFailure(ticker, "insufficient_funds", context);
                        break; // Stop processing remaining tickers
                    }
                    TradingCommonUtils.logTradeFailure(ticker, "processing_error: " + e.getMessage(), context);
                }
            }

            context.getLogger().log("Completed processing. Orders submitted: " + ordersSubmitted + 
                ", Orders rejected: " + ordersRejected);
            
            return TradingErrorHandler.createSuccessResponse("Processing completed", Map.of(
                "orders_submitted", ordersSubmitted,
                "orders_rejected", ordersRejected,
                "scan_date", scanDate,
                "account_equity", accountEquity
            ));

        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "handleRequest");
        }
    }

    /**
     * Fetches all filtered tickers with their position sizes for the given scan date from DynamoDB
     */
    public Map<String, Double> fetchFilteredTickersWithPositionSizes(String scanDate) {
        return TradingCommonUtils.executeWithErrorHandling("fetching filtered tickers with position sizes", () -> {
            ScanRequest scanRequest = ScanRequest.builder()
                .tableName(FILTERED_TABLE)
                .filterExpression("scanDate = :scanDate")
                .expressionAttributeValues(Map.of(
                    ":scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();

            ScanResponse response = dynamoDbClient.scan(scanRequest);
            
            Map<String, Double> tickerPositionSizes = new HashMap<>();
            int totalTickers = 0;
            int skippedTickers = 0;
            
            for (Map<String, AttributeValue> item : response.items()) {
                String ticker = item.get("ticker").s();
                
                // Only include tickers with valid position size data (non-zero indicates it should be traded)
                if (item.containsKey("positionSizePercentage") && item.get("positionSizePercentage").n() != null) {
                    try {
                        double positionSize = Double.parseDouble(item.get("positionSizePercentage").n());
                        // Any non-zero position size indicates this ticker should be traded
                        if (positionSize > 0) {
                            totalTickers++;
                            // Validate position size is reasonable (between 0.01 and 0.20 = 1% to 20%)
                            if (positionSize >= 0.01 && positionSize <= 0.20) {
                                tickerPositionSizes.put(ticker, positionSize);
                            } else {
                                System.out.println("Skipping " + ticker + " - invalid position size: " + positionSize);
                                skippedTickers++;
                            }
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Skipping " + ticker + " - corrupted position size data: " + e.getMessage());
                        skippedTickers++;
                    }
                } else {
                    System.out.println("Skipping " + ticker + " - missing position size data");
                    skippedTickers++;
                }
            }

            System.out.println("Position size validation: " + tickerPositionSizes.size() + " valid tickers, " + 
                skippedTickers + " skipped out of " + totalTickers + " total");
            
            return tickerPositionSizes;
        });
    }

    /**
     * Fetches all filtered tickers for the given scan date from DynamoDB (legacy method)
     */
    public List<String> fetchFilteredTickers(String scanDate) {
        return new ArrayList<>(fetchFilteredTickersWithPositionSizes(scanDate).keySet());
    }

    /**
     * Selects option contracts for calendar spread (near-term and far-term calls)
     */
    public Map<String, Object> selectOptionContracts(String ticker, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("selecting option contracts for " + ticker, () -> {
            double currentPrice = getCurrentStockPrice(ticker, credentials);
            if (currentPrice <= 0) {
                throw new RuntimeException("Unable to get current stock price for " + ticker);
            }

            Map<String, List<Map<String, Object>>> contractsByExpiration = fetchAndParseOptionSnapshots(ticker, credentials);
            LocalDate[] expirations = selectNearAndFarExpirations(contractsByExpiration);
            
            if (expirations[0] == null || expirations[1] == null) {
                throw new RuntimeException("Insufficient option expiration dates found for " + ticker + " - need at least two different expirations for calendar spread");
            }

            String[] spreadSymbols = OptionSelectionUtils.findCalendarSpreadSymbols(contractsByExpiration, expirations[0], expirations[1], currentPrice);
            
            if (spreadSymbols == null) {
                throw new RuntimeException("Unable to find calendar spread symbols - no common strike found between " + 
                    expirations[0] + " and " + expirations[1]);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("near_symbol", spreadSymbols[0]);
            result.put("far_symbol", spreadSymbols[1]);
            result.put("near_exp", expirations[0].toString());
            result.put("far_exp", expirations[1].toString());

            return result;
        });
    }

    /**
     * Fetches and parses option snapshots from Alpaca API using existing AlpacaHttpClient method
     */
    private Map<String, List<Map<String, Object>>> fetchAndParseOptionSnapshots(String ticker, AlpacaCredentials credentials) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate expirationGte = today.plusDays(1);
        LocalDate expirationLte = today.plusDays(60);

        try {
            Map<String, OptionSnapshot> optionSnapshots = AlpacaHttpClient.getOptionChain(ticker, expirationGte, expirationLte, "call", credentials);
            
            if (optionSnapshots == null || optionSnapshots.isEmpty()) {
                throw new RuntimeException("No option snapshots found for " + ticker);
            }

            Map<String, List<Map<String, Object>>> contractsByExpiration = new HashMap<>();
            optionSnapshots.forEach((symbol, snapshot) -> {
                try {
                    Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(symbol);
                    String expiration = (String) parsed.get("expiration");
                    double strike = (Double) parsed.get("strike");
                    
                    Map<String, Object> contractInfo = new HashMap<>();
                    contractInfo.put("symbol", symbol);
                    contractInfo.put("strike", strike);
                    contractInfo.put("snapshot", snapshot);
                    
                    contractsByExpiration.computeIfAbsent(expiration, k -> new ArrayList<>()).add(contractInfo);
                } catch (RuntimeException e) {
                    // Skip invalid symbols and continue processing
                    System.out.println("Skipping invalid option symbol: " + symbol + " - " + e.getMessage());
                }
            });
            
            return contractsByExpiration;
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch option chain for " + ticker, e);
        }
    }

    /**
     * Selects near and far expiration dates for calendar spread
     */
    private LocalDate[] selectNearAndFarExpirations(Map<String, List<Map<String, Object>>> contractsByExpiration) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Convert to string list for shared utility
        List<String> expirationStrings = new ArrayList<>(contractsByExpiration.keySet());
        
        // Use shared utility for consistency
        LocalDate nearExpiration = OptionSelectionUtils.findShortLegExpiration(expirationStrings, today);
        LocalDate farExpiration = OptionSelectionUtils.findFarLegExpiration(expirationStrings, today, nearExpiration);
            
        return new LocalDate[]{nearExpiration, farExpiration};
    }


    /**
     * Calculates the debit for a calendar spread
     */
    public double calculateDebit(String nearSymbol, String farSymbol, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("calculating debit", () -> {
            String endpoint = "/options/quotes/latest?symbols=" + nearSymbol + "," + farSymbol + "&feed=opra";
            String responseBody = AlpacaHttpClient.getAlpacaOptions(endpoint, credentials);
            JsonNode quotes = JsonUtils.parseJson(responseBody).get("quotes");
            
            if (quotes == null || !quotes.isObject()) return 0.0;
            
            JsonNode nearQuote = quotes.get(nearSymbol);
            JsonNode farQuote = quotes.get(farSymbol);
            if (nearQuote == null || farQuote == null) return 0.0;
            
            double nearBid = JsonParsingUtils.getBidPrice(nearQuote);
            double farAsk = JsonParsingUtils.getAskPrice(farQuote);
            return Math.max(0.0, farAsk - nearBid);
        });
    }

    /**
     * Gets account equity from Alpaca
     */
    public double getAccountEquity(AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("getting account equity", () -> {
            String responseBody = AlpacaHttpClient.getAlpacaTrading("/account", credentials);
            return JsonUtils.parseJson(responseBody).get("equity").asDouble();
        });
    }

    /**
     * Builds the entry order JSON for Alpaca
     */
    public String buildEntryOrderJson(String ticker, String nearSymbol, String farSymbol, int qty, double limitPrice) {
        return TradingCommonUtils.executeWithErrorHandling("building order JSON", () -> {
            Map<String, Object> order = new HashMap<>();
            order.put("order_class", "mleg");
            order.put("type", "limit");
            order.put("time_in_force", "day");
            order.put("limit_price", limitPrice);
            order.put("qty", qty);

            List<Map<String, Object>> legs = new ArrayList<>();
            
            Map<String, Object> farLeg = new HashMap<>();
            farLeg.put("symbol", farSymbol);
            farLeg.put("side", "buy");
            farLeg.put("ratio_qty", "1");
            farLeg.put("position_intent", "buy_to_open");
            legs.add(farLeg);

            Map<String, Object> nearLeg = new HashMap<>();
            nearLeg.put("symbol", nearSymbol);
            nearLeg.put("side", "sell");
            nearLeg.put("ratio_qty", "1");
            nearLeg.put("position_intent", "sell_to_open");
            legs.add(nearLeg);

            order.put("legs", legs);
            return JsonUtils.toJson(order);
        });
    }

    /**
     * Submits order to Alpaca
     */
    public Map<String, Object> submitOrder(String orderJson, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("submitting order", () -> {
            String responseBody = AlpacaHttpClient.postAlpacaTrading("/orders", orderJson, credentials);
            JsonNode orderNode = JsonUtils.parseJson(responseBody);
            return Map.of(
                "orderId", orderNode.get("id").asText(),
                "status", orderNode.get("status").asText()
            );
        });
    }


    // Helper methods


    private double getCurrentStockPrice(String ticker, AlpacaCredentials credentials) {
        String endpoint = "/stocks/" + ticker + "/quotes/latest?feed=sip";
        String responseBody = AlpacaHttpClient.getAlpacaData(endpoint, credentials);
        JsonNode quote = JsonUtils.parseJson(responseBody).get("quote");
        return quote != null ? JsonParsingUtils.getMidPrice(quote) : 0.0;
    }




    private int calculateQuantity(double debit, double totalDebitTarget) {
        if (debit <= 0) {
            return 0;
        }
        
        double maxQuantity = totalDebitTarget / (debit * CONTRACT_MULTIPLIER);
        return (int) Math.floor(maxQuantity);
    }

}