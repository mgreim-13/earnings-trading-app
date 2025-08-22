"""
Trade Repository
Handles all trade-related database operations.
"""

import logging
from datetime import datetime
from typing import List, Dict, Optional
from .base_repository import BaseRepository

logger = logging.getLogger(__name__)

class TradeRepository(BaseRepository):
    """Repository for trade-related database operations."""
    
    def add_selected_trade(self, trade_data: Dict) -> bool:
        """Add a new selected trade to the database."""
        try:
            query = """
                INSERT INTO selected_trades (
                    ticker, earnings_date, earnings_time, total_score, expected_move,
                    underlying_price, short_expiration, long_expiration, strike_price,
                    option_type, debit_cost, quantity, status, short_symbol, long_symbol,
                    days_between_expirations, short_bid, short_ask, long_bid, long_ask,
                    target_short_exp, target_long_exp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            
            params = (
                trade_data['ticker'],
                trade_data['earnings_date'],
                trade_data['earnings_time'],
                trade_data['total_score'],
                trade_data.get('expected_move'),
                trade_data.get('underlying_price'),
                trade_data.get('short_expiration'),
                trade_data.get('long_expiration'),
                trade_data.get('strike_price'),
                trade_data.get('option_type', 'call'),
                trade_data.get('debit_cost'),
                trade_data.get('quantity', 1),
                'pending',
                trade_data.get('short_symbol'),
                trade_data.get('long_symbol'),
                trade_data.get('days_between_expirations'),
                trade_data.get('short_bid'),
                trade_data.get('short_ask'),
                trade_data.get('long_bid'),
                trade_data.get('long_ask'),
                trade_data.get('target_short_exp'),
                trade_data.get('target_long_exp')
            )
            
            success = self.execute_update(query, params)
            if success:
                logger.info(f"✅ Added selected trade for {trade_data['ticker']} to database")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to add selected trade: {e}")
            return False
    
    def get_selected_trades_by_status(self, status: str = None) -> List[Dict]:
        """Get selected trades from selected_trades table, optionally filtered by status."""
        try:
            if status:
                query = """
                    SELECT * FROM selected_trades WHERE status = ? ORDER BY created_at DESC
                """
                params = (status,)
            else:
                query = """
                    SELECT * FROM selected_trades ORDER BY created_at DESC
                """
                params = ()
            
            return self.execute_query(query, params)
            
        except Exception as e:
            logger.error(f"❌ Failed to get selected trades by status: {e}")
            return []
    
    def get_trade_by_id(self, trade_id: int) -> Optional[Dict]:
        """Get a specific trade by ID."""
        try:
            query = "SELECT * FROM selected_trades WHERE id = ?"
            results = self.execute_query(query, (trade_id,))
            return results[0] if results else None
            
        except Exception as e:
            logger.error(f"❌ Failed to get trade by ID {trade_id}: {e}")
            return None
    
    def update_trade_status(self, trade_id: int, status: str) -> bool:
        """Update the status of a trade."""
        try:
            query = """
                UPDATE selected_trades 
                SET status = ?, updated_at = CURRENT_TIMESTAMP 
                WHERE id = ?
            """
            success = self.execute_update(query, (status, trade_id))
            if success:
                logger.info(f"✅ Updated trade {trade_id} status to {status}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to update trade {trade_id} status: {e}")
            return False
    
    def add_trade_history(self, trade_data: Dict) -> bool:
        """Add a trade to the trade history table."""
        try:
            query = """
                INSERT INTO trade_history (
                    ticker, trade_type, entry_time, exit_time, entry_price, 
                    exit_price, quantity, pnl, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            
            params = (
                trade_data['ticker'],
                trade_data['trade_type'],
                trade_data.get('entry_time'),
                trade_data.get('exit_time'),
                trade_data.get('entry_price'),
                trade_data.get('exit_price'),
                trade_data.get('quantity', 1),
                trade_data.get('pnl'),
                trade_data.get('status', 'open')
            )
            
            success = self.execute_update(query, params)
            if success:
                logger.info(f"✅ Added trade history for {trade_data['ticker']}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to add trade history: {e}")
            return False
    
    def get_trade_history(self, limit: int = 50) -> List[Dict]:
        """Get recent trade history."""
        try:
            query = """
                SELECT * FROM trade_history 
                ORDER BY created_at DESC 
                LIMIT ?
            """
            return self.execute_query(query, (limit,))
            
        except Exception as e:
            logger.error(f"❌ Failed to get trade history: {e}")
            return []
    
    def update_trade_order_info(self, trade_id: int, order_id: str = None, 
                               entry_order_id: str = None, exit_order_id: str = None,
                               entry_filled_at: str = None, exit_filled_at: str = None,
                               entry_price: float = None, exit_price: float = None,
                               pnl: float = None) -> bool:
        """Update trade order information."""
        try:
            # Build dynamic update query
            updates = []
            params = []
            
            if order_id is not None:
                updates.append("order_id = ?")
                params.append(order_id)
            
            if entry_order_id is not None:
                updates.append("entry_order_id = ?")
                params.append(entry_order_id)
            
            if exit_order_id is not None:
                updates.append("exit_order_id = ?")
                params.append(exit_order_id)
            
            if entry_filled_at is not None:
                updates.append("entry_filled_at = ?")
                params.append(entry_filled_at)
            
            if exit_filled_at is not None:
                updates.append("exit_filled_at = ?")
                params.append(exit_filled_at)
            
            if entry_price is not None:
                updates.append("entry_price = ?")
                params.append(entry_price)
            
            if exit_price is not None:
                updates.append("exit_price = ?")
                params.append(exit_price)
            
            if pnl is not None:
                updates.append("pnl = ?")
                params.append(pnl)
            
            if not updates:
                logger.warning("⚠️ No updates provided for trade order info")
                return True
            
            updates.append("updated_at = CURRENT_TIMESTAMP")
            params.append(trade_id)
            
            query = f"""
                UPDATE selected_trades 
                SET {', '.join(updates)}
                WHERE id = ?
            """
            
            success = self.execute_update(query, tuple(params))
            if success:
                logger.info(f"✅ Updated order info for trade {trade_id}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to update trade order info for {trade_id}: {e}")
            return False
    
    def get_selected_trades(self) -> List[Dict]:
        """Get all selected trades."""
        try:
            query = """
                SELECT * FROM selected_trades 
                ORDER BY created_at DESC
            """
            return self.execute_query(query)
            
        except Exception as e:
            logger.error(f"❌ Failed to get selected trades: {e}")
            return []
    
    def cleanup_old_trade_history(self, cutoff_date: datetime) -> int:
        """Clean up old trade history records."""
        try:
            query = """
                DELETE FROM trade_history 
                WHERE created_at < ?
            """
            success = self.execute_update(query, (cutoff_date.isoformat(),))
            
            if success:
                # Get count of deleted records
                count_query = """
                    SELECT COUNT(*) FROM trade_history 
                    WHERE created_at < ?
                """
                count = self.execute_scalar(count_query, (cutoff_date.isoformat(),))
                deleted_count = count if count else 0
                logger.info(f"✅ Cleaned up {deleted_count} old trade history records")
                return deleted_count
            else:
                return 0
                
        except Exception as e:
            logger.error(f"❌ Failed to cleanup old trade history: {e}")
            return 0
