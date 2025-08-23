"""
Trading Safety Module
Provides decorators and safety checks to prevent accidental live trading during tests.
Simplified to use a single TESTING_MODE constant for all safety controls.
"""

import functools
import logging
from typing import Callable, Any, Dict
import config

logger = logging.getLogger(__name__)

class TradingSafetyError(Exception):
    """Exception raised when trading safety checks fail."""
    pass

def safe_trading_mode(func: Callable) -> Callable:
    """
    Unified decorator for trading safety checks.
    
    This decorator ensures that live trading operations cannot be executed
    when in testing mode, unless paper trading is enabled.
    
    Safety Logic:
    - TESTING_MODE = true: Only paper trading allowed (safe by default)
    - TESTING_MODE = false: Live trading allowed (production mode)
    """
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        try:
            # Get current trading mode
            current_creds = config.get_current_alpaca_credentials()
            paper_trading = current_creds['paper_trading']
            
            if config.TESTING_MODE:
                # In testing mode - only allow paper trading
                if not paper_trading:
                    error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades in testing mode"
                    logger.error(f"❌ {error_msg}")
                    raise TradingSafetyError(error_msg)
                else:
                    logger.debug(f"✅ Paper trading allowed in testing mode for {func.__name__}")
            else:
                # In production mode - live trading allowed
                logger.debug(f"✅ Production mode - live trading allowed for {func.__name__}")
            
            # If we get here, trading is allowed
            return func(*args, **kwargs)
            
        except Exception as e:
            if isinstance(e, TradingSafetyError):
                raise
            logger.error(f"❌ Error in trading safety check for {func.__name__}: {e}")
            raise TradingSafetyError(f"Trading safety check failed: {str(e)}")
    
    return wrapper

def require_paper_trading(func: Callable) -> Callable:
    """
    Decorator to require paper trading mode.
    
    This decorator ensures that the function can only run in paper trading mode.
    """
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        current_creds = config.get_current_alpaca_credentials()
        if not current_creds['paper_trading']:
            error_msg = f"PAPER TRADING REQUIRED: {func.__name__} can only run in paper trading mode"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        logger.debug(f"✅ Paper trading check passed for {func.__name__}")
        return func(*args, **kwargs)
    
    return wrapper

def require_live_trading(func: Callable) -> Callable:
    """
    Decorator to require live trading mode.
    
    This decorator ensures that the function can only run in live trading mode.
    """
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        current_creds = config.get_current_alpaca_credentials()
        if current_creds['paper_trading']:
            error_msg = f"LIVE TRADING REQUIRED: {func.__name__} can only run in live trading mode"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        if config.TESTING_MODE:
            error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades in testing mode"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        logger.debug(f"✅ Live trading check passed for {func.__name__}")
        return func(*args, **kwargs)
    
    return wrapper

def get_trading_safety_status() -> Dict[str, Any]:
    """
    Get comprehensive trading safety status.
    
    Returns:
        Dict containing current safety configuration and status.
    """
    try:
        current_creds = config.get_current_alpaca_credentials()
        
        return {
            'testing_mode': config.TESTING_MODE,
            'paper_trading': current_creds['paper_trading'],
            'live_trading': not current_creds['paper_trading'],
            'safety_level': 'HIGH' if config.TESTING_MODE else 'NORMAL',
            'trading_allowed': current_creds['paper_trading'] or not config.TESTING_MODE,
            'live_trading_allowed': not config.TESTING_MODE and not current_creds['paper_trading'],
            'paper_trading_allowed': current_creds['paper_trading'],
            'description': 'Safe mode - paper trading only' if config.TESTING_MODE else 'Production mode - live trading allowed'
        }
        
    except Exception as e:
        logger.error(f"Error getting trading safety status: {e}")
        return {
            'error': str(e),
            'testing_mode': config.TESTING_MODE,
            'safety_level': 'UNKNOWN'
        }
