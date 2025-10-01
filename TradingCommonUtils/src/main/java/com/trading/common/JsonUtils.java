package com.trading.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Utility class for JSON operations with centralized error handling
 * Consolidates JSON parsing and serialization logic
 */
public class JsonUtils {
    
    /**
     * Parse JSON string with error handling
     * 
     * @param json JSON string to parse
     * @return JsonNode or null if parsing fails
     */
    public static JsonNode parseJson(String json) {
        try {
            return CommonConstants.OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON parsing failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse JSON string safely (returns null on error instead of throwing)
     * 
     * @param json JSON string to parse
     * @return JsonNode or null if parsing fails
     */
    public static JsonNode parseJsonSafely(String json) {
        try {
            return CommonConstants.OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
    
    /**
     * Convert object to JSON string with error handling
     * 
     * @param object Object to serialize
     * @return JSON string
     */
    public static String toJson(Object object) {
        try {
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert object to JSON string safely (returns fallback on error)
     * 
     * @param object Object to serialize
     * @param fallback Fallback string if serialization fails
     * @return JSON string or fallback
     */
    public static String toJsonSafely(Object object, String fallback) {
        try {
            return CommonConstants.OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }
    
    /**
     * Check if JSON string is valid
     * 
     * @param json JSON string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidJson(String json) {
        return parseJsonSafely(json) != null;
    }
    
    // Private constructor to prevent instantiation
    private JsonUtils() {}
}
