"""
Tests for the trading safety module.
Tests the simplified trading safety system using only TESTING_MODE.
"""

import pytest
from unittest.mock import Mock, patch
from trading_safety import (
    safe_trading_mode,
    require_paper_trading,
    require_live_trading,
    TradingSafetyError,
    get_trading_safety_status
)

class TestTradingSafety:
    """Test the trading safety functionality."""

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_safe_trading_mode_paper_mode(self, mock_config):
        """Test that paper trading is allowed in testing mode."""
        # Mock paper trading credentials
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': True}
            
            @safe_trading_mode
            def test_function():
                return "success"
            
            # Paper trading should be allowed in testing mode
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_safe_trading_mode_live_mode_blocked(self, mock_config):
        """Test that live trading is blocked in testing mode."""
        # Mock live trading credentials
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            @safe_trading_mode
            def test_function():
                return "success"
            
            # Live trading should be blocked in testing mode
            with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_safe_trading_mode_paper_trading_allowed_when_live_disabled(self, mock_config):
        """Test that paper trading is allowed even when live trading is disabled in testing mode."""
        # Mock paper trading credentials
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': True}
            
            @safe_trading_mode
            def test_function():
                return "success"
            
            # Paper trading should be allowed
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_safe_trading_mode_production_mode(self):
        """Test that live trading is allowed in production mode."""
        # Mock production mode (TESTING_MODE = False)
        with patch('config.TESTING_MODE', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            @safe_trading_mode
            def test_function():
                return "success"
            
            # Live trading should be allowed in production mode
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_require_paper_trading_success(self):
        """Test that require_paper_trading allows paper trading."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': True}
            
            @require_paper_trading
            def test_function():
                return "success"
            
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_require_paper_trading_failure(self):
        """Test that require_paper_trading blocks live trading."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            @require_paper_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="PAPER TRADING REQUIRED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_require_live_trading_success(self):
        """Test that require_live_trading allows live trading in production mode."""
        with patch('config.TESTING_MODE', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            @require_live_trading
            def test_function():
                return "success"
            
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_require_live_trading_blocked_by_testing_mode(self):
        """Test that require_live_trading blocks live trading in testing mode."""
        with patch('config.TESTING_MODE', True), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            @require_live_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_require_live_trading_blocked_by_paper_mode(self):
        """Test that require_live_trading blocks paper trading."""
        with patch('config.TESTING_MODE', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': True}
            
            @require_live_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="LIVE TRADING REQUIRED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_get_trading_safety_status_testing_mode(self):
        """Test get_trading_safety_status in testing mode."""
        with patch('config.TESTING_MODE', True), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': True}
            
            status = get_trading_safety_status()
            
            assert status['testing_mode'] is True
            assert status['paper_trading'] is True
            assert status['live_trading'] is False
            assert status['safety_level'] == 'HIGH'
            assert status['trading_allowed'] is True
            assert status['live_trading_allowed'] is False
            assert status['paper_trading_allowed'] is True
            assert 'Safe mode' in status['description']

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_get_trading_safety_status_production_mode(self):
        """Test get_trading_safety_status in production mode."""
        with patch('config.TESTING_MODE', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {'paper_trading': False}
            
            status = get_trading_safety_status()
            
            assert status['testing_mode'] is False
            assert status['paper_trading'] is False
            assert status['live_trading'] is True
            assert status['safety_level'] == 'NORMAL'
            assert status['trading_allowed'] is True
            assert status['live_trading_allowed'] is True
            assert status['paper_trading_allowed'] is False
            assert 'Production mode' in status['description']

    @pytest.mark.unit
    @pytest.mark.trading_safety
    def test_get_trading_safety_status_error_handling(self):
        """Test get_trading_safety_status error handling."""
        with patch('config.TESTING_MODE', True), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.side_effect = Exception("Credential error")
            
            status = get_trading_safety_status()
            
            assert 'error' in status
            assert status['testing_mode'] is True
            assert status['safety_level'] == 'UNKNOWN'
