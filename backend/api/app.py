"""
Main FastAPI Application for Earnings Calendar Spread Trading
Provides REST API endpoints for scanning, trading, and monitoring operations.

DISCLAIMER: This software is for educational and informational purposes only. 
Trading options involves substantial risk and is not suitable for all investors. 
Past performance does not guarantee future results. Always consult with a qualified 
financial advisor before making investment decisions.
"""

import logging
import traceback
from datetime import datetime, timedelta, timezone
import numpy as np
import pytz
# Removed: Path import - no longer needed for .env file updates
from typing import List, Dict, Optional, Any
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError


from core.database import Database
from core.alpaca_client import AlpacaClient
from core.earnings_scanner import EarningsScanner
from services.scheduler import TradingScheduler
from utils.filters import compute_recommendation
from utils.cache_service import cache_service
import config

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

def convert_numpy_types(obj: Any) -> Any:
    """Convert numpy data types to regular Python types for JSON serialization."""
    if isinstance(obj, np.integer):
        return int(obj)
    elif isinstance(obj, np.floating):
        # Handle NaN and infinite values
        if np.isnan(obj) or np.isinf(obj):
            return None
        return float(obj)
    elif isinstance(obj, np.ndarray):
        # Convert numpy array to list and handle NaN values in the array
        converted_list = []
        for item in obj:
            if isinstance(item, (np.floating, np.integer)):
                if np.isnan(item) or np.isinf(item):
                    converted_list.append(None)
                else:
                    converted_list.append(convert_numpy_types(item))
            else:
                converted_list.append(convert_numpy_types(item))
        return converted_list
    elif isinstance(obj, dict):
        return {key: convert_numpy_types(value) for key, value in obj.items()}
    elif isinstance(obj, list):
        return [convert_numpy_types(item) for item in obj]
    else:
        return obj

# Initialize FastAPI app
app = FastAPI(
    title="Earnings Calendar Spread Trading API",
    description="API for automated earnings calendar spread trading using Alpaca",
    version="1.0.0"
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],  # Frontend development URLs
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["*"],
)

