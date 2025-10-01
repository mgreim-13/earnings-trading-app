package com.trading.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.ZoneId;

/**
 * Common constants and shared instances used across TradingCommonUtils
 * Centralizes shared resources to avoid duplication
 */
public class CommonConstants {
    
    // Time zone constants
    public static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    
    // Shared ObjectMapper instance
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    
    // Private constructor to prevent instantiation
    private CommonConstants() {}
}
