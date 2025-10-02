package com.trading.common;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for parsing Alpaca option symbols.
 * Handles the standard Alpaca option symbol format: SYMBOL + YYYYMMDD + C/P + STRIKE
 * Example: AAPL20251025C150 -> AAPL, 2025-10-25, C, 150.0
 */
public class OptionSymbolUtils {

    /**
     * Parses an Alpaca option symbol to extract its components.
     *
     * @param symbol The option symbol string (e.g., "AAPL20251025C150")
     * @return A Map containing "underlying", "expiration", "type", and "strike".
     * @throws IllegalArgumentException if the symbol format is invalid.
     */
    public static Map<String, Object> parseOptionSymbol(String symbol) {
        if (symbol == null || symbol.length() < 10) {
            throw new IllegalArgumentException("Invalid option symbol format - too short: " + symbol);
        }
        
        try {
            return parseAlpacaFormat(symbol);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse option symbol: " + symbol, e);
        }
    }

    /**
     * Parse Alpaca format: SYMBOL + YYYYMMDD + C/P + STRIKE
     * Example: AAPL20251025C150 -> AAPL, 2025-10-25, C, 150.0
     */
    private static Map<String, Object> parseAlpacaFormat(String symbol) {
        // Find the option type (C or P) - look from the end backwards
        int optionTypeIndex = -1;
        for (int i = symbol.length() - 1; i >= 0; i--) {
            if (symbol.charAt(i) == 'C' || symbol.charAt(i) == 'P') {
                optionTypeIndex = i;
                break;
            }
        }
        
        if (optionTypeIndex == -1) {
            throw new IllegalArgumentException("Invalid option symbol format - cannot find option type: " + symbol);
        }
        
        // Extract components
        String type = symbol.substring(optionTypeIndex, optionTypeIndex + 1);
        String strikeStr = symbol.substring(optionTypeIndex + 1);
        
        // Find the date - it should be 6 digits before the option type
        // Look for 6 consecutive digits before the option type
        String dateStr = "";
        String underlying = "";
        for (int i = optionTypeIndex - 6; i >= 0; i--) {
            if (i + 6 <= optionTypeIndex) {
                String candidate = symbol.substring(i, i + 6);
                if (candidate.matches("\\d{6}")) {
                    dateStr = candidate;
                    underlying = symbol.substring(0, i);
                    break;
                }
            }
        }
        
        if (dateStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid option symbol format - cannot find 6-digit date: " + symbol);
        }
        
        // Validate option type
        if (!type.equals("C") && !type.equals("P")) {
            throw new IllegalArgumentException("Invalid option type: " + type + " in symbol: " + symbol);
        }
        
        // Parse date (YYMMDD -> YYYY-MM-DD)
        int year = Integer.parseInt(dateStr.substring(0, 2));
        int month = Integer.parseInt(dateStr.substring(2, 4));
        int day = Integer.parseInt(dateStr.substring(4, 6));
        
        // Convert 2-digit year to 4-digit year (assume 20xx for years 00-99)
        if (year < 50) {
            year += 2000;
        } else {
            year += 1900;
        }
        
        String expiration = String.format("%04d-%02d-%02d", year, month, day);
        
        // Parse strike (convert to decimal) - handle zero-padded format
        // Alpaca uses 8-digit zero-padded strikes (e.g., "00115000" = 115.0)
        double strike;
        if (strikeStr.length() == 8) {
            // Convert from 8-digit zero-padded format (e.g., "00115000" -> 115.0)
            strike = Double.parseDouble(strikeStr) / 1000.0;
        } else {
            // Handle regular format
            strike = Double.parseDouble(strikeStr);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("expiration", expiration);
        result.put("strike", strike);
        result.put("type", type);
        result.put("underlying", underlying);
        
        return result;
    }

    /**
     * Validate option symbol format
     */
    public static boolean isValidOptionSymbol(String symbol) {
        try {
            parseOptionSymbol(symbol);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract underlying symbol from option symbol
     */
    public static String getUnderlyingSymbol(String optionSymbol) {
        Map<String, Object> parsed = parseOptionSymbol(optionSymbol);
        return (String) parsed.get("underlying");
    }

    /**
     * Extract expiration date from option symbol
     */
    public static LocalDate getExpirationDate(String optionSymbol) {
        Map<String, Object> parsed = parseOptionSymbol(optionSymbol);
        String expirationStr = (String) parsed.get("expiration");
        return LocalDate.parse(expirationStr);
    }

    /**
     * Extract strike price from option symbol
     */
    public static double getStrikePrice(String optionSymbol) {
        Map<String, Object> parsed = parseOptionSymbol(optionSymbol);
        return (Double) parsed.get("strike");
    }

    /**
     * Extract option type (C or P) from option symbol
     */
    public static String getOptionType(String optionSymbol) {
        Map<String, Object> parsed = parseOptionSymbol(optionSymbol);
        return (String) parsed.get("type");
    }
}