"""
Tests for the database module.
"""

import pytest
import sqlite3
import tempfile
import os
from unittest.mock import Mock, patch, MagicMock
from core.database import Database


class TestDatabase:
    """Test database functionality."""

    @pytest.fixture(autouse=True)
    def setup_database(self, test_db_path):
        """Setup test database."""
        self.db_path = test_db_path
        self.database = Database(self.db_path)

    @pytest.mark.unit
    @pytest.mark.database
    def test_database_initialization(self):
        """Test database initialization."""
        assert self.database.db_path == self.db_path
        assert self.database.base_repo is not None
        assert self.database.trade_repo is not None
        assert self.database.scan_repo is not None
        assert self.database.settings_repo is not None
        assert self.database.selections_repo is not None

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_setting_delegation(self):
        """Test that get_setting delegates to settings repository."""
        with patch.object(self.database.settings_repo, 'get_setting') as mock_get:
            mock_get.return_value = "test_value"
            result = self.database.get_setting("test_key")
            mock_get.assert_called_once_with("test_key")
            assert result == "test_value"

    @pytest.mark.unit
    @pytest.mark.database
    def test_set_setting_delegation(self):
        """Test that set_setting delegates to settings repository."""
        with patch.object(self.database.settings_repo, 'set_setting') as mock_set:
            mock_set.return_value = True
            result = self.database.set_setting("test_key", "test_value")
            mock_set.assert_called_once_with("test_key", "test_value")
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_selected_trade_delegation(self):
        """Test that add_selected_trade delegates to trade repository."""
        trade_data = {"ticker": "AAPL", "strategy": "test"}
        with patch.object(self.database.trade_repo, 'add_selected_trade') as mock_add:
            mock_add.return_value = True
            result = self.database.add_selected_trade(trade_data)
            mock_add.assert_called_once_with(trade_data)
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selected_trades_by_status_delegation(self):
        """Test that get_selected_trades_by_status delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'get_selected_trades_by_status') as mock_get:
            mock_get.return_value = [{"id": 1, "ticker": "AAPL"}]
            result = self.database.get_selected_trades_by_status("pending")
            mock_get.assert_called_once_with("pending")
            assert result == [{"id": 1, "ticker": "AAPL"}]

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_by_id_delegation(self):
        """Test that get_trade_by_id delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'get_trade_by_id') as mock_get:
            mock_get.return_value = {"id": 1, "ticker": "AAPL"}
            result = self.database.get_trade_by_id(1)
            mock_get.assert_called_once_with(1)
            assert result == {"id": 1, "ticker": "AAPL"}

    @pytest.mark.unit
    @pytest.mark.database
    def test_update_trade_status_delegation(self):
        """Test that update_trade_status delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'update_trade_status') as mock_update:
            mock_update.return_value = True
            result = self.database.update_trade_status(1, "completed")
            mock_update.assert_called_once_with(1, "completed")
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_trade_history_delegation(self):
        """Test that add_trade_history delegates to trade repository."""
        trade_data = {"ticker": "AAPL", "pnl": 100.0}
        with patch.object(self.database.trade_repo, 'add_trade_history') as mock_add:
            mock_add.return_value = True
            result = self.database.add_trade_history(trade_data)
            mock_add.assert_called_once_with(trade_data)
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_history_delegation(self):
        """Test that get_trade_history delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'get_trade_history') as mock_get:
            mock_get.return_value = [{"id": 1, "ticker": "AAPL"}]
            result = self.database.get_trade_history(10)
            mock_get.assert_called_once_with(10)
            assert result == [{"id": 1, "ticker": "AAPL"}]

    @pytest.mark.unit
    @pytest.mark.database
    def test_update_trade_order_info_delegation(self):
        """Test that update_trade_order_info delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'update_trade_order_info') as mock_update:
            mock_update.return_value = True
            result = self.database.update_trade_order_info(
                1, order_id="123", entry_price=150.0
            )
            mock_update.assert_called_once_with(
                1, "123", None, None, None, None, 150.0, None, None
            )
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_selected_trades_delegation(self):
        """Test that get_selected_trades delegates to trade repository."""
        with patch.object(self.database.trade_repo, 'get_selected_trades') as mock_get:
            mock_get.return_value = [{"id": 1, "ticker": "AAPL"}]
            result = self.database.get_selected_trades()
            mock_get.assert_called_once()
            assert result == [{"id": 1, "ticker": "AAPL"}]

    @pytest.mark.unit
    @pytest.mark.database
    def test_add_scan_result_delegation(self):
        """Test that add_scan_result delegates to scan repository."""
        scan_data = {"ticker": "AAPL", "score": 85.5}
        with patch.object(self.database.scan_repo, 'add_scan_result') as mock_add:
            mock_add.return_value = True
            result = self.database.add_scan_result(scan_data)
            mock_add.assert_called_once_with(scan_data)
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_recent_scan_results_delegation(self):
        """Test that get_recent_scan_results delegates to scan repository."""
        with patch.object(self.database.scan_repo, 'get_recent_scan_results') as mock_get:
            mock_get.return_value = [{"ticker": "AAPL", "score": 85.5}]
            result = self.database.get_recent_scan_results(7)
            mock_get.assert_called_once_with(7)
            assert result == [{"ticker": "AAPL", "score": 85.5}]

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_latest_scan_result_delegation(self):
        """Test that get_latest_scan_result delegates to scan repository."""
        with patch.object(self.database.scan_repo, 'get_latest_scan_result') as mock_get:
            mock_get.return_value = {"ticker": "AAPL", "score": 85.5}
            result = self.database.get_latest_scan_result("AAPL")
            mock_get.assert_called_once_with("AAPL")
            assert result == {"ticker": "AAPL", "score": 85.5}

    @pytest.mark.unit
    @pytest.mark.database
    def test_set_trade_selection_delegation(self):
        """Test that set_trade_selection delegates to selections repository."""
        with patch.object(self.database.selections_repo, 'set_trade_selection') as mock_set:
            mock_set.return_value = True
            result = self.database.set_trade_selection("AAPL", "2024-01-15", True)
            mock_set.assert_called_once_with("AAPL", "2024-01-15", True)
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_trade_selections_delegation(self):
        """Test that get_trade_selections delegates to selections repository."""
        with patch.object(self.database.selections_repo, 'get_trade_selections') as mock_get:
            mock_get.return_value = [{"ticker": "AAPL", "strategy": "test"}]
            result = self.database.get_trade_selections()
            mock_get.assert_called_once()
            assert result == [{"ticker": "AAPL", "strategy": "test"}]

    @pytest.mark.unit
    @pytest.mark.database
    def test_manually_deselect_stock_delegation(self):
        """Test that manually_deselect_stock delegates to selections repository."""
        with patch.object(self.database.selections_repo, 'manually_deselect_stock') as mock_deselect:
            mock_deselect.return_value = True
            result = self.database.manually_deselect_stock("AAPL", "2024-01-15")
            mock_deselect.assert_called_once_with("AAPL", "2024-01-15")
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_clear_all_trade_selections_delegation(self):
        """Test that clear_all_trade_selections delegates to selections repository."""
        with patch.object(self.database.selections_repo, 'clear_all_trade_selections') as mock_clear:
            mock_clear.return_value = True
            result = self.database.clear_all_trade_selections()
            mock_clear.assert_called_once()
            assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_database_with_custom_path(self):
        """Test database initialization with custom path."""
        import tempfile
        import os
        
        # Create a temporary file that we can actually write to
        temp_fd, custom_path = tempfile.mkstemp(suffix='.db')
        os.close(temp_fd)  # Close the file descriptor
        
        try:
            db = Database(custom_path)
            assert db.base_repo.db_path == custom_path
        finally:
            # Clean up
            if os.path.exists(custom_path):
                os.remove(custom_path)

    @pytest.mark.unit
    @pytest.mark.database
    def test_database_repositories_initialized(self):
        """Test that all repositories are properly initialized."""
        assert hasattr(self.database, 'base_repo')
        assert hasattr(self.database, 'trade_repo')
        assert hasattr(self.database, 'scan_repo')
        assert hasattr(self.database, 'settings_repo')
        assert hasattr(self.database, 'selections_repo')
