"""
Scan Repository
Handles all scan result-related database operations.
"""

import logging
import json
from datetime import datetime
from typing import List, Dict, Optional
from .base_repository import BaseRepository

logger = logging.getLogger(__name__)

class ScanRepository(BaseRepository):
    """Repository for scan result-related database operations."""
    
    def add_scan_result(self, scan_data: Dict) -> bool:
        """Add a new scan result to the database."""
        try:
            query = """
                INSERT INTO scan_results (
                    ticker, earnings_date, earnings_time, recommendation_score, 
                    filters, reasoning
                ) VALUES (?, ?, ?, ?, ?, ?)
            """
            
            # Convert filters dict to JSON string
            filters_json = json.dumps(scan_data.get('filters', {}))
            
            params = (
                scan_data['ticker'],
                scan_data['earnings_date'],
                scan_data['earnings_time'],
                scan_data['recommendation_score'],
                filters_json,
                scan_data.get('reasoning', '')
            )
            
            success = self.execute_update(query, params)
            if success:
                logger.info(f"✅ Added scan result for {scan_data['ticker']}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to add scan result: {e}")
            return False
    
    def get_recent_scan_results(self, days: int = 7) -> List[Dict]:
        """Get recent scan results from the last N days."""
        try:
            query = """
                SELECT * FROM scan_results 
                WHERE scanned_at >= datetime('now', 'localtime', ? || ' days')
                ORDER BY scanned_at DESC
            """
            
            results = self.execute_query(query, (f'-{days}',))
            
            # Parse filters JSON back to dict
            for result in results:
                if result.get('filters'):
                    try:
                        filters_data = json.loads(result['filters'])
                        # Map filters back to scores for frontend compatibility
                        result['scores'] = filters_data
                    except json.JSONDecodeError:
                        result['filters'] = {}
                        result['scores'] = {}
                else:
                    result['scores'] = {}
                
                # Map recommendation_score to total_score for frontend compatibility
                result['total_score'] = result.get('recommendation_score')
            
            return results
            
        except Exception as e:
            logger.error(f"❌ Failed to get recent scan results: {e}")
            return []
    
    def get_latest_scan_result(self, ticker: str) -> Optional[Dict]:
        """Get the latest scan result for a specific ticker."""
        try:
            query = """
                SELECT * FROM scan_results 
                WHERE ticker = ? 
                ORDER BY scanned_at DESC 
                LIMIT 1
            """
            
            results = self.execute_query(query, (ticker,))
            if not results:
                return None
            
            result = results[0]
            
            # Parse filters JSON back to dict and map to scores
            if result.get('filters'):
                try:
                    filters_data = json.loads(result['filters'])
                    # Map filters back to scores for frontend compatibility
                    result['scores'] = filters_data
                except json.JSONDecodeError:
                    result['filters'] = {}
                    result['scores'] = {}
            else:
                result['scores'] = {}
            
            # Map recommendation_score to total_score for frontend compatibility
            result['total_score'] = result.get('recommendation_score')
            
            return result
            
        except Exception as e:
            logger.error(f"❌ Failed to get latest scan result for {ticker}: {e}")
            return None
    
    def get_cached_scan_results(self, tickers: List[str], ttl_minutes: int = 5) -> Dict[str, Dict]:
        """Get cached scan results for multiple tickers within TTL."""
        try:
            if not tickers:
                return {}
            
            # Build placeholders for IN clause
            placeholders = ','.join(['?' for _ in tickers])
            
            query = f"""
                SELECT * FROM scan_results 
                WHERE ticker IN ({placeholders})
                AND scanned_at >= datetime('now', 'localtime', '-{ttl_minutes} minutes')
                ORDER BY scanned_at DESC
            """
            
            results = self.execute_query(query, tuple(tickers))
            
            # Group by ticker and get latest for each
            ticker_results = {}
            for result in results:
                ticker = result['ticker']
                if ticker not in ticker_results or result['scanned_at'] > ticker_results[ticker]['scanned_at']:
                    # Parse filters JSON back to dict and map to scores
                    if result.get('filters'):
                        try:
                            filters_data = json.loads(result['filters'])
                            # Map filters back to scores for frontend compatibility
                            result['scores'] = filters_data
                        except json.JSONDecodeError:
                            result['filters'] = {}
                            result['scores'] = {}
                    else:
                        result['scores'] = {}
                    
                    # Map recommendation_score to total_score for frontend compatibility
                    result['total_score'] = result.get('recommendation_score')
                    
                    ticker_results[ticker] = result
            
            return ticker_results
            
        except Exception as e:
            logger.error(f"❌ Failed to get cached scan results: {e}")
            return {}
    
    def clear_scan_results(self) -> bool:
        """Clear all scan results from the database."""
        try:
            query = "DELETE FROM scan_results"
            success = self.execute_update(query)
            
            if success:
                logger.info("✅ Cleared all scan results")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to clear scan results: {e}")
            return False
    
    def get_scan_results_stats(self) -> Dict:
        """Get statistics about scan results."""
        try:
            stats = {}
            
            # Total count
            total_query = "SELECT COUNT(*) FROM scan_results"
            stats['total_results'] = self.execute_scalar(total_query) or 0
            
            # Recent count (last 24 hours)
            recent_query = """
                SELECT COUNT(*) FROM scan_results 
                WHERE scanned_at >= datetime('now', 'localtime', '-1 day')
            """
            stats['recent_results'] = self.execute_scalar(recent_query) or 0
            
            # Unique tickers
            unique_query = "SELECT COUNT(DISTINCT ticker) FROM scan_results"
            stats['unique_tickers'] = self.execute_scalar(unique_query) or 0
            
            # Average score
            avg_query = "SELECT AVG(recommendation_score) FROM scan_results"
            avg_score = self.execute_scalar(avg_query)
            stats['average_score'] = round(avg_score, 2) if avg_score else 0
            
            # Score distribution
            score_dist_query = """
                SELECT 
                    CASE 
                        WHEN recommendation_score >= 80 THEN 'high'
                        WHEN recommendation_score >= 60 THEN 'medium'
                        ELSE 'low'
                    END as score_range,
                    COUNT(*) as count
                FROM scan_results 
                GROUP BY score_range
            """
            score_dist = self.execute_query(score_dist_query)
            
            stats['score_distribution'] = {
                'high': 0,
                'medium': 0,
                'low': 0
            }
            
            for row in score_dist:
                score_range = row['score_range']
                count = row['count']
                if score_range in stats['score_distribution']:
                    stats['score_distribution'][score_range] = count
            
            return stats
            
        except Exception as e:
            logger.error(f"❌ Failed to get scan results stats: {e}")
            return {}
    
    def cleanup_old_scan_results(self, cutoff_date: datetime) -> int:
        """Clean up old scan results."""
        try:
            query = """
                DELETE FROM scan_results 
                WHERE scanned_at < ?
            """
            success = self.execute_update(query, (cutoff_date.isoformat(),))
            
            if success:
                # Get count of deleted records
                count_query = """
                    SELECT COUNT(*) FROM scan_results 
                    WHERE scanned_at < ?
                """
                count = self.execute_scalar(count_query, (cutoff_date.isoformat(),))
                deleted_count = count if count else 0
                logger.info(f"✅ Cleaned up {deleted_count} old scan results")
                return deleted_count
            else:
                return 0
                
        except Exception as e:
            logger.error(f"❌ Failed to cleanup old scan results: {e}")
            return 0
    
    def get_scan_results_by_date_range(self, start_date: datetime, end_date: datetime) -> List[Dict]:
        """Get scan results within a specific date range."""
        try:
            query = """
                SELECT * FROM scan_results 
                WHERE scanned_at >= ? AND scanned_at <= ?
                ORDER BY scanned_at DESC
            """
            
            results = self.execute_query(query, (start_date.isoformat(), end_date.isoformat()))
            
            # Parse filters JSON back to dict
            for result in results:
                if result.get('filters'):
                    try:
                        result['filters'] = json.loads(result['filters'])
                    except json.JSONDecodeError:
                        result['filters'] = {}
            
            return results
            
        except Exception as e:
            logger.error(f"❌ Failed to get scan results by date range: {e}")
            return []
    
    def get_top_scanned_tickers(self, limit: int = 10) -> List[Dict]:
        """Get the most frequently scanned tickers."""
        try:
            query = """
                SELECT ticker, COUNT(*) as scan_count, 
                       AVG(recommendation_score) as avg_score,
                       MAX(scanned_at) as last_scanned
                FROM scan_results 
                GROUP BY ticker 
                ORDER BY scan_count DESC, avg_score DESC
                LIMIT ?
            """
            
            return self.execute_query(query, (limit,))
            
        except Exception as e:
            logger.error(f"❌ Failed to get top scanned tickers: {e}")
            return []
