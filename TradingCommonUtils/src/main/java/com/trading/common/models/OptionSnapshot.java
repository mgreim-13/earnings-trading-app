package com.trading.common.models;

/**
 * Option snapshot data from Alpaca API
 */
public class OptionSnapshot {
    private final String symbol;
    private final double strike;
    private final String expiration;
    private final String type;
    private final double impliedVol;
    private final double delta;
    private final double theta;
    private final double gamma;
    private final double vega;
    private final double bid;
    private final double ask;
    private final long bidSize;
    private final long askSize;
    
    public OptionSnapshot(String symbol, double strike, String expiration, String type, 
                        double impliedVol, double delta, double theta, double gamma, double vega,
                        double bid, double ask, long bidSize, long askSize) {
        this.symbol = symbol;
        this.strike = strike;
        this.expiration = expiration;
        this.type = type;
        this.impliedVol = impliedVol;
        this.delta = delta;
        this.theta = theta;
        this.gamma = gamma;
        this.vega = vega;
        this.bid = bid;
        this.ask = ask;
        this.bidSize = bidSize;
        this.askSize = askSize;
    }
    
    public String getSymbol() { return symbol; }
    public double getStrike() { return strike; }
    public String getExpiration() { return expiration; }
    public String getType() { return type; }
    public double getImpliedVol() { return impliedVol; }
    public double getDelta() { return delta; }
    public double getTheta() { return theta; }
    public double getGamma() { return gamma; }
    public double getVega() { return vega; }
    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public long getBidSize() { return bidSize; }
    public long getAskSize() { return askSize; }
    public double getMidPrice() { return (bid + ask) / 2.0; }
    public double getBidAskSpread() { return ask - bid; }
    public double getBidAskSpreadPercent() { 
        double mid = getMidPrice();
        return mid > 0 ? getBidAskSpread() / mid : 0.0; 
    }
    public long getTotalSize() { return bidSize + askSize; }
}
