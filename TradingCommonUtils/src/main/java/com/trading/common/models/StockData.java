package com.trading.common.models;

/**
 * Data class for stock information used across multiple trading components
 * Consolidates stock data structure to eliminate duplication
 */
public class StockData {
    private final String ticker;
    private final double currentPrice;
    private final double averageVolume;
    private final double rv30;
    private final double iv30;
    private final double termStructureSlope;
    
    public StockData(String ticker, double currentPrice, double averageVolume, double rv30, double iv30, double termStructureSlope) {
        this.ticker = ticker;
        this.currentPrice = currentPrice;
        this.averageVolume = averageVolume;
        this.rv30 = rv30;
        this.iv30 = iv30;
        this.termStructureSlope = termStructureSlope;
    }
    
    public String getTicker() { 
        return ticker; 
    }
    
    public double getCurrentPrice() { 
        return currentPrice; 
    }
    
    public double getAverageVolume() { 
        return averageVolume; 
    }
    
    public double getRv30() { 
        return rv30; 
    }
    
    public double getIv30() { 
        return iv30; 
    }
    
    public double getTermStructureSlope() { 
        return termStructureSlope; 
    }
    
    @Override
    public String toString() {
        return String.format("StockData{ticker='%s', price=%.2f, avgVolume=%.0f, rv30=%.3f, iv30=%.3f, termSlope=%.3f}", 
            ticker, currentPrice, averageVolume, rv30, iv30, termStructureSlope);
    }
}
