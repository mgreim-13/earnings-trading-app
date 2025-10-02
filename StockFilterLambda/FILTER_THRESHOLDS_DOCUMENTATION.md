# FilterThresholds.java - Complete Documentation

This document provides a detailed description of all filter thresholds used in the StockFilterLambda application for earnings calendar spread trading.

## Overview
The `FilterThresholds.java` file serves as the central configuration hub for all filter criteria. It contains thresholds for 6 core filters plus additional configuration parameters for position sizing, portfolio management, and execution logic.

---

## 🎯 CORE THRESHOLDS

### Volume Threshold
- **Constant**: `VOLUME_THRESHOLD = 1,500,000`
- **Purpose**: Minimum average daily trading volume required for adequate liquidity
- **Usage**: Applied in `StockFilterLambda.java` lines 186, 363, 573
- **Rationale**: Ensures sufficient liquidity for entering/exiting calendar spreads around earnings

### IV Ratio Threshold  
- **Constant**: `IV_RATIO_THRESHOLD = 1.20`
- **Purpose**: Minimum implied volatility ratio (short-term IV / long-term IV)
- **Usage**: Used in `IVRatioFilter.java` and `StockFilterLambda.java` line 651
- **Rationale**: Requires short-term options to have 20% higher IV than long-term options, indicating earnings-related volatility skew

### ATM Threshold
- **Constant**: `ATM_THRESHOLD = 0.02` (2%)
- **Purpose**: Maximum distance from current stock price for "at-the-money" options
- **Usage**: Applied in `StockFilterLambda.java` lines 1032, 1037, 1249, 1254
- **Rationale**: Ensures options are close enough to current price for effective calendar spreads

---

## 💧 LIQUIDITY FILTER THRESHOLDS

### Bid-Ask Spread Threshold
- **Constant**: `BID_ASK_THRESHOLD = 0.05` (5%)
- **Purpose**: Maximum acceptable bid-ask spread percentage
- **Usage**: Used in `LiquidityFilter.java` line 41
- **Rationale**: Tighter spreads ensure better execution prices for calendar spreads

### Quote Depth Threshold
- **Constant**: `QUOTE_DEPTH_THRESHOLD = 200`
- **Purpose**: Minimum combined bid+ask size for liquidity
- **Usage**: Applied in `LiquidityFilter.java` line 42
- **Rationale**: Ensures sufficient market depth for larger position sizes

### Daily Option Trades Threshold
- **Constant**: `MIN_DAILY_OPTION_TRADES = 300`
- **Purpose**: Minimum daily option trading volume
- **Usage**: Used in `LiquidityFilter.java` line 43
- **Rationale**: Higher activity indicates better liquidity and tighter spreads

### Stock Price Range
- **Constants**: 
  - `MIN_STOCK_PRICE = 30.0` ($30 minimum)
  - `MAX_STOCK_PRICE = 400.0` ($400 maximum)
- **Purpose**: Acceptable stock price range for trading
- **Usage**: Applied in `LiquidityFilter.java` lines 44-45
- **Rationale**: Avoids penny stocks and extremely expensive stocks with poor liquidity

---

## 📊 IV RATIO FILTER THRESHOLDS

### IV Ratio Requirement
- **Constant**: Uses `IV_RATIO_THRESHOLD = 1.20` from core thresholds
- **Purpose**: Ensures earnings-related volatility skew exists
- **Usage**: Applied in `IVRatioFilter.java` and main filter logic
- **Rationale**: Calendar spreads profit from volatility crush, so we need elevated short-term IV

---

## 📈 TERM STRUCTURE FILTER THRESHOLDS

### Slope Threshold
- **Constant**: `SLOPE_THRESHOLD = 0.05` (5%)
- **Purpose**: Minimum IV difference between earnings week and long-term options
- **Usage**: Used in `TermStructureFilter.java` line 28
- **Rationale**: Requires backwardation in options term structure, indicating earnings expectations

---

## ⚡ EXECUTION SPREAD FILTER THRESHOLDS

### Debit-to-Price Ratio
- **Constant**: `MAX_DEBIT_TO_PRICE_RATIO = 0.04` (4%)
- **Purpose**: Maximum acceptable debit cost relative to stock price
- **Usage**: Applied in `ExecutionSpreadFilter.java` line 29
- **Rationale**: Ensures calendar spreads are cost-effective and have positive theta

---

## 📅 EARNINGS STABILITY FILTER THRESHOLDS

### Stability Threshold
- **Constant**: `STABILITY_THRESHOLD = 0.70` (70%)
- **Purpose**: Minimum percentage of earnings that must show stability
- **Usage**: Used in `EarningsStabilityFilter.java` line 31 and `StockFilterLambda.java` line 999
- **Rationale**: Requires 70% of historical earnings to have moves < 5%

### Earnings Move Threshold
- **Constant**: `EARNINGS_STABILITY_THRESHOLD = 0.05` (5%)
- **Purpose**: Maximum earnings move to be considered "stable"
- **Usage**: Applied in `EarningsStabilityFilter.java` line 32
- **Rationale**: Defines what constitutes a stable earnings reaction

### Straddle Historical Multiplier
- **Constant**: `STRADDLE_HISTORICAL_MULTIPLIER = 1.5`
- **Purpose**: Multiplier for determining if current straddle is overpriced
- **Usage**: Used in `StockFilterLambda.java` line 828
- **Rationale**: Flags when current options are 1.5x more expensive than historical average

---

