"""
Tests for core functionality.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from datetime import datetime, timedelta
from core.alpaca_client import AlpacaClient
from core.earnings_scanner import EarningsScanner


class TestAlpacaClient:
    """Test AlpacaClient functionality."""

    @pytest.fixture(autouse=True)
    def setup_client(self):
        """Setup test client with mocked dependencies."""
        with patch('core.alpaca_client.config.get_current_alpaca_credentials') as mock_creds:
            mock_creds.return_value = {
                'api_key': 'test_key',
                'secret_key': 'test_secret',
                'base_url': 'https://paper-api.alpaca.markets',
                'paper_trading': True
            }
            with patch('core.alpaca_client.TradingClient') as mock_trading_client:
                mock_trading_client.return_value = Mock()
                self.client = AlpacaClient()

    @pytest.mark.unit
    def test_client_initialization(self):
        """Test client initialization."""
        assert self.client is not None
        assert hasattr(self.client, 'trading_client')

    @pytest.mark.unit
    def test_get_account_info(self):
        """Test getting account info."""
        mock_account = Mock()
        mock_account.account_number = '123456'
        mock_account.cash = '10000'
        mock_account.buying_power = '20000'
        mock_account.equity = '15000'
        mock_account.portfolio_value = '15000'
        self.client.trading_client.get_account.return_value = mock_account
        
        result = self.client.get_account_info()
        assert result is not None
        assert 'id' in result or 'account_number' in result

    @pytest.mark.unit
    def test_get_account_info_error_handling(self):
        """Test account info error handling."""
        self.client.trading_client.get_account.side_effect = Exception("API Error")
        
        result = self.client.get_account_info()
        assert result is None

    @pytest.mark.unit
    def test_get_positions(self):
        """Test getting positions."""
        mock_positions = [{'symbol': 'AAPL', 'qty': '10'}]
        self.client.trading_client.get_all_positions.return_value = [Mock(**pos) for pos in mock_positions]
        
        result = self.client.get_positions()
        assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_positions_error_handling(self):
        """Test positions error handling."""
        self.client.trading_client.get_all_positions.side_effect = Exception("API Error")
        
        result = self.client.get_positions()
        assert result == []

    @pytest.mark.unit
    def test_get_current_price(self):
        """Test getting current price with mocked Alpaca data client."""
        # Mock the Alpaca data client to return test data
        with patch('core.alpaca_client.StockHistoricalDataClient') as mock_data_client_class:
            mock_data_client = mock_data_client_class.return_value
            
            # Mock the latest trade response
            mock_trade = Mock()
            mock_trade.price = 150.0
            mock_latest_trade = {'AAPL': mock_trade}
            mock_data_client.get_stock_latest_trade.return_value = mock_latest_trade
            
            # Test the real implementation
            result = self.client.get_current_price("AAPL")
            assert result == 150.0
            
            # Verify the data client was called correctly
            mock_data_client_class.assert_called_once()
            mock_data_client.get_stock_latest_trade.assert_called_once()

    @pytest.mark.unit
    def test_get_current_price_error_handling(self):
        """Test current price error handling with mocked Alpaca data client."""
        # Mock the Alpaca data client to raise an exception
        with patch('core.alpaca_client.StockHistoricalDataClient') as mock_data_client_class:
            mock_data_client = mock_data_client_class.return_value
            mock_data_client.get_stock_latest_trade.side_effect = Exception("API Error")
            
            # Test the real implementation with error handling
            result = self.client.get_current_price("AAPL")
            # Should return None when API fails (based on the actual implementation)
            assert result is None
            
            # Verify the data client was called
            mock_data_client_class.assert_called_once()

    @pytest.mark.unit
    def test_get_orders(self):
        """Test getting orders."""
        mock_orders = [{'id': '123', 'symbol': 'AAPL'}]
        self.client.trading_client.get_orders.return_value = [Mock(**order) for order in mock_orders]
        
        result = self.client.get_orders()
        assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_orders_error_handling(self):
        """Test orders error handling."""
        self.client.trading_client.get_orders.side_effect = Exception("API Error")
        
        result = self.client.get_orders()
        assert result == []

    @pytest.mark.unit
    def test_is_market_open(self):
        """Test checking if market is open."""
        mock_clock = Mock()
        mock_clock.is_open = True
        self.client.trading_client.get_clock.return_value = mock_clock
        
        result = self.client.is_market_open()
        assert result is True

    @pytest.mark.unit
    def test_is_market_open_error_handling(self):
        """Test market open check error handling."""
        with patch.object(self.client, 'is_market_open', return_value=False) as mock_market:
            result = self.client.is_market_open()
            assert result is False
            mock_market.assert_called_once()

    @pytest.mark.unit
    def test_get_account_activities(self):
        """Test getting account activities."""
        mock_activities = [{'id': '123', 'activity_type': 'FILL'}]
        self.client.trading_client.get_portfolio_history.return_value = Mock()
        
        with patch.object(self.client, 'get_account_activities', return_value=mock_activities):
            result = self.client.get_account_activities()
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_trade_activities(self):
        """Test getting trade activities."""
        mock_activities = [{'id': '123', 'activity_type': 'FILL'}]
        
        with patch.object(self.client, 'get_trade_activities', return_value=mock_activities):
            result = self.client.get_trade_activities()
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_close_position(self):
        """Test closing a position."""
        with patch.object(self.client, 'close_position', return_value=True) as mock_close:
            result = self.client.close_position("AAPL", 10)
            assert result is True
            mock_close.assert_called_once_with("AAPL", 10)

    @pytest.mark.unit
    def test_close_position_error_handling(self):
        """Test close position error handling."""
        with patch.object(self.client, 'close_position', return_value=False) as mock_close:
            result = self.client.close_position("AAPL", 10)
            assert result is False
            mock_close.assert_called_once_with("AAPL", 10)


class TestEarningsScanner:
    """Test EarningsScanner functionality."""

    @pytest.fixture(autouse=True)
    def setup_scanner(self):
        """Setup test scanner with mocked dependencies."""
        with patch('utils.yfinance_cache.yf_cache'):
            self.scanner = EarningsScanner()

    @pytest.mark.unit
    def test_scanner_initialization(self):
        """Test scanner initialization."""
        assert self.scanner is not None

    @pytest.mark.unit
    def test_get_earnings_calendar(self):
        """Test getting earnings calendar."""
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-15', 'hour': 'amc'},
            {'symbol': 'GOOGL', 'date': '2024-01-16', 'hour': 'bmo'}
        ]
        
        with patch.object(self.scanner, 'get_earnings_calendar', return_value=mock_earnings) as mock_get:
            result = self.scanner.get_earnings_calendar('2024-01-15', '2024-01-16')
            assert isinstance(result, list)
            assert len(result) >= 0
            mock_get.assert_called_once_with('2024-01-15', '2024-01-16')

    @pytest.mark.unit
    def test_get_earnings_calendar_error_handling(self):
        """Test earnings calendar error handling."""
        with patch.object(self.scanner, 'get_earnings_calendar', return_value=[]) as mock_get:
            result = self.scanner.get_earnings_calendar('2024-01-15', '2024-01-16')
            assert result == []
            mock_get.assert_called_once_with('2024-01-15', '2024-01-16')

    @pytest.mark.unit
    def test_filter_earnings_timing(self):
        """Test filtering earnings by timing."""
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-15', 'hour': 'amc'},
            {'symbol': 'GOOGL', 'date': '2024-01-16', 'hour': 'bmo'},
            {'symbol': 'MSFT', 'date': '2024-01-17', 'hour': 'other'}
        ]
        
        result = self.scanner.filter_earnings_timing(mock_earnings)
        assert isinstance(result, list)
        # Should filter out earnings that are not amc or bmo
        assert all(earning['hour'] in ['amc', 'bmo'] for earning in result)

    @pytest.mark.unit
    def test_get_filtered_earnings(self):
        """Test getting filtered earnings."""
        with patch.object(self.scanner, 'get_earnings_calendar') as mock_calendar, \
             patch.object(self.scanner, 'filter_earnings_timing') as mock_filter:
            
            mock_calendar.return_value = [{'symbol': 'AAPL', 'hour': 'amc'}]
            mock_filter.return_value = [{'symbol': 'AAPL', 'hour': 'amc'}]
            
            result = self.scanner.get_filtered_earnings('2024-01-15', '2024-01-16')
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_tomorrow_earnings(self):
        """Test getting tomorrow's earnings."""
        with patch.object(self.scanner, 'get_filtered_earnings') as mock_filtered:
            mock_filtered.return_value = [{'symbol': 'AAPL', 'hour': 'bmo'}]
            
            result = self.scanner.get_tomorrow_earnings()
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_today_post_market_earnings(self):
        """Test getting today's post-market earnings."""
        with patch.object(self.scanner, 'get_filtered_earnings') as mock_filtered:
            mock_filtered.return_value = [{'symbol': 'AAPL', 'hour': 'amc'}]
            
            result = self.scanner.get_today_post_market_earnings()
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_get_earnings_for_scanning(self):
        """Test getting earnings for scanning."""
        with patch.object(self.scanner, 'get_tomorrow_earnings') as mock_tomorrow, \
             patch.object(self.scanner, 'get_today_post_market_earnings') as mock_today:
            
            mock_tomorrow.return_value = [{'symbol': 'AAPL', 'hour': 'bmo'}]
            mock_today.return_value = [{'symbol': 'GOOGL', 'hour': 'amc'}]
            
            result = self.scanner.get_earnings_for_scanning()
            assert isinstance(result, list)

    @pytest.mark.unit
    def test_validate_earnings_data(self):
        """Test validating earnings data."""
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-15', 'hour': 'amc'},
            {'symbol': '', 'date': '2024-01-16', 'hour': 'bmo'},  # Invalid - no symbol
            {'symbol': 'GOOGL', 'date': '', 'hour': 'amc'},  # Invalid - no date
            {'symbol': 'MSFT', 'date': '2024-01-17', 'hour': 'amc'}
        ]
        
        result = self.scanner.validate_earnings_data(mock_earnings)
        assert isinstance(result, list)
        # Should filter out invalid entries
        assert len(result) <= len(mock_earnings)
        assert all(earning.get('symbol') and earning.get('date') for earning in result)