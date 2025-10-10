package com.trading.lambda;

import java.util.Map;

/**
 * Represents the result of a trade evaluation using the gatekeeper system
 */
public class TradeDecision {
    private final String ticker;
    private final boolean approved;
    private final String reason;
    private double positionSizePercentage;
    private final Map<String, Boolean> filterResults;
    
    public TradeDecision(String ticker, boolean approved, String reason, double positionSizePercentage, Map<String, Boolean> filterResults) {
        this.ticker = ticker;
        this.approved = approved;
        this.reason = reason;
        this.positionSizePercentage = positionSizePercentage;
        this.filterResults = filterResults;
    }
    
    public String getTicker() {
        return ticker;
    }
    
    public boolean isApproved() { 
        return approved; 
    }
    
    public String getReason() { 
        return reason; 
    }
    
    public double getPositionSizePercentage() { 
        return positionSizePercentage; 
    }
    
    public void setPositionSizePercentage(double positionSizePercentage) {
        this.positionSizePercentage = positionSizePercentage;
    }
    
    public Map<String, Boolean> getFilterResults() { 
        return filterResults; 
    }
}
