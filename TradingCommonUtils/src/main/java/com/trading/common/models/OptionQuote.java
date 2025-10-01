package com.trading.common.models;

/**
 * Option quote data from Alpaca API
 */
public class OptionQuote {
    private final String symbol;
    private final double bid;
    private final double ask;
    private final long bidSize;
    private final long askSize;
    private final long timestamp;
    
    public OptionQuote(String symbol, double bid, double ask, long bidSize, long askSize, long timestamp) {
        this.symbol = symbol;
        this.bid = bid;
        this.ask = ask;
        this.bidSize = bidSize;
        this.askSize = askSize;
        this.timestamp = timestamp;
    }
    
    public String getSymbol() { return symbol; }
    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public long getBidSize() { return bidSize; }
    public long getAskSize() { return askSize; }
    public long getTimestamp() { return timestamp; }
    public double getMidPrice() { 
        return com.trading.common.PriceUtils.calculateMidPrice(bid, ask);
    }
    public double getBidAskSpread() { 
        return com.trading.common.PriceUtils.calculateSpread(bid, ask);
    }
    public long getTotalSize() { return bidSize + askSize; }
}
