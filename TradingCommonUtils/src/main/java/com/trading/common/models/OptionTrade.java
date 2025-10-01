package com.trading.common.models;

/**
 * Option trade data from Alpaca API
 */
public class OptionTrade {
    private final String symbol;
    private final double price;
    private final long size;
    private final long timestamp;
    
    public OptionTrade(String symbol, double price, long size, long timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.size = size;
        this.timestamp = timestamp;
    }
    
    public String getSymbol() { return symbol; }
    public double getPrice() { return price; }
    public long getSize() { return size; }
    public long getTimestamp() { return timestamp; }
}
