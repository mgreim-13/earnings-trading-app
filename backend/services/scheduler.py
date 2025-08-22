"""
Job Scheduler Module
Core scheduling functionality for automated trading operations.
Coordinates between specialized managers for different responsibilities.
"""

import logging
from datetime import datetime, timedelta
from typing import List, Dict, Optional
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.date import DateTrigger
import pytz

from core.earnings_scanner import EarningsScanner
from core.alpaca_client import AlpacaClient
from core.database import Database
# AdvancedOrderMonitor functionality now integrated into OrderMonitor

# Import our specialized managers
from .trade_executor import TradeExecutor
from .order_monitor import OrderMonitor
from .data_manager import DataManager
from .scan_manager import ScanManager

logger = logging.getLogger(__name__)

class TradingScheduler:
    """
    Core trading scheduler that coordinates specialized managers.
    This class handles all scheduling and coordination for trading operations.
    """
    
    def __init__(self):
        """Initialize the job scheduler and all specialized managers."""
        logger.info("Initializing Trading Scheduler...")
        
        # Core components
        self.scheduler = BackgroundScheduler()
        self.earnings_scanner = EarningsScanner()
        self.alpaca_client = AlpacaClient()
        self.database = Database()
        # AdvancedOrderMonitor functionality now integrated into OrderMonitor
        
        # Timezone
        self.et_tz = pytz.timezone('US/Eastern')
        
        # Initialize specialized managers
        self.trade_executor = TradeExecutor(self.alpaca_client, self.database, self.earnings_scanner)
        self.order_monitor = OrderMonitor(self.scheduler, self.alpaca_client, self.database)
        self.data_manager = DataManager(self.alpaca_client, self.database)
        self.scan_manager = ScanManager(self.earnings_scanner, self.database)
        
        # Setup scheduled jobs
        self.setup_scheduler()
        logger.info("Trading Scheduler initialized successfully")

    def setup_scheduler(self):
        """Setup all scheduled jobs."""
        try:
            logger.info("Setting up scheduled jobs...")
            
            # Daily scan at 3:00 PM ET
            self.scheduler.add_job(
                func=self.scan_manager.daily_scan_job,
                trigger=CronTrigger(hour=15, minute=0, timezone=self.et_tz),
                id='daily_scan',
                name='Daily Earnings Scan',
                replace_existing=True
            )
            
            # Trade entry at 3:45 PM ET
            self.scheduler.add_job(
                func=self.trade_entry_job,
                trigger=CronTrigger(hour=15, minute=45, timezone=self.et_tz),
                id='trade_entry',
                name='Trade Entry',
                replace_existing=True
            )
            
            # Trade exit at 9:45 AM ET
            self.scheduler.add_job(
                func=self.trade_exit_job,
                trigger=CronTrigger(hour=9, minute=45, timezone=self.et_tz),
                id='trade_exit',
                name='Trade Exit',
                replace_existing=True
            )
            
            # Market close protection at 3:55 PM ET
            self.scheduler.add_job(
                func=self.data_manager.market_close_protection_job,
                trigger=CronTrigger(hour=15, minute=55, timezone=self.et_tz),
                id='market_close_protection',
                name='Market Close Protection',
                replace_existing=True
            )
            
            # Data cleanup job (weekly on Sunday at 2 AM)
            self.scheduler.add_job(
                func=self.data_manager.data_cleanup_job,
                trigger=CronTrigger(day_of_week='sun', hour=2, timezone=self.et_tz),
                id='data_cleanup',
                name='Data Cleanup',
                replace_existing=True
            )
            
            logger.info("All scheduled jobs configured successfully")
            
        except Exception as e:
            logger.error(f"Failed to setup scheduler: {e}")
            raise

    def start(self):
        """Start the scheduler."""
        try:
            if not self.scheduler.running:
                self.scheduler.start()
                logger.info("Job scheduler started successfully")
            else:
                logger.info("Job scheduler is already running")
        except Exception as e:
            logger.error(f"Failed to start scheduler: {e}")
            raise

    def stop(self):
        """Stop the scheduler and all monitoring."""
        try:
            # Stop all advanced monitoring first
            self.order_monitor.stop_all_advanced_monitoring()
            
            # Stop the scheduler
            if self.scheduler.running:
                self.scheduler.shutdown(wait=False)
                logger.info("Job scheduler stopped successfully")
            else:
                logger.info("Job scheduler is not running")
                
        except Exception as e:
            logger.error(f"Failed to stop scheduler: {e}")
            raise

    def get_scheduler_status(self) -> Dict:
        """Get comprehensive scheduler status."""
        try:
            jobs = []
            for job in self.scheduler.get_jobs():
                jobs.append({
                    'id': job.id,
                    'name': job.name,
                    'next_run': job.next_run_time.isoformat() if job.next_run_time else None,
                    'trigger': str(job.trigger)
                })
            
            return {
                'running': self.scheduler.running,
                'jobs': jobs,
                'job_count': len(jobs),
                'active_monitors': self.order_monitor.get_active_monitor_count(),
                'active_trade_ids': self.order_monitor.get_active_trade_ids(),
                'timezone': str(self.et_tz)
            }
            
        except Exception as e:
            logger.error(f"Error getting scheduler status: {e}")
            return {'error': str(e)}

    def trade_entry_job(self):
        """Execute trade entry job - delegates to trade executor."""
        try:
            logger.info("Starting trade entry job...")
            
            # Get selected trades
            selected_trades = self.database.get_selected_trades()
            
            if not selected_trades:
                logger.info("No trades selected for execution")
                return
            
            logger.info(f"Found {len(selected_trades)} selected trades")
            
            # Filter trades for today's execution
            today = datetime.now().date()
            today_trades = []
            
            for trade in selected_trades:
                try:
                    earnings_date_str = trade.get('earnings_date', '')
                    if earnings_date_str:
                        earnings_date = datetime.strptime(earnings_date_str, '%Y-%m-%d').date()
                        # Execute trades for earnings today or tomorrow
                        if earnings_date >= today and earnings_date <= today + timedelta(days=1):
                            today_trades.append(trade)
                except Exception as e:
                    logger.warning(f"Error parsing earnings date for trade {trade.get('id', 'unknown')}: {e}")
                    continue
            
            if not today_trades:
                logger.info("No trades scheduled for execution today")
                return
            
            logger.info(f"Executing {len(today_trades)} trades for today/tomorrow earnings")
            
            # Execute trades using the trade executor
            import asyncio
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            
            try:
                execution_result = loop.run_until_complete(
                    self.trade_executor.execute_trades_with_parallel_preparation(today_trades)
                )
                
                # Schedule monitoring for executed trades
                if execution_result.get('success') and execution_result.get('executed_trades'):
                    for trade_data in execution_result['executed_trades']:
                        try:
                            trade_id = str(trade_data.get('trade_id', trade_data.get('symbol', 'unknown')))
                            order_id = trade_data.get('order_id')
                            
                            if order_id:
                                # Schedule comprehensive monitoring
                                self.order_monitor.schedule_comprehensive_monitoring(
                                    trade_id=trade_id,
                                    execution_result=trade_data,
                                    monitoring_type='entry'
                                )
                                
                                logger.info(f"Scheduled monitoring for {trade_data['symbol']} (Trade: {trade_id})")
                        
                        except Exception as e:
                            logger.error(f"Error scheduling monitoring for trade: {e}")
                
                logger.info("Trade entry job completed")
                logger.info(f"Results: {execution_result.get('message', 'No message')}")
                
            finally:
                loop.close()
            
        except Exception as e:
            logger.error(f"Error in trade entry job: {e}")

    def trade_exit_job(self):
        """Execute trade exit job - delegates to trade executor."""
        try:
            logger.info("Starting trade exit job...")
            
            # Get positions that need to be exited
            positions = self.alpaca_client.get_positions()
            
            if not positions:
                logger.info("No positions to exit")
                return
            
            # Filter for calendar spread positions
            calendar_positions = []
            for position in positions:
                symbol = position['symbol']
                
                # Extract underlying symbol from option symbol
                underlying_symbol = symbol.split('C')[0] if 'C' in symbol else symbol.split('P')[0] if 'P' in symbol else symbol
                
                if self.trade_executor._is_calendar_spread_position(underlying_symbol):
                    trade_info = self.trade_executor._get_calendar_spread_trade_info(underlying_symbol)
                    if trade_info:
                        calendar_positions.append(trade_info)
            
            if not calendar_positions:
                logger.info("No calendar spread positions to exit")
                return
            
            logger.info(f"Found {len(calendar_positions)} calendar spread positions to exit")
            
            # Exit each position
            for trade_info in calendar_positions:
                try:
                    symbol = trade_info['symbol']
                    logger.info(f"Exiting calendar spread position for {symbol}")
                    
                    # Close the calendar spread
                    exit_result = self.alpaca_client.close_calendar_spread(
                        symbol=symbol,
                        short_exp=trade_info['short_expiration'],
                        long_exp=trade_info['long_expiration'],
                        option_type='call',
                        quantity=trade_info['position_size']
                    )
                    
                    if exit_result:
                        # Schedule exit monitoring
                        trade_id = str(trade_info['trade_id'])
                        order_id = exit_result.get('order_id')
                        
                        if order_id:
                            self.order_monitor.schedule_comprehensive_monitoring(
                                trade_id=trade_id,
                                execution_result=exit_result,
                                monitoring_type='exit'
                            )
                            
                            logger.info(f"Scheduled exit monitoring for {symbol} (Trade: {trade_id})")
                        
                        # Update trade status
                        self.database.update_trade_status(trade_info['trade_id'], 'exiting')
                        
                    else:
                        logger.error(f"Failed to exit position for {symbol}")
                
                except Exception as e:
                    logger.error(f"Error exiting position for {trade_info.get('symbol', 'unknown')}: {e}")
                    continue
            
            logger.info("Trade exit job completed")
            
        except Exception as e:
            logger.error(f"Error in trade exit job: {e}")

    # Delegation methods for external access to specialized managers
    def execute_specific_trades(self, trade_ids: List[int]) -> Dict:
        """Execute specific trades by ID."""
        try:
            # Get selected trades by IDs
            selected_trades = []
            for trade_id in trade_ids:
                trade = self.database.get_trade_by_id(trade_id)
                if trade:
                    selected_trades.append(trade)
            
            if not selected_trades:
                return {'success': False, 'message': 'No valid trades found'}
            
            # Execute using trade executor
            import asyncio
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            
            try:
                result = loop.run_until_complete(
                    self.trade_executor.execute_trades_with_parallel_preparation(selected_trades)
                )
                return result
            finally:
                loop.close()
                
        except Exception as e:
            logger.error(f"Error executing specific trades: {e}")
            return {'success': False, 'message': str(e)}

    def scan_specific_symbols(self, symbols: List[str]) -> Dict:
        """Scan specific symbols."""
        return self.scan_manager.scan_specific_symbols(symbols)

    def get_scan_summary(self, days: int = 7) -> Dict:
        """Get scan summary."""
        return self.scan_manager.get_scan_summary(days)

    def force_cleanup(self, days_to_keep: int = 30) -> Dict:
        """Force immediate data cleanup."""
        return self.data_manager.force_cleanup_now(days_to_keep)

    def get_data_statistics(self) -> Dict:
        """Get data statistics."""
        return self.data_manager.get_data_statistics()

    def optimize_database(self) -> Dict:
        """Optimize database."""
        return self.data_manager.optimize_database()

    def get_monitoring_status(self, trade_id: str = None) -> Dict:
        """Get monitoring status."""
        if trade_id:
            return self.order_monitor.get_comprehensive_monitoring_status(trade_id)
        else:
            return {
                'active_monitors': self.order_monitor.get_active_monitor_count(),
                'active_trade_ids': self.order_monitor.get_active_trade_ids()
            }
