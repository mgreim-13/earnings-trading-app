"""
Configuration file for the Earnings Calendar Spread Trading Application.
Contains all configurable parameters, API keys, and trading settings.
"""

import os
import logging
from dotenv import load_dotenv
from pathlib import Path

# Configure logger
logger = logging.getLogger(__name__)

# Load environment variables
logger.info("🔍 Loading environment variables...")

# Load from .env file if it exists
env_path = Path(__file__).parent / '.env'
if env_path.exists():
    logger.info(f"   Found .env file at: {env_path}")
    load_dotenv(env_path)
    logger.info("   .env file loaded")
else:
    logger.info("   No .env file found")

# Load from system environment
logger.info("   Loading from system environment...")

# Validate required environment variables
def validate_environment():
    """Validate that required environment variables are set."""
    required_vars = [
        'PAPER_ALPACA_API_KEY',
        'PAPER_ALPACA_SECRET_KEY',
        'LIVE_ALPACA_API_KEY', 
        'LIVE_ALPACA_SECRET_KEY'
    ]
    
    missing_vars = []
    for var in required_vars:
        if not os.getenv(var):
            missing_vars.append(var)
    
    if missing_vars:
        logger.warning(f"⚠️ Missing environment variables: {missing_vars}")
        logger.warning("   Some features may not work properly")
    else:
        logger.info("✅ All required environment variables are set")

# Run validation
validate_environment()

# CRITICAL: Log the exact environment variables at startup
logger.info("🔍 ENVIRONMENT VARIABLES AT STARTUP:")
logger.info(f"   PAPER_ALPACA_API_KEY: '{os.getenv('PAPER_ALPACA_API_KEY', 'Not set')[:8] if os.getenv('PAPER_ALPACA_API_KEY') else 'Not set'}...'")
logger.info(f"   PAPER_ALPACA_SECRET_KEY: '{os.getenv('PAPER_ALPACA_SECRET_KEY', 'Not set')[:8] if os.getenv('PAPER_ALPACA_SECRET_KEY') else 'Not set'}...'")
logger.info(f"   LIVE_ALPACA_API_KEY: '{os.getenv('LIVE_ALPACA_API_KEY', 'Not set')[:8] if os.getenv('LIVE_ALPACA_API_KEY') else 'Not set'}...'")
logger.info(f"   LIVE_ALPACA_SECRET_KEY: '{os.getenv('LIVE_ALPACA_SECRET_KEY', 'Not set')[:8] if os.getenv('LIVE_ALPACA_SECRET_KEY') else 'Not set'}...'")

# CRITICAL: Log the exact lengths at startup
logger.info("🔍 ENVIRONMENT VARIABLE LENGTHS AT STARTUP:")
logger.info(f"   PAPER_ALPACA_API_KEY length: {len(os.getenv('PAPER_ALPACA_API_KEY', '') or '')}")
logger.info(f"   PAPER_ALPACA_SECRET_KEY length: {len(os.getenv('PAPER_ALPACA_SECRET_KEY', '') or '')}")
logger.info(f"   LIVE_ALPACA_API_KEY length: {len(os.getenv('LIVE_ALPACA_API_KEY', '') or '')}")
logger.info(f"   LIVE_ALPACA_SECRET_KEY length: {len(os.getenv('LIVE_ALPACA_SECRET_KEY', '') or '')}")

# Alpaca API credentials - separate for paper and live
PAPER_ALPACA_API_KEY = os.getenv("PAPER_ALPACA_API_KEY")
PAPER_ALPACA_SECRET_KEY = os.getenv("PAPER_ALPACA_SECRET_KEY")
LIVE_ALPACA_API_KEY = os.getenv("LIVE_ALPACA_API_KEY")
LIVE_ALPACA_SECRET_KEY = os.getenv("LIVE_ALPACA_SECRET_KEY")

