"""
Pytest configuration and shared fixtures for the trading application tests.
"""

import pytest
import os
import tempfile
import shutil
from unittest.mock import Mock, patch
from pathlib import Path

# Set testing environment
os.environ['TESTING_MODE'] = 'true'
os.environ['PREVENT_LIVE_TRADING_IN_TESTS'] = 'true'
os.environ['LIVE_TRADING_ALLOWED'] = 'false'

@pytest.fixture(scope="session")
def test_db_path():
    """Create a temporary test database path."""
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_trading_app.db")
    yield db_path
    shutil.rmtree(temp_dir)

@pytest.fixture(scope="function")
def mock_config():
    """Mock configuration for testing."""
    with patch('config.TESTING_MODE', True), \
         patch('config.PREVENT_LIVE_TRADING_IN_TESTS', True), \
         patch('config.LIVE_TRADING_ALLOWED', False), \
         patch('config.PAPER_ALPACA_API_KEY', 'test_paper_key'), \
         patch('config.PAPER_ALPACA_SECRET_KEY', 'test_paper_secret'), \
         patch('config.LIVE_ALPACA_API_KEY', 'test_live_key'), \
         patch('config.LIVE_ALPACA_SECRET_KEY', 'test_live_secret'):
        yield

@pytest.fixture(scope="function")
def mock_alpaca_client():
    """Mock Alpaca client for testing."""
    mock_client = Mock()
    mock_client.get_account.return_value = Mock(
        cash=10000.0,
        buying_power=20000.0,
        equity=15000.0
    )
    return mock_client

@pytest.fixture(scope="function")
def sample_trade_data():
    """Sample trade data for testing."""
    return {
        'ticker': 'AAPL',
        'strategy': 'earnings_spread',
        'entry_date': '2024-01-15',
        'expiration': '2024-01-19',
        'strike_price': 150.0,
        'quantity': 1,
        'status': 'pending'
    }

@pytest.fixture(scope="function")
def sample_scan_data():
    """Sample scan data for testing."""
    return {
        'ticker': 'AAPL',
        'scan_date': '2024-01-15',
        'earnings_date': '2024-01-18',
        'volatility': 0.25,
        'score': 85.5
    }
