"""
Centralized cache service for scan results.
Provides a unified interface for caching and retrieving scan results across all endpoints.
"""

import logging
from typing import Dict, List, Optional
from core.database import Database
from .filters import compute_recommendation

logger = logging.getLogger(__name__)

class CacheService:
    def __init__(self):
        self.db = Database()
    
    def get_cached_scan_result(self, ticker: str, ttl_minutes: int = 5) -> Optional[Dict]:
        """Get cached scan result for a single ticker."""
        try:
            cached_results = self.db.get_cached_scan_results([ticker], ttl_minutes=ttl_minutes)
            return cached_results.get(ticker)
        except Exception as e:
            logger.error(f"Failed to get cached result for {ticker}: {e}")
            return None
    
    def get_cached_scan_results(self, tickers: List[str], ttl_minutes: int = 5) -> Dict[str, Dict]:
        """Get cached scan results for multiple tickers."""
        try:
            results = self.db.get_cached_scan_results(tickers, ttl_minutes)
            return results
        except Exception as e:
            logger.error(f"Failed to get cached results for {len(tickers)} tickers: {e}")
            return {}
    
    def get_or_compute_scan_result(self, ticker: str, earnings_date: str = '', ttl_minutes: int = 5) -> Dict:
        
        try:
            # Try to get from cache first
            cached_result = self.get_cached_scan_result(ticker, ttl_minutes)
            
            if cached_result and cached_result.get('result'):
                return cached_result.get('result')
            
            # Not in cache or expired, compute fresh result
            recommendation = compute_recommendation(ticker)
            
            if isinstance(recommendation, dict):
                scan_result = recommendation
                # Cache the successful result
                self._cache_scan_result(ticker, scan_result, earnings_date)
                return scan_result
            else:
                # Stock was skipped
                scan_result = {
                    'skip_reason': recommendation if isinstance(recommendation, str) else 'Unknown skip reason',
                    'total_score': 0,
                    'recommendation': 'skip'
                }
                # Cache the skip result
                self._cache_scan_result(ticker, scan_result, earnings_date)
                return scan_result
                
        except Exception as e:
            logger.error(f"Failed to get/compute scan result for {ticker}: {e}")
            # Return error result
            error_result = {
                'skip_reason': f'Scan failed: {str(e)}',
                'total_score': 0,
                'recommendation': 'skip'
            }
            # Try to cache the error result
            try:
                self._cache_scan_result(ticker, error_result, earnings_date)
            except:
                pass  # Don't fail if we can't cache the error
            return error_result
    
    def get_or_compute_scan_results(self, tickers_with_dates: List[Dict], ttl_minutes: int = 5) -> Dict[str, Dict]:
        """Get cached scan results or compute fresh ones if not cached."""
        try:
            if not tickers_with_dates:
                return {}
            
            # Extract ticker symbols
            tickers = [item['ticker'] for item in tickers_with_dates if item.get('ticker')]
            
            # Try to get from cache first
            cached_results = self.get_cached_scan_results(tickers, ttl_minutes)
            
            # Find which tickers need fresh computation
            tickers_to_compute = [ticker for ticker in tickers if ticker not in cached_results]
            
            # Compute fresh results for missing tickers
            fresh_results = {}
            for ticker in tickers_to_compute:
                try:
                    result = self.get_or_compute_scan_result(ticker, ttl_minutes=ttl_minutes)
                    fresh_results[ticker] = result
                        
                except Exception as e:
                    logger.error(f"Failed to compute scan result for {ticker}: {e}")
                    continue
            
            # Combine cached and fresh results
            all_results = {**cached_results, **fresh_results}
            
            return all_results
            
        except Exception as e:
            logger.error(f"Failed to get/compute scan results: {e}")
            return {}
    
    def _cache_scan_result(self, ticker: str, scan_result: Dict, earnings_date: str = ''):
        """Internal method to cache a scan result."""
        try:
            cache_data = scan_result.copy()
            cache_data.update({
                'earnings_date': earnings_date,
                'earnings_time': 'amc',  # Default earnings time
                'recommendation_score': scan_result.get('total_score', 0),
                'filters': scan_result.get('scores', {}),  # Store scores in filters column
                'reasoning': scan_result.get('reasoning', ''),
                'ticker': ticker
            })
            
            success = self.db.add_scan_result(cache_data)
            if success:
                logger.info(f"✅ Cached scan result for {ticker}")
            return success
                
        except Exception as e:
            logger.error(f"Failed to cache scan result for {ticker}: {e}")
            return False
    
    def clear_cache(self, older_than_minutes: int = None):
        """Clear cached results, optionally only those older than specified minutes."""
        try:
            if older_than_minutes:
                # Clear only old entries (would need to implement in database)
                logger.info(f"Clearing cache entries older than {older_than_minutes} minutes")
                # For now, just clear all
                self.clear_cache()
            else:
                # Clear all entries using database abstraction
                success = self.db.clear_scan_results()
                if success:
                    logger.info("Cleared all cached scan results")
                else:
                    logger.warning("Failed to clear scan results")
        except Exception as e:
            logger.error(f"Failed to clear cache: {e}")
    
    def get_cache_stats(self) -> Dict:
        """Get cache statistics."""
        try:
            # Use database abstraction instead of direct SQLite access
            stats = self.db.get_scan_results_stats()
            if stats:
                return stats
            else:
                return {'error': 'Failed to get cache statistics'}
                
        except Exception as e:
            logger.error(f"Failed to get cache stats: {e}")
            return {'error': str(e)}

# Global cache service instance
cache_service = CacheService()
