package com.example;

import java.util.*;

/**
 * Results container for filter analysis testing
 */
public class FilterAnalysisResults {
    
    private final Map<String, FilterStats> filterStats = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    
    public void addFilterResult(String filterName, String ticker, boolean passed) {
        filterStats.computeIfAbsent(filterName, k -> new FilterStats())
                  .addResult(ticker, passed);
    }
    
    public void addFilterError(String filterName, String ticker, String error) {
        filterStats.computeIfAbsent(filterName, k -> new FilterStats())
                  .addError(ticker, error);
    }
    
    public void addError(String ticker, String error) {
        errors.put(ticker, error);
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public Set<String> getFilterNames() {
        return filterStats.keySet();
    }
    
    public FilterStats getFilterStats(String filterName) {
        return filterStats.getOrDefault(filterName, new FilterStats());
    }
    
    public boolean passedAllFilters(String ticker, List<String> filters) {
        return filters.stream().allMatch(filter -> 
            filterStats.getOrDefault(filter, new FilterStats()).passedTickers.contains(ticker));
    }
    
    public int getPassedFilterCount(String ticker, List<String> filters) {
        return (int) filters.stream().filter(filter -> 
            filterStats.getOrDefault(filter, new FilterStats()).passedTickers.contains(ticker)).count();
    }
    
    public void printErrorSummary() {
        if (errors.isEmpty()) {
            System.out.println("No errors occurred during analysis.");
        } else {
            errors.forEach((ticker, error) -> 
                System.out.println(ticker + ": " + error));
        }
    }
    
    public static class FilterStats {
        private final Set<String> passedTickers = new HashSet<>();
        private final Set<String> failedTickers = new HashSet<>();
        private final Set<String> errorTickers = new HashSet<>();
        
        public void addResult(String ticker, boolean passed) {
            if (passed) {
                passedTickers.add(ticker);
            } else {
                failedTickers.add(ticker);
            }
        }
        
        public void addError(String ticker, String error) {
            errorTickers.add(ticker);
        }
        
        public int getPassedCount() { return passedTickers.size(); }
        public int getTotalCount() { return passedTickers.size() + failedTickers.size(); }
        public double getPassRate() { 
            int total = getTotalCount();
            return total > 0 ? (double) getPassedCount() / total * 100 : 0; 
        }
        public Set<String> getPassedTickers() { return passedTickers; }
        public Set<String> getFailedTickers() { return failedTickers; }
        public Set<String> getErrorTickers() { return errorTickers; }
    }
}