# CRITICAL: Log the exact values assigned to config variables
logger.info("🔍 CONFIG VARIABLES ASSIGNED:")
logger.info(f"   PAPER_ALPACA_API_KEY: '{PAPER_ALPACA_API_KEY[:8] if PAPER_ALPACA_API_KEY else 'None'}...'")
logger.info(f"   PAPER_ALPACA_SECRET_KEY: '{PAPER_ALPACA_SECRET_KEY[:8] if PAPER_ALPACA_SECRET_KEY else 'None'}...'")
logger.info(f"   LIVE_ALPACA_API_KEY: '{LIVE_ALPACA_API_KEY[:8] if LIVE_ALPACA_API_KEY else 'None'}...'")
logger.info(f"   LIVE_ALPACA_SECRET_KEY: '{LIVE_ALPACA_SECRET_KEY[:8] if LIVE_ALPACA_SECRET_KEY else 'None'}...'")

# CRITICAL: Log the exact lengths assigned to config variables
logger.info("🔍 CONFIG VARIABLE LENGTHS ASSIGNED:")
logger.info(f"   PAPER_ALPACA_API_KEY length: {len(PAPER_ALPACA_API_KEY) if PAPER_ALPACA_API_KEY else 0}")
logger.info(f"   PAPER_ALPACA_SECRET_KEY length: {len(PAPER_ALPACA_SECRET_KEY) if PAPER_ALPACA_SECRET_KEY else 0}")
logger.info(f"   LIVE_ALPACA_API_KEY length: {len(LIVE_ALPACA_API_KEY) if LIVE_ALPACA_API_KEY else 0}")
logger.info(f"   LIVE_ALPACA_SECRET_KEY length: {len(LIVE_ALPACA_SECRET_KEY) if LIVE_ALPACA_SECRET_KEY else 0}")

logger.info("🔍 Environment variables loaded")
logger.info(f"   PAPER_ALPACA_API_KEY: {PAPER_ALPACA_API_KEY[:8] if PAPER_ALPACA_API_KEY else 'None'}...")
logger.info(f"   PAPER_ALPACA_SECRET_KEY: {PAPER_ALPACA_SECRET_KEY[:8] if PAPER_ALPACA_SECRET_KEY else 'None'}...")
logger.info(f"   LIVE_ALPACA_API_KEY: {LIVE_ALPACA_API_KEY[:8] if LIVE_ALPACA_API_KEY else 'None'}...")
logger.info(f"   LIVE_ALPACA_SECRET_KEY: {LIVE_ALPACA_SECRET_KEY[:8] if LIVE_ALPACA_SECRET_KEY else 'None'}...")

# Try to load from database if environment variables are not set
if not LIVE_ALPACA_API_KEY or not PAPER_ALPACA_API_KEY:
    logger.info("🔍 Some credentials missing from environment, trying database...")
    try:
        from repositories.settings_repository import SettingsRepository
        repo = SettingsRepository()
        
        # Load from database
        db_live_key = repo.get_setting('alpaca_live_api_key')
        db_live_secret = repo.get_setting('alpaca_live_secret_key')
        db_paper_key = repo.get_setting('alpaca_paper_api_key')
        db_paper_secret = repo.get_setting('alpaca_paper_secret_key')
        
        # Update environment variables if they're missing
        if not LIVE_ALPACA_API_KEY and db_live_key:
            LIVE_ALPACA_API_KEY = db_live_key
            logger.info(f"   Loaded live API key from database: {db_live_key[:8]}...")
        
        if not LIVE_ALPACA_SECRET_KEY and db_live_secret:
            LIVE_ALPACA_SECRET_KEY = db_live_secret
            logger.info(f"   Loaded live secret from database: {db_live_secret[:8]}...")
        
        if not PAPER_ALPACA_API_KEY and db_paper_key:
            PAPER_ALPACA_API_KEY = db_paper_key
            logger.info(f"   Loaded paper API key from database: {db_paper_key[:8]}...")
        
        if not PAPER_ALPACA_SECRET_KEY and db_paper_secret:
            PAPER_ALPACA_SECRET_KEY = db_paper_secret
            logger.info(f"   Loaded paper secret from database: {db_paper_secret[:8]}...")
            
    except Exception as e:
        logger.warning(f"   Failed to load from database: {e}")
        logger.warning("   Continuing with environment variables only")

