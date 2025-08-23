"""
Tests for utility modules.
"""

import pytest
import time
from unittest.mock import Mock, patch, MagicMock
import pandas as pd
import numpy as np
from datetime import datetime, timedelta

# Import the actual functions from filters.py
from utils.filters import (
    filter_dates, 
    yang_zhang, 
    build_term_structure, 
    get_current_price, 
    calculate_rsi, 
    get_dynamic_thresholds, 
    get_partial_score, 
    compute_recommendation
)
from utils.cache_service import CacheService
from utils.yfinance_cache import yf_cache, with_retry


class TestFilters:
    """Test the filters module functions."""
    
    @pytest.mark.unit
    def test_filter_dates(self):
        """Test date filtering functionality."""
        # Test with future dates (45+ days from now)
        future_date = (datetime.today() + timedelta(days=50)).strftime("%Y-%m-%d")
        dates = [future_date, "2025-12-31", "2026-01-01"]
        result = filter_dates(dates)
        assert len(result) > 0
        assert all(datetime.strptime(date, "%Y-%m-%d").date() >= (datetime.today().date() + timedelta(days=45)) for date in result)
    
    @pytest.mark.unit
    def test_yang_zhang(self):
        """Test Yang-Zhang volatility calculation."""
        # Create sample price data
        dates = pd.date_range('2024-01-01', periods=50, freq='D')
        price_data = pd.DataFrame({
            'High': np.random.uniform(100, 110, 50),
            'Open': np.random.uniform(100, 110, 50),
            'Low': np.random.uniform(90, 100, 50),
            'Close': np.random.uniform(100, 110, 50)
        }, index=dates)
        
        result = yang_zhang(price_data, window=30)
        assert isinstance(result, float)
        assert result > 0
    
    @pytest.mark.unit
    def test_build_term_structure(self):
        """Test term structure building."""
        days = [30, 60, 90]
        ivs = [0.2, 0.25, 0.3]
        
        term_spline = build_term_structure(days, ivs)
        result = term_spline(45)
        
        assert isinstance(result, float)
        assert 0.2 <= result <= 0.3
    
    @pytest.mark.unit
    @patch('utils.filters.yf_cache.get_ticker_history')
    def test_get_current_price(self, mock_cache):
        """Test current price retrieval."""
        mock_cache.return_value = pd.DataFrame({
            'Close': [100.0]
        })
        
        result = get_current_price("AAPL")
        assert result == 100.0
    
    @pytest.mark.unit
    def test_calculate_rsi(self):
        """Test RSI calculation."""
        prices = pd.Series([100, 101, 102, 101, 100, 99, 98, 97, 96, 95, 94, 93, 92, 91, 90])
        rsi = calculate_rsi(prices, period=14)
        
        assert isinstance(rsi.iloc[-1], float)
        assert 0 <= rsi.iloc[-1] <= 100
    
    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_get_dynamic_thresholds(self, mock_ticker):
        """Test dynamic threshold calculation."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap
            'sector': 'Technology'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        assert isinstance(thresholds, dict)
        assert 'min_iv_rv_ratio' in thresholds

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_technology(self, mock_ticker):
        """Test that Technology sector gets appropriate adjustments."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            'sector': 'Technology'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Technology sector adjustments should be applied and preserved
        assert thresholds['max_beta'] == 1.8  # Technology adjustment, not 1.6 (mid tier)
        assert thresholds['rsi_upper'] == 70  # Technology adjustment preserved
        assert thresholds['rsi_lower'] == 30  # Technology adjustment preserved
        
        # Other thresholds should remain from mid-tier
        assert thresholds['min_avg_volume'] == 500000  # Mid tier value
        assert thresholds['max_ts_slope'] == -0.003  # Mid tier value preserved

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_healthcare(self, mock_ticker):
        """Test that Healthcare sector gets appropriate adjustments."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 15000000000,  # 15B market cap (large tier)
            'sector': 'Healthcare'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Healthcare adjustments should override large tier thresholds
        assert thresholds['max_hist_earn_move'] == 18.0  # Healthcare adjustment, not 8.0 (large tier)
        assert thresholds['max_beta'] == 2.0  # Healthcare adjustment, not 1.3 (large tier)
        
        # Note: min_iv_rv_ratio gets overridden by historical volatility calculation
        
        # Other thresholds should remain from large tier
        assert thresholds['min_avg_volume'] == 1000000  # Large tier value
        assert thresholds['max_ts_slope'] == -0.004  # Large tier value preserved

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_financial_services(self, mock_ticker):
        """Test that Financial Services sector gets appropriate adjustments."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 3000000000,  # 3B market cap (mid tier)
            'sector': 'Financial Services'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Financial Services adjustments should override mid tier thresholds
        assert thresholds['max_beta'] == 1.6  # Financial Services adjustment, not 1.6 (mid tier) - same value
        assert thresholds['rsi_lower'] == 30  # Financial Services adjustment preserved
        assert thresholds['rsi_upper'] == 70  # Financial Services adjustment preserved
        
        # Other thresholds should remain from mid tier
        assert thresholds['min_avg_volume'] == 500000  # Mid tier value

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_energy(self, mock_ticker):
        """Test that Energy sector gets appropriate adjustments."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 800000000,  # 800M market cap (small tier)
            'sector': 'Energy'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Energy adjustments should override small tier thresholds
        assert thresholds['max_hist_earn_move'] == 20.0  # Energy adjustment, not 15.0 (small tier)
        assert thresholds['max_beta'] == 2.5  # Energy adjustment, not 2.0 (small tier)
        
        # Other thresholds should remain from small tier
        assert thresholds['min_avg_volume'] == 300000  # Small tier value

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_unknown_sector(self, mock_ticker):
        """Test that unknown sectors fall back to default (no adjustments)."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            'sector': 'Unknown Sector'  # Not in SECTOR_ADJUSTMENTS
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Should use mid tier thresholds with no sector adjustments
        assert thresholds['max_beta'] == 1.6  # Mid tier value
        assert thresholds['min_avg_volume'] == 500000  # Mid tier value

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_override_logic(self, mock_ticker):
        """Test that sector adjustments properly override base thresholds."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            'sector': 'Technology'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Verify that Technology sector adjustments override mid tier values
        # Mid tier: max_beta = 1.6, Technology: max_beta = 1.8
        assert thresholds['max_beta'] == 1.8  # Technology override
        assert thresholds['max_beta'] != 1.6  # Not mid tier value

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_consumer_cyclical(self, mock_ticker):
        """Test that Consumer Cyclical sector gets appropriate adjustments."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 12000000000,  # 12B market cap (large tier)
            'sector': 'Consumer Cyclical'
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Consumer Cyclical adjustments should override large tier thresholds
        assert thresholds['max_beta'] == 1.7  # Consumer Cyclical adjustment, not 1.3 (large tier)
        
        # Other thresholds should remain from large tier
        assert thresholds['min_avg_volume'] == 1000000  # Large tier value
        assert thresholds['max_ts_slope'] == -0.004  # Large tier value preserved

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_missing_sector_info(self, mock_ticker):
        """Test handling when sector information is missing."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            # 'sector' key is missing
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        # Should raise ValueError due to missing sector
        with pytest.raises(ValueError, match="Missing sector data for stock"):
            get_dynamic_thresholds(mock_stock)

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_none_sector(self, mock_ticker):
        """Test handling when sector is None."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            'sector': None
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        # Should raise ValueError due to None sector
        with pytest.raises(ValueError, match="Missing sector data for stock"):
            get_dynamic_thresholds(mock_stock)

    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_sector_adjustments_defaults_only_when_missing(self, mock_ticker):
        """Test that defaults are only applied when sector adjustments don't specify values."""
        mock_stock = Mock()
        mock_stock.info = {
            'marketCap': 5000000000,  # 5B market cap (mid tier)
            'sector': 'Technology'  # Technology specifies rsi_upper = 70, rsi_lower = 30
        }
        mock_stock.history.return_value = pd.DataFrame({
            'High': np.random.uniform(100, 110, 252),
            'Open': np.random.uniform(100, 110, 252),
            'Low': np.random.uniform(90, 100, 252),
            'Close': np.random.uniform(100, 110, 252)
        })
        
        thresholds = get_dynamic_thresholds(mock_stock)
        
        # Sector adjustments should be preserved when specified
        assert thresholds['rsi_upper'] == 70  # Technology adjustment preserved
        assert thresholds['rsi_lower'] == 30  # Technology adjustment preserved
        assert thresholds['max_beta'] == 1.8  # Technology adjustment preserved
        
        # Values not specified by sector should come from market cap tier
        assert thresholds['max_opt_spread'] == 0.12  # Mid tier value (not in Technology config)
        assert thresholds['max_short_pct'] == 7.0  # Mid tier value (not in Technology config)
    
    @pytest.mark.unit
    def test_get_partial_score(self):
        """Test partial scoring functionality."""
        # Test minimum threshold
        score = get_partial_score(100, 90, is_min=True)
        assert score == 1.0
        
        # Test maximum threshold
        score = get_partial_score(80, 90, is_min=False)
        assert score == 1.0
        
        # Test marginal case
        score = get_partial_score(85, 90, is_min=True, margin=0.1)
        assert score == 0.5
    
    @pytest.mark.unit
    @patch('utils.filters.yf.Ticker')
    def test_compute_recommendation(self, mock_ticker):
        """Test recommendation computation."""
        mock_stock = Mock()
        mock_stock.options = ["2025-12-31", "2026-01-31"]  # Future dates
        mock_stock.option_chain.return_value = Mock(
            calls=pd.DataFrame({
                'strike': [100, 101, 102],
                'impliedVolatility': [0.2, 0.21, 0.22],
                'bid': [5, 4, 3],
                'ask': [5.5, 4.5, 3.5],
                'openInterest': [100, 200, 300],
                'volume': [50, 100, 150]
            }),
            puts=pd.DataFrame({
                'strike': [100, 101, 102],
                'impliedVolatility': [0.2, 0.21, 0.22],
                'bid': [5, 4, 3],
                'ask': [5.5, 4.5, 3.5],
                'openInterest': [100, 200, 300],
                'volume': [50, 100, 150]
            })
        )
        mock_stock.info = {
            'marketCap': 5000000000,
            'sector': 'Technology',
            'beta': 1.2,
            'shortPercentOfFloat': 0.05,

        }
        mock_stock.earnings_dates = pd.DataFrame({
            'Earnings Date': pd.date_range('2023-01-01', periods=4, freq='QE')
        })
        
        # Set the mock_ticker to return our mock_stock
        mock_ticker.return_value = mock_stock
        
        with patch('utils.filters.get_current_price', return_value=100.0), \
             patch('utils.filters.yang_zhang', return_value=0.25), \
             patch('utils.filters.yf_cache.get_ticker_history') as mock_cache, \
             patch('utils.filters.get_dynamic_thresholds') as mock_thresholds:
            
            # Mock the dynamic thresholds to return a valid dict
            mock_thresholds.return_value = {
                'min_iv_rv_ratio': 1.1,
                'max_ts_slope': -0.00406,
                'max_opt_spread': 0.1,
                'max_short_pct': 10.0,
                'rsi_lower': 40,
                'rsi_upper': 60,

                'min_avg_volume': 1000000,
                'min_atm_oi': 100,
                'min_opt_volume': 50,
                'max_hist_earn_move': 20.0,
                'min_iv_percentile': 50,
                'max_beta': 2.0  # Added missing key
            }
            
            mock_cache.return_value = pd.DataFrame({
                'Close': np.random.uniform(100, 110, 90),
                'Volume': np.random.uniform(1000000, 5000000, 90)
            })
            
            result = compute_recommendation("AAPL")
            assert isinstance(result, dict)
            assert 'total_score' in result
            assert 'recommendation' in result

