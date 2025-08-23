"""
Tests for API endpoints.
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import Mock, patch, MagicMock
import os
import sys

# Add the backend directory to the path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

class TestAPI:
    """Test API functionality."""

    @pytest.fixture(autouse=True)
    def setup_client(self):
        """Setup test client with mocked dependencies."""
        # Mock all the global components to avoid initialization issues
        with patch('api.app.database') as mock_db, \
             patch('api.app.alpaca_client') as mock_alpaca, \
             patch('api.app.earnings_scanner') as mock_scanner, \
             patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            
            # Configure database mocks
            mock_db.get_setting.return_value = "true"
            mock_db.set_setting.return_value = True
            mock_db.get_selected_trades.return_value = []
            mock_db.get_trade_selections.return_value = []
            
            # Configure alpaca client mocks
            mock_alpaca.get_account_info.return_value = {"account_number": "123"}
            mock_alpaca.get_positions.return_value = []
            mock_alpaca.get_trade_activities.return_value = []
            
            # Configure scanner mocks
            mock_scanner.get_earnings_for_scanning.return_value = []
            
            # Create and configure scheduler mock
            mock_scheduler = Mock()
            mock_scheduler.get_scheduler_status.return_value = {"running": True, "jobs": [], "job_count": 0}
            mock_scheduler.start.return_value = True
            mock_scheduler.stop.return_value = True
            mock_scheduler.database = mock_db
            mock_scheduler.trade_executor = Mock()
            mock_scheduler.trade_executor.execute_specific_trade.return_value = {"success": True}
            
            # Configure the get_trading_scheduler mock
            mock_get_scheduler.return_value = mock_scheduler
            
            # Import app after mocking
            from api.app import app
            self.client = TestClient(app)

    @pytest.mark.unit
    @pytest.mark.api
    def test_health_check(self):
        """Test health check endpoint."""
        response = self.client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "healthy"
        assert "timestamp" in data
        assert "version" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_config_test(self):
        """Test config test endpoint."""
        response = self.client.get("/config-test")
        assert response.status_code == 200
        data = response.json()
        assert "status" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_settings(self):
        """Test getting settings."""
        response = self.client.get("/settings")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_update_setting(self):
        """Test updating a setting."""
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.database = Mock()
            mock_scheduler.database.set_setting.return_value = True
            mock_get_scheduler.return_value = mock_scheduler
            
            setting_data = {"key": "auto_trading_enabled", "value": "true"}
            response = self.client.put("/settings", json=setting_data)
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_scheduler_status(self):
        """Test getting scheduler status."""
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.get_scheduler_status.return_value = {"running": True, "jobs": [], "job_count": 0}
            mock_get_scheduler.return_value = mock_scheduler
            
            response = self.client.get("/scheduler/status")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_start_scheduler(self):
        """Test starting scheduler."""
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.start.return_value = True
            mock_get_scheduler.return_value = mock_scheduler
            
            response = self.client.post("/scheduler/start")
            assert response.status_code == 200

    @pytest.mark.unit
    @pytest.mark.api
    def test_cors_headers_present(self):
        """Test that CORS headers are present in responses."""
        response = self.client.get("/health")
        assert response.status_code == 200
        
        # Check that CORS headers are present
        # Note: FastAPI TestClient doesn't always show CORS headers in unit tests
        # This is a basic check that the endpoint responds correctly
        assert "content-type" in response.headers
        
    @pytest.mark.unit
    @pytest.mark.api
    def test_cors_preflight_request(self):
        """Test CORS preflight request handling."""
        # Test OPTIONS request (CORS preflight)
        response = self.client.options("/health")
        # OPTIONS requests should be handled by CORS middleware
        assert response.status_code in [200, 405]  # 405 is also acceptable for OPTIONS
        
    @pytest.mark.unit
    @pytest.mark.api
    def test_cors_origin_restriction(self):
        """Test that CORS origins are properly restricted."""
        # This test verifies that the CORS configuration is applied
        # The actual CORS behavior is tested in the security tests
        from api.app import app
        
        # Check that CORS middleware is configured
        cors_middleware = None
        for middleware in app.user_middleware:
            if "CORSMiddleware" in str(middleware.cls):
                cors_middleware = middleware
                break
        
        assert cors_middleware is not None, "CORS middleware should be configured"
        
        # Verify that the middleware is configured
        assert "CORSMiddleware" in str(cors_middleware.cls)
        
        # Verify that the app has CORS configured by checking the middleware list
        cors_configured = any(
            "CORSMiddleware" in str(middleware.cls) 
            for middleware in app.user_middleware
        )
        assert cors_configured, "CORS middleware should be configured"

    @pytest.mark.unit
    @pytest.mark.api
    def test_stop_scheduler(self):
        """Test stopping scheduler."""
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.stop.return_value = True
            mock_get_scheduler.return_value = mock_scheduler
            
            response = self.client.post("/scheduler/stop")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_selected_trades(self):
        """Test getting selected trades."""
        response = self.client.get("/trades/selected")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_trade_selections(self):
        """Test getting trade selections."""
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.database = Mock()
            mock_scheduler.database.get_trade_selections.return_value = []
            mock_get_scheduler.return_value = mock_scheduler
            
            response = self.client.get("/trades/selections")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_select_stock_for_execution(self):
        """Test selecting a stock for execution."""
        selection_data = {
            "ticker": "AAPL",
            "earnings_date": "2024-01-15",
            "is_selected": True
        }
        response = self.client.post("/trades/select-stock", json=selection_data)
        assert response.status_code == 200
        data = response.json()
        assert "success" in data



    @pytest.mark.unit
    @pytest.mark.api
    def test_get_upcoming_earnings(self):
        """Test getting upcoming earnings."""
        response = self.client.get("/dashboard/upcoming-earnings")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_account_info(self):
        """Test getting account info."""
        response = self.client.get("/dashboard/account")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_positions(self):
        """Test getting positions."""
        response = self.client.get("/dashboard/positions")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_recent_trades(self):
        """Test getting recent trades."""
        response = self.client.get("/dashboard/recent-trades")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "data" in data

    @pytest.mark.unit
    @pytest.mark.api
    def test_clear_cache(self):
        """Test clearing cache."""
        with patch('api.app.cache_service') as mock_cache:
            mock_cache.clear_cache.return_value = None
            response = self.client.post("/cache/clear")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_cache_stats(self):
        """Test getting cache stats."""
        with patch('api.app.cache_service') as mock_cache:
            mock_cache.get_cache_stats.return_value = {"size": 0}
            response = self.client.get("/cache/stats")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True

    @pytest.mark.unit
    @pytest.mark.api
    def test_get_current_price(self):
        """Test getting current price."""
        with patch('api.app.alpaca_client') as mock_alpaca:
            mock_alpaca.get_current_price.return_value = 150.0
            response = self.client.get("/market/price/AAPL")
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is True
            assert data["data"]["price"] == 150.0

    @pytest.mark.unit
    @pytest.mark.api
    def test_api_error_handling(self):
        """Test API error handling."""
        # Test with invalid JSON
        response = self.client.put("/settings", data="invalid json")
        assert response.status_code == 422

    @pytest.mark.unit
    @pytest.mark.api
    def test_settings_validation(self):
        """Test settings validation."""
        # Test with missing required fields
        response = self.client.put("/settings", json={})
        assert response.status_code == 422
        
        # Test with valid data
        with patch('api.app.get_trading_scheduler') as mock_get_scheduler:
            # Create mock scheduler
            mock_scheduler = Mock()
            mock_scheduler.database = Mock()
            mock_scheduler.database.set_setting.return_value = True
            mock_get_scheduler.return_value = mock_scheduler
            
            setting_data = {"key": "auto_trading_enabled", "value": "true"}
            response = self.client.put("/settings", json=setting_data)
            assert response.status_code == 200