# Function to get current API credentials based on trading mode
def get_current_alpaca_credentials():
    """Get current Alpaca API credentials based on trading mode."""
    logger.info("🔍 Config: Getting current Alpaca credentials...")
    
    # Dynamically get the current trading mode from database
    current_paper_trading = get_current_paper_trading_mode()
    logger.info(f"   Current PAPER_TRADING_ENABLED from DB: {current_paper_trading}")
    logger.info(f"   PAPER_ALPACA_API_KEY: {PAPER_ALPACA_API_KEY[:8] if PAPER_ALPACA_API_KEY else 'None'}...")
    logger.info(f"   LIVE_ALPACA_API_KEY: {LIVE_ALPACA_API_KEY[:8] if LIVE_ALPACA_API_KEY else 'None'}...")
    
    # CRITICAL: Log the exact lengths of all credentials
    logger.info("🔍 CREDENTIAL LENGTHS IN CONFIG MODULE:")
    logger.info(f"   PAPER_ALPACA_API_KEY length: {len(PAPER_ALPACA_API_KEY) if PAPER_ALPACA_API_KEY else 0}")
    logger.info(f"   PAPER_ALPACA_SECRET_KEY length: {len(PAPER_ALPACA_SECRET_KEY) if PAPER_ALPACA_SECRET_KEY else 0}")
    logger.info(f"   LIVE_ALPACA_API_KEY length: {len(LIVE_ALPACA_API_KEY) if LIVE_ALPACA_API_KEY else 0}")
    logger.info(f"   LIVE_ALPACA_SECRET_KEY length: {len(LIVE_ALPACA_SECRET_KEY) if LIVE_ALPACA_SECRET_KEY else 0}")
    
    # CRITICAL: Log the first few characters of each credential for debugging
    logger.info("🔍 CREDENTIAL PARTIAL CONTENT IN CONFIG MODULE:")
    logger.info(f"   PAPER_ALPACA_API_KEY: '{PAPER_ALPACA_API_KEY[:8] if PAPER_ALPACA_API_KEY else 'None'}...'")
    logger.info(f"   PAPER_ALPACA_SECRET_KEY: '{PAPER_ALPACA_SECRET_KEY[:8] if PAPER_ALPACA_SECRET_KEY else 'None'}...'")
    logger.info(f"   LIVE_ALPACA_API_KEY: '{LIVE_ALPACA_API_KEY[:8] if LIVE_ALPACA_API_KEY else 'None'}...'")
    logger.info(f"   LIVE_ALPACA_SECRET_KEY: '{LIVE_ALPACA_SECRET_KEY[:8] if LIVE_ALPACA_SECRET_KEY else 'None'}...'")
    
    if current_paper_trading:
        # Use paper trading credentials
        api_key = PAPER_ALPACA_API_KEY
        secret_key = PAPER_ALPACA_SECRET_KEY
        base_url = "https://paper-api.alpaca.markets"
        logger.info("   Using PAPER trading mode")
    else:
        # Use live trading credentials
        api_key = LIVE_ALPACA_API_KEY
        secret_key = LIVE_ALPACA_SECRET_KEY
        base_url = "https://api.alpaca.markets"
        logger.info("   Using LIVE trading mode")
    
    # CRITICAL: Log the final selected credentials (partial for security)
    logger.info("🔍 FINAL SELECTED CREDENTIALS:")
    logger.info(f"   Final API key: '{api_key[:8] if api_key else 'None'}...'")
    logger.info(f"   Final secret key: '{secret_key[:8] if secret_key else 'None'}...'")
    logger.info(f"   Final base URL: '{base_url}'")
    logger.info(f"   Final paper trading flag: {current_paper_trading}")
    
    logger.info(f"   Selected API key: {api_key[:8] if api_key else 'None'}...")
    logger.info(f"   Selected base URL: {base_url}")
    
    result = {
        'api_key': api_key,
        'secret_key': secret_key,
        'base_url': base_url,
        'paper_trading': current_paper_trading
    }
    
    logger.info(f"✅ Credentials retrieved successfully")
    return result

