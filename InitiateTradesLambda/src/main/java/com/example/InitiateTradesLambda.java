package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.models.AlpacaCredentials;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * AWS Lambda function for initiating calendar spread trades.
 * Processes filtered tickers from DynamoDB and submits calendar spread orders to Alpaca.
 */
public class InitiateTradesLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String FILTERED_TABLE = System.getenv().getOrDefault("FILTERED_TABLE", "FilteredTickersTable");
    private static final String ALPACA_SECRET_NAME = System.getenv().getOrDefault("ALPACA_SECRET_NAME", "alpaca-api-keys");
    private static final String ALPACA_API_URL = System.getenv().getOrDefault("ALPACA_API_URL", "https://paper-api.alpaca.markets/v2");
    private static final String ALPACA_DATA_URL = "https://data.alpaca.markets/v1beta1";
    private static final double TARGET_DEBIT_PERCENTAGE = 0.06;
    private static final int CONTRACT_MULTIPLIER = 100;
    private final DynamoDbClient dynamoDbClient;

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

            if (!TradingCommonUtils.isMarketOpen(credentials.getApiKeyId(), credentials.getSecretKey())) {
                return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("orders_submitted", 0));
            }

            double accountEquity = getAccountEquity(credentials);
            List<String> tickers = fetchFilteredTickers(scanDate);
            int ordersSubmitted = 0;
            double totalDebitTarget = accountEquity * TARGET_DEBIT_PERCENTAGE;

            for (String ticker : tickers) {
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
                    } else {
                        TradingCommonUtils.logTradeFailure(ticker, "order_submission_failed", context);
                    }
                } catch (Exception e) {
                    TradingCommonUtils.logTradeFailure(ticker, "processing_error: " + e.getMessage(), context);
                }
            }

            context.getLogger().log("Completed processing. Orders submitted: " + ordersSubmitted);
            return TradingErrorHandler.createSuccessResponse("Processing completed", Map.of(
                "orders_submitted", ordersSubmitted,
                "scan_date", scanDate,
                "account_equity", accountEquity
            ));

        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "handleRequest");
        }
    }

    /**
     * Fetches all filtered tickers for the given scan date from DynamoDB
     */
    public List<String> fetchFilteredTickers(String scanDate) {
        return TradingCommonUtils.executeWithErrorHandling("fetching filtered tickers", () -> {
            ScanRequest scanRequest = ScanRequest.builder()
                .tableName(FILTERED_TABLE)
                .filterExpression("scanDate = :scanDate")
                .expressionAttributeValues(Map.of(
                    ":scanDate", AttributeValue.builder().s(scanDate).build()
                ))
                .build();

            ScanResponse response = dynamoDbClient.scan(scanRequest);
            
            List<String> tickers = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                String ticker = item.get("ticker").s();
                String status = item.get("status").s();
                
                if ("Recommended".equals(status) || "Consider".equals(status)) {
                    tickers.add(ticker);
                }
            }

            return tickers;
        });
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

            String[] symbols = findOptionSymbols(contractsByExpiration, expirations, currentPrice);
            
            Map<String, Object> result = new HashMap<>();
            result.put("near_symbol", symbols[0]);
            result.put("far_symbol", symbols[1]);
            result.put("near_exp", expirations[0].toString());
            result.put("far_exp", expirations[1].toString());
            result.put("strike", findATMStrikeFromContractInfo(contractsByExpiration.get(expirations[0].toString()), currentPrice));

            return result;
        });
    }

    /**
     * Fetches and parses option snapshots from Alpaca API
     */
    private Map<String, List<Map<String, Object>>> fetchAndParseOptionSnapshots(String ticker, AlpacaCredentials credentials) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        String expirationGte = today.plusDays(1).toString();
        String expirationLte = today.plusDays(60).toString();

        String url = ALPACA_DATA_URL + "/options/snapshots/" + ticker + 
                    "?type=call&expiration_date_gte=" + expirationGte + "&expiration_date_lte=" + expirationLte + 
                    "&feed=indicative&limit=1000";

        String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
        JsonNode snapshots = TradingCommonUtils.parseJson(responseBody).get("snapshots");
        if (snapshots == null || !snapshots.isObject() || snapshots.size() == 0) {
            throw new RuntimeException("No option snapshots found for " + ticker);
        }

        Map<String, List<Map<String, Object>>> contractsByExpiration = new HashMap<>();
        snapshots.fields().forEachRemaining(entry -> {
            String symbol = entry.getKey();
            JsonNode snapshot = entry.getValue();
            try {
                Map<String, Object> parsed = parseOptionSymbol(symbol);
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
    }

    /**
     * Selects near and far expiration dates for calendar spread
     */
    private LocalDate[] selectNearAndFarExpirations(Map<String, List<Map<String, Object>>> contractsByExpiration) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        List<LocalDate> expirations = contractsByExpiration.keySet().stream()
            .map(exp -> {
                try { return LocalDate.parse(exp); } 
                catch (Exception e) { return null; }
            })
            .filter(Objects::nonNull)
            .sorted()
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        LocalDate nearExpiration = expirations.stream()
            .filter(exp -> exp.isAfter(today))
            .findFirst()
            .orElse(null);

        LocalDate farExpiration = expirations.stream()
            .filter(exp -> exp.isAfter(nearExpiration))
            .min((a, b) -> Long.compare(
                Math.abs(a.toEpochDay() - today.plusDays(30).toEpochDay()),
                Math.abs(b.toEpochDay() - today.plusDays(30).toEpochDay())
            ))
            .orElse(null);
            
        return new LocalDate[]{nearExpiration, farExpiration};
    }

    /**
     * Finds option symbols for near and far legs
     */
    private String[] findOptionSymbols(Map<String, List<Map<String, Object>>> contractsByExpiration, 
                                     LocalDate[] expirations, double currentPrice) {
        double nearStrike = findATMStrikeFromContractInfo(contractsByExpiration.get(expirations[0].toString()), currentPrice);
        double farStrike = findATMStrikeFromContractInfo(contractsByExpiration.get(expirations[1].toString()), currentPrice);

        if (nearStrike <= 0 || farStrike <= 0) {
            throw new RuntimeException("Unable to find valid ATM strikes - nearStrike: " + nearStrike + ", farStrike: " + farStrike);
        }

        String nearSymbol = findSymbolForStrike(contractsByExpiration.get(expirations[0].toString()), nearStrike);
        String farSymbol = findSymbolForStrike(contractsByExpiration.get(expirations[1].toString()), farStrike);

        if (nearSymbol == null || farSymbol == null) {
            throw new RuntimeException("Unable to find option symbols - nearSymbol: " + nearSymbol + ", farSymbol: " + farSymbol);
        }

        return new String[]{nearSymbol, farSymbol};
    }

    /**
     * Calculates the debit for a calendar spread
     */
    public double calculateDebit(String nearSymbol, String farSymbol, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("calculating debit", () -> {
            String url = ALPACA_DATA_URL + "/options/quotes/latest?symbols=" + nearSymbol + "," + farSymbol + "&feed=indicative";
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
            JsonNode quotes = TradingCommonUtils.parseJson(responseBody).get("quotes");
            
            if (quotes == null || !quotes.isObject()) return 0.0;
            
            JsonNode nearQuote = quotes.get(nearSymbol);
            JsonNode farQuote = quotes.get(farSymbol);
            if (nearQuote == null || farQuote == null) return 0.0;
            
            double nearBid = getBidPrice(nearQuote);
            double farAsk = getAskPrice(farQuote);
            return Math.max(0.0, farAsk - nearBid);
        });
    }

    /**
     * Gets account equity from Alpaca
     */
    public double getAccountEquity(AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("getting account equity", () -> {
            String url = ALPACA_API_URL + "/account";
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
            return TradingCommonUtils.parseJson(responseBody).get("equity").asDouble();
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
            return TradingCommonUtils.toJson(order);
        });
    }

    /**
     * Submits order to Alpaca
     */
    public Map<String, Object> submitOrder(String orderJson, AlpacaCredentials credentials) {
        return TradingCommonUtils.executeWithErrorHandling("submitting order", () -> {
            String url = ALPACA_API_URL + "/orders";
            String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "POST", orderJson);
            JsonNode orderNode = TradingCommonUtils.parseJson(responseBody);
            return Map.of(
                "orderId", orderNode.get("id").asText(),
                "status", orderNode.get("status").asText()
            );
        });
    }


    // Helper methods

    private Map<String, Object> parseOptionSymbol(String symbol) {
        if (symbol.length() < 15) {
            throw new RuntimeException("Invalid option symbol format - too short: " + symbol);
        }
        
        int optionTypeIndex = -1;
        for (int i = 4; i < symbol.length(); i++) {
            if (symbol.charAt(i) == 'C' || symbol.charAt(i) == 'P') {
                optionTypeIndex = i;
                break;
            }
        }
        
        if (optionTypeIndex < 10) {
            throw new RuntimeException("Invalid option symbol format - cannot find option type: " + symbol);
        }
        
        try {
            String expirationStr = symbol.substring(optionTypeIndex - 6, optionTypeIndex);
            String year = "20" + expirationStr.substring(0, 2);
            String month = expirationStr.substring(2, 4);
            String day = expirationStr.substring(4, 6);
            String expiration = year + "-" + month + "-" + day;
            
            String strikeStr = symbol.substring(optionTypeIndex + 1);
            double strike = Double.parseDouble(strikeStr) / 1000.0;
            
            Map<String, Object> result = new HashMap<>();
            result.put("expiration", expiration);
            result.put("strike", strike);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse option symbol: " + symbol + " - " + e.getMessage(), e);
        }
    }

    private double getCurrentStockPrice(String ticker, AlpacaCredentials credentials) {
        String url = ALPACA_DATA_URL + "/stocks/" + ticker + "/quotes/latest?feed=indicative";
        String responseBody = TradingCommonUtils.makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), "GET", null);
        JsonNode quote = TradingCommonUtils.parseJson(responseBody).get("quote");
        return quote != null ? getMidPrice(quote) : 0.0;
    }


    private double findATMStrikeFromContractInfo(List<Map<String, Object>> contractInfos, double currentPrice) {
        if (contractInfos == null || contractInfos.isEmpty()) {
            return 0.0;
        }

        double closestStrike = 0.0;
        double minDiff = Double.MAX_VALUE;

        for (Map<String, Object> contractInfo : contractInfos) {
            double strike = (Double) contractInfo.get("strike");
            double diff = Math.abs(strike - currentPrice);
            if (diff < minDiff) {
                minDiff = diff;
                closestStrike = strike;
            }
        }

        return closestStrike;
    }

    private String findSymbolForStrike(List<Map<String, Object>> contractInfos, double targetStrike) {
        if (contractInfos == null || contractInfos.isEmpty()) {
            throw new RuntimeException("No contract information available for strike " + targetStrike);
        }

        for (Map<String, Object> contractInfo : contractInfos) {
            double strike = (Double) contractInfo.get("strike");
            if (Math.abs(strike - targetStrike) < 0.001) { // Allow for small floating point differences
                return (String) contractInfo.get("symbol");
            }
        }

        throw new RuntimeException("No contract found for strike " + targetStrike + " in available contracts");
    }

    private double getPrice(JsonNode quote, String field) {
        return quote.has(field) ? quote.get(field).asDouble() : 0.0;
    }

    private double getBidPrice(JsonNode quote) {
        return getPrice(quote, "bp");
    }

    private double getAskPrice(JsonNode quote) {
        return getPrice(quote, "ap");
    }

    private double getMidPrice(JsonNode quote) {
        double bid = getBidPrice(quote);
        double ask = getAskPrice(quote);
        return (bid > 0 && ask > 0) ? (bid + ask) / 2.0 : getPrice(quote, "last");
    }

    private int calculateQuantity(double debit, double totalDebitTarget) {
        if (debit <= 0) {
            return 0;
        }
        
        double maxQuantity = totalDebitTarget / (debit * CONTRACT_MULTIPLIER);
        return (int) Math.floor(maxQuantity);
    }

}