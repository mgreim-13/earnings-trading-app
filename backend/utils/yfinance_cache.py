"""
YFinance caching and rate limiting wrapper to prevent API rate limits.
Implements intelligent caching, request delays, and retry logic.
"""

import time
import logging
from datetime import datetime, timedelta
from typing import Dict, Any, Optional, List
import yfinance as yf
from functools import wraps
import threading
import pandas as pd

logger = logging.getLogger(__name__)

class YFinanceCache:
    """Caching and rate limiting wrapper for yfinance."""
    
    def __init__(self, cache_dir: str = None, default_ttl: int = 300):
        """
        Initialize the cache.
        
        Args:
            cache_dir: Directory to store cache files (deprecated - using database caching)
            default_ttl: Default time-to-live for cache entries in seconds
        """
        self.cache_dir = cache_dir
        self.default_ttl = default_ttl
        self.memory_cache = {}
        self.last_request_time = {}
        self.request_lock = threading.Lock()
        self.min_request_interval = 0.1  # 100ms between requests
        
        # Note: File-based caching is deprecated in favor of database caching
        # Cache directory creation removed as it's no longer used
        
        logger.info(f"YFinance cache initialized with {default_ttl}s TTL (database caching enabled)")
    
    def _get_cache_key(self, symbol: str, method: str, **kwargs) -> str:
        """Generate a cache key for the request."""
        key_parts = [symbol, method]
        for k, v in sorted(kwargs.items()):
            key_parts.append(f"{k}={v}")
        return "_".join(key_parts)
    

    

    
    def _rate_limit(self, symbol: str) -> None:
        """Apply rate limiting per symbol."""
        with self.request_lock:
            now = time.time()
            last_time = self.last_request_time.get(symbol, 0)
            
            time_since_last = now - last_time
            if time_since_last < self.min_request_interval:
                sleep_time = self.min_request_interval - time_since_last
                logger.debug(f"Rate limiting {symbol}: sleeping {sleep_time:.3f}s")
                time.sleep(sleep_time)
            
            self.last_request_time[symbol] = time.time()
    
    def get_ticker_info(self, symbol: str, ttl: int = None) -> Optional[Dict]:
        """Get ticker info with memory caching only."""
        ttl = ttl or self.default_ttl
        
        # Check memory cache first
        cache_key = self._get_cache_key(symbol, "info")
        if cache_key in self.memory_cache:
            cached_data = self.memory_cache[cache_key]
            if time.time() - cached_data.get('timestamp', 0) < ttl:
                logger.debug(f"Memory cache hit for {symbol} info")
                return cached_data.get('data')
        
        # Apply rate limiting
        self._rate_limit(symbol)
        
        try:
            logger.debug(f"Fetching {symbol} info from yfinance")
            ticker = yf.Ticker(symbol)
            info = ticker.info
            
            # Store in memory cache
            self.memory_cache[cache_key] = {
                'data': info,
                'timestamp': time.time()
            }
            
            return info
            
        except Exception as e:
            logger.error(f"Failed to get ticker info for {symbol}: {e}")
            return None
    
    def get_ticker_history(self, symbol: str, period: str = "1y", start: str = None, 
                          end: str = None, ttl: int = None) -> Optional[pd.DataFrame]:
        """Get ticker history with memory caching only."""
        ttl = ttl or self.default_ttl
        
        # Create cache key
        cache_key = self._get_cache_key(symbol, "history", period=period, start=start, end=end)
        
        # Check memory cache first
        if cache_key in self.memory_cache:
            cached_data = self.memory_cache[cache_key]
            if time.time() - cached_data.get('timestamp', 0) < ttl:
                logger.debug(f"Memory cache hit for {symbol} history ({period})")
                return cached_data.get('data')
        
        # Apply rate limiting
        self._rate_limit(symbol)
        
        try:
            logger.debug(f"Fetching {symbol} history ({period}) from yfinance")
            ticker = yf.Ticker(symbol)
            
            if start and end:
                hist = ticker.history(start=start, end=end)
            else:
                hist = ticker.history(period=period)
            
            # Store in memory cache
            self.memory_cache[cache_key] = {
                'data': hist,
                'timestamp': time.time()
            }
            
            return hist
            
        except Exception as e:
            logger.error(f"Failed to get history for {symbol}: {e}")
            return None
    
    def get_options_chain(self, symbol: str, expiration: str, ttl: int = None) -> Optional[Dict]:
        """Get options chain with memory caching only."""
        ttl = ttl or self.default_ttl
        
        cache_key = self._get_cache_key(symbol, "options", expiration=expiration)
        
        # Check memory cache first
        if cache_key in self.memory_cache:
            cached_data = self.memory_cache[cache_key]
            if time.time() - cached_data.get('timestamp', 0) < ttl:
                logger.debug(f"Memory cache hit for {symbol} options ({expiration})")
                return cached_data.get('data')
        
        # Apply rate limiting
        self._rate_limit(symbol)
        
        try:
            logger.debug(f"Fetching {symbol} options ({expiration}) from yfinance")
            ticker = yf.Ticker(symbol)
            options = ticker.option_chain(expiration)
            
            # Convert to dictionary format
            result = {
                'calls': options.calls.to_dict('records') if not options.calls.empty else [],
                'puts': options.puts.to_dict('records') if not options.puts.empty else []
            }
            
            # Store in memory cache
            self.memory_cache[cache_key] = {
                'data': result,
                'timestamp': time.time()
            }
            
            return result
            
        except Exception as e:
            logger.error(f"Failed to get options for {symbol}: {e}")
            return None
    
    def get_expiration_dates(self, symbol: str, ttl: int = None) -> Optional[List[str]]:
        """Get expiration dates with memory caching only."""
        ttl = ttl or self.default_ttl
        
        cache_key = self._get_cache_key(symbol, "expirations")
        
        # Check memory cache first
        if cache_key in self.memory_cache:
            cached_data = self.memory_cache[cache_key]
            if time.time() - cached_data.get('timestamp', 0) < ttl:
                logger.debug(f"Memory cache hit for {symbol} expirations")
                return cached_data.get('data')
        
        # Apply rate limiting
        self._rate_limit(symbol)
        
        try:
            logger.debug(f"Fetching {symbol} expirations from yfinance")
            ticker = yf.Ticker(symbol)
            expirations = ticker.options
            
            if not expirations:
                return []
            
            # Store in memory cache
            self.memory_cache[cache_key] = {
                'data': expirations,
                'timestamp': time.time()
            }
            
            return expirations
            
        except Exception as e:
            logger.error(f"Failed to get expirations for {symbol}: {e}")
            return None
    
    def clear_cache(self, symbol: str = None) -> None:
        """Clear memory cache for a symbol or all symbols."""
        if symbol:
            # Clear cache for specific symbol
            keys_to_remove = [key for key in self.memory_cache.keys() if key.startswith(symbol + "_")]
            for key in keys_to_remove:
                del self.memory_cache[key]
            logger.info(f"Cleared memory cache for {symbol}")
        else:
            # Clear all memory cache
            self.memory_cache.clear()
            logger.info("Cleared all memory cache")

# Global instance
yf_cache = YFinanceCache()

# Retry decorator for API calls
def with_retry(max_retries: int = 3, delay: float = 1.0):
    """Decorator to add retry logic with exponential backoff."""
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            last_exception = None
            
            for attempt in range(max_retries):
                try:
                    return func(*args, **kwargs)
                except Exception as e:
                    last_exception = e
                    if attempt < max_retries - 1:
                        sleep_time = delay * (2 ** attempt)  # Exponential backoff
                        logger.warning(f"Attempt {attempt + 1} failed: {e}. Retrying in {sleep_time}s...")
                        time.sleep(sleep_time)
                    else:
                        logger.error(f"All {max_retries} attempts failed: {e}")
            
            raise last_exception
        return wrapper
    return decorator
