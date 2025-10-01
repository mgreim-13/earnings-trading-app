package com.trading.common.models;

/**
 * Option bar (OHLCV) data from Alpaca API
 */
public class OptionBar {
    private final String symbol;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final long volume;
    private final String timestamp;
    
    public OptionBar(String symbol, double open, double high, double low, double close, long volume, String timestamp) {
        this.symbol = symbol;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
    }
    
    public String getSymbol() { return symbol; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public String getTimestamp() { return timestamp; }
}
