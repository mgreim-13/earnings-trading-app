"""
Tests for repository functionality.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
import tempfile
import os
from datetime import datetime
from repositories.trade_repository import TradeRepository
from repositories.scan_repository import ScanRepository
from repositories.settings_repository import SettingsRepository
from repositories.trade_selections_repository import TradeSelectionsRepository


class TestTradeRepository:
    """Test TradeRepository functionality."""

    @pytest.fixture(autouse=True)
    def setup_repository(self):
        """Setup test repository with temporary database."""
        self.temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
        self.temp_db.close()
        self.repository = TradeRepository(self.temp_db.name)

    def teardown_method(self):
        """Clean up temporary database."""
        if hasattr(self, 'temp_db') and os.path.exists(self.temp_db.name):
            os.unlink(self.temp_db.name)

    @pytest.mark.unit
    @pytest.mark.database
    def test_repository_initialization(self):
        """Test repository initialization."""
        assert self.repository.db_path == self.temp_db.name

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_selected_trade(self):
        """Test adding a selected trade."""
        trade_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'total_score': 85.5,
            'expected_move': '5.2%',
            'underlying_price': 150.0
        }
        
        result = self.repository.add_selected_trade(trade_data)
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selected_trades(self):
        """Test getting selected trades."""
        # Add a trade first
        trade_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'total_score': 85.5
        }
        self.repository.add_selected_trade(trade_data)
        
        result = self.repository.get_selected_trades()
        assert isinstance(result, list)
        assert len(result) >= 0

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selected_trades_by_status(self):
        """Test getting selected trades by status."""
        result = self.repository.get_selected_trades_by_status('pending')
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_by_id(self):
        """Test getting trade by ID."""
        # Add a trade first
        trade_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'total_score': 85.5
        }
        self.repository.add_selected_trade(trade_data)
        
        # Get all trades to find the ID
        trades = self.repository.get_selected_trades()
        if trades:
            trade_id = trades[0]['id']
            result = self.repository.get_trade_by_id(trade_id)
            assert result is not None
            assert result['ticker'] == 'AAPL'

    @pytest.mark.unit
    @pytest.mark.database
    def test_update_trade_status(self):
        """Test updating trade status."""
        # Add a trade first
        trade_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'total_score': 85.5
        }
        self.repository.add_selected_trade(trade_data)
        
        # Get the trade ID
        trades = self.repository.get_selected_trades()
        if trades:
            trade_id = trades[0]['id']
            result = self.repository.update_trade_status(trade_id, 'executed')
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_trade_history(self):
        """Test adding trade history."""
        trade_data = {
            'ticker': 'AAPL',
            'trade_type': 'calendar_spread',
            'entry_time': datetime.now(),
            'entry_price': 2.50,
            'quantity': 1,
            'status': 'open'
        }
        
        result = self.repository.add_trade_history(trade_data)
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_history(self):
        """Test getting trade history."""
        result = self.repository.get_trade_history(limit=10)
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_update_trade_order_info(self):
        """Test updating trade order info."""
        # Add a trade first
        trade_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'total_score': 85.5
        }
        self.repository.add_selected_trade(trade_data)
        
        # Get the trade ID
        trades = self.repository.get_selected_trades()
        if trades:
            trade_id = trades[0]['id']
            result = self.repository.update_trade_order_info(trade_id, order_id='12345')
            assert result is True


class TestScanRepository:
    """Test ScanRepository functionality."""

    @pytest.fixture(autouse=True)
    def setup_repository(self):
        """Setup test repository with temporary database."""
        self.temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
        self.temp_db.close()
        self.repository = ScanRepository(self.temp_db.name)

    def teardown_method(self):
        """Clean up temporary database."""
        if hasattr(self, 'temp_db') and os.path.exists(self.temp_db.name):
            os.unlink(self.temp_db.name)

    @pytest.mark.unit
    @pytest.mark.database
    def test_repository_initialization(self):
        """Test repository initialization."""
        assert self.repository.db_path == self.temp_db.name

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_scan_result(self):
        """Test adding scan result."""
        scan_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'recommendation_score': 85.5,
            'filters': '{}',
            'reasoning': 'High score'
        }
        
        result = self.repository.add_scan_result(scan_data)
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_recent_scan_results(self):
        """Test getting recent scan results."""
        result = self.repository.get_recent_scan_results()
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_latest_scan_result(self):
        """Test getting latest scan result."""
        # Add a scan result first
        scan_data = {
            'ticker': 'AAPL',
            'earnings_date': '2024-01-15',
            'earnings_time': 'amc',
            'recommendation_score': 85.5,
            'filters': '{}',
            'reasoning': 'High score'
        }
        self.repository.add_scan_result(scan_data)
        
        result = self.repository.get_latest_scan_result('AAPL')
        assert result is None or isinstance(result, dict)


class TestSettingsRepository:
    """Test SettingsRepository functionality."""

    @pytest.fixture(autouse=True)
    def setup_repository(self):
        """Setup test repository with temporary database."""
        self.temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
        self.temp_db.close()
        self.repository = SettingsRepository(self.temp_db.name)

    def teardown_method(self):
        """Clean up temporary database."""
        if hasattr(self, 'temp_db') and os.path.exists(self.temp_db.name):
            os.unlink(self.temp_db.name)

    @pytest.mark.unit
    @pytest.mark.database
    def test_repository_initialization(self):
        """Test repository initialization."""
        assert self.repository.db_path == self.temp_db.name

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_setting(self):
        """Test getting a setting."""
        result = self.repository.get_setting('auto_trading_enabled')
        # Should return None or a string value
        assert result is None or isinstance(result, str)

    @pytest.mark.unit
    @pytest.mark.database
    def test_set_setting(self):
        """Test setting a value."""
        result = self.repository.set_setting('auto_trading_enabled', 'true')
        assert result is True
        
        # Verify the setting was saved
        value = self.repository.get_setting('auto_trading_enabled')
        assert value == 'true'

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_all_settings(self):
        """Test getting all settings."""
        # Set some settings first
        self.repository.set_setting('auto_trading_enabled', 'true')
        self.repository.set_setting('risk_percentage', '2.0')
        
        result = self.repository.get_all_settings()
        assert isinstance(result, dict)
        assert 'auto_trading_enabled' in result
        assert 'risk_percentage' in result


class TestTradeSelectionsRepository:
    """Test TradeSelectionsRepository functionality."""

    @pytest.fixture(autouse=True)
    def setup_repository(self):
        """Setup test repository with temporary database."""
        self.temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
        self.temp_db.close()
        self.repository = TradeSelectionsRepository(self.temp_db.name)

    def teardown_method(self):
        """Clean up temporary database."""
        if hasattr(self, 'temp_db') and os.path.exists(self.temp_db.name):
            os.unlink(self.temp_db.name)

    @pytest.mark.unit
    @pytest.mark.database
    def test_repository_initialization(self):
        """Test repository initialization."""
        assert self.repository.db_path == self.temp_db.name

    @pytest.mark.unit
    @pytest.mark.database
    def test_set_trade_selection(self):
        """Test setting trade selection."""
        result = self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_selections(self):
        """Test getting trade selections."""
        # Add a selection first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        
        result = self.repository.get_trade_selections()
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selected_tickers_for_date(self):
        """Test getting selected tickers for date."""
        # Add a selection first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        
        result = self.repository.get_selected_tickers_for_date('2024-01-15')
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_manually_deselect_stock(self):
        """Test manually deselecting stock."""
        result = self.repository.manually_deselect_stock('AAPL', '2024-01-15')
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_is_manually_deselected(self):
        """Test checking if stock is manually deselected."""
        # First create a record by setting a trade selection
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        
        # Now manually deselect it
        self.repository.manually_deselect_stock('AAPL', '2024-01-15')
        
        # Check if it's marked as manually deselected
        result = self.repository.is_manually_deselected('AAPL', '2024-01-15')
        assert result is True
        
        # Check a stock that wasn't manually deselected (should return False)
        result2 = self.repository.is_manually_deselected('GOOGL', '2024-01-15')
        assert result2 is False
        
        # Test with a stock that doesn't exist in the database
        result3 = self.repository.is_manually_deselected('NONEXISTENT', '2024-01-15')
        assert result3 is False

    @pytest.mark.unit
    @pytest.mark.database
    def test_clear_all_trade_selections(self):
        """Test clearing all trade selections."""
        # Add some selections first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        self.repository.set_trade_selection('GOOGL', '2024-01-16', True)
        
        result = self.repository.clear_all_trade_selections()
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_clear_manually_deselected_stocks(self):
        """Test clearing manually deselected flags."""
        # Manually deselect some stocks first
        self.repository.manually_deselect_stock('AAPL', '2024-01-15')
        
        result = self.repository.clear_manually_deselected_stocks()
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selection_stats(self):
        """Test getting selection statistics."""
        # Add some selections first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        self.repository.set_trade_selection('GOOGL', '2024-01-16', False)
        
        result = self.repository.get_selection_stats()
        assert isinstance(result, dict)
        assert 'total_selections' in result
        assert 'currently_selected' in result

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selections_by_ticker(self):
        """Test getting selections by ticker."""
        # Add selections first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        self.repository.set_trade_selection('AAPL', '2024-01-16', False)
        
        result = self.repository.get_selections_by_ticker('AAPL')
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selections_by_date_range(self):
        """Test getting selections by date range."""
        # Add selections first
        self.repository.set_trade_selection('AAPL', '2024-01-15', True)
        self.repository.set_trade_selection('GOOGL', '2024-01-16', True)
        
        result = self.repository.get_selections_by_date_range('2024-01-15', '2024-01-16')
        assert isinstance(result, list)

    @pytest.mark.unit
    @pytest.mark.database
    def test_bulk_update_selections(self):
        """Test bulk updating selections."""
        selections = [
            {'ticker': 'AAPL', 'earnings_date': '2024-01-15', 'is_selected': True},
            {'ticker': 'GOOGL', 'earnings_date': '2024-01-16', 'is_selected': False}
        ]
        
        result = self.repository.bulk_update_selections(selections)
        assert result is True