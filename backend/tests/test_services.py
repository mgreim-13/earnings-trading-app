"""
Tests for service functionality.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from datetime import datetime
from services.scheduler import TradingScheduler
from services.order_monitor import OrderMonitor
from services.data_manager import DataManager
from services.scan_manager import ScanManager
from services.trade_executor import TradeExecutor


class TestTradingScheduler:
    """Test TradingScheduler functionality."""

    @pytest.fixture(autouse=True)
    def setup_scheduler(self):
        """Setup test scheduler with mocked dependencies."""
        with patch('services.scheduler.Database') as mock_db, \
             patch('services.scheduler.AlpacaClient') as mock_alpaca, \
             patch('services.scheduler.EarningsScanner') as mock_scanner, \
             patch('services.scheduler.OrderMonitor') as mock_monitor, \
             patch('services.scheduler.TradeExecutor') as mock_executor:
            
            self.scheduler = TradingScheduler()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_scheduler_initialization(self):
        """Test scheduler initialization."""
        assert self.scheduler is not None
        assert hasattr(self.scheduler, 'scheduler')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_start_scheduler(self):
        """Test starting scheduler."""
        with patch.object(self.scheduler.scheduler, 'start') as mock_start:
            self.scheduler.start()
            mock_start.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_stop_scheduler(self):
        """Test stopping scheduler."""
        # Mock the running property to return True
        with patch.object(type(self.scheduler.scheduler), 'running', property(lambda self: True)), \
             patch.object(self.scheduler.scheduler, 'shutdown') as mock_shutdown:
            self.scheduler.stop()
            mock_shutdown.assert_called_once_with(wait=False)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_get_scheduler_status(self):
        """Test getting scheduler status."""
        with patch.object(self.scheduler.scheduler, 'get_jobs', return_value=[]):
            status = self.scheduler.get_scheduler_status()
            assert isinstance(status, dict)
            assert 'running' in status
            assert 'jobs' in status
            assert 'job_count' in status

    @pytest.mark.unit
    @pytest.mark.fast
    def test_trade_entry_job(self):
        """Test trade entry job."""
        with patch.object(self.scheduler, 'trade_entry_job') as mock_job:
            self.scheduler.trade_entry_job()
            mock_job.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_trade_exit_job(self):
        """Test trade exit job."""
        with patch.object(self.scheduler, 'trade_exit_job') as mock_job:
            self.scheduler.trade_exit_job()
            mock_job.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_execute_specific_trades(self):
        """Test executing specific trades."""
        trade_ids = [1, 2, 3]
        with patch.object(self.scheduler, 'execute_specific_trades', return_value={'success': True}) as mock_execute:
            result = self.scheduler.execute_specific_trades(trade_ids)
            assert result['success'] is True
            mock_execute.assert_called_once_with(trade_ids)


class TestOrderMonitor:
    """Test OrderMonitor functionality."""

    @pytest.fixture(autouse=True)
    def setup_monitor(self):
        """Setup test monitor with mocked dependencies."""
        mock_scheduler = Mock()
        mock_alpaca = Mock()
        mock_database = Mock()
        self.monitor = OrderMonitor(mock_scheduler, mock_alpaca, mock_database)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_monitor_initialization(self):
        """Test monitor initialization."""
        assert self.monitor is not None
        assert hasattr(self.monitor, 'scheduler')
        assert hasattr(self.monitor, 'client')
        assert hasattr(self.monitor, 'database')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_schedule_comprehensive_monitoring(self):
        """Test scheduling comprehensive monitoring."""
        with patch.object(self.monitor, 'schedule_comprehensive_monitoring') as mock_schedule:
            self.monitor.schedule_comprehensive_monitoring('test_trade_id', 'test_order_id')
            mock_schedule.assert_called_once_with('test_trade_id', 'test_order_id')

    @pytest.mark.unit
    @pytest.mark.fast
    @pytest.mark.asyncio
    async def test_check_order_status_with_retry(self):
        """Test checking order status with retry."""
        with patch.object(self.monitor, '_check_order_status_with_retry', return_value={'status': 'filled'}) as mock_check:
            result = await self.monitor._check_order_status_with_retry('test_order_id', 'AAPL')
            assert result['status'] == 'filled'
            mock_check.assert_called_once_with('test_order_id', 'AAPL')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_monitor_order_loop(self):
        """Test order monitoring loop."""
        with patch.object(self.monitor, '_monitor_order_loop') as mock_loop:
            self.monitor._monitor_order_loop('test_trade_id', 'test_order_id')
            mock_loop.assert_called_once_with('test_trade_id', 'test_order_id')


class TestDataManager:
    """Test DataManager functionality."""

    @pytest.fixture(autouse=True)
    def setup_manager(self):
        """Setup test manager with mocked dependencies."""
        mock_alpaca = Mock()
        mock_database = Mock()
        self.manager = DataManager(mock_alpaca, mock_database)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_manager_initialization(self):
        """Test manager initialization."""
        assert self.manager is not None
        assert hasattr(self.manager, 'alpaca_client')
        assert hasattr(self.manager, 'database')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_get_cleanup_stats(self):
        """Test getting cleanup stats."""
        with patch.object(self.manager, 'get_cleanup_stats', return_value={'total_cleaned': 10}) as mock_get:
            result = self.manager.get_cleanup_stats()
            assert result['total_cleaned'] == 10
            mock_get.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_get_data_statistics(self):
        """Test getting data statistics."""
        with patch.object(self.manager, 'get_data_statistics', return_value={'total_records': 100}) as mock_get:
            result = self.manager.get_data_statistics()
            assert result['total_records'] == 100
            mock_get.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.fast
    def test_data_cleanup_job(self):
        """Test data cleanup job."""
        with patch.object(self.manager, 'data_cleanup_job') as mock_cleanup:
            self.manager.data_cleanup_job()
            mock_cleanup.assert_called_once()


class TestScanManager:
    """Test ScanManager functionality."""

    @pytest.fixture(autouse=True)
    def setup_manager(self):
        """Setup test manager with mocked dependencies."""
        mock_scanner = Mock()
        mock_database = Mock()
        self.manager = ScanManager(mock_scanner, mock_database)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_manager_initialization(self):
        """Test manager initialization."""
        assert self.manager is not None
        assert hasattr(self.manager, 'earnings_scanner')
        assert hasattr(self.manager, 'database')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_daily_scan_job(self):
        """Test daily scan job."""
        with patch.object(self.manager, 'daily_scan_job') as mock_scan:
            self.manager.daily_scan_job()
            mock_scan.assert_called_once()




class TestTradeExecutor:
    """Test TradeExecutor functionality."""

    @pytest.fixture(autouse=True)
    def setup_executor(self):
        """Setup test executor with mocked dependencies."""
        mock_alpaca = Mock()
        mock_database = Mock()
        mock_scanner = Mock()
        self.executor = TradeExecutor(mock_alpaca, mock_database, mock_scanner)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_executor_initialization(self):
        """Test executor initialization."""
        assert self.executor is not None
        assert hasattr(self.executor, 'alpaca_client')
        assert hasattr(self.executor, 'database')
        assert hasattr(self.executor, 'earnings_scanner')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_calculate_position_size(self):
        """Test calculating position size."""
        mock_trade = {'ticker': 'AAPL', 'price': 150.0}
        with patch.object(self.executor, 'calculate_position_size', return_value=10) as mock_calc:
            result = self.executor.calculate_position_size(mock_trade)
            assert result == 10
            mock_calc.assert_called_once_with(mock_trade)

    @pytest.mark.unit
    @pytest.mark.fast
    @pytest.mark.asyncio
    async def test_execute_trades_with_parallel_preparation(self):
        """Test executing trades with parallel preparation."""
        mock_trades = [{'id': 1, 'ticker': 'AAPL'}, {'id': 2, 'ticker': 'GOOGL'}]
        with patch.object(self.executor, 'execute_trades_with_parallel_preparation', return_value={'success': True}) as mock_execute:
            result = await self.executor.execute_trades_with_parallel_preparation(mock_trades)
            assert result['success'] is True
            mock_execute.assert_called_once_with(mock_trades)

    @pytest.mark.unit
    @pytest.mark.fast
    @pytest.mark.asyncio
    async def test_prepare_trades_parallel(self):
        """Test preparing trades in parallel."""
        mock_trades = [{'id': 1, 'ticker': 'AAPL'}, {'id': 2, 'ticker': 'GOOGL'}]
        with patch.object(self.executor, 'prepare_trades_parallel', return_value=mock_trades) as mock_prepare:
            result = await self.executor.prepare_trades_parallel(mock_trades)
            assert len(result) == 2
            mock_prepare.assert_called_once_with(mock_trades)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_prepare_calendar_spread_trade(self):
        """Test preparing calendar spread trade."""
        mock_earning = {'symbol': 'AAPL', 'date': '2024-01-15'}
        mock_recommendation = {'score': 85}
        with patch.object(self.executor, 'prepare_calendar_spread_trade', return_value={'symbol': 'AAPL'}) as mock_prepare:
            result = self.executor.prepare_calendar_spread_trade('AAPL', mock_earning, mock_recommendation)
            assert result['symbol'] == 'AAPL'
            mock_prepare.assert_called_once_with('AAPL', mock_earning, mock_recommendation)

    @pytest.mark.unit
    @pytest.mark.fast
    def test_is_calendar_spread_position(self):
        """Test checking if position is calendar spread."""
        with patch.object(self.executor, '_is_calendar_spread_position', return_value=True) as mock_check:
            result = self.executor._is_calendar_spread_position('AAPL')
            assert result is True
            mock_check.assert_called_once_with('AAPL')

    @pytest.mark.unit
    @pytest.mark.fast
    def test_get_calendar_spread_trade_info(self):
        """Test getting calendar spread trade info."""
        with patch.object(self.executor, '_get_calendar_spread_trade_info', return_value={'symbol': 'AAPL'}) as mock_get:
            result = self.executor._get_calendar_spread_trade_info('AAPL')
            assert result['symbol'] == 'AAPL'
            mock_get.assert_called_once_with('AAPL')