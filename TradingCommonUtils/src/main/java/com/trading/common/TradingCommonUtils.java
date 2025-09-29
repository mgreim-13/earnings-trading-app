package com.trading.common;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.common.models.AlpacaCredentials;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared utilities for all trading lambda functions
 */
public class TradingCommonUtils {
    
    // Constants
    private static final String ALPACA_API_URL = "https://paper-api.alpaca.markets/v2";
    private static final String ALPACA_DATA_URL = "https://data.alpaca.markets/v1beta1";
    private static final String APCA_API_KEY_HEADER = "APCA-API-KEY-ID";
    private static final String APCA_SECRET_KEY_HEADER = "APCA-API-SECRET-KEY";
    private static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    
    // Shared clients
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder().build();
    
    // HTTP client with connection pooling
    private static final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    private static final HttpClient httpClient;
    
    static {
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(10);
        httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .build();
    }
    
    /**
     * Check if market is open using Alpaca API
     */
    public static boolean isMarketOpen(String apiKey, String secretKey) {
        try {
            String url = ALPACA_API_URL + "/clock";
            String responseBody = makeHttpRequest(url, apiKey, secretKey, "GET", null);
            JsonNode clockNode = objectMapper.readTree(responseBody);
            return clockNode.get("is_open").asBoolean();
        } catch (Exception e) {
            return false; // Conservative approach
        }
    }
    
    /**
     * Check if a date is a market holiday using Finnhub API
     */
    public static boolean isMarketHoliday(LocalDate date, String finnhubApiKey) {
        try {
            // Use Finnhub calendar API to check for earnings/holidays
            String url = "https://finnhub.io/api/v1/calendar/earnings?from=" + date + "&to=" + date + "&token=" + finnhubApiKey;
            String responseBody = makeHttpRequest(url, "", "", "GET", null);
            JsonNode responseNode = objectMapper.readTree(responseBody);
            
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

            JsonNode secretNode = parseJson(secretString);
            
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
                credentials.setBaseUrl(ALPACA_API_URL);
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
     * Make HTTP request to Alpaca API
     */
    public static String makeAlpacaRequest(String url, String method, String body, AlpacaCredentials credentials) {
        return makeHttpRequest(url, credentials.getApiKeyId(), credentials.getSecretKey(), method, body);
    }
    
    /**
     * Make HTTP request with API key and secret
     */
    public static String makeHttpRequest(String url, String apiKey, String secretKey, String method, String body) {
        try {
            HttpResponse response = method.equals("POST") ? 
                makePostRequest(url, apiKey, secretKey, body) : 
                makeGetRequest(url, apiKey, secretKey);
            
            String responseBody = EntityUtils.toString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200 && statusCode != 201) {
                throw new RuntimeException("HTTP " + statusCode + ": " + responseBody);
            }
            return responseBody;
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
            LocalDateTime.now(EST_ZONE)
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
            LocalDateTime.now(EST_ZONE)
        ));
    }
    
    /**
     * Create standardized success response
     */
    public static String createSuccessResponse(String message, Object data) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", message);
            response.put("timestamp", LocalDateTime.now(EST_ZONE).toString());
            if (data != null) {
                response.put("data", data);
            }
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"success\",\"message\":\"" + message + "\"}";
        }
    }
    
    /**
     * Create standardized error response
     */
    public static String createErrorResponse(String message, int statusCode) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", message);
            response.put("statusCode", statusCode);
            response.put("timestamp", LocalDateTime.now(EST_ZONE).toString());
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + message + "\"}";
        }
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
     * Parse JSON string
     */
    public static JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON parsing failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert object to JSON string
     */
    public static String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get current date string in EST
     */
    public static String getCurrentDateString() {
        return LocalDateTime.now(EST_ZONE).toLocalDate().toString();
    }
    
    /**
     * Get current datetime string in EST
     */
    public static String getCurrentDateTimeString() {
        return LocalDateTime.now(EST_ZONE).toString();
    }
    
    // Private helper methods
    
    private static HttpResponse makeGetRequest(String url, String apiKey, String secretKey) throws Exception {
        HttpGet request = new HttpGet(url);
        request.setHeader(APCA_API_KEY_HEADER, apiKey);
        request.setHeader(APCA_SECRET_KEY_HEADER, secretKey);
        return httpClient.execute(request);
    }

    private static HttpResponse makePostRequest(String url, String apiKey, String secretKey, String body) throws Exception {
        HttpPost request = new HttpPost(url);
        request.setHeader(APCA_API_KEY_HEADER, apiKey);
        request.setHeader(APCA_SECRET_KEY_HEADER, secretKey);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new StringEntity(body));
        return httpClient.execute(request);
    }
}
