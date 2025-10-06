package com.trading;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.TradingCommonUtils;
import com.trading.common.JsonUtils;
import com.trading.common.TradingErrorHandler;
import com.trading.common.AlpacaHttpClient;
import com.trading.common.models.AlpacaCredentials;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EnableRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.DisableRuleRequest;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Market Scheduler Lambda - Conditionally triggers other lambdas based on market holidays
 */
public class MarketSchedulerLambda implements RequestHandler<Map<String, Object>, String> {
    
    private static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    private static final DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
    private static final EventBridgeClient eventBridgeClient = EventBridgeClient.builder().build();
    
    
    // Finnhub API configuration
    private static final String FINNHUB_SECRET_NAME = System.getenv().getOrDefault("FINNHUB_SECRET_NAME", "trading/finnhub/credentials");
    private static final String FINNHUB_API_URL = "https://finnhub.io/api/v1";
    
    // DynamoDB table names
    private static final String EARNINGS_TABLE = System.getenv("EARNINGS_TABLE");
    private static final String FILTERED_TABLE = System.getenv("FILTERED_TABLE");
    
    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            context.getLogger().log("Market Scheduler Lambda started");
            
            // Get current time in EST
            LocalDateTime now = LocalDateTime.now(EST_ZONE);
            LocalDate today = now.toLocalDate();
            
            // Only run on weekdays (Monday-Friday)
            if (today.getDayOfWeek().getValue() > 5) {
                context.getLogger().log(String.format("Skipping execution - %s is a weekend", today));
                return TradingErrorHandler.createSuccessResponse("Skipped - weekend day", null);
            }
            
            // When running at 1 AM EST, we're checking the market status for the current day
            // (which will be the trading day when the scheduled lambdas should run)
            MarketStatus marketStatus = getMarketStatus(today, context);
            
            context.getLogger().log(String.format("Market status for %s (trading day): %s", today, marketStatus));
            
            // Determine which lambdas to trigger based on the event source
            String eventSource = (String) event.get("source");
            if (eventSource == null) {
                eventSource = "manual"; // Default for manual triggers
            }
            
            switch (eventSource) {
                case "daily-schedule":
                    handleDailyScheduling(marketStatus, context);
                    break;
                case "create-tables":
                    handleCreateTables(marketStatus, context);
                    break;
                case "cleanup-tables-early":
                    handleCleanupTablesEarly(context);
                    break;
                case "cleanup-tables-normal":
                    handleCleanupTablesNormal(context);
                    break;
                default:
                    context.getLogger().log("Unknown event source: " + eventSource);
                    return TradingErrorHandler.createErrorResponse("Unknown event source", 400);
            }
            
