"""
Trading Safety Module
Provides decorators and safety checks to prevent accidental live trading during tests.
"""

import functools
import logging
from typing import Callable, Any
import config

logger = logging.getLogger(__name__)

class TradingSafetyError(Exception):
    """Exception raised when trading safety checks fail."""
    pass

def prevent_live_trading_in_tests(func: Callable) -> Callable:
    """
    Decorator to prevent live trading during testing.
    
    This decorator ensures that live trading operations cannot be executed
    during testing, even if the function is called.
    """
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        # Check if we're in testing mode
        if config.TESTING_MODE:
            logger.warning(f"⚠️ TESTING MODE: {func.__name__} called during testing")
            
            # If we're preventing live trading in tests, check trading mode
            current_creds = config.get_current_alpaca_credentials()
            if config.PREVENT_LIVE_TRADING_IN_TESTS and not current_creds['paper_trading']:
                error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades during testing"
                logger.error(f"❌ {error_msg}")
                raise TradingSafetyError(error_msg)
            
            # If live trading is not allowed, block only for live trading mode
            current_creds = config.get_current_alpaca_credentials()
            if not current_creds['paper_trading'] and not config.LIVE_TRADING_ALLOWED:
                error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades (LIVE_TRADING_ALLOWED=false)"
                logger.error(f"❌ {error_msg}")
                raise TradingSafetyError(error_msg)
        
        # Check if we're trying to prevent live trading in tests
        current_creds = config.get_current_alpaca_credentials()
        if config.PREVENT_LIVE_TRADING_IN_TESTS and not current_creds['paper_trading']:
            error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades (PREVENT_LIVE_TRADING_IN_TESTS=true)"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        # Check if live trading is not allowed (only for live trading mode)
        current_creds = config.get_current_alpaca_credentials()
        if not current_creds['paper_trading'] and not config.LIVE_TRADING_ALLOWED:
            error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades (LIVE_TRADING_ALLOWED=false)"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        # If we get here, trading is allowed
        logger.debug(f"✅ Trading safety check passed for {func.__name__}")
        return func(*args, **kwargs)
    
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
        
        if not config.LIVE_TRADING_ALLOWED:
            error_msg = f"LIVE TRADING BLOCKED: {func.__name__} cannot execute live trades (LIVE_TRADING_ALLOWED=false)"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        logger.debug(f"✅ Live trading check passed for {func.__name__}")
        return func(*args, **kwargs)
    
    return wrapper

def safe_trading_mode(func: Callable) -> Callable:
    """
    Decorator to ensure safe trading mode based on configuration.
    
    This decorator automatically chooses the appropriate safety level:
    - If in testing mode: applies prevent_live_trading_in_tests
    - If paper trading: allows execution
    - If live trading: checks LIVE_TRADING_ALLOWED
    """
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        # Apply the appropriate safety check
        if config.TESTING_MODE:
            # In testing mode, be extra careful
            current_creds = config.get_current_alpaca_credentials()
            if config.PREVENT_LIVE_TRADING_IN_TESTS and not current_creds['paper_trading']:
                error_msg = f"TESTING MODE: {func.__name__} blocked - live trading not allowed during tests"
                logger.error(f"❌ {error_msg}")
                raise TradingSafetyError(error_msg)
        
        # Check live trading permissions
        current_creds = config.get_current_alpaca_credentials()
        if not current_creds['paper_trading'] and not config.LIVE_TRADING_ALLOWED:
            error_msg = f"TRADING BLOCKED: {func.__name__} cannot execute trades (LIVE_TRADING_ALLOWED=false)"
            logger.error(f"❌ {error_msg}")
            raise TradingSafetyError(error_msg)
        
        logger.debug(f"✅ Safe trading check passed for {func.__name__}")
        return func(*args, **kwargs)
    
    return wrapper

def get_trading_safety_status() -> dict:
    """
    Get the current trading safety status.
    
    Returns:
        dict: Current safety configuration and status
    """
    current_creds = config.get_current_alpaca_credentials()
    return {
        'paper_trading_enabled': current_creds['paper_trading'],
        'testing_mode': config.TESTING_MODE,
        'live_trading_allowed': config.LIVE_TRADING_ALLOWED,
        'prevent_live_trading_in_tests': config.PREVENT_LIVE_TRADING_IN_TESTS,
        'current_mode': 'PAPER' if current_creds['paper_trading'] else 'LIVE',
        'trading_allowed': current_creds['paper_trading'] or config.LIVE_TRADING_ALLOWED,
        'safety_level': 'HIGH' if config.TESTING_MODE else 'NORMAL'
    }