# Function to get current paper trading mode from database
def get_current_paper_trading_mode():
    """Get current paper trading mode from database, fallback to environment variable."""
    try:
        from repositories.settings_repository import SettingsRepository
        repo = SettingsRepository()
        db_paper_trading = repo.get_setting('paper_trading_enabled')
        
        if db_paper_trading is not None:
            # Convert string to boolean
            current_mode = db_paper_trading.lower() == 'true'
            logger.info(f"🔍 Database paper_trading_enabled: '{db_paper_trading}' -> {current_mode}")
            return current_mode
        else:
            # Default to paper trading if no setting found
            logger.info(f"🔍 No database setting found, defaulting to paper trading (True)")
            return True
            
    except Exception as e:
        logger.warning(f"🔍 Failed to read from database: {e}, defaulting to paper trading (True)")
        return True

# Function to get current data URL (always the same)
def get_current_data_url():
    """Get current Alpaca data URL."""
    return "https://data.alpaca.markets"

FINNHUB_API_KEY = os.getenv("FINNHUB_API_KEY")

# Trading Configuration - Now controlled by UI/database
# AUTO_TRADING_ENABLED and RISK_PERCENTAGE are managed via the settings page

# Refined Calendar Spread Execution Rules
CALENDAR_SPREAD_RULES = {
    'entry_time': '15:45',  # 3:45 PM ET
    'exit_time': '09:45',   # 9:45 AM ET
    'entry_timing_tolerance': 60,  # 1 minute tolerance for entry timing
    'exit_timing_tolerance': 60,   # 1 minute tolerance for exit timing
    'polling_interval': 30,  # 30 seconds between price updates
    'max_monitoring_time': 8 * 60,  # 8 minutes maximum monitoring

    'entry_slippage_protection': 0.05,  # 5% slippage protection for entry
    'exit_slippage_protection': 0.10,   # 10% slippage protection for exit
    'market_order_monitoring_time': 2 * 60,  # 2 minutes for market order monitoring
    'force_market_exit_deadline': 10,  # 10 minutes after 9:45 AM (9:55 AM)
    'market_close_protection_buffer': 5,  # 5 minutes before market close (3:55 PM)
    'exit_deadline_buffer': 7,  # 7 minutes before 9:45 AM exit deadline (9:38 AM)
}

# Server Configuration
SERVER_HOST = os.getenv("SERVER_HOST", "0.0.0.0")
SERVER_PORT = int(os.getenv("SERVER_PORT", "8000"))
API_TIMEOUT = int(os.getenv("API_TIMEOUT", "300"))  # 5 minutes in seconds
FINNHUB_TIMEOUT = int(os.getenv("FINNHUB_TIMEOUT", "30"))  # 30 seconds for Finnhub

# Test Configuration
TESTING_MODE = os.getenv("TESTING_MODE", "false").lower() == "true"
LIVE_TRADING_ALLOWED = os.getenv("LIVE_TRADING_ALLOWED", "false").lower() == "true"
PREVENT_LIVE_TRADING_IN_TESTS = os.getenv("PREVENT_LIVE_TRADING_IN_TESTS", "true").lower() == "true"

# Filter Weights for Scoring
FILTER_WEIGHTS = {
    'avg_volume': 0.11,
    'iv30_rv30': 0.20,
    'ts_slope_0_45': 0.17,
    'hist_earn_vol': 0.13,
    'option_liquidity': 0.18,
    'iv_percentile': 0.10,
    'beta': 0.04,
    'short': 0.04,
    'rsi': 0.02,
    'analyst': 0.01
}

# Scoring Thresholds
SCORING_THRESHOLDS = {
    'recommended': 80.0,    # Score above this is recommended
    'consider': 60.0,        # Score above this is worth considering
    'marginal_margin': 0.1,  # 10% margin for partial credit
    'rsi_neutral_zone': 10   # RSI within 10 points of 50 gets partial credit
}

# Auto-selection threshold for scanner
AUTO_SELECT_THRESHOLD = 80.0  # Stocks with score >= 80 are automatically selected

# Liquidity Requirements
LIQUIDITY_REQUIREMENTS = {
    'min_option_liquidity_score': 0.6,  # Stocks below this are automatically marked as "avoid"
    'liquidity_override_enabled': True   # Whether to enforce the liquidity requirement
}