            return TradingErrorHandler.createSuccessResponse("Market scheduler completed successfully", null);
            
        } catch (Exception e) {
            context.getLogger().log("Error in Market Scheduler: " + e.getMessage());
            e.printStackTrace();
            return TradingErrorHandler.createErrorResponse("Market scheduler failed: " + e.getMessage(), 500);
        }
    }
    
    private MarketStatus getMarketStatus(LocalDate date, Context context) {
        try {
            // Check if it's a weekend
            if (date.getDayOfWeek().getValue() > 5) {
                return MarketStatus.WEEKEND;
            }
            
            // Check for holidays using Finnhub
            if (isMarketHoliday(date, context)) {
                return MarketStatus.HOLIDAY;
            }
            
            // Check if it's an early closure day
            if (isEarlyClosureDay(date, context)) {
                return MarketStatus.EARLY_CLOSURE;
            }
            
            return MarketStatus.NORMAL;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking market status: " + e.getMessage());
            // Conservative approach - assume market is closed on error
            return MarketStatus.HOLIDAY;
        }
    }
    
    private boolean isMarketHoliday(LocalDate date, Context context) {
        try {
            // Get Finnhub API key
            String apiKey = getFinnhubApiKey();
            if (apiKey == null) {
                context.getLogger().log("Finnhub API key not found, using hardcoded holiday list");
                return isHardcodedHoliday(date);
            }
            
            // Call Finnhub calendar API with proper authentication
            String url = FINNHUB_API_URL + "/calendar/earnings?from=" + date + "&to=" + date + "&token=" + apiKey;
            String response = makeFinnhubRequest(url, apiKey);
            
            JsonNode responseNode = JsonUtils.parseJson(response);
            
            // Check if there are any earnings events (simplified holiday check)
            // In a real implementation, you'd use a dedicated holiday API
            return responseNode.has("earningsCalendar") && responseNode.get("earningsCalendar").size() == 0;
            
        } catch (Exception e) {
            context.getLogger().log("Error checking holiday status: " + e.getMessage());
            // Fall back to hardcoded holiday list
            return isHardcodedHoliday(date);
        }
    }
    
    private boolean isHardcodedHoliday(LocalDate date) {
        // Common US market holidays for 2025
        String monthDay = date.format(DateTimeFormatter.ofPattern("MM-dd"));
        
        return monthDay.equals("01-01") || // New Year's Day
               monthDay.equals("01-20") || // Martin Luther King Jr. Day
               monthDay.equals("02-17") || // Presidents' Day
               monthDay.equals("04-18") || // Good Friday
               monthDay.equals("05-26") || // Memorial Day
               monthDay.equals("06-19") || // Juneteenth
               monthDay.equals("07-04") || // Independence Day
               monthDay.equals("09-01") || // Labor Day
               monthDay.equals("11-27") || // Thanksgiving Day
               monthDay.equals("12-25");  // Christmas Day
    }
    
    private boolean isEarlyClosureDay(LocalDate date, Context context) {
        // Common early closure days (simplified)
        // In production, you'd get this from a more comprehensive API
        String monthDay = date.format(DateTimeFormatter.ofPattern("MM-dd"));
        
        // Common early closure days
        return monthDay.equals("11-24") || // Black Friday
               monthDay.equals("12-24") || // Christmas Eve
               monthDay.equals("07-03");   // Day before Independence Day
    }
    
    private String getFinnhubApiKey() {
        try {
            // Get Finnhub API key from Secrets Manager
            // The secret should contain the API key directly in the "apiKey" field
            Map<String, String> credentials = TradingCommonUtils.getAlpacaCredentialsAsMap(FINNHUB_SECRET_NAME);
            return credentials.get("apiKey");
        } catch (Exception e) {
            return null;
        }
    }
    
    private void handleDailyScheduling(MarketStatus marketStatus, Context context) {
        try {
            context.getLogger().log("Starting daily scheduling for market status: " + marketStatus);
            
            // Skip all scheduling on holidays
            if (marketStatus == MarketStatus.HOLIDAY) {
                context.getLogger().log("Skipping all scheduling - market holiday");
                disableAllTradingRules(context);
                return;
            }
            
            // Determine if it's early closure
            boolean isEarlyClosure = (marketStatus == MarketStatus.EARLY_CLOSURE);
            
            if (isEarlyClosure) {
                context.getLogger().log("Configuring for early closure day");
                enableEarlyClosureRules(context);
                disableNormalRules(context);
            } else {
                context.getLogger().log("Configuring for normal trading day");
                enableNormalRules(context);
                disableEarlyClosureRules(context);
            }
            
            context.getLogger().log("Daily scheduling completed successfully");
            
        } catch (Exception e) {
            context.getLogger().log("Error in daily scheduling: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void enableNormalRules(Context context) {
        try {
            String environment = System.getenv("ENVIRONMENT");
            if (environment == null) environment = "dev";
            
            // Enable normal trading day rules
            enableRule(environment + "-normal-scan-earnings-rule", context);
            enableRule(environment + "-normal-stock-filter-rule", context);
            enableRule(environment + "-normal-initiate-trades-rule", context);
            enableRule(environment + "-normal-initiate-exit-trades-rule", context);
            enableRule(environment + "-normal-monitor-trades-rule-1", context);
            enableRule(environment + "-normal-monitor-trades-rule-2", context);
            
            context.getLogger().log("Normal trading day rules enabled");
            
        } catch (Exception e) {
            context.getLogger().log("Error enabling normal rules: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void disableNormalRules(Context context) {
        try {
            String environment = System.getenv("ENVIRONMENT");
            if (environment == null) environment = "dev";
            
            // Disable normal trading day rules
            disableRule(environment + "-normal-scan-earnings-rule", context);
            disableRule(environment + "-normal-stock-filter-rule", context);
            disableRule(environment + "-normal-initiate-trades-rule", context);
            disableRule(environment + "-normal-initiate-exit-trades-rule", context);
            disableRule(environment + "-normal-monitor-trades-rule-1", context);
            disableRule(environment + "-normal-monitor-trades-rule-2", context);
            
            context.getLogger().log("Normal trading day rules disabled");
            
        } catch (Exception e) {
            context.getLogger().log("Error disabling normal rules: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void enableEarlyClosureRules(Context context) {
        try {
            String environment = System.getenv("ENVIRONMENT");
            if (environment == null) environment = "dev";
            
            // Enable early closure day rules
            enableRule(environment + "-early-scan-earnings-rule", context);
            enableRule(environment + "-early-stock-filter-rule", context);
            enableRule(environment + "-early-initiate-trades-rule", context);
            enableRule(environment + "-early-initiate-exit-trades-rule", context);
            enableRule(environment + "-early-monitor-trades-rule-1", context);
            enableRule(environment + "-early-monitor-trades-rule-2", context);
            
            context.getLogger().log("Early closure day rules enabled");
            
        } catch (Exception e) {
            context.getLogger().log("Error enabling early closure rules: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void disableEarlyClosureRules(Context context) {
        try {
            String environment = System.getenv("ENVIRONMENT");
            if (environment == null) environment = "dev";
            
            // Disable early closure day rules
            disableRule(environment + "-early-scan-earnings-rule", context);
            disableRule(environment + "-early-stock-filter-rule", context);
            disableRule(environment + "-early-initiate-trades-rule", context);
            disableRule(environment + "-early-initiate-exit-trades-rule", context);
            disableRule(environment + "-early-monitor-trades-rule-1", context);
            disableRule(environment + "-early-monitor-trades-rule-2", context);
            
            context.getLogger().log("Early closure day rules disabled");
            
        } catch (Exception e) {
            context.getLogger().log("Error disabling early closure rules: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void disableAllTradingRules(Context context) {
        try {
            disableNormalRules(context);
            disableEarlyClosureRules(context);
            context.getLogger().log("All trading rules disabled");
            
        } catch (Exception e) {
            context.getLogger().log("Error disabling all trading rules: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void enableRule(String ruleName, Context context) {
        try {
            EnableRuleRequest request = EnableRuleRequest.builder()
                .name(ruleName)
                .build();
            
            eventBridgeClient.enableRule(request);
            context.getLogger().log("Enabled rule: " + ruleName);
            
        } catch (Exception e) {
            context.getLogger().log("Error enabling rule " + ruleName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void disableRule(String ruleName, Context context) {
        try {
            DisableRuleRequest request = DisableRuleRequest.builder()
                .name(ruleName)
                .build();
            
            eventBridgeClient.disableRule(request);
            context.getLogger().log("Disabled rule: " + ruleName);
            
        } catch (Exception e) {
            context.getLogger().log("Error disabling rule " + ruleName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    
    /**
     * Handle table creation event (triggered 5 minutes before ScanEarningsLambda)
     */
    private void handleCreateTables(MarketStatus marketStatus, Context context) {
        if (marketStatus == MarketStatus.HOLIDAY) {
            context.getLogger().log("Skipping table creation - market holiday");
            return;
        }
        
        context.getLogger().log("Creating DynamoDB tables 5 minutes before ScanEarningsLambda");
        createDynamoDBTables(context);
    }
    
    /**
     * Handle early closure table cleanup (triggered at 1:00 PM EST)
     */
    private void handleCleanupTablesEarly(Context context) {
        try {
            context.getLogger().log("Cleaning up DynamoDB tables after early closure ScanEarningsLambda (1:00 PM EST)");
            
            // Delete tables to avoid idle costs
            deleteTableIfExists(EARNINGS_TABLE, context);
            deleteTableIfExists(FILTERED_TABLE, context);
            
            context.getLogger().log("DynamoDB tables cleaned up successfully after early closure");
            
        } catch (Exception e) {
            context.getLogger().log("Error cleaning up tables after early closure: " + e.getMessage());
        }
    }
    
    /**
     * Handle normal day table cleanup (triggered at 4:00 PM EST)
     */
    private void handleCleanupTablesNormal(Context context) {
        try {
            context.getLogger().log("Cleaning up DynamoDB tables after normal day ScanEarningsLambda (4:00 PM EST)");
            
            // Delete tables to avoid idle costs
            deleteTableIfExists(EARNINGS_TABLE, context);
            deleteTableIfExists(FILTERED_TABLE, context);
            
            context.getLogger().log("DynamoDB tables cleaned up successfully after normal day");
            
        } catch (Exception e) {
            context.getLogger().log("Error cleaning up tables after normal day: " + e.getMessage());
        }
    }
    
    /**
     * Create DynamoDB tables for temporary data storage
     * Tables are created only on market-open days, 5 minutes before ScanEarningsLambda runs
     * Tables are cleaned up at 4:00 PM EST
     */
    private void createDynamoDBTables(Context context) {
        try {
            // Create earnings data table
            createTableIfNotExists(EARNINGS_TABLE, "scanDate", "ticker", context);
            
            // Create filtered stocks table
            createTableIfNotExists(FILTERED_TABLE, "scanDate", "ticker", context);
            
            context.getLogger().log("DynamoDB tables created/verified successfully");
            
        } catch (Exception e) {
            context.getLogger().log("Error creating DynamoDB tables: " + e.getMessage());
            // Don't throw - continue with lambda execution
        }
    }
    
    /**
     * Create a DynamoDB table if it doesn't exist
     */
    private void createTableIfNotExists(String tableName, String partitionKey, String sortKey, Context context) {
        try {
            // Check if table exists
            DescribeTableRequest describeRequest = DescribeTableRequest.builder()
                .tableName(tableName)
                .build();
            
            try {
                dynamoDbClient.describeTable(describeRequest);
                context.getLogger().log("Table " + tableName + " already exists");
                return;
            } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
                // Table doesn't exist, create it
                context.getLogger().log("Creating table: " + tableName);
            }
            
            // Create table
            CreateTableRequest createRequest = CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName(partitionKey)
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName(sortKey)
                        .attributeType(ScalarAttributeType.S)
                        .build()
                )
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName(partitionKey)
                        .keyType(KeyType.HASH)
                        .build(),
                    KeySchemaElement.builder()
                        .attributeName(sortKey)
                        .keyType(KeyType.RANGE)
                        .build()
                )
                .tags(
                    software.amazon.awssdk.services.dynamodb.model.Tag.builder().key("Environment").value(System.getenv().getOrDefault("ENVIRONMENT", "dev")).build(),
                    software.amazon.awssdk.services.dynamodb.model.Tag.builder().key("Purpose").value("Trading data - cleaned up at 4:00 PM").build()
                )
                .build();
            
            dynamoDbClient.createTable(createRequest);
            
            context.getLogger().log("Successfully created table: " + tableName);
            
        } catch (Exception e) {
            context.getLogger().log("Error creating table " + tableName + ": " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Delete a DynamoDB table if it exists
     */
    private void deleteTableIfExists(String tableName, Context context) {
        try {
            // Check if table exists
            DescribeTableRequest describeRequest = DescribeTableRequest.builder()
                .tableName(tableName)
                .build();
            
            try {
                dynamoDbClient.describeTable(describeRequest);
                // Table exists, delete it
                DeleteTableRequest deleteRequest = DeleteTableRequest.builder()
                    .tableName(tableName)
                    .build();
                
                dynamoDbClient.deleteTable(deleteRequest);
                context.getLogger().log("Successfully deleted table: " + tableName);
                
            } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
                // Table doesn't exist, nothing to delete
                context.getLogger().log("Table " + tableName + " does not exist, nothing to delete");
            }
            
        } catch (Exception e) {
            context.getLogger().log("Error deleting table " + tableName + ": " + e.getMessage());
            // Don't throw - continue with cleanup
        }
    }

    /**
     * Makes HTTP request to Finnhub API
     */
    private String makeFinnhubRequest(String url, String apiKey) throws IOException {
        try {
            AlpacaCredentials credentials = new AlpacaCredentials();
            credentials.setApiKey(apiKey);
            credentials.setSecretKey(""); // Finnhub doesn't use secret key
            return AlpacaHttpClient.makeAlpacaRequest(url, "GET", null, credentials);
        } catch (Exception e) {
            throw new IOException("Finnhub API request failed: " + e.getMessage(), e);
        }
    }

    private enum MarketStatus {
        NORMAL,
        EARLY_CLOSURE,
        HOLIDAY,
        WEEKEND
    }
}
