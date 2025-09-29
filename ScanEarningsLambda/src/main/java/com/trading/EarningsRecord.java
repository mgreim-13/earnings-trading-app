package com.trading;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents an earnings record with ticker, date, and time information.
 */
public class EarningsRecord {
    @JsonProperty("symbol")
    private String ticker;
    
    @JsonProperty("date")
    private String earningsDate;
    
    @JsonProperty("hour")
    private String time;

    // Default constructor for Jackson
    public EarningsRecord() {}

    public EarningsRecord(String ticker, String earningsDate, String time) {
        this.ticker = ticker;
        this.earningsDate = earningsDate;
        this.time = time;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getEarningsDate() {
        return earningsDate;
    }

    public void setEarningsDate(String earningsDate) {
        this.earningsDate = earningsDate;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EarningsRecord that = (EarningsRecord) o;
        return Objects.equals(ticker, that.ticker) &&
               Objects.equals(earningsDate, that.earningsDate) &&
               Objects.equals(time, that.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker, earningsDate, time);
    }

    @Override
    public String toString() {
        return "EarningsRecord{" +
               "ticker='" + ticker + '\'' +
               ", earningsDate='" + earningsDate + '\'' +
               ", time='" + time + '\'' +
               '}';
    }
}