class TestCacheService:
    """Test the cache service."""
    
    @pytest.mark.unit
    def test_cache_service_initialization(self):
        """Test cache service initialization."""
        cache = CacheService()
        assert cache is not None
    
    @pytest.mark.unit
    def test_cache_get_cached_scan_result(self):
        """Test getting cached scan results."""
        cache = CacheService()
        # This will test the actual method that exists
        result = cache.get_cached_scan_result("AAPL")
        # Should return None if no cache exists, but not crash
        assert result is None or isinstance(result, dict)
    
    @pytest.mark.unit
    def test_cache_get_or_compute_scan_result(self):
        """Test get or compute scan result."""
        cache = CacheService()
        # This will test the actual method that exists
        result = cache.get_or_compute_scan_result("AAPL")
        # Should return a result dict
        assert isinstance(result, dict)
        assert 'recommendation' in result
    
    @pytest.mark.unit
    def test_cache_clear_cache(self):
        """Test clearing cache."""
        cache = CacheService()
        # This should not crash
        cache.clear_cache()

class TestYFinanceCache:
    """Test the yfinance cache functionality."""
    
    @pytest.mark.unit
    def test_yf_cache_initialization(self):
        """Test yfinance cache initialization."""
        assert yf_cache is not None
    
    @pytest.mark.unit
    @patch('utils.yfinance_cache.yf.Ticker')
    def test_with_retry_decorator(self, mock_ticker):
        """Test the retry decorator."""
        mock_stock = Mock()
        mock_stock.history.return_value = pd.DataFrame({
            'Close': [100.0]
        })
        mock_ticker.return_value = mock_stock
        
        @with_retry(max_retries=2, delay=0.1)
        def test_function():
            return "success"
        
        result = test_function()
        assert result == "success"
