"""
Earnings Calendar Spread Trading Filters
This module implements the exact filter logic from the provided code for analyzing stocks
and computing trading recommendations based on options data, volatility, and technical indicators.

DISCLAIMER: This software is for educational and informational purposes only. 
Trading options involves substantial risk and is not suitable for all investors. 
Past performance does not guarantee future results. Always consult with a qualified 
financial advisor before making investment decisions.
"""

import yfinance as yf
from datetime import datetime, timedelta
from scipy.interpolate import interp1d
import numpy as np
import pandas as pd
import config
from .yfinance_cache import yf_cache, with_retry
import logging

logger = logging.getLogger(__name__)

def filter_dates(dates):
    """Filter dates to include options suitable for earnings calendar spread trading."""
    today = datetime.today().date()
    cutoff_date = today + timedelta(days=45)
    
    try:
        sorted_dates = sorted([datetime.strptime(date, "%Y-%m-%d").date() for date in dates])
    except ValueError as e:
        raise ValueError(f"Invalid date format in dates: {e}")
    
    # Filter to only include dates that are 45+ days in the future
    filtered_dates = [date for date in sorted_dates if date >= cutoff_date]
    
    if len(filtered_dates) > 0:
        return [date.strftime("%Y-%m-%d") for date in filtered_dates]
    
    raise ValueError("No date 45 days or more in the future found.")

def yang_zhang(price_data, window=30, trading_periods=252, return_last_only=True):
    """Calculate Yang-Zhang volatility estimator."""
    # Input validation
    if price_data is None or price_data.empty:
        raise ValueError("Price data is empty or None")
    
    required_columns = ['High', 'Open', 'Low', 'Close']
    missing_columns = [col for col in required_columns if col not in price_data.columns]
    if missing_columns:
        raise KeyError(f"Missing required columns: {missing_columns}")
    
    if len(price_data) < window:
        raise ValueError(f"Insufficient data points. Need at least {window}, got {len(price_data)}")
    
    log_ho = (price_data['High'] / price_data['Open']).apply(np.log)
    log_lo = (price_data['Low'] / price_data['Open']).apply(np.log)
    log_co = (price_data['Close'] / price_data['Open']).apply(np.log)
    log_oc = (price_data['Open'] / price_data['Close'].shift(1)).apply(np.log)
    log_oc_sq = log_oc**2
    log_cc = (price_data['Close'] / price_data['Close'].shift(1)).apply(np.log)
    log_cc_sq = log_cc**2
    
    rs = log_ho * (log_ho - log_co) + log_lo * (log_lo - log_co)
    
    close_vol = log_cc_sq.rolling(
        window=window,
        center=False
    ).sum() * (1.0 / (window - 1.0))
    
    open_vol = log_oc_sq.rolling(
        window=window,
        center=False
    ).sum() * (1.0 / (window - 1.0))
    
    window_rs = rs.rolling(
        window=window,
        center=False
    ).sum() * (1.0 / (window - 1.0))
    
    k = 0.34 / (1.34 + ((window + 1) / (window - 1)))
    
    result = (open_vol + k * close_vol + (1 - k) * window_rs).apply(np.sqrt) * np.sqrt(trading_periods)
    
    if return_last_only:
        return result.iloc[-1]
    else:
        return result.dropna()

def build_term_structure(days, ivs):
    """Build term structure spline for IV interpolation."""
    days = np.array(days)
    ivs = np.array(ivs)
    sort_idx = days.argsort()
    days = days[sort_idx]
    ivs = ivs[sort_idx]
    
    spline = interp1d(days, ivs, kind='linear', fill_value="extrapolate")
    
    def term_spline(dte):
        if dte < days[0]:
            return ivs[0]
        elif dte > days[-1]:
            return ivs[-1]
        else:
            return float(spline(dte))
    
    return term_spline

@with_retry(max_retries=3, delay=0.5)
def get_current_price(ticker):
    """Get current price with caching and retry logic."""
    if isinstance(ticker, str):
        # If ticker is a symbol string, use cached method
        hist = yf_cache.get_ticker_history(ticker, period='1d', ttl=60)
        if hist is not None and not hist.empty:
            return hist['Close'].iloc[0]
        # Fallback to direct yfinance if cache fails
        stock = yf.Ticker(ticker)
        todays_data = stock.history(period='1d')
        return todays_data['Close'].iloc[0]
    else:
        # If ticker is a yfinance Ticker object, use it directly
        todays_data = ticker.history(period='1d')
        return todays_data['Close'].iloc[0]

