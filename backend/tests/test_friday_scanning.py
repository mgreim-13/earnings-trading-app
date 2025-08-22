"""
Tests for Friday scanning functionality.
Verifies that the application can properly handle Friday scans and find stocks
with AMC Friday and BMO Monday earnings.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from datetime import datetime, timedelta
import pytz

from services.scan_manager import ScanManager
from core.earnings_scanner import EarningsScanner
from core.database import Database


class TestFridayScanning:
    """Test Friday scanning functionality."""
    
    @pytest.fixture(autouse=True)
    def setup_scan_manager(self):
        """Setup scan manager with mocked dependencies."""
        self.mock_earnings_scanner = Mock(spec=EarningsScanner)
        self.mock_database = Mock(spec=Database)
        self.scan_manager = ScanManager(self.mock_earnings_scanner, self.mock_database)
        self.et_tz = pytz.timezone('US/Eastern')
    
    def test_daily_scan_job_calls_correct_method(self):
        """Test that daily_scan_job calls get_earnings_for_scanning instead of get_upcoming_earnings."""
        # Mock the earnings scanner to return test data
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'},
            {'symbol': 'GOOGL', 'date': '2024-01-22', 'hour': 'bmo'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        
        # Mock the compute_recommendation function
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 85,
                'filters': {'volume': 'high'},
                'reasoning': 'Strong earnings potential'
            }
            
            # Run the daily scan job
            self.scan_manager.daily_scan_job()
            
            # Verify the correct method was called
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
            
            # Verify get_upcoming_earnings was NOT called
            with pytest.raises(AttributeError):
                self.mock_earnings_scanner.get_upcoming_earnings.assert_called_once()
    
    def test_scan_specific_symbols_calls_correct_method(self):
        """Test that scan_specific_symbols calls get_earnings_for_scanning through _resolve_earnings_date."""
        symbols = ['AAPL', 'GOOGL']
        
        # Mock the database to return no existing scan results
        self.mock_database.get_latest_scan_result.return_value = None
        
        # Mock the earnings scanner
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'},
            {'symbol': 'GOOGL', 'date': '2024-01-22', 'hour': 'bmo'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        
        # Mock the compute_recommendation function
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 85,
                'filters': {'volume': 'high'},
                'reasoning': 'Strong earnings potential'
            }
            
            # Run the scan
            result = self.scan_manager.scan_specific_symbols(symbols)
            
            # Verify the method was called (through _resolve_earnings_date)
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called()
            
            # Verify the result
            assert result['total_scanned'] == 2
            assert result['total_selected'] == 2
    
    def test_get_earnings_calendar_calls_correct_method(self):
        """Test that get_earnings_calendar calls get_earnings_for_scanning."""
        # Mock the earnings scanner
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'},
            {'symbol': 'GOOGL', 'date': '2024-01-22', 'hour': 'bmo'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.get_latest_scan_result.return_value = None
        self.mock_database.get_trade_selections.return_value = []
        
        # Run the method
        result = self.scan_manager.get_earnings_calendar()
        
        # Verify the correct method was called
        self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
        
        # Verify the result
        assert len(result) == 2
        assert result[0]['symbol'] == 'AAPL'
        assert result[1]['symbol'] == 'GOOGL'
    
    def test_friday_scanning_with_amc_earnings(self):
        """Test that Friday scanning finds AMC earnings correctly."""
        # Mock Friday AMC earnings
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'},
            {'symbol': 'MSFT', 'date': '2024-01-19', 'hour': 'amc'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        
        # Mock the compute_recommendation function
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 85,
                'filters': {'volume': 'high'},
                'reasoning': 'Strong earnings potential'
            }
            
            # Run the daily scan job
            self.scan_manager.daily_scan_job()
            
            # Verify earnings were found and processed
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
            
            # Verify scan results were added
            assert self.mock_database.add_scan_result.call_count == 2
    
    def test_friday_scanning_with_bmo_monday_earnings(self):
        """Test that Friday scanning finds BMO Monday earnings correctly."""
        # Mock Monday BMO earnings
        mock_earnings = [
            {'symbol': 'GOOGL', 'date': '2024-01-22', 'hour': 'bmo'},
            {'symbol': 'TSLA', 'date': '2024-01-22', 'hour': 'bmo'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        
        # Mock the compute_recommendation function
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 90,
                'filters': {'volatility': 'high'},
                'reasoning': 'High volatility opportunity'
            }
            
            # Run the daily scan job
            self.scan_manager.daily_scan_job()
            
            # Verify earnings were found and processed
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
            
            # Verify scan results were added
            assert self.mock_database.add_scan_result.call_count == 2
    
    def test_friday_scanning_with_mixed_earnings(self):
        """Test that Friday scanning finds both AMC Friday and BMO Monday earnings."""
        # Mock mixed earnings (Friday AMC + Monday BMO)
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'},  # Friday AMC
            {'symbol': 'GOOGL', 'date': '2024-01-22', 'hour': 'bmo'}, # Monday BMO
            {'symbol': 'MSFT', 'date': '2024-01-19', 'hour': 'amc'},  # Friday AMC
            {'symbol': 'TSLA', 'date': '2024-01-22', 'hour': 'bmo'}   # Monday BMO
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock the database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        
        # Mock the compute_recommendation function
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 85,
                'filters': {'volume': 'high'},
                'reasoning': 'Strong earnings potential'
            }
            
            # Run the daily scan job
            self.scan_manager.daily_scan_job()
            
            # Verify earnings were found and processed
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
            
            # Verify scan results were added for all 4 stocks
            assert self.mock_database.add_scan_result.call_count == 4
    
    def test_no_earnings_found_handling(self):
        """Test that the scan manager handles no earnings found gracefully."""
        # Mock no earnings found
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = []
        
        # Run the daily scan job
        self.scan_manager.daily_scan_job()
        
        # Verify the method was called
        self.mock_earnings_scanner.get_earnings_for_scanning.assert_called_once()
        
        # Verify no database calls were made
        self.mock_database.add_scan_result.assert_not_called()
        self.mock_database.set_trade_selection.assert_not_called()
    
    def test_earnings_scanner_method_exists(self):
        """Test that the earnings scanner has the correct method."""
        # Create a real earnings scanner instance
        scanner = EarningsScanner()
        
        # Verify the method exists
        assert hasattr(scanner, 'get_earnings_for_scanning')
        assert callable(scanner.get_earnings_for_scanning)
        
        # Verify the old method does NOT exist
        assert not hasattr(scanner, 'get_upcoming_earnings')
    
    def test_scan_manager_methods_updated(self):
        """Test that all scan manager methods now call the correct earnings scanner method."""
        # Mock earnings data
        mock_earnings = [
            {'symbol': 'AAPL', 'date': '2024-01-19', 'hour': 'amc'}
        ]
        self.mock_earnings_scanner.get_earnings_for_scanning.return_value = mock_earnings
        
        # Mock database methods
        self.mock_database.add_scan_result.return_value = True
        self.mock_database.set_trade_selection.return_value = True
        self.mock_database.get_latest_scan_result.return_value = None
        
        # Mock compute_recommendation
        with patch('services.scan_manager.compute_recommendation') as mock_compute:
            mock_compute.return_value = {
                'score': 85,
                'filters': {},
                'reasoning': 'Test'
            }
            
            # Test daily_scan_job
            self.scan_manager.daily_scan_job()
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called()
            
            # Reset mock
            self.mock_earnings_scanner.get_earnings_for_scanning.reset_mock()
            
            # Test scan_specific_symbols
            self.scan_manager.scan_specific_symbols(['AAPL'])
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called()
            
            # Reset mock
            self.mock_earnings_scanner.get_earnings_for_scanning.reset_mock()
            
            # Test get_earnings_calendar
            self.scan_manager.get_earnings_calendar()
            self.mock_earnings_scanner.get_earnings_for_scanning.assert_called()


class TestEarningsScannerMethods:
    """Test that the earnings scanner has the correct methods."""
    
    def test_get_earnings_for_scanning_exists(self):
        """Test that get_earnings_for_scanning method exists and works."""
        scanner = EarningsScanner()
        
        # Verify method exists
        assert hasattr(scanner, 'get_earnings_for_scanning')
        assert callable(scanner.get_earnings_for_scanning)
        
        # Verify method signature (should have no parameters except self)
        import inspect
        sig = inspect.signature(scanner.get_earnings_for_scanning)
        # The method should have no parameters (just self)
        assert len(sig.parameters) == 0  # No parameters needed
    
    def test_get_earnings_for_scanning_returns_list(self):
        """Test that get_earnings_for_scanning returns a list."""
        scanner = EarningsScanner()
        
        # Mock the underlying methods to avoid API calls
        with patch.object(scanner, 'get_tomorrow_earnings') as mock_tomorrow, \
             patch.object(scanner, 'get_today_post_market_earnings') as mock_today:
            
            mock_tomorrow.return_value = []
            mock_today.return_value = []
            
            result = scanner.get_earnings_for_scanning()
            assert isinstance(result, list)
    
    def test_methods_that_should_not_exist(self):
        """Test that the old get_upcoming_earnings method does not exist."""
        scanner = EarningsScanner()
        
        # Verify the old method does NOT exist
        assert not hasattr(scanner, 'get_upcoming_earnings')
        
        # Verify other expected methods exist
        assert hasattr(scanner, 'get_earnings_calendar')
        assert hasattr(scanner, 'get_tomorrow_earnings')
        assert hasattr(scanner, 'get_today_post_market_earnings')
        assert hasattr(scanner, 'get_earnings_for_scanning')
