"""
Trade Selections Repository
Handles all trade selection-related database operations.
"""

import logging
from datetime import datetime
from typing import List, Dict, Optional
from .base_repository import BaseRepository

logger = logging.getLogger(__name__)

class TradeSelectionsRepository(BaseRepository):
    """Repository for trade selection-related database operations."""
    
    def set_trade_selection(self, ticker: str, earnings_date: str, is_selected: bool) -> bool:
        """Set whether a stock is selected for trading on a specific earnings date."""
        try:
            query = """
                INSERT OR REPLACE INTO trade_selections (
                    ticker, earnings_date, is_selected, manually_deselected, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """
            
            # If manually deselecting, set manually_deselected flag
            manually_deselected = not is_selected
            
            params = (ticker, earnings_date, is_selected, manually_deselected)
            success = self.execute_update(query, params)
            
            if success:
                action = "selected" if is_selected else "deselected"
                logger.info(f"✅ {ticker} {action} for {earnings_date}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to set trade selection for {ticker}: {e}")
            return False
    
    def get_trade_selections(self) -> List[Dict]:
        """Get all trade selections."""
        try:
            query = """
                SELECT * FROM trade_selections 
                ORDER BY created_at DESC
            """
            return self.execute_query(query)
            
        except Exception as e:
            logger.error(f"❌ Failed to get trade selections: {e}")
            return []
    
    def get_selected_tickers_for_date(self, earnings_date: str) -> List[str]:
        """Get list of tickers selected for a specific earnings date."""
        try:
            query = """
                SELECT ticker FROM trade_selections 
                WHERE earnings_date = ? AND is_selected = TRUE
                ORDER BY ticker
            """
            results = self.execute_query(query, (earnings_date,))
            return [row['ticker'] for row in results]
            
        except Exception as e:
            logger.error(f"❌ Failed to get selected tickers for {earnings_date}: {e}")
            return []
    
    def manually_deselect_stock(self, ticker: str, earnings_date: str) -> bool:
        """Manually deselect a stock (user action)."""
        try:
            query = """
                UPDATE trade_selections 
                SET is_selected = FALSE, manually_deselected = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE ticker = ? AND earnings_date = ?
            """
            success = self.execute_update(query, (ticker, earnings_date))
            
            if success:
                logger.info(f"✅ Manually deselected {ticker} for {earnings_date}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to manually deselect {ticker}: {e}")
            return False
    
    def clear_all_trade_selections(self) -> bool:
        """Clear all trade selections (reset to unselected)."""
        try:
            query = """
                UPDATE trade_selections 
                SET is_selected = FALSE, updated_at = CURRENT_TIMESTAMP
            """
            success = self.execute_update(query)
            
            if success:
                logger.info("✅ Cleared all trade selections")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to clear all trade selections: {e}")
            return False
    
    def is_manually_deselected(self, ticker: str, earnings_date: str) -> bool:
        """Check if a stock was manually deselected by the user."""
        try:
            query = """
                SELECT manually_deselected FROM trade_selections 
                WHERE ticker = ? AND earnings_date = ?
            """
            result = self.execute_scalar(query, (ticker, earnings_date))
            return bool(result)
            
        except Exception as e:
            logger.error(f"❌ Failed to check manual deselection for {ticker}: {e}")
            return False
    
    def clear_manually_deselected_stocks(self) -> bool:
        """Clear the manually deselected flag for all stocks."""
        try:
            query = """
                UPDATE trade_selections 
                SET manually_deselected = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE manually_deselected = TRUE
            """
            success = self.execute_update(query)
            
            if success:
                logger.info("✅ Cleared manually deselected flags")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to clear manually deselected flags: {e}")
            return False
    
    def get_selection_stats(self) -> Dict:
        """Get statistics about trade selections."""
        try:
            stats = {}
            
            # Total selections
            total_query = "SELECT COUNT(*) FROM trade_selections"
            stats['total_selections'] = self.execute_scalar(total_query) or 0
            
            # Currently selected
            selected_query = "SELECT COUNT(*) FROM trade_selections WHERE is_selected = TRUE"
            stats['currently_selected'] = self.execute_scalar(selected_query) or 0
            
            # Manually deselected
            manual_deselected_query = "SELECT COUNT(*) FROM trade_selections WHERE manually_deselected = TRUE"
            stats['manually_deselected'] = self.execute_scalar(manual_deselected_query) or 0
            
            # Selections by date
            date_stats_query = """
                SELECT earnings_date, COUNT(*) as count
                FROM trade_selections 
                WHERE is_selected = TRUE
                GROUP BY earnings_date 
                ORDER BY earnings_date DESC
            """
            date_stats = self.execute_query(date_stats_query)
            stats['selections_by_date'] = date_stats
            
            # Most selected tickers
            ticker_stats_query = """
                SELECT ticker, COUNT(*) as selection_count
                FROM trade_selections 
                WHERE is_selected = TRUE
                GROUP BY ticker 
                ORDER BY selection_count DESC
                LIMIT 10
            """
            ticker_stats = self.execute_query(ticker_stats_query)
            stats['top_selected_tickers'] = ticker_stats
            
            return stats
            
        except Exception as e:
            logger.error(f"❌ Failed to get selection stats: {e}")
            return {}
    
    def get_selections_by_ticker(self, ticker: str) -> List[Dict]:
        """Get all selections for a specific ticker."""
        try:
            query = """
                SELECT * FROM trade_selections 
                WHERE ticker = ? 
                ORDER BY earnings_date DESC
            """
            return self.execute_query(query, (ticker,))
            
        except Exception as e:
            logger.error(f"❌ Failed to get selections for {ticker}: {e}")
            return []
    
    def get_selections_by_date_range(self, start_date: str, end_date: str) -> List[Dict]:
        """Get selections within a date range."""
        try:
            query = """
                SELECT * FROM trade_selections 
                WHERE earnings_date >= ? AND earnings_date <= ?
                ORDER BY earnings_date DESC, ticker
            """
            return self.execute_query(query, (start_date, end_date))
            
        except Exception as e:
            logger.error(f"❌ Failed to get selections by date range: {e}")
            return []
    
    def bulk_update_selections(self, selections: List[Dict]) -> bool:
        """Update multiple trade selections at once."""
        try:
            if not selections:
                return True
            
            # Use a transaction for bulk update
            with self._get_connection() as conn:
                cursor = conn.cursor()
                
                for selection in selections:
                    ticker = selection['ticker']
                    earnings_date = selection['earnings_date']
                    is_selected = selection['is_selected']
                    manually_deselected = not is_selected
                    
                    cursor.execute("""
                        INSERT OR REPLACE INTO trade_selections (
                            ticker, earnings_date, is_selected, manually_deselected, updated_at
                        ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, (ticker, earnings_date, is_selected, manually_deselected))
                
                conn.commit()
                logger.info(f"✅ Bulk updated {len(selections)} trade selections")
                return True
                
        except Exception as e:
            logger.error(f"❌ Failed to bulk update trade selections: {e}")
            return False
    
    def cleanup_old_selections(self, cutoff_date: datetime) -> int:
        """Clean up old trade selections."""
        try:
            query = """
                DELETE FROM trade_selections 
                WHERE created_at < ?
            """
            success = self.execute_update(query, (cutoff_date.isoformat(),))
            
            if success:
                # Get count of deleted records
                count_query = """
                    SELECT COUNT(*) FROM trade_selections 
                    WHERE created_at < ?
                """
                count = self.execute_scalar(count_query, (cutoff_date.isoformat(),))
                deleted_count = count if count else 0
                logger.info(f"✅ Cleaned up {deleted_count} old trade selections")
                return deleted_count
            else:
                return 0
                
        except Exception as e:
            logger.error(f"❌ Failed to cleanup old trade selections: {e}")
            return 0
    
    def _get_connection(self):
        """Get a database connection for transaction operations."""
        import sqlite3
        return sqlite3.connect(self.db_path)