def calculate_rsi(prices, period=14):
    """Calculate RSI (Relative Strength Index)."""
    delta = prices.diff(1)
    gain = delta.where(delta > 0, 0)
    loss = -delta.where(delta < 0, 0)
    avg_gain = gain.rolling(window=period, min_periods=1).mean()
    avg_loss = loss.rolling(window=period, min_periods=1).mean()
    rs = avg_gain / avg_loss
    rsi = 100 - (100 / (1 + rs))
    
    # Handle division by zero and NaN values
    rsi = rsi.fillna(50)  # Fill NaN with neutral RSI value
    rsi = rsi.replace([np.inf, -np.inf], 50)  # Replace infinite values
    
    return rsi

def get_dynamic_thresholds(stock):
    """
    Compute dynamic thresholds based on stock characteristics (market cap, sector, historical data).
    Returns a dictionary of thresholds adjusted for the specific stock.
    
    Raises:
        ValueError: If unable to compute dynamic thresholds (missing critical data)
    """
    try:
        info = stock.info
        market_cap = info.get('marketCap', None)
        sector = info.get('sector', None)
        
        # Require essential data - skip if missing
        if market_cap is None:
            raise ValueError(f"Missing market cap data for stock")
        if sector is None:
            raise ValueError(f"Missing sector data for stock")
        
        # Market cap tier
        if market_cap > 10e9:
            cap_tier = 'large'
        elif market_cap > 2e9:
            cap_tier = 'mid'
        else:
            cap_tier = 'small'
        
        # Base thresholds from tiers
        thresholds = config.MARKET_CAP_TIERS.get(cap_tier, config.MARKET_CAP_TIERS['mid']).copy()
        
        # Sector adjustments
        sector_adj = config.SECTOR_ADJUSTMENTS.get(sector, config.SECTOR_ADJUSTMENTS['default'])
        thresholds.update(sector_adj)
        
        # Historical percentile-based (for IV/RV and IV percentile)
        try:
            full_history = stock.history(period='1y')
            if full_history.empty:
                raise ValueError(f"Insufficient historical data (need at least 1 year)")
            
            rv_series = yang_zhang(full_history, window=30, return_last_only=False).dropna()
            if rv_series.empty:
                raise ValueError(f"Unable to compute realized volatility from historical data")
            
            # Dynamic IV/RV: 75th percentile of historical RV as proxy base
            hist_rv_75 = np.percentile(rv_series, 75)
            thresholds['min_iv_rv_ratio'] = max(thresholds['min_iv_rv_ratio'], hist_rv_75 * 1.1)  # Slight buffer
            
            # Enhance IV percentile threshold to be stock-specific (e.g., aim for >60th if volatile)
            thresholds['min_iv_percentile'] = 60 if np.mean(rv_series) > 0.3 else 50  # Higher for high-vol stocks
            
        except Exception as e:
            raise ValueError(f"Failed to compute historical volatility thresholds: {str(e)}")
        
        # Other defaults (can expand for slope normalization, etc.)
        thresholds['max_ts_slope'] = -0.00406  # Could normalize: e.g., -0.0002 * front_iv (add if fetching front_iv)
        thresholds['max_opt_spread'] = 0.1
        thresholds['max_short_pct'] = 10.0
        thresholds['rsi_lower'] = 40
        thresholds['rsi_upper'] = 60

        
        return thresholds
        
    except Exception as e:
        # Don't fall back to static thresholds - if we can't compute dynamic ones, skip the stock
        raise ValueError(f"Dynamic threshold computation failed: {str(e)}")

def get_partial_score(value, threshold, is_min=True, margin=None):
    """
    Compute a partial score for a filter based on how close the value is to the threshold.
    Returns 1.0 for full pass, 0.5 for marginal (within margin% of threshold), 0.0 for fail.
    
    Args:
        value: The actual value to evaluate
        threshold: The threshold to compare against
        is_min: True if threshold is a minimum (value should be >= threshold), False if maximum
        margin: Percentage margin for partial credit (default 10%)
    """
    if margin is None:
        margin = config.SCORING_THRESHOLDS['marginal_margin']
    
    if value is None:
        return 0.0
    
    if is_min:
        if value >= threshold:
            return 1.0
        elif value >= threshold * (1 - margin):
            return 0.5
        else:
            return 0.0
    else:  # is_max
        if value <= threshold:
            return 1.0
        elif value <= threshold * (1 + margin):
            return 0.5
        else:
            return 0.0

