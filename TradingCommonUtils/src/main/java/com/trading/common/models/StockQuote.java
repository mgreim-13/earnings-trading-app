package com.trading.common.models;

/**
 * Stock quote data from Alpaca API
 */
public class StockQuote {
    private final String symbol;
    private final double askPrice;
    private final double bidPrice;
    private final long askSize;
    private final long bidSize;
    private final long timestamp;
    
    public StockQuote(String symbol, double askPrice, double bidPrice, long askSize, long bidSize, long timestamp) {
        this.symbol = symbol;
        this.askPrice = askPrice;
        this.bidPrice = bidPrice;
        this.askSize = askSize;
        this.bidSize = bidSize;
        this.timestamp = timestamp;
    }
    
    public String getSymbol() { return symbol; }
    public double getAskPrice() { return askPrice; }
    public double getBidPrice() { return bidPrice; }
    public long getAskSize() { return askSize; }
    public long getBidSize() { return bidSize; }
    public long getTimestamp() { return timestamp; }
    public double getMidPrice() { 
        return com.trading.common.PriceUtils.calculateMidPrice(bidPrice, askPrice);
    }
    
    @Override
    public String toString() {
        return String.format("StockQuote{symbol='%s', askPrice=%.2f, bidPrice=%.2f, askSize=%d, bidSize=%d, timestamp=%d}", 
                           symbol, askPrice, bidPrice, askSize, bidSize, timestamp);
    }
}
