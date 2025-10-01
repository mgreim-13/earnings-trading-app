package com.example;

import java.util.Map;

/**
 * Represents the result of a trade evaluation using the gatekeeper system
 */
public class TradeDecision {
    private final boolean approved;
    private final String reason;
    private final double positionSizePercentage;
    private final Map<String, Boolean> filterResults;
    
    public TradeDecision(boolean approved, String reason, double positionSizePercentage, Map<String, Boolean> filterResults) {
        this.approved = approved;
        this.reason = reason;
        this.positionSizePercentage = positionSizePercentage;
        this.filterResults = filterResults;
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
    
    public Map<String, Boolean> getFilterResults() { 
        return filterResults; 
    }
}
