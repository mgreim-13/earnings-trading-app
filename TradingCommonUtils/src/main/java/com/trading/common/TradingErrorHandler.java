package com.trading.common;

import com.amazonaws.services.lambda.runtime.Context;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized error handling for trading lambda functions
 */
public class TradingErrorHandler {
    
    
    /**
     * Handle error and return appropriate response
     */
    public static String handleError(Exception e, Context context, String operation) {
        String errorMessage = "Error in " + operation + ": " + e.getMessage();
        context.getLogger().log(errorMessage);
        e.printStackTrace();
        
        return createErrorResponse(errorMessage, 500);
    }
    
    /**
     * Create success response with data
     */
    public static String createSuccessResponse(String message, Object data) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", message != null ? message : "Operation completed");
            response.put("timestamp", LocalDateTime.now(CommonConstants.EST_ZONE).toString());
            if (data != null) {
                response.put("data", data);
            }
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"success\",\"message\":\"" + (message != null ? message : "Operation completed") + "\"}";
        }
    }
    
    /**
     * Create success response without data
     */
    public static String createSuccessResponse(String message) {
        return createSuccessResponse(message, null);
    }
    
    /**
     * Create error response
     */
    public static String createErrorResponse(String message, int statusCode) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", message != null ? message : "Unknown error occurred");
            response.put("statusCode", statusCode);
            response.put("timestamp", LocalDateTime.now(CommonConstants.EST_ZONE).toString());
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + (message != null ? message : "Unknown error occurred") + "\"}";
        }
    }
    
    /**
     * Create skipped response
     */
    public static String createSkippedResponse(String reason, Object data) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "skipped");
            response.put("reason", reason);
            response.put("timestamp", LocalDateTime.now(CommonConstants.EST_ZONE).toString());
            if (data != null) {
                response.put("data", data);
            }
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"skipped\",\"reason\":\"" + reason + "\"}";
        }
    }
    
    /**
     * Log error details to CloudWatch
     */
    public static void logError(String errorType, String details, Context context) {
        Map<String, Object> errorLog = new HashMap<>();
        errorLog.put("timestamp", LocalDateTime.now(CommonConstants.EST_ZONE).toString());
        errorLog.put("errorType", errorType);
        errorLog.put("details", details);
        errorLog.put("functionName", context.getFunctionName());
        errorLog.put("requestId", context.getAwsRequestId());
        
        try {
            String errorJson = CommonConstants.OBJECT_MAPPER.writeValueAsString(errorLog);
            context.getLogger().log("CLOUDWATCH_ERROR_LOG: " + errorJson);
        } catch (Exception e) {
            context.getLogger().log("Failed to create error log JSON: " + e.getMessage());
        }
    }
}
