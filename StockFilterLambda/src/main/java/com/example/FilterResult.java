package com.example;

/**
 * Result of a filter evaluation
 */
public class FilterResult {
    private final boolean passed;
    private final String name;
    
    public FilterResult(String name, boolean passed) {
        this.name = name;
        this.passed = passed;
    }
    
    public boolean isPassed() { return passed; }
    public String getName() { return name; }
}