## 💥 VOLATILITY CRUSH FILTER THRESHOLDS

### Volatility Crush Threshold
- **Constant**: `VOLATILITY_CRUSH_THRESHOLD = 0.90` (90%)
- **Purpose**: Maximum post-earnings/pre-earnings volatility ratio
- **Usage**: Applied in `StockVolatilityCrushFilter.java` line 31 and `StockFilterLambda.java` line 1144
- **Rationale**: Requires post-earnings volatility to be < 90% of pre-earnings volatility

### Crush Percentage
- **Constant**: `CRUSH_PERCENTAGE = 0.50` (50%)
- **Purpose**: Minimum percentage of earnings that must show volatility crush
- **Usage**: Used in `StockVolatilityCrushFilter.java` line 32 and `StockFilterLambda.java` line 1119
- **Rationale**: Requires 50% of historical earnings to show volatility crush pattern

### Volatility Lookback Days
- **Constant**: `VOLATILITY_LOOKBACK_DAYS = 365`
- **Purpose**: Historical data lookback period for volatility analysis
- **Usage**: Applied in `StockVolatilityCrushFilter.java` line 33
- **Rationale**: Uses 1 year of historical data for statistical significance

---

## 🔧 EXECUTION & VALIDATION THRESHOLDS

### Cached Data Spread Threshold
- **Constant**: `CACHED_DATA_SPREAD_THRESHOLD = 0.05` (5%)
- **Purpose**: Spread threshold for cached data path validation
- **Usage**: Applied in `StockFilterLambda.java` line 606
- **Rationale**: Ensures cached data meets quality standards

### Execution Spread Cached Threshold
- **Constant**: `EXECUTION_SPREAD_CACHED_THRESHOLD = 0.10` (10%)
- **Purpose**: Spread threshold for ATM options in cached execution path
- **Usage**: Used in `StockFilterLambda.java` line 1268
- **Rationale**: Allows slightly wider spreads when using cached data

### Validation Spread Threshold
- **Constant**: `VALIDATION_SPREAD_THRESHOLD = 0.10` (10%)
- **Purpose**: Rejection threshold for validation logic
- **Usage**: Applied in validation logic
- **Rationale**: Rejects trades with spreads > 10%

### Minimum Option Size
- **Constant**: `MIN_OPTION_SIZE = 50`
- **Purpose**: Minimum option size for liquidity validation
- **Usage**: Used in `StockFilterLambda.java` lines 611, 1271
- **Rationale**: Ensures sufficient contract size for execution

---

## 💰 POSITION SIZING THRESHOLDS

### Base Position Size
- **Constant**: `BASE_POSITION_SIZE = 0.05` (5%)
- **Purpose**: Base position size for approved trades
- **Usage**: Applied in `StockFilterLambda.java` line 397
- **Rationale**: Conservative base allocation per trade

### Optional Filter Bonus
- **Constant**: `OPTIONAL_FILTER_BONUS = 0.01` (1%)
- **Purpose**: Additional position size bonus per optional filter passed
- **Usage**: Used in `StockFilterLambda.java` lines 404, 411
- **Rationale**: Rewards stocks that pass additional quality filters

### Maximum Position Size
- **Constant**: `MAX_POSITION_SIZE = 0.07` (7%)
- **Purpose**: Maximum position size (base + 2 optional bonuses)
- **Usage**: Calculated from base + bonuses
- **Rationale**: Caps maximum risk per trade

---

## 📊 PORTFOLIO MANAGEMENT THRESHOLDS

### Daily Portfolio Allocation
- **Constant**: `MAX_DAILY_PORTFOLIO_ALLOCATION = 0.30` (30%)
- **Purpose**: Maximum total portfolio allocation per day
- **Usage**: Applied in `StockFilterLambda.java` line 110
- **Rationale**: Prevents over-concentration in single day's earnings

---

## 🎯 FILTER CATEGORIES

### Required Filters (Must Pass)
1. **Volume Filter**: Ensures adequate liquidity
2. **IV Ratio Filter**: Requires earnings-related volatility skew
3. **Term Structure Filter**: Requires backwardation
4. **Execution Spread Filter**: Ensures cost-effectiveness

### Optional Filters (Provide Bonuses)
1. **Earnings Stability Filter**: +1% position size bonus
2. **Volatility Crush Filter**: +1% position size bonus

---

## 🔄 THRESHOLD UPDATES

The volatility crush thresholds were recently updated based on real-world testing:
- **Previous**: 70% crush percentage, 80% volatility threshold (0% pass rate)
- **Current**: 50% crush percentage, 90% volatility threshold (5.9% pass rate)

This update made the filter more realistic while maintaining its quality standards.

---

## 📝 CONVENIENCE METHODS

The class provides three summary methods for logging and debugging:
- `getCoreThresholdsSummary()`: Volume, IV ratio, ATM thresholds
- `getLiquidityThresholdsSummary()`: Spread, depth, trades, price range
- `getAllThresholdsSummary()`: All major thresholds in one string

---

## 🎯 STRATEGY ALIGNMENT

All thresholds are specifically calibrated for **earnings calendar spread trading**:
- **Entry**: 15 minutes before market close (day before earnings)
- **Exit**: 15 minutes after market open (day after earnings)
- **Structure**: Sell short-term calls, buy long-term calls
- **Profit Source**: Volatility crush and time decay

The thresholds ensure only high-quality, liquid stocks with favorable volatility patterns are selected for this specific strategy.