# Add request logging middleware
@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Log all incoming HTTP requests."""
    logger.info(f"🔍 Incoming request: {request.method} {request.url}")
    
    # Log request body for POST/PUT requests
    if request.method in ["POST", "PUT"]:
        try:
            body = await request.body()
            if body:
                logger.info(f"🔍 Request body: {body.decode()}")
            else:
                logger.info(f"🔍 Request body: (empty)")
        except Exception as e:
            logger.warning(f"⚠️ Could not read request body: {e}")
    
    # Process the request
    response = await call_next(request)
    
    # Log response status
    logger.info(f"🔍 Response status: {response.status_code}")
    
    return response

# Removed duplicate health endpoint - see the main one below

# Global variable for Alpaca client
alpaca_client: AlpacaClient = None

def get_alpaca_client() -> AlpacaClient:
    """Get the global Alpaca client instance."""
    global alpaca_client
    
    # CRITICAL: Log when global client is requested
    logger.info("🔍 GET_ALPACA_CLIENT: Global client requested...")
    if alpaca_client is None:
        logger.info("   No global client exists, creating new one...")
        alpaca_client = AlpacaClient()
        logger.info("✅ New global client created")
    else:
        logger.info(f"   Returning existing global client: {type(alpaca_client)}")
        logger.info(f"   Existing client trading client type: {type(alpaca_client.trading_client)}")
    
    return alpaca_client

def set_alpaca_client(client: AlpacaClient):
    """Set the global Alpaca client instance."""
    global alpaca_client
    
    # CRITICAL: Log what we're setting as global client
    logger.info("🔍 SET_ALPACA_CLIENT: Setting global client...")
    logger.info(f"   New client type: {type(client)}")
    logger.info(f"   New client trading client type: {type(client.trading_client)}")
    
    # Log the old client if it exists
    if alpaca_client:
        logger.info(f"   Old client type: {type(alpaca_client)}")
        logger.info(f"   Old client trading client type: {type(alpaca_client.trading_client)}")
    else:
        logger.info("   No previous global client")
    
    alpaca_client = client
    logger.info("✅ Global Alpaca client updated successfully")

def recreate_alpaca_client():
    """Force recreation of the global Alpaca client with current settings."""
    global alpaca_client
    
    logger.info("🔄 RECREATE_ALPACA_CLIENT: Forcing recreation of global client...")
    
    # Clear the old client
    if alpaca_client:
        logger.info(f"   Clearing old client: {type(alpaca_client)}")
        alpaca_client = None
    
    # Create new client with current settings
    new_client = AlpacaClient()
    logger.info(f"   Created new client: {type(new_client)}")
    
    # Set as global client
    set_alpaca_client(new_client)
    
    logger.info("✅ Global Alpaca client recreated successfully")
    return new_client

# Initialize components
try:
    database = Database()
    alpaca_client = get_alpaca_client()  # Use the function instead of direct assignment
    earnings_scanner = EarningsScanner()
    # Note: trading_scheduler is initialized in main.py, not here
    logger.info("All components initialized successfully")
except Exception as e:
    logger.error(f"Failed to initialize components: {e}")
    raise

# Pydantic models for API requests/responses
class SettingUpdate(BaseModel):
    key: str
    value: str

# Removed: AlpacaAccountSettings model - no longer needed

class TradeSelection(BaseModel):
    trade_ids: List[int]

class TradeSelectionRequest(BaseModel):
    ticker: str
    earnings_date: str
    is_selected: bool



# Global scheduler reference - will be set by main.py
_trading_scheduler = None

def set_trading_scheduler(scheduler):
    """Set the global trading scheduler reference."""
    global _trading_scheduler
    _trading_scheduler = scheduler

def get_trading_scheduler():
    """Get the global trading scheduler reference."""
    global _trading_scheduler
    if _trading_scheduler is None:
        raise RuntimeError("Trading scheduler not initialized. Call set_trading_scheduler() first.")
    return _trading_scheduler

# Health check endpoint
@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
                    "timestamp": datetime.now(timezone.utc).isoformat(),
        "version": "1.0.0"
    }

# Configuration test endpoint
@app.get("/config-test")
async def config_test():
    """Test endpoint to verify configuration loading."""
    try:
        # Get current credentials
        current_creds = config.get_current_alpaca_credentials()
        
        return {
            "status": "success",
            "current_credentials": {
                "paper_trading": current_creds.get('paper_trading'),
                "base_url": current_creds.get('base_url'),
                "api_key_length": len(current_creds.get('api_key', '')) if current_creds.get('api_key') else 0
            },
            "server_host": config.SERVER_HOST,
            "server_port": config.SERVER_PORT
        }
    except Exception as e:
        logger.error(f"Config test failed: {e}")
        return {
            "status": "error",
            "error": str(e)
        }



# Dashboard endpoints
@app.get("/dashboard/account")
async def get_account_info():
    """Get current account information."""
    try:
        logger.info("🔍 Dashboard: Getting account info...")
        current_client = get_alpaca_client()  # Use the function to get the global client
        logger.info(f"   Current alpaca_client: {type(current_client)}")
        
        # Get current credentials for logging
        current_creds = config.get_current_alpaca_credentials()
        logger.info(f"   Current trading mode: {'PAPER' if current_creds['paper_trading'] else 'LIVE'}")
        logger.info(f"   Current base URL: {current_creds['base_url']}")
        
        # CRITICAL: Log the exact global client being used
        logger.info("🔍 GLOBAL CLIENT DETAILS:")
        logger.info(f"   Global client type: {type(current_client)}")
        if current_client:
            logger.info(f"   Global client trading client type: {type(current_client.trading_client)}")
            logger.info("   Global client trading client paper mode: Could not determine")
        else:
            logger.info("   Global client is None!")
        
        account_info = current_client.get_account_info()
        logger.info(f"   Account info result: {type(account_info)}")
        
        if not account_info:
            logger.error("❌ Account info is None or empty")
            raise HTTPException(status_code=500, detail="Failed to fetch account information")
        
        return {
            "success": True,
            "data": account_info
        }
        
    except Exception as e:
        logger.error(f"❌ Failed to get account info: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch account information")

@app.get("/dashboard/positions")
async def get_positions():
    """Get current open positions."""
    try:
        logger.info("🔍 Dashboard: Getting positions...")
        current_client = get_alpaca_client()  # Use the function to get the global client
        logger.info(f"   Current alpaca_client: {type(current_client)}")
        
        # Get current credentials for logging
        current_creds = config.get_current_alpaca_credentials()
        logger.info(f"   Current trading mode: {'PAPER' if current_creds['paper_trading'] else 'LIVE'}")
        logger.info(f"   Current base URL: {current_creds['base_url']}")
        
        # CRITICAL: Log the exact global client being used
        logger.info("🔍 GLOBAL CLIENT DETAILS:")
        logger.info(f"   Global client type: {type(current_client)}")
        if current_client:
            logger.info(f"   Global client trading client type: {type(current_client.trading_client)}")
            logger.info("   Global client trading client paper mode: Could not determine")
        else:
            logger.info("   Global client is None!")
        
        positions = current_client.get_positions()
        logger.info(f"   Positions result: {type(positions)}, count: {len(positions) if positions else 0}")
        
        if positions is None:
            logger.warning("⚠️ Positions is None")
            positions = []
        
        logger.info(f"✅ Positions retrieved successfully: {len(positions)} positions")
        
        return {
            "success": True,
            "data": positions
        }
        
    except Exception as e:
        logger.error(f"❌ Failed to get positions: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch positions")

@app.get("/dashboard/recent-trades")
async def get_recent_trades(limit: int = 50):
    """Get recent trade history from Alpaca account activities."""
    try:
        # Validate input
        if limit < 1 or limit > 1000:
            raise HTTPException(status_code=400, detail="Limit must be between 1 and 1000")
        
        logger.info(f"🔍 Getting recent trades with limit: {limit}")
        
        # First try to get actual trade history from Alpaca account activities
        current_client = get_alpaca_client()  # Use the function to get the global client
        
        # CRITICAL: Log the exact global client being used
        logger.info("🔍 GLOBAL CLIENT DETAILS FOR RECENT TRADES:")
        logger.info(f"   Global client type: {type(current_client)}")
        if current_client:
            logger.info(f"   Global client trading client type: {type(current_client.trading_client)}")
            logger.info("   Global client trading client paper mode: Could not determine")
        else:
            logger.info("   Global client is None!")
        
        trades = current_client.get_trade_activities(limit=limit)
        logger.info(f"🔍 Alpaca trade activities returned: {len(trades)} trades")
        
        # If no Alpaca trades, show current positions as recent trades
        if not trades:
            logger.info("🔍 No Alpaca trades found, getting current positions...")
            try:
                positions = current_client.get_positions()
                logger.info(f"🔍 Found {len(positions)} positions")
                
                for position in positions[:limit]:
                    trade = {
                        'id': position.get('asset_id'),
                        'ticker': position.get('symbol'),
                        'trade_type': position.get('side', 'long'),
                        'entry_time': datetime.now(timezone.utc).isoformat(),  # Use current time as approximation
                        'exit_time': None,
                        'entry_price': position.get('avg_entry_price'),
                        'exit_price': None,
                        'quantity': position.get('qty'),
                        'pnl': position.get('unrealized_pl'),
                        'status': 'open',
                        'order_type': 'market',
                        'asset_class': position.get('asset_class')
                    }
                    trades.append(trade)
                
                logger.info(f"🔍 Created {len(trades)} trades from positions")
            except Exception as pos_error:
                logger.error(f"Failed to get positions: {pos_error}")
        
        # If still no trades, fall back to local database
        if not trades:
            logger.info("🔍 No trades from positions, falling back to local database...")
            trades = database.get_trade_history(limit=limit)
            logger.info(f"🔍 Local database returned {len(trades)} trades")
        
        logger.info(f"🔍 Final result: {len(trades)} trades")
        return {
            "success": True,
            "data": trades
        }
    except Exception as e:
        logger.error(f"Failed to get recent trades: {e}")
        # Fall back to local database on error
        try:
            trades = database.get_trade_history(limit=limit)
            return {
                "success": True,
                "data": trades
            }
        except Exception as db_error:
            logger.error(f"Failed to get trade history from database: {db_error}")
            raise HTTPException(status_code=500, detail=str(e))

@app.get("/dashboard/upcoming-earnings")
async def get_upcoming_earnings():
    """Get upcoming earnings announcements for the strict time frame (AMC today / BMO tomorrow)."""
    try:
        # Use the strict time frame from earnings scanner
        earnings = earnings_scanner.get_earnings_for_scanning()
        
        # Transform data to match frontend expectations
        transformed_earnings = []
        
        for earning in earnings:
            transformed_earning = {
                'symbol': earning.get('symbol', ''),
                'date': earning.get('date', ''),
                'hour': earning.get('hour', ''),
                'quarter': earning.get('quarter', ''),
                'year': earning.get('year', ''),
                'estimate': earning.get('epsEstimate'),
                'actual': earning.get('epsActual'),
                'revenue_estimate': earning.get('revenueEstimate'),
                'revenue_actual': earning.get('revenueActual'),
                'status': 'upcoming',
                'has_data': earning.get('epsActual') is not None
            }
            transformed_earnings.append(transformed_earning)
        
        return {
            "success": True,
            "data": transformed_earnings
        }
    except Exception as e:
        logger.error(f"Failed to get upcoming earnings: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/dashboard/upcoming-earnings-with-scan")
async def get_upcoming_earnings_with_scan():
    """Get upcoming earnings announcements with scan results for the strict time frame (AMC today / BMO tomorrow)."""
    try:
        # Use the strict time frame from earnings scanner
        try:
            earnings = earnings_scanner.get_earnings_for_scanning()
        except Exception as e:
            logger.error(f"Failed to get earnings for scanning: {e}")
            raise HTTPException(status_code=500, detail=f"Failed to get earnings data: {str(e)}")
        
        if not earnings:
            logger.warning("No earnings found for scanning")
            return {
                "success": True,
                "data": [],
                "filter_weights": config.FILTER_WEIGHTS,
                "message": "No upcoming earnings found"
            }
        
        # Prepare ticker data for batch processing
        tickers_with_dates = [
            {
                'ticker': earning.get('symbol', ''),
                'earnings_date': earning.get('date', '')
            }
            for earning in earnings if earning.get('symbol')
        ]
        
        # Get scan results from cache (5 minute TTL for reasonable performance)
        scan_results = cache_service.get_or_compute_scan_results(tickers_with_dates, ttl_minutes=5)
        
        # Transform data to match frontend expectations
        transformed_earnings = []
        
        for earning in earnings:
            try:
                symbol = earning.get('symbol', '')
                scan_result = scan_results.get(symbol)
                
                # Convert numpy types in scan result to regular Python types
                if scan_result:
                    scan_result = convert_numpy_types(scan_result)
                
                transformed_earning = {
                    'symbol': symbol,
                    'date': earning.get('date', ''),
                    'hour': earning.get('hour', ''),
                    'quarter': earning.get('quarter', ''),
                    'year': earning.get('year', ''),
                    'estimate': earning.get('epsEstimate'),
                    'actual': earning.get('epsActual'),
                    'revenue_estimate': earning.get('revenueEstimate'),
                    'revenue_actual': earning.get('revenueActual'),
                    'status': 'upcoming',
                    'has_data': earning.get('epsActual') is not None,
                    # Add scan results
                    'scan_result': scan_result
                }
                
                transformed_earnings.append(transformed_earning)
            except Exception as e:
                logger.error(f"Failed to transform earning {earning.get('symbol', 'unknown')}: {e}")
                continue
        
        # Additional safety check: ensure no NaN values remain in the data
        def clean_nan_values(obj):
            """Recursively clean any remaining NaN values from the object."""
            if isinstance(obj, dict):
                return {key: clean_nan_values(value) for key, value in obj.items()}
            elif isinstance(obj, list):
                return [clean_nan_values(item) for item in obj]
            elif isinstance(obj, float) and (obj != obj):  # Check for NaN
                return None
            elif isinstance(obj, float) and (obj == float('inf') or obj == float('-inf')):
                return None
            else:
                return obj
        
        # Clean any remaining NaN values
        cleaned_earnings = clean_nan_values(transformed_earnings)
        
        # Auto-select high-scoring stocks (unless manually deselected)
        auto_selected_count = 0
        for earning in cleaned_earnings:
            if earning.get('scan_result') and earning['scan_result'].get('total_score'):
                total_score = earning['scan_result']['total_score']
                symbol = earning['symbol']
                earnings_date = earning['date']
                
                # Auto-select stocks with score >= 80 (unless manually deselected)
                if total_score >= 80:
                    # Check if this stock has been manually deselected
                    if not get_trading_scheduler().database.is_manually_deselected(symbol, earnings_date):
                        # Auto-select the stock
                        success = get_trading_scheduler().database.set_trade_selection(symbol, earnings_date, True)
                        if success:
                            auto_selected_count += 1
                            logger.info(f"🎯 Auto-selected {symbol} (score: {total_score}) for {earnings_date}")
                        else:
                            logger.warning(f"⚠️ Failed to auto-select {symbol} for {earnings_date}")
                    else:
                        logger.info(f"⏭️ Skipping auto-selection of {symbol} - manually deselected by user")
        
        if auto_selected_count > 0:
            logger.info(f"🎯 Auto-selected {auto_selected_count} high-scoring stocks")
        
        # Get filter weights from config
        
        return {
            "success": True,
            "data": cleaned_earnings,
            "filter_weights": config.FILTER_WEIGHTS
        }
    except Exception as e:
        logger.error(f"Failed to get upcoming earnings with scan: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# Cache management endpoints
@app.post("/cache/clear")
async def clear_cache():
    """Clear all cached data."""
    try:
        cache_service.clear_cache()
        return {"success": True, "message": "Cache cleared successfully"}
    except Exception as e:
        logger.error(f"Failed to clear cache: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/cache/clear-corrupted")
async def clear_corrupted_cache():
    """Clear corrupted cache files and reset the cache."""
    try:
        cache_service.clear_corrupted_cache()
        return {"success": True, "message": "Corrupted cache cleared successfully"}
    except Exception as e:
        logger.error(f"Failed to clear corrupted cache: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/cache/stats")
async def get_cache_stats():
    """Get cache statistics."""
    try:
        stats = cache_service.get_cache_stats()
        return {"success": True, "data": stats}
    except Exception as e:
        logger.error(f"Failed to get cache stats: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# Settings endpoints
@app.get("/settings")
async def get_settings():
    """Get all application settings."""
    try:
        settings = {}
        for key in ['auto_trading_enabled', 'risk_percentage', 'paper_trading_enabled']:
            value = database.get_setting(key)
            settings[key] = value
        
        return {
            "success": True,
            "data": settings
        }
    except Exception as e:
        logger.error(f"Failed to get settings: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.put("/settings")
async def update_setting(setting: SettingUpdate):
    """Update a setting value."""
    try:
        # Validate inputs
        if not setting.key or not setting.value:
            raise HTTPException(status_code=400, detail="Setting key and value are required")
        
        # Validate setting key
        valid_keys = ['auto_trading_enabled', 'risk_percentage', 'paper_trading_enabled']
        if setting.key not in valid_keys:
            raise HTTPException(status_code=400, detail=f"Invalid setting key. Must be one of: {', '.join(valid_keys)}")
        
        # Validate risk percentage if that's the setting
        if setting.key == 'risk_percentage':
            try:
                risk_value = float(setting.value)
                if risk_value < 0 or risk_value > 100:
                    raise HTTPException(status_code=400, detail="Risk percentage must be between 0 and 100")
            except ValueError:
                raise HTTPException(status_code=400, detail="Risk percentage must be a valid number")
        
        success = database.set_setting(setting.key, setting.value)
        if not success:
            raise HTTPException(status_code=500, detail="Failed to update setting")
        
        # Automatically control scheduler when auto_trading_enabled changes
        if setting.key == 'auto_trading_enabled':
            if setting.value.lower() in ['true', '1', 'yes', 'on']:
                get_trading_scheduler().start()
                logger.info("Automated trading enabled - scheduler started automatically")
            else:
                get_trading_scheduler().stop()
                logger.info("Automated trading disabled - scheduler stopped automatically")
        
        # Automatically recreate Alpaca client when paper_trading_enabled changes
        if setting.key == 'paper_trading_enabled':
            logger.info(f"Paper trading mode changed to: {setting.value}")
            logger.info("Recreating Alpaca client with new credentials...")
            recreate_alpaca_client()
            logger.info("Alpaca client recreated with new trading mode")
        
        return {
            "success": True,
            "message": f"Setting {setting.key} updated successfully"
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update setting: {e}")
        raise HTTPException(status_code=500, detail=str(e))



# Removed: Alpaca account settings update endpoint - no longer needed

@app.get("/scan/raw-earnings")
async def get_raw_earnings_data():
    """Get the raw earnings data that the scanner is finding."""
    try:
        # Get earnings scanner instance
        earnings_scanner = get_trading_scheduler().earnings_scanner
        
        # Get the raw earnings data
        tomorrow_earnings = earnings_scanner.get_tomorrow_earnings()
        today_earnings = earnings_scanner.get_today_post_market_earnings()
        
        # Get the combined earnings for scanning
        combined_earnings = earnings_scanner.get_earnings_for_scanning()
        
        # Get some broader earnings data to compare
        
        utc_now = datetime.now(timezone.utc)
        eastern_tz = pytz.timezone('US/Eastern')
        today = utc_now.astimezone(eastern_tz)
        
        # Get earnings for the next few days
        broader_earnings = []
        for i in range(0, 7):  # Today + next 6 days
            check_date = (today + timedelta(days=i)).strftime("%Y-%m-%d")
            date_earnings = earnings_scanner.get_earnings_calendar(check_date, check_date)
            if date_earnings:
                broader_earnings.append({
                    'date': check_date,
                    'count': len(date_earnings),
                    'sample': date_earnings[:5] if date_earnings else []
                })
        
        return {
            "success": True,
            "data": {
                "current_time": {
                    "utc": utc_now.isoformat(),
                    "eastern": today.isoformat()
                },
                "tomorrow_bmo_earnings": {
                    "count": len(tomorrow_earnings),
                    "earnings": tomorrow_earnings
                },
                "today_amc_earnings": {
                    "count": len(today_earnings),
                    "earnings": today_earnings
                },
                "combined_scanning_earnings": {
                    "count": len(combined_earnings),
                    "earnings": combined_earnings
                },
                "broader_earnings": broader_earnings
            }
        }
        
    except Exception as e:
        logger.error(f"Raw earnings endpoint failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# Trade management endpoints
@app.get("/trades/selected")
async def get_selected_trades(status: Optional[str] = None):
    """Get selected trades, optionally filtered by status."""
    try:
        # Validate status if provided
        if status is not None and status not in ['selected', 'pending', 'executed', 'cancelled', 'failed']:
            raise HTTPException(status_code=400, detail="Invalid status. Must be one of: selected, pending, executed, cancelled, failed")
        
        trades = database.get_selected_trades_by_status(status=status)
        return {
            "success": True,
            "data": trades
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get selected trades: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/trades/select")
async def select_trades_for_trading(selection: TradeSelection):
    """Mark selected trades for automatic execution."""
    try:
        # Validate inputs
        if not selection.trade_ids:
            raise HTTPException(status_code=400, detail="Trade IDs list is required")
        
        if not isinstance(selection.trade_ids, list):
            raise HTTPException(status_code=400, detail="Trade IDs must be a list")
        
        if len(selection.trade_ids) == 0:
            raise HTTPException(status_code=400, detail="Trade IDs list cannot be empty")
        
        if len(selection.trade_ids) > 100:
            raise HTTPException(status_code=400, detail="Cannot select more than 100 trades at once")
        
        logger.info(f"🔍 Trade selection request received: {selection}")
        logger.info(f"🔍 Trade IDs: {selection.trade_ids}")
        
        success_count = 0
        for trade_id in selection.trade_ids:
            if not trade_id:
                logger.warning(f"⚠️ Skipping empty trade ID")
                continue
                
            logger.info(f"🔍 Processing trade ID: {trade_id}")
            if database.update_trade_status(trade_id, 'selected'):
                success_count += 1
                logger.info(f"✅ Successfully selected trade ID: {trade_id}")
            else:
                logger.warning(f"⚠️ Failed to select trade ID: {trade_id}")
        
        logger.info(f"🎯 Trade selection complete: {success_count}/{len(selection.trade_ids)} trades selected")
        
        return {
            "success": True,
            "message": f"Successfully selected {success_count} trades for execution"
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"❌ Failed to select trades: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/trades/select-stock")
async def select_stock_for_execution(selection: TradeSelectionRequest):
    """Select or deselect a stock for automated trade execution."""
    try:
        # Validate inputs
        if not selection.ticker or not selection.earnings_date:
            raise HTTPException(status_code=400, detail="Ticker and earnings date are required")
        
        # Validate ticker format
        if not selection.ticker.replace('-', '').replace('.', '').isalnum():
            raise HTTPException(status_code=400, detail="Invalid ticker format")
        
        # Validate earnings date format (basic check)
        if len(selection.earnings_date) != 10 or selection.earnings_date.count('-') != 2:
            raise HTTPException(status_code=400, detail="Invalid earnings date format. Use YYYY-MM-DD")
        
        logger.info(f"🔍 Stock selection request received: {selection}")
        logger.info(f"🔍 Ticker: {selection.ticker}")
        logger.info(f"🔍 Earnings date: {selection.earnings_date}")
        logger.info(f"🔍 Is selected: {selection.is_selected}")
        
        # Handle selection/deselection
        if selection.is_selected:
            # User is selecting the stock
            logger.info(f"🔍 User selecting {selection.ticker} for {selection.earnings_date}")
            success = get_trading_scheduler().database.set_trade_selection(selection.ticker, selection.earnings_date, True)
        else:
            # User is deselecting the stock - mark as manually deselected to prevent auto-selection
            logger.info(f"🔍 User deselecting {selection.ticker} for {selection.earnings_date} - marking as manually deselected")
            success = get_trading_scheduler().database.manually_deselect_stock(selection.ticker, selection.earnings_date)
        
        if success:
            logger.info(f"✅ Trade selection updated: {selection.ticker} {'selected' if selection.is_selected else 'deselected'} for {selection.earnings_date}")
            
            # Verify the update by querying the database
            logger.info(f"🔍 Verifying database update for {selection.ticker}...")
            try:
                selections = get_trading_scheduler().database.get_trade_selections()
                logger.info(f"🔍 All trade selections after update: {selections}")
                
                # Find our specific selection
                our_selection = next((s for s in selections if s['ticker'] == selection.ticker and s['earnings_date'] == selection.earnings_date), None)
                if our_selection:
                    logger.info(f"✅ Verification successful: Found selection {our_selection}")
                else:
                    logger.error(f"❌ Verification failed: Selection not found in database after update!")
                    
            except Exception as e:
                logger.error(f"❌ Failed to verify database update: {e}")
            
            return {
                "success": True,
                "message": f"Trade selection updated for {selection.ticker} on {selection.earnings_date}",
                "is_selected": selection.is_selected
            }
        else:
            logger.error(f"❌ Failed to update trade selection for {selection.ticker}")
            return {
                "success": False,
                "error": "Failed to update trade selection"
            }
            
    except Exception as e:
        logger.error(f"❌ Failed to select trade: {e}")
        return {
            "success": False,
            "error": str(e)
        }



# Scheduler management endpoints
@app.get("/scheduler/status")
async def get_scheduler_status():
    """Get current scheduler status and job information."""
    try:
        status = get_trading_scheduler().get_scheduler_status()
        return {
            "success": True,
            "data": status
        }
    except Exception as e:
        logger.error(f"Failed to get scheduler status: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/scheduler/start")
async def start_scheduler():
    """Start the trading scheduler."""
    try:
        get_trading_scheduler().start()
        return {
            "success": True,
            "message": "Trading scheduler started successfully"
        }
    except Exception as e:
        logger.error(f"Failed to start scheduler: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/scheduler/stop")
async def stop_scheduler():
    """Stop the trading scheduler."""
    try:
        get_trading_scheduler().stop()
        return {
            "success": True,
            "message": "Trading scheduler stopped successfully"
        }
    except Exception as e:
        logger.error(f"Failed to stop scheduler: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# Direct trade execution endpoint
@app.post("/trades/execute")
async def execute_and_monitor_trades(request: Dict):
    """
    Execute and monitor trades directly using the scheduler's _execute_and_monitor_trades method.
    
    This endpoint allows direct testing of calendar spread entry and exit functionality.
    It works with all trades and monitoring functionality regardless of time or stock selection.
    
    Request body:
    {
        "order_type": "entry" | "exit",
        "trades": [
            {
                "ticker": "AAPL",                    # ← Use 'ticker', not 'symbol'
                "earnings_date": "2024-01-15",
                "earnings_time": "amc",              # ← Should be 'amc'
                "recommendation_score": 85,          # ← Add recommendation score
                "filters": {},                       # ← Add filters object
                "reasoning": "Direct endpoint test", # ← Add reasoning
                "status": "selected",                # ← Should be 'selected'
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            }
        ]
    }
    
    Note: The data format must match what the automated scheduler sends to the trade executor.
    """
    try:
        # Validate request
        if not request:
            raise HTTPException(status_code=400, detail="Request body is required")
        
        order_type = request.get('order_type')
        trades = request.get('trades')
        
        if not order_type:
            raise HTTPException(status_code=400, detail="order_type is required ('entry' or 'exit')")
        
        if order_type not in ['entry', 'exit']:
            raise HTTPException(status_code=400, detail="order_type must be 'entry' or 'exit'")
        
        if not trades or not isinstance(trades, list):
            raise HTTPException(status_code=400, detail="trades must be a non-empty list")
        
        if len(trades) == 0:
            raise HTTPException(status_code=400, detail="trades list cannot be empty")
        
        # Validate each trade has required fields
        for i, trade in enumerate(trades):
            if not isinstance(trade, dict):
                raise HTTPException(status_code=400, detail=f"Trade {i} must be a dictionary")
            
            if not trade.get('ticker'):  # ← Changed from 'symbol' to 'ticker'
                raise HTTPException(status_code=400, detail=f"Trade {i} must have a 'ticker' field")
            
            if order_type == 'entry' and not trade.get('earnings_date'):
                raise HTTPException(status_code=400, detail=f"Entry trade {i} must have an 'earnings_date' field")
            
            if order_type == 'exit' and not trade.get('trade_id'):
                raise HTTPException(status_code=400, detail=f"Exit trade {i} must have a 'trade_id' field")
        
        logger.info(f"🔍 Executing {len(trades)} {order_type} trades via direct endpoint")
        logger.info(f"   Trades: {[t.get('ticker') for t in trades]}")  # ← Changed from 'symbol' to 'ticker'
        
        # Get the scheduler and execute trades
        scheduler = get_trading_scheduler()
        
        # Call the private method directly
        # Note: This bypasses the normal job scheduling but maintains all execution and monitoring logic
        scheduler._execute_and_monitor_trades(trades, order_type)
        
        return {
            "success": True,
            "message": f"Successfully initiated execution and monitoring for {len(trades)} {order_type} trades",
            "data": {
                "order_type": order_type,
                "trade_count": len(trades),
                "symbols": [t.get('ticker') for t in trades],  # ← Changed from 'symbol' to 'ticker'
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to execute trades: {e}")
        logger.error(f"Stack trace: {traceback.format_exc()}")
        raise HTTPException(status_code=500, detail=f"Failed to execute trades: {str(e)}")

# Market data endpoints
@app.get("/market/price/{symbol}")
async def get_current_price(symbol: str):
    """Get current market price for a symbol."""
    try:
        # Validate input
        if not symbol:
            raise HTTPException(status_code=400, detail="Symbol is required")
        
        # Validate symbol format
        if not symbol.replace('-', '').replace('.', '').isalnum():
            raise HTTPException(status_code=400, detail="Invalid symbol format")
        
        price = alpaca_client.get_current_price(symbol.upper())
        if price is None:
            raise HTTPException(status_code=404, detail="Price not found")
        
        return {
            "success": True,
            "data": {
                "symbol": symbol.upper(),
                "price": price,
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get price for {symbol}: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/market/options/{symbol}")
async def discover_options(symbol: str, target_expiration: str = None):
    """Discover available options for a symbol using Alpaca API."""
    try:
        # Validate input
        if not symbol:
            raise HTTPException(status_code=400, detail="Symbol is required")
        
        # Validate symbol format
        if not symbol.replace('-', '').replace('.', '').isalnum():
            raise HTTPException(status_code=400, detail="Invalid symbol format")
        
        options_data = alpaca_client.discover_available_options(symbol.upper(), target_expiration)
        if not options_data:
            raise HTTPException(status_code=404, detail="Options not found")
        
        return {
            "success": True,
            "data": options_data
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to discover options for {symbol}: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/market/calendar-spread/{symbol}")
async def calculate_calendar_spread(symbol: str, short_exp: str, long_exp: str, option_type: str = "call"):
    """Calculate the cost of a calendar spread using call options."""
    try:
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            raise HTTPException(status_code=400, detail="Symbol, short_exp, and long_exp are required")
        
        # Validate symbol format
        if not symbol.replace('-', '').replace('.', '').isalnum():
            raise HTTPException(status_code=400, detail="Invalid symbol format")
        
        # For this strategy, we always use call options
        if option_type.lower() != 'call':
            logger.info(f"Strategy requires call options, but {option_type} was requested. Using calls.")
            option_type = 'call'
        
        spread_info = alpaca_client.calculate_calendar_spread_cost(
            symbol.upper(), short_exp, long_exp, option_type
        )
        
        if not spread_info:
            raise HTTPException(status_code=404, detail="Could not calculate calendar spread")
        
        return {
            "success": True,
            "data": spread_info,
            "strategy": "Call Calendar Spread",
            "description": f"Strategy: Sell {short_exp} call, buy {long_exp} call at strike {spread_info['strike_price']}"
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to calculate calendar spread for {symbol}: {e}")
        raise HTTPException(status_code=500, detail=str(e))





@app.get("/trades/selections")
async def get_trade_selections():
    """Get all current trade selections."""
    try:
        selections = get_trading_scheduler().database.get_trade_selections()
        return {
            "success": True,
            "data": selections
        }
    except Exception as e:
        logger.error(f"Failed to get trade selections: {e}")
        return {
            "success": False,
            "error": str(e)
        }

@app.get("/trades/selections/stats")
async def get_trade_selection_stats():
    """Get statistics about trade selections."""
    try:
        all_selections = get_trading_scheduler().database.get_trade_selections()
        selected_trades = get_trading_scheduler().database.get_selected_trades()
        
        stats = {
            "total_selections": len(all_selections),
            "selected_count": len(selected_trades),
            "total_selected": len([s for s in all_selections if s['is_selected']]),
            "total_deselected": len([s for s in all_selections if not s['is_selected']])
        }
        
        return {
            "success": True,
            "data": stats
        }
    except Exception as e:
        logger.error(f"Failed to get trade selection stats: {e}")
        return {
            "success": False,
            "error": str(e)
        }

# Manual trade execution removed - trades are now executed automatically by the scheduler


@app.delete("/trades/selections/clear-all")
async def clear_all_trade_selections():
    """Clear all trade selections (for testing/reset purposes)."""
    try:
        success = get_trading_scheduler().database.clear_all_trade_selections()
        if success:
            return {
                "success": True,
                "message": "All trade selections cleared"
            }
        else:
            return {
                "success": False,
                "error": "Failed to clear trade selections"
            }
    except Exception as e:
        logger.error(f"Failed to clear trade selections: {e}")
        return {
            "success": False,
            "error": str(e)
        }

@app.delete("/trades/selections/clear-manually-deselected")
async def clear_manually_deselected_stocks():
    """Clear manually deselected flags (for testing/reset purposes)."""
    try:
        success = get_trading_scheduler().database.clear_manually_deselected_stocks()
        if success:
            return {
                "success": True,
                "message": "Manually deselected flags cleared"
            }
        else:
            return {
                "success": False,
                "error": "Failed to clear manually deselected flags"
            }
    except Exception as e:
        logger.error(f"Failed to clear manually deselected flags: {e}")
        return {
            "success": False,
            "error": str(e)
        }

# Error handlers
@app.exception_handler(ValidationError)
async def validation_exception_handler(request: Request, exc: ValidationError):
    """Handle validation errors with detailed logging."""
    logger.error(f"🔍 Validation error in {request.method} {request.url}")
    logger.error(f"🔍 Request body: {await request.body()}")
    logger.error(f"🔍 Validation errors: {exc.errors()}")
    logger.error(f"🔍 Model: {exc.model}")
    return HTTPException(status_code=422, detail=f"Validation error: {exc.errors()}")

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Global exception handler."""
    logger.error(f"Unhandled exception: {exc}")
    return HTTPException(status_code=500, detail=str(exc))



@app.get("/debug/alpaca-activities")
async def debug_alpaca_activities():
    """Debug endpoint to see raw Alpaca account activities response."""
    try:
        activities = alpaca_client.get_account_activities(limit=5)
        return {
            "success": True,
            "data": activities,
            "count": len(activities)
        }
    except Exception as e:
        logger.error(f"Debug endpoint failed: {e}")
        return {
            "success": False,
            "error": str(e)
        }

@app.get("/debug/alpaca-positions")
async def debug_alpaca_positions():
    """Debug endpoint to see current Alpaca positions."""
    try:
        positions = alpaca_client.get_positions()
        return {
            "success": True,
            "data": positions,
            "count": len(positions)
        }
    except Exception as e:
        logger.error(f"Debug endpoint failed: {e}")
        return {
            "success": False,
            "error": str(e)
        }

# Removed: .env file update endpoint - no longer needed


