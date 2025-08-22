"""
Tests for the trading safety module.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from trading_safety import (
    prevent_live_trading_in_tests,
    require_paper_trading,
    require_live_trading,
    TradingSafetyError
)


class TestTradingSafety:
    """Test trading safety decorators and functionality."""

    @pytest.mark.unit
    @pytest.mark.safety
    def test_prevent_live_trading_in_tests_paper_mode(self, mock_config):
        """Test that paper trading is allowed during tests."""
        with patch('config.get_current_alpaca_credentials') as mock_creds, \
             patch('config.LIVE_TRADING_ALLOWED', True):
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @prevent_live_trading_in_tests
            def test_function():
                return "success"
            
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.safety
    def test_prevent_live_trading_in_tests_live_mode_blocked(self, mock_config):
        """Test that live trading is blocked during tests."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': False,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @prevent_live_trading_in_tests
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.safety
    def test_prevent_live_trading_in_tests_paper_trading_allowed_when_live_disabled(self, mock_config):
        """Test that paper trading is allowed even when live trading is disabled."""
        with patch('config.LIVE_TRADING_ALLOWED', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @prevent_live_trading_in_tests
            def test_function():
                return "success"
            
            # Paper trading should be allowed even when live trading is disabled
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.safety
    def test_require_paper_trading_success(self, mock_config):
        """Test that paper trading requirement passes in paper mode."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @require_paper_trading
            def test_function():
                return "success"
            
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.safety
    def test_require_paper_trading_failure(self, mock_config):
        """Test that paper trading requirement fails in live mode."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': False,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @require_paper_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="PAPER TRADING REQUIRED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.safety
    def test_require_live_trading_success(self, mock_config):
        """Test that live trading requirement passes in live mode."""
        with patch('config.LIVE_TRADING_ALLOWED', True), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': False,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @require_live_trading
            def test_function():
                return "success"
            
            result = test_function()
            assert result == "success"

    @pytest.mark.unit
    @pytest.mark.safety
    def test_require_live_trading_failure_paper_mode(self, mock_config):
        """Test that live trading requirement fails in paper mode."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @require_live_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="LIVE TRADING REQUIRED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.safety
    def test_require_live_trading_failure_disabled(self, mock_config):
        """Test that live trading requirement fails when disabled."""
        with patch('config.LIVE_TRADING_ALLOWED', False), \
             patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': False,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @require_live_trading
            def test_function():
                return "success"
            
            with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
                test_function()

    @pytest.mark.unit
    @pytest.mark.safety
    def test_decorator_preserves_function_metadata(self, mock_config):
        """Test that decorators preserve function metadata."""
        with patch('config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @prevent_live_trading_in_tests
            def test_function():
                """Test function docstring."""
                return "success"
            
            assert test_function.__name__ == "test_function"
            assert test_function.__doc__ == "Test function docstring."

    @pytest.mark.unit
    @pytest.mark.safety
    def test_decorator_with_arguments(self, mock_config):
        """Test that decorators work with functions that have arguments."""
        with patch('config.get_current_alpaca_credentials') as mock_creds, \
             patch('config.LIVE_TRADING_ALLOWED', True):
            mock_creds.return_value = {
                'paper_trading': True,
                'api_key': 'test_key',
                'secret_key': 'test_secret'
            }
            
            @prevent_live_trading_in_tests
            def test_function(arg1, arg2, kwarg1=None):
                return f"{arg1}_{arg2}_{kwarg1}"
            
            result = test_function("a", "b", kwarg1="c")
            assert result == "a_b_c"

    @pytest.mark.unit
    @pytest.mark.safety
    def test_trading_safety_error_inheritance(self):
        """Test that TradingSafetyError inherits from Exception."""
        error = TradingSafetyError("Test error")
        assert isinstance(error, Exception)
        assert str(error) == "Test error"
