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
            
            # Daily scan at 3:30 PM ET
            self.scheduler.add_job(
                func=self.scan_manager.daily_scan_job,
                trigger=CronTrigger(hour=15, minute=30, timezone=self.et_tz),
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
            
            # Data cleanup job (Friday at 4:05 PM ET)
            self.scheduler.add_job(
                func=self.data_manager.data_cleanup_job,
                trigger=CronTrigger(day_of_week='fri', hour=16, minute=5, timezone=self.et_tz),
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

    def _get_selected_trades_for_execution(self) -> List[Dict]:
        """Get all selected trades (both auto and manually selected) from trade_selections table and convert to execution format."""
        try:
            # Get all trade selections that are marked as selected
            trade_selections = self.database.get_trade_selections()
            
            if not trade_selections:
                return []
            
            # Filter for all selected trades (both auto and manually selected, but not manually deselected)
            selected_trades = [
                selection for selection in trade_selections 
                if selection.get('is_selected') and not selection.get('manually_deselected')
            ]
            
            if not selected_trades:
                return []
            
            # Convert to the format expected by trade executor
            execution_trades = []
            for selection in selected_trades:
                # Get the latest scan result for this ticker
                scan_result = self.database.get_latest_scan_result(selection['ticker'])
                
                if scan_result:
                    # Create trade data in the format expected by trade executor
                    trade_data = {
                        'ticker': selection['ticker'],
                        'earnings_date': selection['earnings_date'],
                        'earnings_time': 'amc',  # Default to AMC
                        'recommendation_score': scan_result.get('recommendation_score', 0),
                        'filters': scan_result.get('filters', {}),
                        'reasoning': scan_result.get('reasoning', ''),
                        'status': 'selected'
                    }
                    execution_trades.append(trade_data)
            
            logger.info(f"Found {len(execution_trades)} selected trades for execution (auto + manual)")
            return execution_trades
            
        except Exception as e:
            logger.error(f"Error getting selected trades for execution: {e}")
            return []

    def get_scheduler_status(self) -> Dict:
        """Get comprehensive scheduler status."""
        try:
            jobs = []
            for job in self.scheduler.get_jobs():
                try:
                    next_run = job.next_run_time.isoformat() if hasattr(job, 'next_run_time') and job.next_run_time else None
                except AttributeError:
                    next_run = None
                
                jobs.append({
                    'id': job.id,
                    'name': job.name,
                    'next_run': next_run,
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

    def _execute_and_monitor_trades(self, trade_data_list: List[Dict], order_type: str):
        """
        Common method for executing and monitoring trades.
        
        Args:
            trade_data_list: List of trade data to execute
            order_type: Either 'entry' or 'exit' to indicate the type of order
        """
        try:
            if not trade_data_list:
                logger.info(f"No trades to {order_type}")
                return
            
            logger.info(f"Executing {len(trade_data_list)} {order_type} trades")
            
            # Execute trades using the trade executor
            import asyncio
            import concurrent.futures
            
            # Use ThreadPoolExecutor to run async functions in a separate thread
            with concurrent.futures.ThreadPoolExecutor() as executor:
                if order_type == 'entry':
                    future = executor.submit(
                        asyncio.run,
                        self.trade_executor.execute_trades_with_parallel_preparation(trade_data_list)
                    )
                else:  # exit
                    future = executor.submit(
                        asyncio.run,
                        self.trade_executor.execute_exit_trades(trade_data_list)
                    )
                
                execution_result = future.result(timeout=60)  # 60 second timeout
            
            # Schedule monitoring for executed trades
            if execution_result.get('success') and execution_result.get('executed_trades'):
                for trade_data in execution_result['executed_trades']:
                    try:
                        trade_id = str(trade_data.get('trade_id', trade_data.get('ticker', 'unknown')))
                        order_id = trade_data.get('order_id')
                        
                        if order_id:
                            # Schedule comprehensive monitoring
                            self.order_monitor.schedule_comprehensive_monitoring(
                                trade_id=trade_id,
                                execution_result=trade_data,
                                monitoring_type=order_type
                            )
                            
                            logger.info(f"Scheduled {order_type} monitoring for {trade_data['ticker']} (Trade: {trade_id})")
                    
                    except Exception as e:
                        logger.error(f"Error scheduling {order_type} monitoring for trade: {e}")
            
            # Handle exit-specific logic
            if order_type == 'exit' and execution_result.get('success'):
                for trade_data in execution_result['executed_trades']:
                    try:
                        trade_id = trade_data.get('trade_id')
                        if trade_id:
                            # Update trade status for exits
                            self.database.update_trade_status(trade_id, 'exiting')
                    except Exception as e:
                        logger.error(f"Error updating trade status for {trade_data.get('ticker', 'unknown')}: {e}")
            
            logger.info(f"Trade {order_type} job completed")
            if order_type == 'entry':
                logger.info(f"Results: {execution_result.get('message', 'No message')}")
                
        except Exception as e:
            logger.error(f"Error in trade {order_type} execution: {e}")

    def trade_entry_job(self):
        """Execute trade entry job - delegates to trade executor."""
        try:
            logger.info("Starting trade entry job...")
            
            # Get all selected trades (both auto and manually selected) from trade_selections table
            selected_trades = self._get_selected_trades_for_execution()
            
            if not selected_trades:
                logger.info("No trades selected for execution")
                return
            
            logger.info(f"Found {len(selected_trades)} selected trades")
            
            # Filter trades for today's execution
            today = datetime.now(self.et_tz).date()
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
            
            # Use common execution and monitoring method
            self._execute_and_monitor_trades(today_trades, 'entry')
            
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
            
            # Use common execution and monitoring method
            self._execute_and_monitor_trades(calendar_positions, 'exit')
            
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



    def force_cleanup(self, days_to_keep: int = 30) -> Dict:
        """Force immediate data cleanup."""
        return self.data_manager.force_cleanup_now(days_to_keep)

    def get_data_statistics(self) -> Dict:
        """Get data statistics."""
        return self.data_manager.get_data_statistics()

    def optimize_database(self) -> Dict:
        """Optimize database."""
        return self.data_manager.optimize_database()


