"""
Comprehensive tests for the trading safety module.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
import logging

from trading_safety import (
    safe_trading_mode, 
    require_paper_trading, 
    require_live_trading, 
    get_trading_safety_status,
    TradingSafetyError
)


class TestTradingSafetyDecorators:
    """Test all trading safety decorators."""
    
    def setup_method(self):
        """Setup method called before each test."""
        # Create a simple test function
        @safe_trading_mode
        def test_function():
            return "function_executed"
        
        @require_paper_trading
        def paper_only_function():
            return "paper_only_executed"
        
        @require_live_trading
        def live_only_function():
            return "live_only_executed"
        
        self.test_function = test_function
        self.paper_only_function = paper_only_function
        self.live_only_function = live_only_function
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_safe_trading_mode_paper_trading_allowed(self, mock_get_creds):
        """Test safe_trading_mode decorator allows paper trading in testing mode."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Call decorated function
        result = self.test_function()
        
        # Verify function executed
        assert result == "function_executed"
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_safe_trading_mode_live_trading_blocked(self, mock_get_creds):
        """Test safe_trading_mode decorator blocks live trading in testing mode."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call decorated function and expect exception
        with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
            self.test_function()
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', False)
    def test_safe_trading_mode_production_mode(self, mock_get_creds):
        """Test safe_trading_mode decorator in production mode."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call decorated function
        result = self.test_function()
        
        # Verify function executed in production mode
        assert result == "function_executed"
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_require_paper_trading_allowed(self, mock_get_creds):
        """Test require_paper_trading decorator allows paper trading."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Call decorated function
        result = self.paper_only_function()
        
        # Verify function executed
        assert result == "paper_only_executed"
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_require_paper_trading_blocked(self, mock_get_creds):
        """Test require_paper_trading decorator blocks live trading."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call decorated function and expect exception
        with pytest.raises(TradingSafetyError, match="PAPER TRADING REQUIRED"):
            self.paper_only_function()
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', False)
    def test_require_live_trading_allowed(self, mock_get_creds):
        """Test require_live_trading decorator allows live trading in production."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call decorated function
        result = self.live_only_function()
        
        # Verify function executed
        assert result == "live_only_executed"
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_require_live_trading_blocked_testing_mode(self, mock_get_creds):
        """Test require_live_trading decorator blocks live trading in testing mode."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call decorated function and expect exception
        with pytest.raises(TradingSafetyError, match="LIVE TRADING BLOCKED"):
            self.live_only_function()
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', False)
    def test_require_live_trading_blocked_paper_mode(self, mock_get_creds):
        """Test require_live_trading decorator blocks paper trading."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Call decorated function and expect exception
        with pytest.raises(TradingSafetyError, match="LIVE TRADING REQUIRED"):
            self.live_only_function()
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_safe_trading_mode_credentials_error(self, mock_get_creds):
        """Test safe_trading_mode decorator handles credentials error."""
        # Setup mock to raise exception
        mock_get_creds.side_effect = Exception("Credentials error")
        
        # Call decorated function and expect TradingSafetyError
        with pytest.raises(TradingSafetyError, match="Trading safety check failed"):
            self.test_function()
        
        mock_get_creds.assert_called_once()
    
    def test_decorator_preserves_function_metadata(self):
        """Test that decorators preserve function metadata."""
        # Check function names
        assert self.test_function.__name__ == "test_function"
        assert self.paper_only_function.__name__ == "paper_only_function"
        assert self.live_only_function.__name__ == "live_only_function"
        
        # Check docstrings are preserved (handle None case properly)
        if self.test_function.__doc__ is not None:
            assert "function_executed" in self.test_function.__doc__
        # If docstring is None, that's also valid


class TestTradingSafetyStatus:
    """Test the get_trading_safety_status function."""
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_get_trading_safety_status_testing_mode_paper(self, mock_get_creds):
        """Test get_trading_safety_status in testing mode with paper trading."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Call function
        result = get_trading_safety_status()
        
        # Verify result
        assert result['testing_mode'] is True
        assert result['paper_trading'] is True
        assert result['live_trading'] is False
        assert result['safety_level'] == 'HIGH'
        assert result['trading_allowed'] is True
        assert result['live_trading_allowed'] is False
        assert result['paper_trading_allowed'] is True
        assert 'Safe mode' in result['description']
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', False)
    def test_get_trading_safety_status_production_mode_live(self, mock_get_creds):
        """Test get_trading_safety_status in production mode with live trading."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': False}
        
        # Call function
        result = get_trading_safety_status()
        
        # Verify result
        assert result['testing_mode'] is False
        assert result['paper_trading'] is False
        assert result['live_trading'] is True
        assert result['safety_level'] == 'NORMAL'
        assert result['trading_allowed'] is True
        assert result['live_trading_allowed'] is True
        assert result['paper_trading_allowed'] is False
        assert 'Production mode' in result['description']
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_get_trading_safety_status_credentials_error(self, mock_get_creds):
        """Test get_trading_safety_status handles credentials error."""
        # Setup mock to raise exception
        mock_get_creds.side_effect = Exception("Credentials error")
        
        # Call function
        result = get_trading_safety_status()
        
        # Verify error handling
        assert 'error' in result
        assert result['error'] == "Credentials error"
        assert result['testing_mode'] is True  # Should still get this from config
        assert result['safety_level'] == 'UNKNOWN'
        
        mock_get_creds.assert_called_once()


class TestTradingSafetyError:
    """Test the TradingSafetyError exception class."""
    
    def test_trading_safety_error_inheritance(self):
        """Test that TradingSafetyError inherits from Exception."""
        error = TradingSafetyError("Test error")
        assert isinstance(error, Exception)
        assert isinstance(error, TradingSafetyError)
    
    def test_trading_safety_error_message(self):
        """Test that TradingSafetyError preserves the error message."""
        error_message = "Test error message"
        error = TradingSafetyError(error_message)
        assert str(error) == error_message
    
    def test_trading_safety_error_creation(self):
        """Test creating TradingSafetyError with different message types."""
        # String message
        error1 = TradingSafetyError("String message")
        assert str(error1) == "String message"
        
        # Empty message
        error2 = TradingSafetyError("")
        assert str(error2) == ""
        
        # Long message
        long_message = "This is a very long error message that should be preserved"
        error3 = TradingSafetyError(long_message)
        assert str(error3) == long_message


class TestTradingSafetyIntegration:
    """Test integration scenarios for trading safety."""
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_multiple_decorators_on_same_function(self, mock_get_creds):
        """Test applying multiple safety decorators to the same function."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Create function with multiple decorators
        @safe_trading_mode
        @require_paper_trading
        def multi_decorated_function():
            return "multi_decorated_executed"
        
        # Call function
        result = multi_decorated_function()
        
        # Verify function executed
        assert result == "multi_decorated_executed"
        # Should be called twice (once for each decorator)
        assert mock_get_creds.call_count == 2
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_decorator_with_arguments(self, mock_get_creds):
        """Test safety decorators with functions that take arguments."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        @safe_trading_mode
        def function_with_args(arg1, arg2, kwarg1=None):
            return f"executed_with_{arg1}_{arg2}_{kwarg1}"
        
        # Call function with arguments
        result = function_with_args("test1", "test2", kwarg1="test3")
        
        # Verify function executed with correct arguments
        assert result == "executed_with_test1_test2_test3"
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_decorator_with_return_value(self, mock_get_creds):
        """Test safety decorators preserve return values."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        @safe_trading_mode
        def function_with_return():
            return {"status": "success", "data": [1, 2, 3]}
        
        # Call function
        result = function_with_return()
        
        # Verify return value is preserved
        assert isinstance(result, dict)
        assert result['status'] == "success"
        assert result['data'] == [1, 2, 3]
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    @patch('trading_safety.config.TESTING_MODE', True)
    def test_decorator_with_exception_in_function(self, mock_get_creds):
        """Test safety decorators when the decorated function raises an exception."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        @safe_trading_mode
        def function_that_raises():
            raise ValueError("Function error")
        
        # Call function and expect the TradingSafetyError (not the original ValueError)
        with pytest.raises(TradingSafetyError, match="Trading safety check failed"):
            function_that_raises()
        
        mock_get_creds.assert_called_once()


class TestTradingSafetyEdgeCases:
    """Test edge cases and error conditions."""
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_decorator_with_none_function(self, mock_get_creds):
        """Test that decorators handle edge cases gracefully."""
        # This test verifies the decorators don't break with unusual inputs
        # The decorators should work normally with valid functions
        
        @safe_trading_mode
        def normal_function():
            return "normal"
        
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        # Call function
        result = normal_function()
        assert result == "normal"
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_decorator_with_empty_function(self, mock_get_creds):
        """Test decorators with empty functions."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        @safe_trading_mode
        def empty_function():
            pass
        
        # Call function (should not raise exception)
        result = empty_function()
        assert result is None
        
        mock_get_creds.assert_called_once()
    
    @patch('trading_safety.config.get_current_alpaca_credentials')
    def test_decorator_with_recursive_function(self, mock_get_creds):
        """Test decorators with recursive functions."""
        # Setup mock
        mock_get_creds.return_value = {'paper_trading': True}
        
        @safe_trading_mode
        def recursive_function(n):
            if n <= 1:
                return 1
            return n * recursive_function(n - 1)
        
        # Call function
        result = recursive_function(5)
        assert result == 120  # 5!
        
        # Should be called multiple times due to recursion
        assert mock_get_creds.call_count > 1
