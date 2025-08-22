"""
Data Management Module
Handles data cleanup, maintenance, and market protection operations.
"""

import logging
from datetime import datetime, timedelta
from typing import Dict, List
import pytz

from core.alpaca_client import AlpacaClient
from core.database import Database

logger = logging.getLogger(__name__)

class DataManager:
    """Handles data cleanup and maintenance operations."""
    
    def __init__(self, alpaca_client: AlpacaClient, database: Database):
        self.alpaca_client = alpaca_client
        self.database = database
        self.et_tz = pytz.timezone('US/Eastern')

    def data_cleanup_job(self):
        """Perform weekly data cleanup operations."""
        try:
            logger.info("Starting weekly data cleanup...")
            
            # Define cleanup parameters
            cleanup_days = 30  # Keep data for 30 days
            cutoff_date = datetime.now() - timedelta(days=cleanup_days)
            
            cleanup_results = {
                'scan_results': 0,
                'trade_history': 0,
                'old_selections': 0,
                'total_cleaned': 0
            }
            
            # Clean old scan results
            try:
                scan_count = self.database.cleanup_old_scan_results(cutoff_date)
                cleanup_results['scan_results'] = scan_count
                logger.info(f"Cleaned {scan_count} old scan results")
            except Exception as e:
                logger.error(f"Error cleaning scan results: {e}")
            
            # Clean old trade history
            try:
                trade_count = self.database.cleanup_old_trade_history(cutoff_date)
                cleanup_results['trade_history'] = trade_count
                logger.info(f"Cleaned {trade_count} old trade history records")
            except Exception as e:
                logger.error(f"Error cleaning trade history: {e}")
            
            # Clean old trade selections (completed/cancelled trades older than 7 days)
            try:
                selection_cutoff = datetime.now() - timedelta(days=7)
                selection_count = self._cleanup_old_trade_selections(selection_cutoff)
                cleanup_results['old_selections'] = selection_count
                logger.info(f"Cleaned {selection_count} old trade selections")
            except Exception as e:
                logger.error(f"Error cleaning trade selections: {e}")
            
            # Calculate total
            cleanup_results['total_cleaned'] = (
                cleanup_results['scan_results'] + 
                cleanup_results['trade_history'] + 
                cleanup_results['old_selections']
            )
            
            # Log cleanup summary
            logger.info("Weekly data cleanup completed:")
            logger.info(f"  - Scan results cleaned: {cleanup_results['scan_results']}")
            logger.info(f"  - Trade history cleaned: {cleanup_results['trade_history']}")
            logger.info(f"  - Old selections cleaned: {cleanup_results['old_selections']}")
            logger.info(f"  - Total records cleaned: {cleanup_results['total_cleaned']}")
            
            # Store cleanup stats
            self._store_cleanup_stats(cleanup_results)
            
        except Exception as e:
            logger.error(f"Error in data cleanup job: {e}")

    def market_close_protection_job(self):
        """Market close protection - cancel unfilled orders before market close."""
        try:
            logger.info("Running market close protection...")
            
            # Check if market is still open
            if not self.alpaca_client.is_market_open():
                logger.info("Market is already closed, skipping protection job")
                return
            
            # Get current time in ET
            current_time = datetime.now(self.et_tz)
            market_close_time = current_time.replace(hour=16, minute=0, second=0, microsecond=0)
            
            # Check if we're within 5 minutes of market close
            time_to_close = (market_close_time - current_time).total_seconds() / 60
            
            if time_to_close > 5:
                logger.info(f"Market closes in {time_to_close:.1f} minutes, no protection needed yet")
                return
            
            logger.warning(f"Market closes in {time_to_close:.1f} minutes - activating protection")
            
            # Get all open orders
            open_orders = self.alpaca_client.get_open_orders()
            
            if not open_orders:
                logger.info("No open orders to protect")
                return
            
            # Cancel unfilled orders
            cancelled_orders = []
            failed_cancellations = []
            
            for order in open_orders:
                try:
                    order_id = order['id']
                    symbol = order['symbol']
                    
                    logger.warning(f"Cancelling order {order_id} for {symbol} due to market close protection")
                    
                    success = self.alpaca_client.cancel_order(order_id)
                    if success:
                        cancelled_orders.append(order)
                        logger.info(f"Successfully cancelled order {order_id}")
                    else:
                        failed_cancellations.append(order)
                        logger.error(f"Failed to cancel order {order_id}")
                        
                except Exception as e:
                    logger.error(f"Error cancelling order {order.get('id', 'unknown')}: {e}")
                    failed_cancellations.append(order)
            
            # Log protection summary
            logger.warning("Market close protection completed:")
            logger.warning(f"  - Orders cancelled: {len(cancelled_orders)}")
            logger.warning(f"  - Failed cancellations: {len(failed_cancellations)}")
            
            if cancelled_orders:
                logger.warning("Cancelled orders:")
                for order in cancelled_orders:
                    logger.warning(f"  - {order['id']}: {order['symbol']} ({order.get('side', 'unknown')})")
            
            if failed_cancellations:
                logger.error("Failed to cancel orders:")
                for order in failed_cancellations:
                    logger.error(f"  - {order['id']}: {order['symbol']} ({order.get('side', 'unknown')})")
            
        except Exception as e:
            logger.error(f"Error in market close protection: {e}")

    def _cleanup_old_trade_selections(self, cutoff_date: datetime) -> int:
        """Clean up old trade selections that are completed or cancelled."""
        try:
            # Get all trade selections
            selections = self.database.get_trade_selections()
            
            cleaned_count = 0
            
            for selection in selections:
                try:
                    # Parse selection date
                    selection_date_str = selection.get('created_at', '')
                    if not selection_date_str:
                        continue
                    
                    selection_date = datetime.fromisoformat(selection_date_str.replace('Z', '+00:00'))
                    
                    # Check if selection is old and completed/cancelled
                    if selection_date < cutoff_date:
                        status = selection.get('status', '').lower()
                        if status in ['completed', 'cancelled', 'failed']:
                            # Remove old selection
                            ticker = selection.get('ticker')
                            earnings_date = selection.get('earnings_date')
                            
                            if ticker and earnings_date:
                                success = self.database.set_trade_selection(ticker, earnings_date, False)
                                if success:
                                    cleaned_count += 1
                                    logger.debug(f"Cleaned old selection: {ticker} ({earnings_date})")
                
                except Exception as e:
                    logger.warning(f"Error cleaning selection {selection.get('ticker', 'unknown')}: {e}")
                    continue
            
            return cleaned_count
            
        except Exception as e:
            logger.error(f"Error in trade selection cleanup: {e}")
            return 0

    def _store_cleanup_stats(self, cleanup_results: Dict):
        """Store cleanup statistics in database."""
        try:
            stats_data = {
                'cleanup_date': datetime.now().isoformat(),
                'scan_results_cleaned': cleanup_results['scan_results'],
                'trade_history_cleaned': cleanup_results['trade_history'],
                'selections_cleaned': cleanup_results['old_selections'],
                'total_cleaned': cleanup_results['total_cleaned']
            }
            
            # Store as a setting for now (could be expanded to dedicated table)
            import json
            self.database.set_setting('last_cleanup_stats', json.dumps(stats_data))
            
        except Exception as e:
            logger.warning(f"Failed to store cleanup stats: {e}")

    def get_cleanup_stats(self) -> Dict:
        """Get the latest cleanup statistics."""
        try:
            import json
            stats_json = self.database.get_setting('last_cleanup_stats')
            
            if stats_json:
                return json.loads(stats_json)
            else:
                return {
                    'cleanup_date': None,
                    'scan_results_cleaned': 0,
                    'trade_history_cleaned': 0,
                    'selections_cleaned': 0,
                    'total_cleaned': 0
                }
                
        except Exception as e:
            logger.error(f"Error getting cleanup stats: {e}")
            return {}

    def force_cleanup_now(self, days_to_keep: int = 30) -> Dict:
        """Force immediate cleanup with custom retention period."""
        try:
            logger.info(f"Starting forced cleanup (keeping {days_to_keep} days of data)...")
            
            cutoff_date = datetime.now() - timedelta(days=days_to_keep)
            
            cleanup_results = {
                'scan_results': 0,
                'trade_history': 0,
                'old_selections': 0,
                'total_cleaned': 0,
                'forced': True,
                'days_kept': days_to_keep
            }
            
            # Clean scan results
            scan_count = self.database.cleanup_old_scan_results(cutoff_date)
            cleanup_results['scan_results'] = scan_count
            
            # Clean trade history
            trade_count = self.database.cleanup_old_trade_history(cutoff_date)
            cleanup_results['trade_history'] = trade_count
            
            # Clean old selections
            selection_cutoff = datetime.now() - timedelta(days=min(days_to_keep, 7))
            selection_count = self._cleanup_old_trade_selections(selection_cutoff)
            cleanup_results['old_selections'] = selection_count
            
            # Calculate total
            cleanup_results['total_cleaned'] = (
                cleanup_results['scan_results'] + 
                cleanup_results['trade_history'] + 
                cleanup_results['old_selections']
            )
            
            # Store stats
            self._store_cleanup_stats(cleanup_results)
            
            logger.info(f"Forced cleanup completed: {cleanup_results['total_cleaned']} records cleaned")
            
            return cleanup_results
            
        except Exception as e:
            logger.error(f"Error in forced cleanup: {e}")
            return {'error': str(e), 'total_cleaned': 0}

    def get_data_statistics(self) -> Dict:
        """Get current data statistics."""
        try:
            stats = {
                'scan_results': 0,
                'trade_history': 0,
                'trade_selections': 0,
                'database_size': 0
            }
            
            # Get scan results stats
            scan_stats = self.database.get_scan_results_stats()
            stats['scan_results'] = scan_stats.get('total_results', 0)
            
            # Get trade history count
            trade_history = self.database.get_trade_history(limit=1000)  # Get up to 1000 to count
            stats['trade_history'] = len(trade_history)
            
            # Get trade selections count
            selections = self.database.get_trade_selections()
            stats['trade_selections'] = len(selections)
            
            # Get database file size (if SQLite)
            try:
                import os
                db_path = getattr(self.database, 'db_path', 'trading_app.db')
                if os.path.exists(db_path):
                    stats['database_size'] = os.path.getsize(db_path)
            except Exception:
                stats['database_size'] = 0
            
            return stats
            
        except Exception as e:
            logger.error(f"Error getting data statistics: {e}")
            return {}

    def optimize_database(self) -> Dict:
        """Optimize database performance."""
        try:
            logger.info("Starting database optimization...")
            
            optimization_results = {
                'vacuum_completed': False,
                'indexes_analyzed': False,
                'size_before': 0,
                'size_after': 0,
                'space_saved': 0
            }
            
            # Get initial size
            try:
                import os
                db_path = getattr(self.database, 'db_path', 'trading_app.db')
                if os.path.exists(db_path):
                    optimization_results['size_before'] = os.path.getsize(db_path)
            except Exception:
                pass
            
            # Run VACUUM to reclaim space (SQLite specific)
            try:
                if hasattr(self.database, 'connection'):
                    cursor = self.database.connection.cursor()
                    cursor.execute("VACUUM")
                    self.database.connection.commit()
                    optimization_results['vacuum_completed'] = True
                    logger.info("Database VACUUM completed")
            except Exception as e:
                logger.warning(f"VACUUM operation failed: {e}")
            
            # Analyze indexes for better query performance
            try:
                if hasattr(self.database, 'connection'):
                    cursor = self.database.connection.cursor()
                    cursor.execute("ANALYZE")
                    self.database.connection.commit()
                    optimization_results['indexes_analyzed'] = True
                    logger.info("Database ANALYZE completed")
            except Exception as e:
                logger.warning(f"ANALYZE operation failed: {e}")
            
            # Get final size
            try:
                import os
                db_path = getattr(self.database, 'db_path', 'trading_app.db')
                if os.path.exists(db_path):
                    optimization_results['size_after'] = os.path.getsize(db_path)
                    optimization_results['space_saved'] = (
                        optimization_results['size_before'] - optimization_results['size_after']
                    )
            except Exception:
                pass
            
            logger.info("Database optimization completed")
            logger.info(f"Space saved: {optimization_results['space_saved']} bytes")
            
            return optimization_results
            
        except Exception as e:
            logger.error(f"Error in database optimization: {e}")
            return {'error': str(e)}
