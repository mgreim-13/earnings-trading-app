package com.trading;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.trading.common.TradingCommonUtils;
import com.trading.common.TradingErrorHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS Lambda function for scanning earnings data from Finnhub API.
 * Fetches earnings data, filters for AMC/BMO earnings, and writes to DynamoDB.
 */
public class ScanEarningsLambda implements RequestHandler<Map<String, Object>, String> {
    
    // Environment variables - resolved dynamically
    private String getEnvVar(String envName, String propName) {
        return System.getenv(envName) != null ? System.getenv(envName) : System.getProperty(propName);
    }
    
    private String getFinnhubUrl() { return getEnvVar("FINNHUB_API_URL", "FINNHUB_API_URL"); }
    private String getFinnhubSecret() { return getEnvVar("FINNHUB_SECRET_NAME", "FINNHUB_SECRET_NAME"); }
    private String getDynamoDbTable() { return getEnvVar("DYNAMODB_TABLE", "DYNAMODB_TABLE"); }
    
    // Constants
    private static final String TIMEZONE = "America/New_York";
    private static final String AMC = "amc";
    private static final String BMO = "bmo";
    private static final int BATCH_SIZE = 25;
    
    
    // AWS clients
    private final DynamoDbClient dynamoDbClient;
    
    public ScanEarningsLambda() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
    }
    
    // Constructor for testing
    public ScanEarningsLambda(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            context.getLogger().log("Starting ScanEarningsLambda execution");
            
            LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));
            context.getLogger().log("Current date (EST): " + today);
            
            // Get API key and check market status
            String finnhubApiKey = TradingCommonUtils.getAlpacaCredentials(getFinnhubSecret()).getApiKey();
            if (finnhubApiKey == null) {
                throw new RuntimeException("Failed to retrieve Finnhub API key from Secrets Manager");
            }

            // Check market status using Alpaca API
            String alpacaSecretName = System.getenv("ALPACA_SECRET_NAME");
            if (alpacaSecretName != null) {
                try {
                    Map<String, String> alpacaCreds = getApiKey(alpacaSecretName);
                    String apiKey = alpacaCreds.get("apiKey");
                    String secretKey = alpacaCreds.get("secretKey");
                    
                    if (apiKey != null && secretKey != null && !TradingCommonUtils.isMarketOpen(apiKey, secretKey)) {
                        context.getLogger().log("Market is closed, skipping earnings scan");
                        return TradingErrorHandler.createSkippedResponse("market_closed", Map.of("earnings_processed", 0));
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error checking market status with Alpaca: " + e.getMessage());
                    // Conservative approach - skip if we can't verify market status
                    context.getLogger().log("Cannot verify market status, skipping earnings scan");
                    return TradingErrorHandler.createSkippedResponse("market_status_unknown", Map.of("earnings_processed", 0));
                }
            } else {
                // Conservative approach - skip if no Alpaca credentials
                context.getLogger().log("No Alpaca credentials available, skipping earnings scan");
                return TradingErrorHandler.createSkippedResponse("no_alpaca_credentials", Map.of("earnings_processed", 0));
            }

            context.getLogger().log("Market is open, proceeding with earnings scan");

            // Process earnings data
            LocalDate nextTradingDay = getNextTradingDay(today, finnhubApiKey);
            List<EarningsRecord> allEarnings = fetchEarningsData(finnhubApiKey, today, nextTradingDay);
            List<EarningsRecord> filteredEarnings = filterEarnings(allEarnings, today, nextTradingDay);

            context.getLogger().log("Fetched " + allEarnings.size() + " earnings, filtered to " + filteredEarnings.size() + " relevant");

            writeToEarningsTable(filteredEarnings, today);
            context.getLogger().log("Successfully wrote " + filteredEarnings.size() + " earnings to DynamoDB");
            
            return TradingErrorHandler.createSuccessResponse("Earnings scan completed successfully", Map.of("earnings_processed", filteredEarnings.size()));

        } catch (Exception e) {
            return TradingErrorHandler.handleError(e, context, "ScanEarningsLambda");
        }
    }

    /**
     * Fetches earnings data from Finnhub API for the given date range.
     */
    public List<EarningsRecord> fetchEarningsData(String apiKey, LocalDate startDate, LocalDate endDate) throws IOException {
        // Validate date range
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        String url = String.format("%s?from=%s&to=%s&token=%s", 
            getFinnhubUrl(), 
            startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            endDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            apiKey);

        String responseBody = TradingCommonUtils.makeHttpRequest(url, apiKey, "", "GET", null);

        // Parse the response - Finnhub returns {"earningsCalendar": [...]}
        com.fasterxml.jackson.databind.JsonNode responseNode = TradingCommonUtils.parseJson(responseBody);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> earningsList = (List<Map<String, Object>>) responseNode.get("earningsCalendar").traverse().readValueAs(List.class);

        if (earningsList == null) {
            return new ArrayList<>();
        }

        return earningsList.stream()
            .map(this::mapToEarningsRecord)
            .collect(Collectors.toList());
    }

    /**
     * Maps a Map from the API response to an EarningsRecord.
     */
    private EarningsRecord mapToEarningsRecord(Map<String, Object> earningsMap) {
        String hour = (String) earningsMap.get("hour");
        return new EarningsRecord(
            (String) earningsMap.get("symbol"),
            (String) earningsMap.get("date"),
            (hour == null || hour.isEmpty()) ? "" : hour.toLowerCase()
        );
    }

    /**
     * Filters earnings for AMC (today) and BMO (next trading day).
     */
    public List<EarningsRecord> filterEarnings(List<EarningsRecord> earnings, LocalDate today, LocalDate nextTradingDay) {
        return earnings.stream()
            .filter(record -> record.getEarningsDate() != null && record.getTime() != null)
            .filter(record -> {
                LocalDate earningsDate = LocalDate.parse(record.getEarningsDate());
                String time = record.getTime().toLowerCase();
                return (earningsDate.equals(today) && AMC.equals(time)) ||
                       (earningsDate.equals(nextTradingDay) && BMO.equals(time));
            })
            .collect(Collectors.toList());
    }

    /**
     * Gets the next trading day (M-F, excluding holidays)
     * Uses TradingCommonUtils.isMarketHoliday for dynamic holiday detection
     */
    public LocalDate getNextTradingDay(LocalDate today, String finnhubApiKey) {
        LocalDate nextDay = today.plusDays(1);
        
        while (isWeekend(nextDay) || TradingCommonUtils.isMarketHoliday(nextDay, finnhubApiKey)) {
            nextDay = nextDay.plusDays(1);
        }
        
        return nextDay;
    }

    /**
     * Checks if a date is a weekend (Saturday or Sunday)
     */
    public boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6; // Saturday (6) or Sunday (7)
    }

    /**
     * Writes earnings records to DynamoDB table in batches.
     */
    public void writeToEarningsTable(List<EarningsRecord> earnings, LocalDate scanDate) {
        String scanDateStr = scanDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        for (int i = 0; i < earnings.size(); i += BATCH_SIZE) {
            List<EarningsRecord> batch = earnings.subList(i, Math.min(i + BATCH_SIZE, earnings.size()));

            List<WriteRequest> writeRequests = batch.stream()
                .map(record -> {
                    // Calculate TTL: current time + 30 minutes
                    long ttl = System.currentTimeMillis() / 1000 + (30 * 60); // 30 minutes from now
                    
                    Map<String, AttributeValue> item = Map.of(
                        "scanDate", AttributeValue.builder().s(scanDateStr).build(),
                        "ticker", AttributeValue.builder().s(record.getTicker()).build(),
                        "earningsDate", AttributeValue.builder().s(record.getEarningsDate()).build(),
                        "time", AttributeValue.builder().s(record.getTime().toUpperCase()).build(),
                        "ttl", AttributeValue.builder().n(String.valueOf(ttl)).build()
                    );
                    return WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(item).build())
                        .build();
                })
                .collect(Collectors.toList());

            dynamoDbClient.batchWriteItem(BatchWriteItemRequest.builder()
                .requestItems(Map.of(getDynamoDbTable(), writeRequests))
                .build());
        }
    }


    /**
     * Retrieves API keys from AWS Secrets Manager.
     */
    public Map<String, String> getApiKey(String secretName) {
        return TradingCommonUtils.getAlpacaCredentialsAsMap(secretName);
    }

}