def compute_recommendation(ticker):
    """
    Main function to compute trading recommendation for a stock.
    Returns a dictionary with scores and recommendation data.
    
    This function integrates with the existing project structure and returns
    the same format expected by cache_service, scheduler, and other components.
    """
    try:
        # Handle None and empty ticker values
        if ticker is None:
            return "No stock symbol provided."
        
        ticker = ticker.strip().upper()
        if not ticker:
            return "No stock symbol provided."
        
        try:
            stock = yf.Ticker(ticker)
            if len(stock.options) == 0:
                return f"SKIP: No options found for stock symbol '{ticker}' - insufficient liquidity for analysis"
        except Exception as e:
            return f"SKIP: Unable to retrieve stock data for '{ticker}' - {str(e)}"
        
        exp_dates = list(stock.options)
        try:
            exp_dates = filter_dates(exp_dates)
        except:
            return "SKIP: Not enough option data - need options expiring 45+ days in future"
        
        options_chains = {}
        for exp_date in exp_dates:
            options_chains[exp_date] = stock.option_chain(exp_date)
        
        try:
            underlying_price = get_current_price(stock)
            if underlying_price is None:
                raise ValueError("No market price found.")
        except Exception:
            return "SKIP: Unable to retrieve underlying stock price - cannot compute analysis"
        
        atm_iv = {}
        straddle = None
        atm_call_bid = atm_call_ask = atm_put_bid = atm_put_ask = None
        atm_call_oi = atm_put_oi = atm_call_volume = atm_put_volume = None
        
        i = 0
        for exp_date, chain in options_chains.items():
            calls = chain.calls
            puts = chain.puts
            
            if calls.empty or puts.empty:
                continue
            
            call_diffs = (calls['strike'] - underlying_price).abs()
            call_idx = call_diffs.idxmin()
            call_iv = calls.loc[call_idx, 'impliedVolatility']
            
            put_diffs = (puts['strike'] - underlying_price).abs()
            put_idx = put_diffs.idxmin()
            put_iv = puts.loc[put_idx, 'impliedVolatility']
            
            atm_iv_value = (call_iv + put_iv) / 2.0
            atm_iv[exp_date] = atm_iv_value
            
            if i == 0:  # Near-term expiration for liquidity and straddle
                atm_call_bid = calls.loc[call_idx, 'bid']
                atm_call_ask = calls.loc[call_idx, 'ask']
                atm_put_bid = puts.loc[put_idx, 'bid']
                atm_put_ask = puts.loc[put_idx, 'ask']
                atm_call_oi = calls.loc[call_idx, 'openInterest']
                atm_put_oi = puts.loc[put_idx, 'openInterest']
                atm_call_volume = calls.loc[call_idx, 'volume']
                atm_put_volume = puts.loc[put_idx, 'volume']
            
            if atm_call_bid is not None and atm_call_ask is not None:
                call_mid = (atm_call_bid + atm_call_ask) / 2.0
            else:
                call_mid = None
            
            if atm_put_bid is not None and atm_put_ask is not None:
                put_mid = (atm_put_bid + atm_put_ask) / 2.0
            else:
                put_mid = None
            
            if call_mid is not None and put_mid is not None:
                straddle = (call_mid + put_mid)
            
            i += 1
        
        if not atm_iv:
            return "SKIP: Could not determine ATM IV for any expiration dates - insufficient options data"
        
        today = datetime.today().date()
        dtes = []
        ivs = []
        for exp_date, iv in atm_iv.items():
            exp_date_obj = datetime.strptime(exp_date, "%Y-%m-%d").date()
            days_to_expiry = (exp_date_obj - today).days
            dtes.append(days_to_expiry)
            ivs.append(iv)
        
        term_spline = build_term_structure(dtes, ivs)
        ts_slope_0_45 = (term_spline(45) - term_spline(dtes[0])) / (45 - dtes[0])
        
        # Use cached price history with fallback
        price_history = yf_cache.get_ticker_history(ticker, period='3mo', ttl=1800)
        if price_history is None or price_history.empty:
            # Fallback to direct API call
            price_history = stock.history(period='3mo')
        
        iv30_rv30 = term_spline(30) / yang_zhang(price_history)
        avg_volume = price_history['Volume'].rolling(30).mean().dropna().iloc[-1]
        expected_move = str(round(straddle / underlying_price * 100,2)) + "%" if straddle else None
        
        # Additional filters
        # Historical Earnings Volatility
        hist_earn_vol = None
        try:
            earnings_dates = stock.earnings_dates
            if earnings_dates is not None and not earnings_dates.empty:
                # Note: This is used for relative time calculations, timezone not critical
                now = datetime.now()
                past_earnings = earnings_dates[earnings_dates['Earnings Date'] < now].tail(4)
                moves = []
                for dt in past_earnings.index:
                    date = dt.date()
                    # Use cached history for earnings volatility calculation
                    start_date = (date - timedelta(days=1)).strftime("%Y-%m-%d")
                    end_date = (date + timedelta(days=1)).strftime("%Y-%m-%d")
                    hist = yf_cache.get_ticker_history(ticker, start=start_date, end=end_date, ttl=1800)
                    if hist is None or hist.empty:
                        hist = stock.history(start=start_date, end=end_date)
                    if len(hist) == 2:
                        close_before = hist['Close'].iloc[0]
                        close_after = hist['Close'].iloc[1]
                        move = abs((close_after - close_before) / close_before) * 100
                        moves.append(move)
                if moves:
                    hist_earn_vol = np.mean(moves)
        except:
            pass
        
        # Option Liquidity Metrics
        avg_oi = (atm_call_oi + atm_put_oi) / 2 if pd.notna(atm_call_oi) and pd.notna(atm_put_oi) else 0
        avg_opt_vol = (atm_call_volume + atm_put_volume) / 2 if pd.notna(atm_call_volume) and pd.notna(atm_put_volume) else 0
        call_spread = ((atm_call_ask - atm_call_bid) / ((atm_call_ask + atm_call_bid) / 2)) if atm_call_bid and atm_call_ask and atm_call_bid > 0 else None
        put_spread = ((atm_put_ask - atm_put_bid) / ((atm_put_ask + atm_put_bid) / 2)) if atm_put_bid and atm_put_ask and atm_put_bid > 0 else None
        avg_spread = np.mean([s for s in [call_spread, put_spread] if s is not None]) if any(s is not None for s in [call_spread, put_spread]) else None
        
        # IV Rank/Percentile (approximation using IV30 vs historical RV distribution)
        iv_percentile = None
        try:
            # Use cached full history with fallback
            full_history = yf_cache.get_ticker_history(ticker, period='1y', ttl=3600)
            if full_history is None or full_history.empty:
                full_history = stock.history(period='1y')
            if not full_history.empty:
                rv_series = yang_zhang(full_history, window=30, return_last_only=False)
                if not rv_series.empty:
                    iv30 = term_spline(30)
                    iv_percentile = (rv_series < iv30).sum() / len(rv_series) * 100
        except:
            pass
        
        # Stock Fundamentals/Technicals
        beta = stock.info.get('beta', None)
        short_pct = stock.info.get('shortPercentOfFloat', None) * 100 if stock.info.get('shortPercentOfFloat') is not None else None

        rsi = calculate_rsi(price_history['Close']).iloc[-1]
        
        # Sentiment and External Factors (short interest for risk)
        # Note: More advanced sentiment (news) could be added with NLP libraries if available
        
        # Get dynamic thresholds based on stock characteristics
        try:
            thresholds = get_dynamic_thresholds(stock)
        except ValueError as e:
            return f"SKIP: {str(e)} - Insufficient data for dynamic analysis"
        
        # Compute weighted scores instead of booleans
        scores = {}
        scores['avg_volume'] = get_partial_score(avg_volume, thresholds['min_avg_volume'])
        scores['iv30_rv30'] = get_partial_score(iv30_rv30, thresholds['min_iv_rv_ratio'])
        scores['ts_slope_0_45'] = get_partial_score(ts_slope_0_45, thresholds['max_ts_slope'], is_min=False)
        scores['hist_earn_vol'] = get_partial_score(hist_earn_vol, thresholds['max_hist_earn_move'], is_min=False) if hist_earn_vol is not None else 0.0
        
        # Option liquidity: average sub-scores
        oi_score = get_partial_score(avg_oi, thresholds['min_atm_oi'])
        spread_score = get_partial_score(avg_spread, thresholds['max_opt_spread'], is_min=False) if avg_spread is not None else 0.0
        opt_vol_score = get_partial_score(avg_opt_vol, thresholds['min_opt_volume'])
        scores['option_liquidity'] = np.mean([oi_score, spread_score, opt_vol_score])
        
        scores['iv_percentile'] = get_partial_score(iv_percentile, thresholds['min_iv_percentile']) if iv_percentile is not None else 0.0
        scores['beta'] = get_partial_score(beta, thresholds['max_beta'], is_min=False) if beta is not None else 0.0
        scores['short'] = get_partial_score(short_pct, thresholds['max_short_pct'], is_min=False) if short_pct is not None else 0.0
        scores['rsi'] = 1.0 if thresholds['rsi_lower'] <= rsi <= thresholds['rsi_upper'] else (0.5 if abs(rsi - 50) < config.SCORING_THRESHOLDS['rsi_neutral_zone'] else 0.0)  # Marginal for near-neutral

        
        # Calculate total weighted score
        total_score = sum(scores[filter_name] * config.FILTER_WEIGHTS[filter_name] for filter_name in scores) * 100  # As percentage
        
        # Return the same format expected by the existing project
        return {
            'scores': scores,
            'total_score': total_score,
            'expected_move': expected_move,
            'thresholds': thresholds,  # Include thresholds for debugging/transparency
            'underlying_price': underlying_price,
            'recommendation': 'recommended' if total_score >= config.SCORING_THRESHOLDS['recommended'] else ('consider' if total_score >= config.SCORING_THRESHOLDS['consider'] else 'avoid'),
            'skip_reason': None
        }
        
    except Exception as e:
        raise Exception(f'Error occurred processing {ticker}: {str(e)}')