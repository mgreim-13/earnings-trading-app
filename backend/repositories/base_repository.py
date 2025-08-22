"""
Base Database Repository
Handles common database operations and initialization.
"""

import sqlite3
import logging
from datetime import datetime
from typing import List, Dict, Optional

logger = logging.getLogger(__name__)

class BaseRepository:
    """Base repository with common database operations."""
    
    def __init__(self, db_path: str = "trading_app.db"):
        self.db_path = db_path
        self.init_database()
    
    def init_database(self):
        """Initialize the database with required tables."""
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                
                # Create settings table
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY,
                        value TEXT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                
                # Create selected trades table
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS selected_trades (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticker TEXT NOT NULL,
                        earnings_date TEXT NOT NULL,
                        earnings_time TEXT NOT NULL,
                        total_score REAL NOT NULL,
                        expected_move TEXT,
                        underlying_price REAL,
                        short_expiration TEXT,
                        long_expiration TEXT,
                        strike_price REAL,
                        option_type TEXT DEFAULT 'call',
                        debit_cost REAL,
                        quantity INTEGER DEFAULT 1,
                        status TEXT DEFAULT 'pending',
                        order_id TEXT,
                        entry_order_id TEXT,
                        exit_order_id TEXT,
                        entry_filled_at TIMESTAMP,
                        exit_filled_at TIMESTAMP,
                        entry_price REAL,
                        exit_price REAL,
                        pnl REAL,
                        short_symbol TEXT,
                        long_symbol TEXT,
                        days_between_expirations INTEGER,
                        short_bid REAL,
                        short_ask REAL,
                        long_bid REAL,
                        long_ask REAL,
                        target_short_exp TEXT,
                        target_long_exp TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                
                # Create trade selection table for user checkboxes
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS trade_selections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticker TEXT NOT NULL,
                        earnings_date TEXT NOT NULL,
                        is_selected BOOLEAN DEFAULT FALSE,
                        manually_deselected BOOLEAN DEFAULT FALSE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE(ticker, earnings_date)
                    )
                """)
                
                # Create trade history table
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS trade_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticker TEXT NOT NULL,
                        trade_type TEXT NOT NULL,
                        entry_time TIMESTAMP,
                        exit_time TIMESTAMP,
                        entry_price REAL,
                        exit_price REAL,
                        quantity INTEGER,
                        pnl REAL,
                        status TEXT DEFAULT 'open',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                
                # Create scan results table
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS scan_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticker TEXT NOT NULL,
                        earnings_date TEXT NOT NULL,
                        earnings_time TEXT NOT NULL,
                        recommendation_score REAL NOT NULL,
                        filters TEXT,
                        reasoning TEXT,
                        scanned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """)
                
                # Run migrations
                self._run_migrations(cursor)
                
                conn.commit()
                logger.info("✅ Database initialized successfully")
                
        except Exception as e:
            logger.error(f"❌ Failed to initialize database: {e}")
            raise
    
    def _run_migrations(self, cursor):
        """Run database migrations to add new columns."""
        try:
            # Migration 1: Add missing columns to selected_trades
            columns_to_add = [
                ('expected_move', 'TEXT'),
                ('underlying_price', 'REAL'),
                ('short_expiration', 'TEXT'),
                ('long_expiration', 'TEXT'),
                ('strike_price', 'REAL'),
                ('option_type', 'TEXT'),
                ('debit_cost', 'REAL'),
                ('quantity', 'INTEGER'),
                ('status', 'TEXT'),
                ('order_id', 'TEXT'),
                ('entry_order_id', 'TEXT'),
                ('exit_order_id', 'TEXT'),
                ('entry_filled_at', 'TIMESTAMP'),
                ('exit_filled_at', 'TIMESTAMP'),
                ('entry_price', 'REAL'),
                ('exit_price', 'REAL'),
                ('pnl', 'REAL'),
                ('short_symbol', 'TEXT'),
                ('long_symbol', 'TEXT'),
                ('days_between_expirations', 'INTEGER'),
                ('short_bid', 'REAL'),
                ('short_ask', 'REAL'),
                ('long_bid', 'REAL'),
                ('long_ask', 'REAL'),
                ('target_short_exp', 'TEXT'),
                ('target_long_exp', 'TEXT')
            ]
            
            for column_name, column_type in columns_to_add:
                try:
                    cursor.execute(f"ALTER TABLE selected_trades ADD COLUMN {column_name} {column_type}")
                    logger.info(f"✅ Added column '{column_name}' to selected_trades")
                except sqlite3.OperationalError as e:
                    if "duplicate column name" in str(e).lower():
                        logger.debug(f"Column '{column_name}' already exists")
                    else:
                        logger.warning(f"Could not add column '{column_name}': {e}")
            
            logger.info("✅ Database migrations completed successfully")
            
        except Exception as e:
            logger.error(f"❌ Failed to run database migrations: {e}")
            # Don't raise here - migrations are optional for functionality
    
    def execute_query(self, query: str, params: tuple = ()) -> List[Dict]:
        """Execute a query and return results as list of dictionaries."""
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params)
                
                columns = [description[0] for description in cursor.description]
                rows = cursor.fetchall()
                
                return [dict(zip(columns, row)) for row in rows]
                
        except Exception as e:
            logger.error(f"❌ Query execution failed: {e}")
            return []
    
    def execute_update(self, query: str, params: tuple = ()) -> bool:
        """Execute an update/insert/delete query."""
        try:
            logger.info(f"🔍 BaseRepository: execute_update called")
            logger.info(f"   Query: {query}")
            logger.info(f"   Params: {params}")
            logger.info(f"   Params count: {len(params)}")
            
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params)
                conn.commit()
                
                logger.info(f"   ✅ execute_update successful")
                logger.info(f"   Rows affected: {cursor.rowcount}")
                return True
                
        except Exception as e:
            logger.error(f"❌ Update execution failed: {e}")
            logger.error(f"   Query: {query}")
            logger.error(f"   Params: {params}")
            import traceback
            logger.error(f"   Full traceback: {traceback.format_exc()}")
            return False
    
    def execute_scalar(self, query: str, params: tuple = ()) -> Optional[any]:
        """Execute a query and return a single value."""
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params)
                result = cursor.fetchone()
                return result[0] if result else None
                
        except Exception as e:
            logger.error(f"❌ Scalar query execution failed: {e}")
            return None
    
    def table_exists(self, table_name: str) -> bool:
        """Check if a table exists."""
        try:
            query = """
                SELECT name FROM sqlite_master 
                WHERE type='table' AND name=?
            """
            result = self.execute_scalar(query, (table_name,))
            return result is not None
            
        except Exception as e:
            logger.error(f"❌ Error checking if table {table_name} exists: {e}")
            return False
    
    def get_table_info(self, table_name: str) -> List[Dict]:
        """Get table schema information."""
        try:
            query = "PRAGMA table_info(?)"
            return self.execute_query(query, (table_name,))
            
        except Exception as e:
            logger.error(f"❌ Error getting table info for {table_name}: {e}")
            return []
    
    def vacuum_database(self) -> bool:
        """Optimize database by reclaiming unused space."""
        try:
            with sqlite3.connect(self.db_path) as conn:
                conn.execute("VACUUM")
                logger.info("✅ Database VACUUM completed")
                return True
                
        except Exception as e:
            logger.error(f"❌ Database VACUUM failed: {e}")
            return False
    
    def analyze_database(self) -> bool:
        """Analyze database for query optimization."""
        try:
            with sqlite3.connect(self.db_path) as conn:
                conn.execute("ANALYZE")
                logger.info("✅ Database ANALYZE completed")
                return True
                
        except Exception as e:
            logger.error(f"❌ Database ANALYZE failed: {e}")
            return False
