package com.trading.common.models;

/**
 * Historical OHLCV bar data from Alpaca API
 */
public class HistoricalBar {
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final long volume;
    private final String timestamp;
    
    public HistoricalBar(double open, double high, double low, double close, long volume, String timestamp) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
    }
    
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public String getTimestamp() { return timestamp; }
}
