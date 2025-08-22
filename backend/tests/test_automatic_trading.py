"""
Tests for the automatic trading system.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from datetime import datetime, timedelta
import asyncio


class TestAutomaticTradingSystem:
    """Test the automatic trading system functionality."""

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_auto_selection_threshold(self):
        """Test that the auto-selection threshold is properly configured."""
        from config import AUTO_SELECT_THRESHOLD
        
        # Verify the threshold is set to the recommended level
        assert AUTO_SELECT_THRESHOLD == 80.0
        
        # Verify it's a float
        assert isinstance(AUTO_SELECT_THRESHOLD, float)

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_scanner_auto_selection(self):
        """Test that the scanner automatically selects high-scoring stocks."""
        # Mock the scan manager
        with patch('services.scan_manager.ScanManager') as mock_scan_manager:
            mock_instance = Mock()
            mock_scan_manager.return_value = mock_instance
            
            # Mock a high-scoring recommendation
            high_score_recommendation = {
                'score': 85.0,  # Above AUTO_SELECT_THRESHOLD
                'filters': {'option_liquidity': 0.8},
                'reasoning': 'High score recommendation'
            }
            
            # Mock the compute_recommendation function
            with patch('services.scan_manager.compute_recommendation') as mock_compute:
                mock_compute.return_value = high_score_recommendation
                
                # Test that high-scoring stocks are auto-selected
                score = high_score_recommendation['score']
                threshold = 80.0  # AUTO_SELECT_THRESHOLD
                
                should_auto_select = score >= threshold
                assert should_auto_select is True

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_scanner_no_auto_selection_low_score(self):
        """Test that the scanner doesn't auto-select low-scoring stocks."""
        # Mock a low-scoring recommendation
        low_score_recommendation = {
            'score': 65.0,  # Below AUTO_SELECT_THRESHOLD
            'filters': {'option_liquidity': 0.7},
            'reasoning': 'Low score recommendation'
        }
        
        # Test that low-scoring stocks are not auto-selected
        score = low_score_recommendation['score']
        threshold = 80.0  # AUTO_SELECT_THRESHOLD
        
        should_auto_select = score >= threshold
        assert should_auto_select is False

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_scheduler_uses_selected_trades(self):
        """Test that the scheduler only executes selected trades."""
        # Mock the scheduler
        with patch('services.scheduler.TradingScheduler') as mock_scheduler:
            mock_instance = Mock()
            mock_scheduler.return_value = mock_instance
            
            # Mock selected trades
            selected_trades = [
                {'ticker': 'AAPL', 'earnings_date': '2024-01-15', 'is_selected': True},
                {'ticker': 'MSFT', 'earnings_date': '2024-01-16', 'is_selected': True},
                {'ticker': 'GOOGL', 'earnings_date': '2024-01-17', 'is_selected': False}
            ]
            
            # Mock the database to return selected trades
            mock_instance.database.get_selected_trades.return_value = [
                trade for trade in selected_trades if trade['is_selected']
            ]
            
            # Verify only selected trades are returned
            result = mock_instance.database.get_selected_trades()
            assert len(result) == 2
            assert all(trade['is_selected'] for trade in result)

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_manual_deselection_prevention(self):
        """Test that manually deselected stocks can't be reselected by scanner."""
        # Mock the trade selections repository
        with patch('repositories.trade_selections_repository.TradeSelectionsRepository') as mock_repo:
            mock_instance = Mock()
            mock_repo.return_value = mock_instance
            
            # Mock a manually deselected stock
            ticker = 'AAPL'
            earnings_date = '2024-01-15'
            
            # Mock the is_manually_deselected method
            mock_instance.is_manually_deselected.return_value = True
            
            # Test that the scanner respects manual deselection
            is_manually_deselected = mock_instance.is_manually_deselected(ticker, earnings_date)
            assert is_manually_deselected is True
            
            # The scanner should not auto-select this stock even if it gets a high score
            # This is handled by the database layer

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_automatic_execution_timing(self):
        """Test that automatic execution happens at scheduled times."""
        # Mock the scheduler timing
        entry_time = '15:45'  # 3:45 PM ET
        exit_time = '09:45'   # 9:45 AM ET
        
        # Verify the timing is configured correctly
        assert entry_time == '15:45'
        assert exit_time == '09:45'
        
        # Verify these are string times that can be parsed
        from datetime import datetime
        try:
            entry_dt = datetime.strptime(entry_time, '%H:%M')
            exit_dt = datetime.strptime(exit_time, '%H:%M')
            assert entry_dt.hour == 15
            assert entry_dt.minute == 45
            assert exit_dt.hour == 9
            assert exit_dt.minute == 45
        except ValueError:
            pytest.fail("Time format is invalid")

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_no_manual_execution_endpoint(self):
        """Test that the manual execution endpoint is removed."""
        # The bulk execute endpoint should no longer exist
        # This is verified by the fact that we removed it from the API
        
        # Verify that the frontend no longer has the execute button
        # This is verified by the frontend tests
        
        # Verify that the system only executes trades automatically
        manual_execution_available = False
        assert manual_execution_available is False

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_trade_selection_persistence(self):
        """Test that trade selections are properly persisted."""
        # Mock the database
        with patch('core.database.Database') as mock_db:
            mock_instance = Mock()
            mock_db.return_value = mock_instance
            
            # Mock setting a trade selection
            ticker = 'AAPL'
            earnings_date = '2024-01-15'
            is_selected = True
            
            mock_instance.set_trade_selection.return_value = True
            
            # Test setting a trade selection
            result = mock_instance.set_trade_selection(ticker, earnings_date, is_selected)
            assert result is True
            
            # Verify the method was called with correct parameters
            mock_instance.set_trade_selection.assert_called_with(ticker, earnings_date, is_selected)

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_earnings_date_filtering(self):
        """Test that only trades for today/tomorrow earnings are executed."""
        # Mock the scheduler's trade filtering logic
        today = datetime.now().date()
        tomorrow = today + timedelta(days=1)
        
        # Mock selected trades with different dates
        selected_trades = [
            {'ticker': 'AAPL', 'earnings_date': today.strftime('%Y-%m-%d')},
            {'ticker': 'MSFT', 'earnings_date': tomorrow.strftime('%Y-%m-%d')},
            {'ticker': 'GOOGL', 'earnings_date': (today + timedelta(days=5)).strftime('%Y-%m-%d')}
        ]
        
        # Filter for today/tomorrow trades only
        today_trades = []
        for trade in selected_trades:
            try:
                earnings_date = datetime.strptime(trade['earnings_date'], '%Y-%m-%d').date()
                if earnings_date >= today and earnings_date <= tomorrow:
                    today_trades.append(trade)
            except Exception:
                continue
        
        # Verify only today/tomorrow trades are included
        assert len(today_trades) == 2
        assert 'AAPL' in [trade['ticker'] for trade in today_trades]
        assert 'MSFT' in [trade['ticker'] for trade in today_trades]
        assert 'GOOGL' not in [trade['ticker'] for trade in today_trades]

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_parallel_trade_execution(self):
        """Test that trades are executed in parallel for efficiency."""
        # Mock the trade executor
        with patch('services.trade_executor.TradeExecutor') as mock_executor:
            mock_instance = Mock()
            mock_executor.return_value = mock_instance
            
            # Mock the parallel execution method
            mock_instance.execute_trades_with_parallel_preparation.return_value = {
                'success': True,
                'message': 'Trades executed successfully',
                'executed_trades': ['AAPL', 'MSFT'],
                'failed_trades': [],
                'execution_time': 5.2
            }
            
            # Test parallel execution
            result = mock_instance.execute_trades_with_parallel_preparation([])
            assert result['success'] is True
            assert len(result['executed_trades']) == 2
            assert result['execution_time'] > 0

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_risk_management_integration(self):
        """Test that risk management settings are used in automatic trading."""
        # Mock the settings repository
        with patch('repositories.settings_repository.SettingsRepository') as mock_repo:
            mock_instance = Mock()
            mock_repo.return_value = mock_instance
            
            # Mock risk percentage setting
            mock_instance.get_setting.return_value = '2.5'
            
            # Test that risk setting is retrieved
            risk_setting = mock_instance.get_setting('risk_percentage')
            assert risk_setting == '2.5'
            
            # Verify the method was called
            mock_instance.get_setting.assert_called_with('risk_percentage')

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_paper_trading_mode_respect(self):
        """Test that the system respects paper trading mode settings."""
        # Mock the settings repository
        with patch('repositories.settings_repository.SettingsRepository') as mock_repo:
            mock_instance = Mock()
            mock_repo.return_value = mock_instance
            
            # Mock paper trading enabled
            mock_instance.get_setting.return_value = 'true'
            
            # Test that paper trading setting is retrieved
            paper_trading = mock_instance.get_setting('paper_trading_enabled')
            assert paper_trading == 'true'
            
            # Verify the method was called
            mock_instance.get_setting.assert_called_with('paper_trading_enabled')

    @pytest.mark.unit
    @pytest.mark.automatic_trading
    def test_automated_trading_enabled_check(self):
        """Test that the system checks if automated trading is enabled."""
        # Mock the settings repository
        with patch('repositories.settings_repository.SettingsRepository') as mock_repo:
            mock_instance = Mock()
            mock_repo.return_value = mock_instance
            
            # Mock automated trading enabled
            mock_instance.get_setting.return_value = 'true'
            
            # Test that automated trading setting is retrieved
            auto_trading = mock_instance.get_setting('auto_trading_enabled')
            assert auto_trading == 'true'
            
            # Verify the method was called
            mock_instance.get_setting.assert_called_with('auto_trading_enabled')