# Market Cap Tiers and Thresholds
MARKET_CAP_TIERS = {
    'large': {  # > $10B
        'min_avg_volume': 1000000,
        'min_iv_rv_ratio': 1.2,
        'max_ts_slope': -0.00406,
        'max_hist_earn_move': 8.0,
        'min_atm_oi': 500,
        'min_opt_volume': 100,
        'max_opt_spread': 0.15,
        'min_iv_percentile': 50,
        'max_beta': 1.5,
        'max_short_pct': 5.0,
        'rsi_lower': 35,
        'rsi_upper': 65,
        'max_analyst_rec': 2.0
    },
    'mid': {    # $2B - $10B
        'min_avg_volume': 500000,
        'min_iv_rv_ratio': 1.3,
        'max_ts_slope': -0.003,
        'max_hist_earn_move': 12.0,
        'min_atm_oi': 300,
        'min_opt_volume': 50,
        'max_opt_spread': 0.20,
        'min_iv_percentile': 45,
        'max_beta': 1.8,
        'max_short_pct': 8.0,
        'rsi_lower': 30,
        'rsi_upper': 70,
        'max_analyst_rec': 2.5
    },
    'small': {  # < $2B
        'min_avg_volume': 200000,
        'min_iv_rv_ratio': 1.4,
        'max_ts_slope': -0.002,
        'max_hist_earn_move': 15.0,
        'min_atm_oi': 100,
        'min_opt_volume': 25,
        'max_opt_spread': 0.25,
        'min_iv_percentile': 40,
        'max_beta': 2.2,
        'max_short_pct': 12.0,
        'rsi_lower': 25,
        'rsi_upper': 75,
        'max_analyst_rec': 3.0
    }
}

# Sector Adjustments
SECTOR_ADJUSTMENTS = {
    'Technology': {
        'min_iv_rv_ratio': 1.1,  # Tech stocks often have higher IV
        'max_beta': 1.8,
        'rsi_upper': 70
    },
    'Healthcare': {
        'min_iv_rv_ratio': 1.4,  # Healthcare often has higher volatility
        'max_hist_earn_move': 18.0,
        'max_beta': 2.0
    },
    'Financial Services': {
        'min_iv_rv_ratio': 1.2,
        'max_beta': 1.6,
        'rsi_lower': 30,
        'rsi_upper': 70
    },
    'Energy': {
        'min_iv_rv_ratio': 1.5,
        'max_hist_earn_move': 20.0,
        'max_beta': 2.5
    },
    'Consumer Cyclical': {
        'min_iv_rv_ratio': 1.3,
        'max_beta': 1.7
    },
    'default': {}
}

# Log final configuration summary
logger.info("=" * 60)
logger.info("🔧 FINAL CONFIGURATION SUMMARY")
logger.info("=" * 60)
logger.info(f"🔧 LIVE_ALPACA_API_KEY: {LIVE_ALPACA_API_KEY}")
logger.info(f"🔧 LIVE_ALPACA_SECRET_KEY: {LIVE_ALPACA_SECRET_KEY[:8] if LIVE_ALPACA_SECRET_KEY and len(LIVE_ALPACA_SECRET_KEY) > 12 else '***'}...{LIVE_ALPACA_SECRET_KEY[-4:] if LIVE_ALPACA_SECRET_KEY and len(LIVE_ALPACA_SECRET_KEY) > 12 else '***'}")
logger.info(f"🔧 PAPER_ALPACA_API_KEY: {PAPER_ALPACA_API_KEY}")
logger.info(f"🔧 PAPER_ALPACA_SECRET_KEY: {PAPER_ALPACA_SECRET_KEY[:8] if PAPER_ALPACA_SECRET_KEY and len(PAPER_ALPACA_SECRET_KEY) > 12 else '***'}...{PAPER_ALPACA_SECRET_KEY[-4:] if PAPER_ALPACA_SECRET_KEY and len(PAPER_ALPACA_SECRET_KEY) > 12 else '***'}")
logger.info(f"🔧 SERVER_HOST: {SERVER_HOST}")
logger.info(f"🔧 SERVER_PORT: {SERVER_PORT}")
logger.info("=" * 60)
