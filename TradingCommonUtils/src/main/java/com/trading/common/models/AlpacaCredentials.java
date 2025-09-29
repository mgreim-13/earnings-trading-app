package com.trading.common.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model class for Alpaca API credentials
 */
public class AlpacaCredentials {
    @JsonProperty("keyId")
    private String keyId;
    
    @JsonProperty("secretKey")
    private String secretKey;
    
    @JsonProperty("baseUrl")
    private String baseUrl;
    
    @JsonProperty("apiKey")
    private String apiKey;
    
    public AlpacaCredentials() {}
    
    public AlpacaCredentials(String keyId, String secretKey) {
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.baseUrl = "https://paper-api.alpaca.markets/v2"; // Default to paper trading
    }
    
    public AlpacaCredentials(String keyId, String secretKey, String baseUrl) {
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
    }
    
    public String getKeyId() {
        return keyId;
    }
    
    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }
    
    public String getSecretKey() {
        return secretKey;
    }
    
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    // For backward compatibility with existing code
    public String getApiKeyId() {
        return keyId != null ? keyId : apiKey;
    }
}
