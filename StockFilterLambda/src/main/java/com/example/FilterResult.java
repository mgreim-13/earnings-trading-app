package com.example;

/**
 * Result of a filter evaluation
 */
public class FilterResult {
    private final boolean passed;
    private final int score;
    private final String name;
    
    public FilterResult(String name, boolean passed, int score) {
        this.name = name;
        this.passed = passed;
        this.score = score;
    }
    
    public boolean isPassed() { return passed; }
    public int getScore() { return score; }
    public String getName() { return name; }
}
