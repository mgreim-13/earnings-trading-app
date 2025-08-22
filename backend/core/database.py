"""
Refactored Database Module
Maintains backward compatibility while using specialized repositories.
"""

import logging
from datetime import datetime
from typing import List, Dict, Optional

from repositories.base_repository import BaseRepository
from repositories.trade_repository import TradeRepository
from repositories.scan_repository import ScanRepository
from repositories.settings_repository import SettingsRepository
from repositories.trade_selections_repository import TradeSelectionsRepository

logger = logging.getLogger(__name__)

class Database:
    """
    Refactored Database class that maintains backward compatibility.
    Delegates to specialized repositories for different concerns.
    """
    
    def __init__(self, db_path: str = "trading_app.db"):
        """Initialize database with all specialized repositories."""
        self.db_path = db_path
        
        # Initialize base repository (handles table creation and common operations)
        self.base_repo = BaseRepository(db_path)
        
        # Initialize specialized repositories
        self.trade_repo = TradeRepository(db_path)
        self.scan_repo = ScanRepository(db_path)
        self.settings_repo = SettingsRepository(db_path)
        self.selections_repo = TradeSelectionsRepository(db_path)
        
        logger.info("✅ Refactored Database initialized with specialized repositories")

    # ==================== SETTINGS METHODS (delegate to SettingsRepository) ====================
    
    def get_setting(self, key: str) -> Optional[str]:
        """Get a setting value by key."""
        return self.settings_repo.get_setting(key)
    
    def set_setting(self, key: str, value: str) -> bool:
        """Set a setting value by key."""
        return self.settings_repo.set_setting(key, value)

    # ==================== TRADE METHODS (delegate to TradeRepository) ====================
    
    def add_selected_trade(self, trade_data: Dict) -> bool:
        """Add a new selected trade to the database."""
        return self.trade_repo.add_selected_trade(trade_data)
    
    def get_selected_trades_by_status(self, status: str = None) -> List[Dict]:
        """Get selected trades from selected_trades table, optionally filtered by status."""
        return self.trade_repo.get_selected_trades_by_status(status)
    
    def get_trade_by_id(self, trade_id: int) -> Optional[Dict]:
        """Get a specific trade by ID."""
        return self.trade_repo.get_trade_by_id(trade_id)
    
    def update_trade_status(self, trade_id: int, status: str) -> bool:
        """Update the status of a trade."""
        return self.trade_repo.update_trade_status(trade_id, status)
    
    def add_trade_history(self, trade_data: Dict) -> bool:
        """Add a trade to the trade history table."""
        return self.trade_repo.add_trade_history(trade_data)
    
    def get_trade_history(self, limit: int = 50) -> List[Dict]:
        """Get recent trade history."""
        return self.trade_repo.get_trade_history(limit)
    
    def update_trade_order_info(self, trade_id: int, order_id: str = None, 
                               entry_order_id: str = None, exit_order_id: str = None,
                               entry_filled_at: str = None, exit_filled_at: str = None,
                               entry_price: float = None, exit_price: float = None,
                               pnl: float = None) -> bool:
        """Update trade order information."""
        return self.trade_repo.update_trade_order_info(
            trade_id, order_id, entry_order_id, exit_order_id,
            entry_filled_at, exit_filled_at, entry_price, exit_price, pnl
        )
    
    def get_selected_trades(self) -> List[Dict]:
        """Get all selected trades."""
        return self.trade_repo.get_selected_trades()

    # ==================== SCAN METHODS (delegate to ScanRepository) ====================
    
    def add_scan_result(self, scan_data: Dict) -> bool:
        """Add a new scan result to the database."""
        return self.scan_repo.add_scan_result(scan_data)
    
    def get_recent_scan_results(self, days: int = 7) -> List[Dict]:
        """Get recent scan results from the last N days."""
        return self.scan_repo.get_recent_scan_results(days)
    
    def get_latest_scan_result(self, ticker: str) -> Optional[Dict]:
        """Get the latest scan result for a specific ticker."""
        return self.scan_repo.get_latest_scan_result(ticker)
    
    def get_cached_scan_results(self, tickers: List[str], ttl_minutes: int = 5) -> Dict[str, Dict]:
        """Get cached scan results for multiple tickers within TTL."""
        return self.scan_repo.get_cached_scan_results(tickers, ttl_minutes)
    
    def clear_scan_results(self) -> bool:
        """Clear all scan results from the database."""
        return self.scan_repo.clear_scan_results()
    
    def get_scan_results_stats(self) -> Dict:
        """Get statistics about scan results."""
        return self.scan_repo.get_scan_results_stats()

    # ==================== TRADE SELECTION METHODS (delegate to TradeSelectionsRepository) ====================
    
    def set_trade_selection(self, ticker: str, earnings_date: str, is_selected: bool) -> bool:
        """Set whether a stock is selected for trading on a specific earnings date."""
        return self.selections_repo.set_trade_selection(ticker, earnings_date, is_selected)
    
    def get_trade_selections(self) -> List[Dict]:
        """Get all trade selections."""
        return self.selections_repo.get_trade_selections()
    
    def get_selected_tickers_for_date(self, earnings_date: str) -> List[str]:
        """Get list of tickers selected for a specific earnings date."""
        return self.selections_repo.get_selected_tickers_for_date(earnings_date)
    
    def manually_deselect_stock(self, ticker: str, earnings_date: str) -> bool:
        """Manually deselect a stock (user action)."""
        return self.selections_repo.manually_deselect_stock(ticker, earnings_date)
    
    def clear_all_trade_selections(self) -> bool:
        """Clear all trade selections (reset to unselected)."""
        return self.selections_repo.clear_all_trade_selections()
    
    def is_manually_deselected(self, ticker: str, earnings_date: str) -> bool:
        """Check if a stock was manually deselected by the user."""
        return self.selections_repo.is_manually_deselected(ticker, earnings_date)
    
    def clear_manually_deselected_stocks(self) -> bool:
        """Clear the manually deselected flag for all stocks."""
        return self.selections_repo.clear_manually_deselected_stocks()

    # ==================== CLEANUP METHODS (delegate to appropriate repositories) ====================
    
    def clear_old_data(self, days: int = 30) -> bool:
        """Clear old data from all tables."""
        try:
            cutoff_date = datetime.now() - datetime.timedelta(days=days)
            
            # Clean up old scan results
            scan_cleaned = self.scan_repo.cleanup_old_scan_results(cutoff_date)
            
            # Clean up old trade history
            history_cleaned = self.trade_repo.cleanup_old_trade_history(cutoff_date)
            
            # Clean up old trade selections
            selections_cleaned = self.selections_repo.cleanup_old_selections(cutoff_date)
            
            # Clean up old settings (if any)
            settings_cleaned = self.settings_repo.cleanup_old_settings(cutoff_date)
            
            total_cleaned = scan_cleaned + history_cleaned + selections_cleaned + settings_cleaned
            
            logger.info(f"✅ Cleaned up {total_cleaned} old records from all tables")
            return True
            
        except Exception as e:
            logger.error(f"❌ Failed to clear old data: {e}")
            return False
    
    def cleanup_old_scan_results(self, cutoff_date: datetime) -> int:
        """Clean up old scan results."""
        return self.scan_repo.cleanup_old_scan_results(cutoff_date)
    
    def cleanup_old_trade_history(self, cutoff_date: datetime) -> int:
        """Clean up old trade history records."""
        return self.trade_repo.cleanup_old_trade_history(cutoff_date)

    # ==================== NEW ENHANCED METHODS ====================
    
    def get_database_stats(self) -> Dict:
        """Get comprehensive database statistics."""
        try:
            stats = {
                'scan_results': self.scan_repo.get_scan_results_stats(),
                'trade_selections': self.selections_repo.get_selection_stats(),
                'database_optimization': {}
            }
            
            # Get database optimization stats
            try:
                # Check database file size
                import os
                if os.path.exists(self.db_path):
                    stats['database_optimization']['file_size_bytes'] = os.path.getsize(self.db_path)
                    stats['database_optimization']['file_size_mb'] = round(os.path.getsize(self.db_path) / (1024 * 1024), 2)
                
                # Get table info
                stats['database_optimization']['tables'] = {}
                for table_name in ['settings', 'selected_trades', 'trade_selections', 'trade_history', 'scan_results']:
                    if self.base_repo.table_exists(table_name):
                        table_info = self.base_repo.get_table_info(table_name)
                        stats['database_optimization']['tables'][table_name] = {
                            'column_count': len(table_info),
                            'columns': [col['name'] for col in table_info]
                        }
                
            except Exception as e:
                logger.warning(f"⚠️ Could not get database optimization stats: {e}")
            
            return stats
            
        except Exception as e:
            logger.error(f"❌ Failed to get database stats: {e}")
            return {}
    
    def optimize_database(self) -> Dict:
        """Optimize database performance."""
        try:
            results = {
                'vacuum': self.base_repo.vacuum_database(),
                'analyze': self.base_repo.analyze_database(),
                'cleanup': self.clear_old_data(30)
            }
            
            logger.info("✅ Database optimization completed")
            return results
            
        except Exception as e:
            logger.error(f"❌ Database optimization failed: {e}")
            return {'error': str(e)}
    
    def get_repository_status(self) -> Dict:
        """Get status of all repositories."""
        try:
            return {
                'base_repository': {
                    'status': 'active',
                    'db_path': self.db_path,
                    'tables_exist': {
                        'settings': self.base_repo.table_exists('settings'),
                        'selected_trades': self.base_repo.table_exists('selected_trades'),
                        'trade_selections': self.base_repo.table_exists('trade_selections'),
                        'trade_history': self.base_repo.table_exists('trade_history'),
                        'scan_results': self.base_repo.table_exists('scan_results')
                    }
                },
                'trade_repository': {'status': 'active'},
                'scan_repository': {'status': 'active'},
                'settings_repository': {'status': 'active'},
                'selections_repository': {'status': 'active'}
            }
            
        except Exception as e:
            logger.error(f"❌ Failed to get repository status: {e}")
            return {'error': str(e)}

    # ==================== BACKWARD COMPATIBILITY ALIASES ====================
    
    # Some methods might have been called differently in the old code
    # These aliases ensure backward compatibility
    
    def get_trades(self) -> List[Dict]:
        """Alias for get_selected_trades() for backward compatibility."""
        return self.get_selected_trades()
    
    def get_trades_by_status(self, status: str) -> List[Dict]:
        """Alias for get_selected_trades_by_status() for backward compatibility."""
        return self.get_selected_trades_by_status(status)
    
    def add_trade(self, trade_data: Dict) -> bool:
        """Alias for add_selected_trade() for backward compatibility."""
        return self.add_selected_trade(trade_data)
    
    def update_trade(self, trade_id: int, **kwargs) -> bool:
        """Alias for update_trade_status() for backward compatibility."""
        if 'status' in kwargs:
            return self.update_trade_status(trade_id, kwargs['status'])
        return False
