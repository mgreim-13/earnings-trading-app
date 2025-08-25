"""
Tests for the configuration module.
"""

import pytest
import os
from unittest.mock import Mock, patch, MagicMock

# Import config module
import config


class TestConfig:
    """Test configuration functionality."""
    
    def setup_method(self):
        """Setup method called before each test."""
        # Store original environment variables
        self.original_env = {}
        for key in ['PAPER_ALPACA_API_KEY', 'PAPER_ALPACA_SECRET_KEY', 
                   'LIVE_ALPACA_API_KEY', 'LIVE_ALPACA_SECRET_KEY',
                   'FINNHUB_API_KEY', 'TESTING_MODE']:
            if key in os.environ:
                self.original_env[key] = os.environ[key]
    
    def teardown_method(self):
        """Teardown method called after each test."""
        # Restore original environment variables
        for key, value in self.original_env.items():
            os.environ[key] = value
        # Clear any test environment variables
        for key in ['PAPER_ALPACA_API_KEY', 'PAPER_ALPACA_SECRET_KEY', 
                   'LIVE_ALPACA_API_KEY', 'LIVE_ALPACA_SECRET_KEY',
                   'FINNHUB_API_KEY', 'TESTING_MODE']:
            if key not in self.original_env:
                os.environ.pop(key, None)
    
    def test_get_current_data_url(self):
        """Test getting the data URL."""
        result = config.get_current_data_url()
        assert result == "https://data.alpaca.markets"
    
    def test_calendar_spread_rules_structure(self):
        """Test that calendar spread rules are properly defined."""
        rules = config.CALENDAR_SPREAD_RULES
        
        # Check required keys exist
        required_keys = [
            'entry_time', 'exit_time', 'entry_timing_tolerance',
            'exit_timing_tolerance', 'polling_interval', 'max_monitoring_time',
            'entry_slippage_protection', 'exit_slippage_protection',
            'market_order_monitoring_time', 'force_market_exit_deadline',
            'market_close_protection_buffer', 'exit_deadline_buffer'
        ]
        
        for key in required_keys:
            assert key in rules, f"Missing required key: {key}"
        
        # Check data types
        assert isinstance(rules['entry_time'], str)
        assert isinstance(rules['exit_time'], str)
        assert isinstance(rules['entry_timing_tolerance'], int)
        assert isinstance(rules['exit_timing_tolerance'], int)
        assert isinstance(rules['polling_interval'], int)
        assert isinstance(rules['max_monitoring_time'], int)
        assert isinstance(rules['entry_slippage_protection'], float)
        assert isinstance(rules['exit_slippage_protection'], float)
    
    def test_filter_weights_structure(self):
        """Test that filter weights are properly defined."""
        weights = config.FILTER_WEIGHTS
        
        # Check required keys exist
        required_keys = [
            'avg_volume', 'iv30_rv30', 'ts_slope_0_45', 'hist_earn_vol',
            'option_liquidity', 'iv_percentile', 'beta', 'short', 'rsi'
        ]
        
        for key in required_keys:
            assert key in weights, f"Missing required key: {key}"
        
        # Check that weights sum to approximately 1.0
        total_weight = sum(weights.values())
        assert abs(total_weight - 1.0) < 0.01, f"Weights should sum to 1.0, got {total_weight}"
        
        # Check all weights are positive
        for key, weight in weights.items():
            assert weight > 0, f"Weight for {key} should be positive, got {weight}"
    
    def test_scoring_thresholds_structure(self):
        """Test that scoring thresholds are properly defined."""
        thresholds = config.SCORING_THRESHOLDS
        
        # Check required keys exist
        required_keys = ['recommended', 'consider', 'marginal_margin', 'rsi_neutral_zone']
        
        for key in required_keys:
            assert key in thresholds, f"Missing required key: {key}"
        
        # Check logical relationships
        assert thresholds['recommended'] > thresholds['consider'], \
            "Recommended threshold should be higher than consider threshold"
        assert thresholds['marginal_margin'] > 0, "Marginal margin should be positive"
        assert thresholds['rsi_neutral_zone'] > 0, "RSI neutral zone should be positive"
    
    def test_market_cap_tiers_structure(self):
        """Test that market cap tiers are properly defined."""
        tiers = config.MARKET_CAP_TIERS
        
        # Check required tiers exist
        required_tiers = ['large', 'mid', 'small']
        
        for tier in required_tiers:
            assert tier in tiers, f"Missing required tier: {tier}"
        
        # Check each tier has required parameters
        required_params = [
            'min_avg_volume', 'min_iv_rv_ratio', 'max_ts_slope',
            'max_hist_earn_move', 'min_atm_oi', 'min_opt_volume',
            'max_opt_spread', 'min_iv_percentile', 'max_beta',
            'max_short_pct', 'rsi_lower', 'rsi_upper'
        ]
        
        for tier_name, tier_config in tiers.items():
            for param in required_params:
                assert param in tier_config, f"Missing parameter {param} in tier {tier_name}"
    
    def test_sector_adjustments_structure(self):
        """Test that sector adjustments are properly defined."""
        adjustments = config.SECTOR_ADJUSTMENTS
        
        # Check required sectors exist
        required_sectors = ['Technology', 'Healthcare', 'Financial Services', 
                          'Energy', 'Consumer Cyclical', 'Utilities', 'default']
        
        for sector in required_sectors:
            assert sector in adjustments, f"Missing required sector: {sector}"
        
        # Check that adjustments contain valid parameters
        valid_params = [
            'min_iv_rv_ratio', 'max_beta', 'rsi_upper', 'rsi_lower',
            'max_hist_earn_move'
        ]
        
        for sector_name, sector_config in adjustments.items():
            for param in sector_config:
                assert param in valid_params, f"Invalid parameter {param} in sector {sector_name}"
    
    def test_trading_configuration_constants(self):
        """Test that trading configuration constants are properly defined."""
        # Check constants exist and have valid values
        assert hasattr(config, 'MAX_RISK_PER_TRADE')
        assert hasattr(config, 'MAX_POSITION_SIZE')
        assert hasattr(config, 'MIN_POSITION_SIZE')
        
        # Check values are reasonable
        assert 0 < config.MAX_RISK_PER_TRADE <= 1.0, "MAX_RISK_PER_TRADE should be between 0 and 1"
        assert config.MAX_POSITION_SIZE > 0, "MAX_POSITION_SIZE should be positive"
        assert config.MIN_POSITION_SIZE > 0, "MIN_POSITION_SIZE should be positive"
        assert config.MAX_POSITION_SIZE >= config.MIN_POSITION_SIZE, \
            "MAX_POSITION_SIZE should be >= MIN_POSITION_SIZE"
    
    def test_server_configuration(self):
        """Test that server configuration is properly defined."""
        # Check constants exist
        assert hasattr(config, 'SERVER_HOST')
        assert hasattr(config, 'SERVER_PORT')
        assert hasattr(config, 'API_TIMEOUT')
        assert hasattr(config, 'FINNHUB_TIMEOUT')
        
        # Check values are reasonable
        assert isinstance(config.SERVER_HOST, str)
        assert isinstance(config.SERVER_PORT, int)
        assert config.SERVER_PORT > 0
        assert config.API_TIMEOUT > 0
        assert config.FINNHUB_TIMEOUT > 0
    
    def test_testing_mode_configuration(self):
        """Test that testing mode configuration is properly defined."""
        # Check constant exists
        assert hasattr(config, 'TESTING_MODE')
        
        # Check value is boolean
        assert isinstance(config.TESTING_MODE, bool)
        
        # In test environment, should be True
        assert config.TESTING_MODE is True
    
    def test_auto_selection_threshold(self):
        """Test that auto-selection threshold is properly defined."""
        # Check constant exists
        assert hasattr(config, 'AUTO_SELECT_THRESHOLD')
        
        # Check value is reasonable
        assert isinstance(config.AUTO_SELECT_THRESHOLD, float)
        assert 0 <= config.AUTO_SELECT_THRESHOLD <= 100, \
            "AUTO_SELECT_THRESHOLD should be between 0 and 100"
    
    def test_liquidity_requirements(self):
        """Test that liquidity requirements are properly defined."""
        # Check constant exists
        assert hasattr(config, 'LIQUIDITY_REQUIREMENTS')
        
        requirements = config.LIQUIDITY_REQUIREMENTS
        
        # Check required keys
        assert 'min_option_liquidity_score' in requirements
        assert 'liquidity_override_enabled' in requirements
        
        # Check values are reasonable
        assert 0 <= requirements['min_option_liquidity_score'] <= 1, \
            "min_option_liquidity_score should be between 0 and 1"
        assert isinstance(requirements['liquidity_override_enabled'], bool)
    
    def test_config_module_imports(self):
        """Test that config module can be imported and has expected attributes."""
        # Test that config module exists
        assert config is not None
        
        # Test that key constants exist
        assert hasattr(config, 'CALENDAR_SPREAD_RULES')
        assert hasattr(config, 'FILTER_WEIGHTS')
        assert hasattr(config, 'SCORING_THRESHOLDS')
        assert hasattr(config, 'MARKET_CAP_TIERS')
        assert hasattr(config, 'SECTOR_ADJUSTMENTS')
        assert hasattr(config, 'TESTING_MODE')
    
    def test_config_values_consistency(self):
        """Test that configuration values are consistent and logical."""
        # Test market cap tiers have consistent structure
        tiers = config.MARKET_CAP_TIERS
        
        for tier_name, tier_config in tiers.items():
            # Each tier should have the same parameters
            assert 'min_avg_volume' in tier_config
            assert 'min_iv_rv_ratio' in tier_config
            assert 'max_ts_slope' in tier_config
            
            # Values should be logical
            assert tier_config['min_avg_volume'] > 0
            assert tier_config['min_iv_rv_ratio'] > 0
        
        # Test that large cap has higher requirements than small cap
        assert tiers['large']['min_avg_volume'] > tiers['small']['min_avg_volume']
        assert tiers['large']['min_iv_rv_ratio'] < tiers['small']['min_iv_rv_ratio']  # More lenient for large cap
