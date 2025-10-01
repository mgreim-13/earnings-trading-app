package com.trading.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.models.AlpacaCredentials;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared utilities for all trading lambda functions
 */
public class TradingCommonUtils {
    
    // Shared clients
    private static final SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder().build();
    
    // HTTP client is now handled by AlpacaHttpClient
    
    
    /**
     * Check if a date is a market holiday using Finnhub API
     */
    public static boolean isMarketHoliday(LocalDate date, String finnhubApiKey) {
        try {
            // Use Finnhub calendar API to check for earnings/holidays
            String url = "https://finnhub.io/api/v1/calendar/earnings?from=" + date + "&to=" + date + "&token=" + finnhubApiKey;
            String responseBody = makeHttpRequest(url, "", "", "GET", null);
            JsonNode responseNode = JsonUtils.parseJson(responseBody);
            
            // If there are no earnings events, it might be a holiday
            // This is a simplified approach - in production you'd use a dedicated holiday API
            return !responseNode.has("earningsCalendar") || responseNode.get("earningsCalendar").size() == 0;
            
        } catch (Exception e) {
            // Conservative approach - assume not a holiday on error
            return false;
        }
    }
    
    /**
     * Get Alpaca API credentials from AWS Secrets Manager
     */
    public static AlpacaCredentials getAlpacaCredentials(String secretName) {
        return executeWithErrorHandling("getting API credentials", () -> {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();

            JsonNode secretNode = JsonUtils.parseJson(secretString);
            
            AlpacaCredentials credentials = new AlpacaCredentials();
            
            // Handle different secret formats
            if (secretNode.has("keyId")) {
                credentials.setKeyId(secretNode.get("keyId").asText());
            } else if (secretNode.has("apiKey")) {
                credentials.setApiKey(secretNode.get("apiKey").asText());
            }
            
            if (secretNode.has("secretKey")) {
                credentials.setSecretKey(secretNode.get("secretKey").asText());
            }
            
            if (secretNode.has("baseUrl")) {
                credentials.setBaseUrl(secretNode.get("baseUrl").asText());
            } else {
                credentials.setBaseUrl("https://paper-api.alpaca.markets/v2");
            }

            return credentials;
        });
    }
    
    /**
     * Get Alpaca API credentials as Map for backward compatibility
     */
    public static Map<String, String> getAlpacaCredentialsAsMap(String secretName) {
        AlpacaCredentials credentials = getAlpacaCredentials(secretName);
        Map<String, String> result = new HashMap<>();
        result.put("apiKey", credentials.getApiKeyId());
        result.put("secretKey", credentials.getSecretKey());
        return result;
    }
    
    
    /**
     * Make HTTP request with API key and secret
     * @deprecated Use AlpacaHttpClient.makeAlpacaRequest() instead
     */
    @Deprecated
    public static String makeHttpRequest(String url, String apiKey, String secretKey, String method, String body) {
        try {
            AlpacaCredentials credentials = new AlpacaCredentials();
            credentials.setApiKey(apiKey);
            credentials.setSecretKey(secretKey);
            return AlpacaHttpClient.makeAlpacaRequest(url, method, body, credentials);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Log successful trade to CloudWatch
     */
    public static void logTradeSuccess(String ticker, String orderId, Context context) {
        context.getLogger().log(String.format(
            "SUCCESSFUL_TRADE: ticker=%s, orderId=%s, timestamp=%s", 
            ticker, 
            orderId, 
            LocalDateTime.now(CommonConstants.EST_ZONE)
        ));
    }

    /**
     * Log failed trade to CloudWatch
     */
    public static void logTradeFailure(String ticker, String reason, Context context) {
        context.getLogger().log(String.format(
            "FAILED_TRADE: ticker=%s, reason=%s, timestamp=%s", 
            ticker, 
            reason, 
            LocalDateTime.now(CommonConstants.EST_ZONE)
        ));
    }
    
    
    /**
     * Execute operation with error handling
     */
    public static <T> T executeWithErrorHandling(String operation, java.util.function.Supplier<T> operationSupplier) {
        try {
            return operationSupplier.get();
        } catch (Exception e) {
            throw new RuntimeException("Error " + operation + ": " + e.getMessage(), e);
        }
    }
    
    
    /**
     * Get current date string in EST
     */
    public static String getCurrentDateString() {
        return LocalDateTime.now(CommonConstants.EST_ZONE).toLocalDate().toString();
    }
    
    /**
     * Get current datetime string in EST
     */
    public static String getCurrentDateTimeString() {
        return LocalDateTime.now(CommonConstants.EST_ZONE).toString();
    }
    
    // Private helper methods
    
}
