package com.trading.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.common.models.AlpacaCredentials;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Common utility class for option selection logic
 * Provides shared methods for selecting option expirations and strikes
 * Used by both filtering and trading components to ensure consistency
 * 
 * This utility works with both OptionSnapshot objects (StockFilterLambda) 
 * and Map<String, Object> contract data (InitiateTradesLambda)
 */
public class OptionSelectionUtils {
    
    /**
     * Find the short leg expiration using the same logic as InitiateTradesLambda
     * This ensures consistency between filtering and actual trading
     * 
     * @param expirations List of available expiration dates (as strings)
     * @param today Current date
     * @return The first available expiration after today, or null if none found
     */
    public static LocalDate findShortLegExpiration(List<String> expirations, LocalDate today) {
        List<LocalDate> parsedExpirations = parseAndSortExpirations(expirations);
        if (parsedExpirations == null || parsedExpirations.isEmpty()) {
            return null;
        }
        
        // Find the first available expiration after today
        return parsedExpirations.stream()
            .filter(exp -> exp.isAfter(today))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find the short leg expiration from an option chain map
     * This is a convenience method that works with Map<String, OptionSnapshot> data structure
     * used by the StockFilterLambda components
     * 
     * @param optionChain Map of expiration dates to OptionSnapshot objects
     * @param today Current date
     * @return The first available expiration after today, or null if none found
     */
    public static LocalDate findShortLegExpirationFromOptionChain(Map<String, com.trading.common.models.OptionSnapshot> optionChain, LocalDate today) {
        if (optionChain == null || optionChain.isEmpty()) {
            return null;
        }
        
        // Extract expiration dates from map keys
        List<String> expirations = new ArrayList<>(optionChain.keySet());
        return findShortLegExpiration(expirations, today);
    }
    
    /**
     * Find the far leg expiration for calendar spreads
     * Looks for an expiration around 30 days from today
     * 
     * @param expirations List of available expiration dates (as strings)
     * @param today Current date
     * @param nearExpiration The near leg expiration date
     * @return The far leg expiration, or null if none found
     */
    public static LocalDate findFarLegExpiration(List<String> expirations, LocalDate today, LocalDate nearExpiration) {
        return findFarLegExpiration(expirations, today, nearExpiration, 30);
    }
    
    /**
     * Find the far leg expiration for calendar spreads with custom target days
     * 
     * @param expirations List of available expiration dates (as strings)
     * @param today Current date
     * @param nearExpiration The near leg expiration date
     * @param targetDays Target number of days from today for the far leg
     * @return The far leg expiration, or null if none found
     */
    public static LocalDate findFarLegExpiration(List<String> expirations, LocalDate today, LocalDate nearExpiration, int targetDays) {
        List<LocalDate> parsedExpirations = parseAndSortExpirations(expirations);
        if (parsedExpirations == null || parsedExpirations.isEmpty()) {
            return null;
        }
        
        // Find expiration after near expiration, closest to target days from today
        LocalDate targetDate = today.plusDays(targetDays);
        
        return parsedExpirations.stream()
            .filter(exp -> exp.isAfter(nearExpiration))
            .min((a, b) -> Long.compare(
                Math.abs(a.toEpochDay() - targetDate.toEpochDay()),
                Math.abs(b.toEpochDay() - targetDate.toEpochDay())
            ))
            .orElse(null);
    }
    
    /**
     * Find the far leg expiration from an option chain map
     * This is a convenience method that works with Map<String, OptionSnapshot> data structure
     * used by the StockFilterLambda components
     * 
     * @param optionChain Map of expiration dates to OptionSnapshot objects
     * @param today Current date
     * @param nearExpiration The near leg expiration date
     * @param targetDays Target number of days from today for the far leg
     * @return The far leg expiration, or null if none found
     */
    public static LocalDate findFarLegExpirationFromOptionChain(Map<String, com.trading.common.models.OptionSnapshot> optionChain, 
                                                               LocalDate today, LocalDate nearExpiration, int targetDays) {
        if (optionChain == null || optionChain.isEmpty()) {
            return null;
        }
        
        // Extract expiration dates from map keys
        List<String> expirations = new ArrayList<>(optionChain.keySet());
        return findFarLegExpiration(expirations, today, nearExpiration, targetDays);
    }
    
    /**
     * Find the far leg expiration from an option chain map with default 30-day target
     * This is a convenience method that works with Map<String, OptionSnapshot> data structure
     * used by the StockFilterLambda components
     * 
     * @param optionChain Map of expiration dates to OptionSnapshot objects
     * @param today Current date
     * @param nearExpiration The near leg expiration date
     * @return The far leg expiration, or null if none found
     */
    public static LocalDate findFarLegExpirationFromOptionChain(Map<String, com.trading.common.models.OptionSnapshot> optionChain, 
                                                               LocalDate today, LocalDate nearExpiration) {
        return findFarLegExpirationFromOptionChain(optionChain, today, nearExpiration, 30);
    }
    
    /**
     * Find ATM strike from call and put contracts ensuring strike consistency for straddles
     * This ensures both call and put use the same strike price
     * 
     * @param callContracts List of call contract information maps
     * @param putContracts List of put contract information maps
     * @param currentPrice Current stock price
     * @return The strike price closest to current price that exists in both call and put, or -1 if none found
     */
    public static double findATMStrikeForStraddle(List<Map<String, Object>> callContracts, 
                                                 List<Map<String, Object>> putContracts, 
                                                 double currentPrice) {
        if (callContracts == null || putContracts == null || 
            callContracts.isEmpty() || putContracts.isEmpty()) {
            return -1.0;
        }
        
        // Extract strikes from both contract types
        Set<Double> callStrikes = extractStrikes(callContracts);
        Set<Double> putStrikes = extractStrikes(putContracts);
        
        // Find common strikes
        Set<Double> commonStrikes = new HashSet<>(callStrikes);
        commonStrikes.retainAll(putStrikes);
        
        if (commonStrikes.isEmpty()) {
            return -1.0; // No common strikes found
        }
        
        // Find the common strike closest to current price
        return findClosestStrike(commonStrikes, currentPrice);
    }
    
    /**
     * Find option symbol for a specific strike from contract information (InitiateTradesLambda format)
     * Uses same tolerance logic as InitiateTradesLambda
     * 
     * @param contracts List of contract information maps
     * @param targetStrike The strike price to find
     * @return The option symbol, or null if not found
     */
    public static String findSymbolForStrike(List<Map<String, Object>> contracts, double targetStrike) {
        if (contracts == null || contracts.isEmpty()) {
            return null;
        }
        
        for (Map<String, Object> contractInfo : contracts) {
            if (contractInfo == null) continue;
            
            Object strikeObj = contractInfo.get("strike");
            Object symbolObj = contractInfo.get("symbol");
            
            if (strikeObj instanceof Double && symbolObj instanceof String) {
                double strike = (Double) strikeObj;
                if (Math.abs(strike - targetStrike) < 0.01) { // Find match within tolerance for safety
                    return (String) symbolObj;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Validate bid/ask prices and calculate mid price with sanity checks
     * Rejects spreads > 20% as they indicate poor liquidity
     * 
     * @param bid Bid price
     * @param ask Ask price
     * @return Mid price if valid, or -1 if invalid
     */
    public static double validateBidAsk(double bid, double ask) {
        if (!PriceUtils.isValidBidAsk(bid, ask)) {
            return -1.0;
        }
        
        // Reject spreads > 20% as they indicate poor liquidity
        if (!PriceUtils.isSpreadAcceptable(bid, ask, 0.20)) {
            return -1.0;
        }
        
        return PriceUtils.calculateMidPrice(bid, ask);
    }
    
    /**
     * Interface for option data that can be used by both modules
     * This allows the utility to work with different data structures
     */
    public interface OptionData {
        double getStrike();
        String getExpiration();
        double getBid();
        double getAsk();
    }
    
    /**
     * Find option for specific strike and expiration from generic option data list
     * Finds the closest available option to target expiration and strike price
     * Works with any object implementing OptionData interface
     * 
     * @param options List of option data objects
     * @param targetStrike The strike price to find
     * @param targetExpiration The target expiration date
     * @return The closest matching option, or null if not found
     */
    public static <T extends OptionData> T findOptionForStrikeAndExpirationFromGenericData(List<T> options, 
                                                                          double targetStrike, 
                                                                          LocalDate targetExpiration) {
        return findClosestOption(options, targetStrike, targetExpiration,
            OptionData::getStrike,
            option -> LocalDate.parse(option.getExpiration()));
    }
    
    /**
     * Fetch option snapshots from Alpaca API using the same approach as InitiateTradesLambda
     * This ensures consistency between filtering and trading components
     * 
     * @param ticker Stock ticker symbol
     * @param credentials Alpaca API credentials
     * @param type Option type ("call" or "put")
     * @param maxDays Maximum days to look ahead
     * @return Map of expiration dates to lists of contract information
     */
    public static Map<String, List<Map<String, Object>>> fetchOptionSnapshots(String ticker, 
                                                                             AlpacaCredentials credentials, 
                                                                             String type, 
                                                                             int maxDays) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        String expirationGte = today.plusDays(1).toString();
        String expirationLte = today.plusDays(maxDays).toString();

        String url = "https://data.alpaca.markets/v1beta1/options/snapshots/" + ticker + 
                    "?type=" + type + "&expiration_date_gte=" + expirationGte + "&expiration_date_lte=" + expirationLte + 
                    "&feed=opra&limit=1000";

        String responseBody = AlpacaHttpClient.makeAlpacaRequest(url, "GET", null, credentials);
        JsonNode snapshots = JsonUtils.parseJson(responseBody).get("snapshots");
        
        if (snapshots == null || !snapshots.isObject() || snapshots.size() == 0) {
            return new HashMap<>();
        }

        Map<String, List<Map<String, Object>>> contractsByExpiration = new HashMap<>();
        snapshots.fields().forEachRemaining(entry -> {
            String symbol = entry.getKey();
            JsonNode snapshot = entry.getValue();
            try {
                Map<String, Object> parsed = OptionSymbolUtils.parseOptionSymbol(symbol);
                String expiration = (String) parsed.get("expiration");
                double strike = (Double) parsed.get("strike");
                
                Map<String, Object> contractInfo = new HashMap<>();
                contractInfo.put("symbol", symbol);
                contractInfo.put("strike", strike);
                contractInfo.put("snapshot", snapshot);
                
                contractsByExpiration.computeIfAbsent(expiration, k -> new ArrayList<>()).add(contractInfo);
            } catch (Exception e) {
                // Skip invalid symbols
            }
        });
        
        return contractsByExpiration;
    }
    
    
    /**
     * Find option for specific strike and expiration from contracts grouped by expiration
     * Finds the closest available option to target expiration and strike price
     * This method works with the same data structure used by InitiateTradesLambda
     * 
     * @param contractsByExpiration Map of expiration dates to contract lists
     * @param targetStrike The strike price to find
     * @param targetExpiration The target expiration date
     * @return The closest matching contract, or null if not found
     */
    public static Map<String, Object> findOptionForStrikeAndExpirationFromContractMaps(
            Map<String, List<Map<String, Object>>> contractsByExpiration,
            double targetStrike, 
            LocalDate targetExpiration) {
        
        if (contractsByExpiration == null || contractsByExpiration.isEmpty()) {
            return null;
        }
        
        // Collect all contracts from all expirations
        List<Map<String, Object>> allContracts = new ArrayList<>();
        contractsByExpiration.entrySet().stream()
            .filter(entry -> {
                try {
                    LocalDate.parse(entry.getKey()); // Validate expiration format
                    return true;
                } catch (Exception e) {
                    return false;
                }
            })
            .forEach(entry -> {
                String expiration = entry.getKey();
                entry.getValue().forEach(contract -> {
                    contract.put("expiration", expiration);
                    allContracts.add(contract);
                });
            });
        
        return findClosestOption(allContracts, targetStrike, targetExpiration,
            contract -> (Double) contract.get("strike"),
            contract -> LocalDate.parse((String) contract.get("expiration")));
    }
    
    /**
     * Find call and put options for straddle ensuring strike consistency
     * This ensures both call and put use the same strike price by pre-filtering
     * contracts to only include strikes that exist in both call and put contracts
     * 
     * @param callContracts Map of call contracts by expiration
     * @param putContracts Map of put contracts by expiration
     * @param targetStrike The strike price to find (should be common to both)
     * @param targetExpiration The target expiration date
     * @return Array with [callContract, putContract] or null if either not found
     */
    public static Map<String, Object>[] findStraddleOptionsForStrikeAndExpiration(
            Map<String, List<Map<String, Object>>> callContracts,
            Map<String, List<Map<String, Object>>> putContracts,
            double targetStrike,
            LocalDate targetExpiration) {
        
        if (callContracts == null || putContracts == null) {
            return null;
        }
        
        // Pre-filter contracts to only include strikes that exist in both call and put
        Map<String, List<Map<String, Object>>> filteredCallContracts = filterContractsByCommonStrikes(callContracts, putContracts);
        Map<String, List<Map<String, Object>>> filteredPutContracts = filterContractsByCommonStrikes(putContracts, callContracts);
        
        if (filteredCallContracts.isEmpty() || filteredPutContracts.isEmpty()) {
            return null;
        }
        
        // Find call option from filtered contracts
        Map<String, Object> callContract = findOptionForStrikeAndExpirationFromContractMaps(filteredCallContracts, targetStrike, targetExpiration);
        if (callContract == null) {
            return null;
        }
        
        // Find put option from filtered contracts
        Map<String, Object> putContract = findOptionForStrikeAndExpirationFromContractMaps(filteredPutContracts, targetStrike, targetExpiration);
        if (putContract == null) {
            return null;
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object>[] result = new Map[2];
        result[0] = callContract;
        result[1] = putContract;
        return result;
    }
    
    /**
     * Filter contracts to only include strikes that exist in both contract maps
     * This ensures strike consistency between call and put contracts for straddles
     * 
     * @param contractsToFilter The contracts to filter
     * @param referenceContracts The contracts to check for common strikes
     * @return Filtered contracts containing only strikes that exist in both maps
     */
    private static Map<String, List<Map<String, Object>>> filterContractsByCommonStrikes(
            Map<String, List<Map<String, Object>>> contractsToFilter,
            Map<String, List<Map<String, Object>>> referenceContracts) {
        
        if (contractsToFilter == null || referenceContracts == null) {
            return new HashMap<>();
        }
        
        // Extract all strikes from reference contracts
        Set<Double> referenceStrikes = new HashSet<>();
        for (List<Map<String, Object>> contractList : referenceContracts.values()) {
            referenceStrikes.addAll(extractStrikes(contractList));
        }
        
        // Filter contracts to only include common strikes
        Map<String, List<Map<String, Object>>> filteredContracts = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : contractsToFilter.entrySet()) {
            String expiration = entry.getKey();
            List<Map<String, Object>> contracts = entry.getValue();
            
            List<Map<String, Object>> filteredContractsForExpiration = contracts.stream()
                .filter(contract -> {
                    Double strike = (Double) contract.get("strike");
                    return strike != null && referenceStrikes.contains(strike);
                })
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            if (!filteredContractsForExpiration.isEmpty()) {
                filteredContracts.put(expiration, filteredContractsForExpiration);
            }
        }
        
        return filteredContracts;
    }
    
    /**
     * Find option symbols for both legs of a calendar spread using the same strike
     * This ensures both legs have the same strike price (required for calendar spreads)
     * 
     * @param contractsByExpiration Map of expiration dates to contract lists
     * @param shortExpiration Short leg expiration date
     * @param longExpiration Long leg expiration date
     * @param currentPrice Current stock price
     * @return Array with [shortSymbol, longSymbol] or null if not found
     */
    public static String[] findCalendarSpreadSymbols(
            Map<String, List<Map<String, Object>>> contractsByExpiration,
            LocalDate shortExpiration,
            LocalDate longExpiration,
            double currentPrice) {
        
        List<Map<String, Object>> shortContracts = contractsByExpiration.get(shortExpiration.toString());
        List<Map<String, Object>> longContracts = contractsByExpiration.get(longExpiration.toString());
        
        if (shortContracts == null || shortContracts.isEmpty() || 
            longContracts == null || longContracts.isEmpty()) {
            return null;
        }
        
        // Find the best common strike between short and long contracts
        double bestStrike = findBestCommonStrikeFromContractMaps(
            Map.of(shortExpiration.toString(), shortContracts),
            Map.of(longExpiration.toString(), longContracts),
            currentPrice);
        
        if (bestStrike < 0) {
            return null;
        }
        
        // Find symbols for both legs at the same strike
        String shortSymbol = findSymbolForStrike(shortContracts, bestStrike);
        String longSymbol = findSymbolForStrike(longContracts, bestStrike);
        
        if (shortSymbol == null || longSymbol == null) {
            return null;
        }
        
        return new String[]{shortSymbol, longSymbol};
    }
    
    /**
     * Parse and sort expiration dates from string list
     * Helper method to eliminate duplicate parsing logic
     * 
     * @param expirations List of expiration date strings
     * @return Sorted list of parsed LocalDate objects, or null if input is invalid
     */
    private static List<LocalDate> parseAndSortExpirations(List<String> expirations) {
        if (expirations == null || expirations.isEmpty()) {
            return null;
        }
        
        return expirations.stream()
            .map(exp -> {
                try {
                    return LocalDate.parse(exp);
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .sorted()
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Find the strike closest to a target price from a collection of strikes
     * Helper method to eliminate duplicate strike finding logic
     * 
     * @param strikes Collection of strike prices
     * @param targetPrice The target price to find closest to
     * @return The closest strike, or -1 if no strikes available
     */
    private static double findClosestStrike(Set<Double> strikes, double targetPrice) {
        if (strikes == null || strikes.isEmpty() || targetPrice <= 0) {
            return -1.0;
        }
        
        return strikes.stream()
            .min((a, b) -> Double.compare(
                Math.abs(a - targetPrice), 
                Math.abs(b - targetPrice)
            ))
            .orElse(-1.0);
    }
    
    /**
     * Extract all strikes from a list of contracts
     * Helper method to eliminate duplicate strike collection logic
     * 
     * @param contracts List of contract maps
     * @return Set of unique strike prices
     */
    private static Set<Double> extractStrikes(List<Map<String, Object>> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return new HashSet<>();
        }
        
        return contracts.stream()
            .filter(Objects::nonNull)
            .map(contract -> contract.get("strike"))
            .filter(Objects::nonNull)
            .filter(strike -> strike instanceof Double)
            .map(strike -> (Double) strike)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }
    
    /**
     * Find the option closest to target strike and expiration
     * Helper method that implements the "closest available" logic
     * Only considers options with expiration dates after today
     * 
     * @param options List of options to search
     * @param targetStrike Target strike price
     * @param targetExpiration Target expiration date
     * @param getStrike Function to extract strike from option
     * @param getExpiration Function to extract expiration from option
     * @return The closest option, or null if none found
     */
    private static <T> T findClosestOption(List<T> options, 
                                         double targetStrike, 
                                         LocalDate targetExpiration,
                                         java.util.function.Function<T, Double> getStrike,
                                         java.util.function.Function<T, LocalDate> getExpiration) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        return options.stream()
            .filter(option -> {
                LocalDate expiration = getExpiration.apply(option);
                return expiration.isAfter(today);
            })
            .min((a, b) -> {
                // First priority: closest expiration to target
                LocalDate expA = getExpiration.apply(a);
                LocalDate expB = getExpiration.apply(b);
                long daysDiffA = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, expA));
                long daysDiffB = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiration, expB));
                int expirationComparison = Long.compare(daysDiffA, daysDiffB);
                
                if (expirationComparison != 0) {
                    return expirationComparison;
                }
                
                // Second priority: closest strike to target (if expirations are equal)
                double strikeDiffA = Math.abs(getStrike.apply(a) - targetStrike);
                double strikeDiffB = Math.abs(getStrike.apply(b) - targetStrike);
                return Double.compare(strikeDiffA, strikeDiffB);
            })
            .orElse(null);
    }
    
    /**
     * Find the best common strike between two option chains for calendar spreads using OptionSnapshot objects
     * This ensures both legs have the same strike price, which is required for calendar spreads
     * Works with OptionSnapshot objects (used by StockFilterLambda)
     * 
     * @param shortChain Map of short leg options (expiration -> OptionSnapshot)
     * @param longChain Map of long leg options (expiration -> OptionSnapshot)  
     * @param currentPrice Current stock price for ATM calculation
     * @return The best common strike closest to current price, or -1 if no common strikes found
     */
    public static double findBestCommonStrikeForCalendarSpreadFromOptionSnapshots(
            Map<String, com.trading.common.models.OptionSnapshot> shortChain,
            Map<String, com.trading.common.models.OptionSnapshot> longChain,
            double currentPrice) {
        
        if (shortChain == null || longChain == null || shortChain.isEmpty() || longChain.isEmpty()) {
            return -1.0;
        }
        
        // Extract strikes from both chains
        Set<Double> shortStrikes = new HashSet<>();
        Set<Double> longStrikes = new HashSet<>();
        
        for (com.trading.common.models.OptionSnapshot option : shortChain.values()) {
            shortStrikes.add(option.getStrike());
        }
        
        for (com.trading.common.models.OptionSnapshot option : longChain.values()) {
            longStrikes.add(option.getStrike());
        }
        
        // Find common strikes
        Set<Double> commonStrikes = new HashSet<>(shortStrikes);
        commonStrikes.retainAll(longStrikes);
        
        if (commonStrikes.isEmpty()) {
            return -1.0; // No common strikes found
        }
        
        // Find the common strike closest to current price
        return findClosestStrike(commonStrikes, currentPrice);
    }
    
    /**
     * Find the best common strike from contract data (InitiateTradesLambda format)
     * Works with contract maps (used by InitiateTradesLambda)
     * 
     * @param shortContracts Map of short leg contracts (expiration -> List<contracts>)
     * @param longContracts Map of long leg contracts (expiration -> List<contracts>)
     * @param currentPrice Current stock price for ATM calculation
     * @return The best common strike closest to current price, or -1 if no common strikes found
     */
    public static double findBestCommonStrikeFromContractMaps(
            Map<String, List<Map<String, Object>>> shortContracts,
            Map<String, List<Map<String, Object>>> longContracts,
            double currentPrice) {
        
        if (shortContracts == null || longContracts == null || 
            shortContracts.isEmpty() || longContracts.isEmpty()) {
            return -1.0;
        }
        
        // Extract strikes from both contract chains
        Set<Double> shortStrikes = new HashSet<>();
        Set<Double> longStrikes = new HashSet<>();
        
        for (List<Map<String, Object>> contracts : shortContracts.values()) {
            shortStrikes.addAll(extractStrikes(contracts));
        }
        
        for (List<Map<String, Object>> contracts : longContracts.values()) {
            longStrikes.addAll(extractStrikes(contracts));
        }
        
        // Find common strikes
        Set<Double> commonStrikes = new HashSet<>(shortStrikes);
        commonStrikes.retainAll(longStrikes);
        
        if (commonStrikes.isEmpty()) {
            return -1.0; // No common strikes found
        }
        
        // Find the common strike closest to current price
        return findClosestStrike(commonStrikes, currentPrice);
    }
    
    /**
     * Find option for a specific strike in an option chain using OptionSnapshot objects
     * Works with OptionSnapshot objects (used by StockFilterLambda)
     * 
     * @param optionChain Map of options (expiration -> OptionSnapshot)
     * @param targetStrike The strike price to find
     * @return The option with the target strike, or null if not found
     */
    public static com.trading.common.models.OptionSnapshot findOptionForStrikeInOptionSnapshotChain(
            Map<String, com.trading.common.models.OptionSnapshot> optionChain,
            double targetStrike) {
        
        if (optionChain == null || optionChain.isEmpty()) {
            return null;
        }
        
        return optionChain.values().stream()
            .filter(option -> Math.abs(option.getStrike() - targetStrike) < 0.01)
            .findFirst()
            .orElse(null);
    }
}
