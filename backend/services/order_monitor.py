"""
Unified Order Monitoring Module
Combines scheduling and execution logic for comprehensive order monitoring.
Handles both entry and exit order monitoring with advanced features.
"""

import asyncio
import logging
import time
import threading
from datetime import datetime, timedelta
from typing import Dict, Optional, List
from dataclasses import dataclass
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.date import DateTrigger
import pytz
import traceback

from core.alpaca_client import AlpacaClient
from core.database import Database

logger = logging.getLogger(__name__)

@dataclass
class OrderMonitorConfig:
    """Configuration for order monitoring."""
    polling_interval: int = 30  # seconds
    max_monitoring_time: int = 8 * 60  # 8 minutes total monitoring time before market order fallback
    entry_slippage_protection: float = 0.05  # 5% for entry orders
    exit_slippage_protection: float = 0.10   # 10% for exit orders
    market_order_monitoring_time: int = 2 * 60  # 2 minutes for market order monitoring

class OrderMonitor:
    """
    Unified order monitor that handles both scheduling and execution.
    Combines the best of both previous implementations.
    """
    
    def __init__(self, scheduler: BackgroundScheduler, alpaca_client: AlpacaClient, 
                 database: Database):
        self.scheduler = scheduler
        self.client = alpaca_client
        self.database = database
        self.et_tz = pytz.timezone('US/Eastern')
        self.config = OrderMonitorConfig()
        self.active_monitors = {}  # trade_id -> monitor_thread
        self.scheduled_jobs = {}   # trade_id -> job_id

    # ==================== SCHEDULING METHODS ====================
    
    def schedule_entry_order_monitoring(self, trade_id: str, order_id: str, symbol: str,
                                      short_exp: str, long_exp: str, quantity: int = 1) -> bool:
        """Schedule entry order monitoring."""
        try:
            logger.info(f"Scheduling entry monitoring for {symbol} (Trade: {trade_id})")
            
            # Schedule monitoring to start in 30 seconds
            monitor_start_time = datetime.now(self.et_tz) + timedelta(seconds=30)
            
            job_id = f"entry_monitor_{trade_id}_{order_id}"
            
            self.scheduler.add_job(
                func=self.monitor_calendar_spread_entry,
                trigger=DateTrigger(run_date=monitor_start_time),
                args=[trade_id, order_id, symbol, short_exp, long_exp, quantity],
                id=job_id,
                name=f'Entry Monitor - {symbol} ({trade_id})',
                replace_existing=True
            )
            
            # Track scheduled job
            self.scheduled_jobs[trade_id] = job_id
            
            logger.info(f"Scheduled entry monitoring job: {job_id}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to schedule entry monitoring for {trade_id}: {e}")
            return False

    def schedule_exit_order_monitoring(self, trade_id: str, order_id: str, symbol: str,
                                     short_exp: str, long_exp: str, quantity: int = 1) -> bool:
        """Schedule exit order monitoring."""
        try:
            logger.info(f"Scheduling exit monitoring for {symbol} (Trade: {trade_id})")
            
            # Schedule monitoring to start in 30 seconds
            monitor_start_time = datetime.now(self.et_tz) + timedelta(seconds=30)
            
            job_id = f"exit_monitor_{trade_id}_{order_id}"
            
            self.scheduler.add_job(
                func=self.monitor_calendar_spread_exit,
                trigger=DateTrigger(run_date=monitor_start_time),
                args=[trade_id, order_id, symbol, short_exp, long_exp, quantity],
                id=job_id,
                name=f'Exit Monitor - {symbol} ({trade_id})',
                replace_existing=True
            )
            
            # Track scheduled job
            self.scheduled_jobs[trade_id] = job_id
            
            logger.info(f"Scheduled exit monitoring job: {job_id}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to schedule exit monitoring for {trade_id}: {e}")
            return False

    def schedule_comprehensive_monitoring(self, trade_id: str, execution_result: Dict, 
                                        monitoring_type: str = 'entry') -> bool:
        """Schedule comprehensive monitoring with all advanced features."""
        try:
            symbol = execution_result.get('symbol')
            order_id = execution_result.get('order_id')
            
            logger.info(f"Scheduling comprehensive {monitoring_type} monitoring for {symbol} (Trade: {trade_id})")
            
            # Schedule monitoring to start immediately
            monitor_start_time = datetime.now(self.et_tz) + timedelta(seconds=5)
            
            job_id = f"comprehensive_{monitoring_type}_monitor_{trade_id}_{order_id}"
            
            if monitoring_type == 'entry':
                monitor_func = self.monitor_calendar_spread_entry
                args = [
                    trade_id,
                    order_id,
                    symbol,
                    execution_result.get('short_expiration'),
                    execution_result.get('long_expiration'),
                    execution_result.get('quantity', 1)
                ]
            else:  # exit
                monitor_func = self.monitor_calendar_spread_exit
                args = [
                    trade_id,
                    order_id,
                    symbol,
                    execution_result.get('short_expiration'),
                    execution_result.get('long_expiration'),
                    execution_result.get('quantity', 1)
                ]
            
            self.scheduler.add_job(
                func=monitor_func,
                trigger=DateTrigger(run_date=monitor_start_time),
                args=args,
                id=job_id,
                name=f'Comprehensive {monitoring_type.title()} Monitor - {symbol} ({trade_id})',
                replace_existing=True
            )
            
            # Track scheduled job
            self.scheduled_jobs[trade_id] = job_id
            
            logger.info(f"Scheduled comprehensive {monitoring_type} monitoring job: {job_id}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to schedule comprehensive monitoring for {trade_id}: {e}")
            return False

    # ==================== EXECUTION METHODS ====================
    
    async def monitor_calendar_spread_entry(self, trade_id: str, order_id: str, symbol: str,
                                           short_exp: str, long_exp: str, quantity: int = 1) -> None:
        """Monitor calendar spread entry order with advanced features."""
        try:
            logger.info(f"🔍 Starting entry monitoring for {symbol} (Trade: {trade_id})")
            
            # Run the monitoring loop directly since it's now async
            await self._monitor_order_loop(
                trade_id=trade_id,
                order_id=order_id,
                symbol=symbol,
                short_exp=short_exp,
                long_exp=long_exp,
                option_type='call',
                is_exit=False,
                quantity=quantity
            )
            
        except Exception as e:
            logger.error(f"❌ Error in entry monitoring for {symbol}: {e}")
        finally:
            # Clean up
            if trade_id in self.active_monitors:
                del self.active_monitors[trade_id]
            if trade_id in self.scheduled_jobs:
                del self.scheduled_jobs[trade_id]

    async def monitor_calendar_spread_exit(self, trade_id: str, order_id: str, symbol: str,
                                          short_exp: str, long_exp: str, quantity: int = 1) -> None:
        """Monitor calendar spread exit order with advanced features."""
        try:
            logger.info(f"🔍 Starting exit monitoring for {symbol} (Trade: {trade_id})")
            
            # Run the monitoring loop directly since it's now async
            await self._monitor_order_loop(
                trade_id=trade_id,
                order_id=order_id,
                symbol=symbol,
                short_exp=short_exp,
                long_exp=long_exp,
                option_type='call',
                is_exit=True,
                quantity=quantity
            )
            
        except Exception as e:
            logger.error(f"❌ Error in exit monitoring for {symbol}: {e}")
        finally:
            # Clean up
            if trade_id in self.active_monitors:
                del self.active_monitors[trade_id]
            if trade_id in self.scheduled_jobs:
                del self.scheduled_jobs[trade_id]

    async def _monitor_order_loop(self, trade_id: str, order_id: str, symbol: str, 
                                 short_exp: str, long_exp: str, option_type: str, 
                                 is_exit: bool, quantity: int = 1) -> None:
        """Generic monitoring loop for both entry and exit orders."""
        try:
            start_time = datetime.now(self.et_tz)
            last_price_update = start_time
            price_update_interval = timedelta(seconds=30)
            poll_count = 0
            
            # Get appropriate slippage protection
            slippage_protection = (self.config.exit_slippage_protection if is_exit 
                                 else self.config.entry_slippage_protection)
            
            logger.info(f"🔍 Starting {'exit' if is_exit else 'entry'} monitoring for {symbol}")
            logger.info(f"  - Max monitoring time: {self.config.max_monitoring_time // 60} minutes")
            logger.info(f"  - Slippage protection: {slippage_protection * 100:.1f}%")
            logger.info(f"  - Polling interval: {self.config.polling_interval} seconds")
            logger.info(f"  - Order ID: {order_id}")
            
            while True:
                current_time = datetime.now(self.et_tz)
                poll_count += 1
                
                # Check if we've exceeded max monitoring time
                elapsed_time = (current_time - start_time).total_seconds()
                if elapsed_time > self.config.max_monitoring_time:
                    logger.warning(f"⏰ Max monitoring time exceeded for {symbol}, switching to market order")
                    await self._handle_monitoring_timeout(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                    break
                
                logger.debug(f"🔍 Poll #{poll_count} for {symbol} (Order: {order_id}) - Elapsed: {elapsed_time:.1f}s")
                
                # Check order status
                order_status = await self._check_order_status_with_retry(order_id, symbol)
                if not order_status:
                    logger.error(f"❌ Could not get order status for {symbol}")
                    break
                
                status = order_status.get('status', '').lower()
                
                if status == 'filled':
                    logger.info(f"✅ Order filled for {symbol}!")
                    await self._handle_order_filled(symbol, order_status, is_exit)
                    break
                    
                elif status in ['cancelled', 'rejected', 'expired']:
                    logger.warning(f"⚠️ Order {status} for {symbol}")
                    await self._handle_order_cancelled(symbol, order_status, is_exit)
                    break
                
                # Update prices periodically for slippage protection
                if current_time - last_price_update > price_update_interval:
                    logger.debug(f"📊 Checking slippage protection for {symbol}")
                    await self._check_slippage_protection(symbol, short_exp, long_exp, option_type, 
                                                      is_exit, quantity, slippage_protection, order_id)
                    last_price_update = current_time
                
                # Wait before next check - FIXED: Use async sleep instead of blocking sleep
                logger.debug(f"⏳ Waiting {self.config.polling_interval} seconds before next poll for {symbol}")
                await asyncio.sleep(self.config.polling_interval)
                
        except Exception as e:
            logger.error(f"❌ Error in monitoring loop for {symbol}: {e}")
            logger.error(f"❌ Stack trace: {traceback.format_exc()}")

    async def _check_order_status_with_retry(self, order_id: str, symbol: str) -> Optional[Dict]:
        """Check order status with retry logic."""
        max_retries = 3
        retry_delay = 5
        
        for attempt in range(max_retries):
            try:
                logger.debug(f"🔍 Checking order status for {symbol} order {order_id} (Attempt {attempt + 1}/{max_retries})")
                order_status = self.client.get_order_status(order_id)
                
                if order_status:
                    return order_status
                    
            except Exception as e:
                logger.warning(f"⚠️ Failed to get order status for {symbol} order {order_id} (Attempt {attempt + 1}/{max_retries}): {e}")
                
                if 'not found' in str(e).lower() or 'invalid' in str(e).lower():
                    break
                
                if attempt < max_retries - 1:
                    await asyncio.sleep(retry_delay)
                    retry_delay *= 2
        
        logger.error(f"❌ Failed to get order status for {symbol} order {order_id} after {max_retries} attempts")
        return None

    async def _check_slippage_protection(self, symbol: str, short_exp: str, long_exp: str,
                                        option_type: str, is_exit: bool, quantity: int,
                                        slippage_protection: float, order_id: str = None) -> None:
        """Check if slippage protection should trigger and update order if needed."""
        try:
            # Skip if no order_id provided (can't update order without ID)
            if not order_id:
                logger.debug(f"No order_id provided for slippage protection check for {symbol}")
                return
                
            # Get current order details to know the original limit price
            current_order = self.client.get_order_status(order_id)
            if not current_order:
                logger.debug(f"Could not get current order details for {order_id}, skipping slippage check")
                return
                
            # Skip if order is already filled, cancelled, or rejected
            order_status = current_order.get('status', '').lower()
            if order_status in ['filled', 'cancelled', 'rejected', 'expired']:
                logger.debug(f"Order {order_id} is {order_status}, skipping slippage check")
                return
                
            original_limit_price = current_order.get('limit_price')
            if not original_limit_price:
                logger.debug(f"Order {order_id} has no limit price, skipping slippage check")
                return
                
            # Get current market prices using existing method
            current_price = self.client.get_current_price(symbol)
            if not current_price:
                logger.debug(f"Could not get current price for {symbol}, skipping slippage check")
                return
                
            target_strike = round(current_price)
            
            # Get options data for both expirations
            short_options = self.client.discover_available_options(symbol, short_exp)
            long_options = self.client.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.debug(f"Could not get options data for {symbol}, skipping slippage check")
                return
            
            # Find the closest strike options
            short_symbol = self.client._find_closest_strike_option(short_options, target_strike)
            long_symbol = self.client._find_closest_strike_option(long_options, target_strike)
            
            if not short_symbol or not long_symbol:
                logger.debug(f"Could not find suitable options for {symbol}, skipping slippage check")
                return
            
            # Calculate current market prices for the spread
            order_type = 'exit' if is_exit else 'entry'
            current_prices = self.client.calculate_calendar_spread_limit_price(
                long_symbol, short_symbol, order_type
            )
            
            if not current_prices:
                logger.debug(f"Could not calculate current prices for {symbol}, skipping slippage check")
                return
            
            current_market_price = current_prices.get('limit_price')
            if not current_market_price:
                logger.debug(f"No current market price available for {symbol}, skipping slippage check")
                return
            
            # Calculate slippage as percentage difference
            price_diff = abs(current_market_price - original_limit_price)
            slippage_pct = price_diff / abs(original_limit_price) if original_limit_price != 0 else 0
            
            logger.debug(f"📊 Slippage check for {symbol}: Original=${original_limit_price:.2f}, Current=${current_market_price:.2f}, Slippage={slippage_pct:.1%}")
            
            # Check if slippage exceeds protection threshold
            if slippage_pct > slippage_protection:
                logger.info(f"🚨 Slippage protection triggered for {symbol}: {slippage_pct:.1%} > {slippage_protection:.1%}")
                
                # Update the order with new limit price
                result = self.client.replace_order(order_id, current_market_price)
                
                if result and result.get('status') == 'replaced':
                    logger.info(f"✅ Updated order {order_id} limit price: ${original_limit_price:.2f} → ${current_market_price:.2f}")
                else:
                    logger.warning(f"⚠️ Failed to update order {order_id} limit price")
            else:
                logger.debug(f"✅ Slippage within tolerance for {symbol}: {slippage_pct:.1%} <= {slippage_protection:.1%}")
            
        except Exception as e:
            logger.debug(f"⚠️ Could not check slippage protection for {symbol}: {e}")
            return

    async def _handle_monitoring_timeout(self, symbol: str, short_exp: str, long_exp: str,
                                       option_type: str, is_exit: bool, quantity: int) -> None:
        """Handle monitoring timeout by switching to market order."""
        try:
            logger.warning(f"⏰ Monitoring timeout for {symbol}, placing market order")
            
            if is_exit:
                # Close position with market order - FIXED: Use market order type
                result = self.client.close_calendar_spread(
                    symbol, short_exp, long_exp, option_type, quantity, order_type="market"
                )
            else:
                # Entry with market order
                logger.info(f"📈 Placing market order for calendar spread entry: {symbol}")
                result = self.client.place_calendar_spread_order(
                    symbol=symbol,
                    short_exp=short_exp,
                    long_exp=long_exp,
                    option_type=option_type,
                    quantity=quantity,
                    order_type="market"
                )
            
            if result:
                logger.info(f"✅ Market order placed successfully for {symbol}")
            else:
                logger.error(f"❌ Failed to place market order for {symbol}")
                
        except Exception as e:
            logger.error(f"❌ Error handling monitoring timeout for {symbol}: {e}")

    async def _handle_order_filled(self, symbol: str, order_status: Dict, is_exit: bool) -> None:
        """Handle order filled event."""
        try:
            logger.info(f"✅ Order filled for {symbol} - {'exit' if is_exit else 'entry'} completed")
            
            # Update database if needed
            if self.database:
                try:
                    # Update trade status to 'filled' in database
                    # This would typically update the trade status and add to trade history
                    logger.info(f"Database update needed for filled order: {symbol}")
                except Exception as db_error:
                    logger.warning(f"Could not update database for filled order: {db_error}")
            
        except Exception as e:
            logger.error(f"❌ Error handling order filled for {symbol}: {e}")

    async def _handle_order_cancelled(self, symbol: str, order_status: Dict, is_exit: bool) -> None:
        """Handle order cancelled event."""
        try:
            logger.warning(f"⚠️ Order cancelled for {symbol} - {'exit' if is_exit else 'entry'} failed")
            
            # Update database if needed
            if self.database:
                try:
                    # Update trade status to 'cancelled' in database
                    # This would typically update the trade status
                    logger.info(f"Database update needed for cancelled order: {symbol}")
                except Exception as db_error:
                    logger.warning(f"Could not update database for cancelled order: {db_error}")
            
        except Exception as e:
            logger.error(f"❌ Error handling order cancelled for {symbol}: {e}")

    # ==================== MANAGEMENT METHODS ====================
    
    def stop_order_monitoring(self, trade_id: str) -> bool:
        """Stop monitoring for a specific trade."""
        try:
            # Stop active monitoring thread
            if trade_id in self.active_monitors:
                thread = self.active_monitors[trade_id]
                if thread.is_alive():
                    # Set a flag to stop the monitoring loop
                    # Note: This is a simple approach - in production you might want a more sophisticated stop mechanism
                    logger.info(f"🛑 Stopping order monitoring for trade {trade_id}")
                del self.active_monitors[trade_id]
                logger.info(f"✅ Stopped order monitoring for trade {trade_id}")
            
            # Remove scheduled job
            if trade_id in self.scheduled_jobs:
                job_id = self.scheduled_jobs[trade_id]
                try:
                    self.scheduler.remove_job(job_id)
                    logger.info(f"✅ Removed scheduled monitoring job {job_id}")
                except Exception as e:
                    logger.warning(f"⚠️ Could not remove scheduled job {job_id}: {e}")
                del self.scheduled_jobs[trade_id]
            
            return True
            
        except Exception as e:
            logger.error(f"❌ Error stopping order monitoring for trade {trade_id}: {e}")
            return False

    def stop_all_advanced_monitoring(self):
        """Stop all advanced monitoring."""
        try:
            logger.info("🛑 Stopping all advanced monitoring...")
            
            # Stop all active monitors
            for trade_id in list(self.active_monitors.keys()):
                self.stop_order_monitoring(trade_id)
            
            # Remove all scheduled jobs
            for trade_id in list(self.scheduled_jobs.keys()):
                job_id = self.scheduled_jobs[trade_id]
                try:
                    self.scheduler.remove_job(job_id)
                    logger.info(f"✅ Removed scheduled job {job_id}")
                except Exception as e:
                    logger.warning(f"⚠️ Could not remove scheduled job {job_id}: {e}")
            
            # Clear tracking
            self.active_monitors.clear()
            self.scheduled_jobs.clear()
            
            logger.info("✅ All advanced monitoring stopped")
            
        except Exception as e:
            logger.error(f"❌ Error stopping all advanced monitoring: {e}")

    def get_active_monitor_count(self) -> int:
        """Get count of active monitoring tasks."""
        return len(self.active_monitors)

    def get_active_trade_ids(self) -> List[str]:
        """Get list of trade IDs with active monitoring."""
        return list(self.active_monitors.keys())

    def get_comprehensive_monitoring_status(self, trade_id: str) -> Optional[Dict]:
        """Get comprehensive monitoring status for a trade."""
        try:
            # Check if monitoring is active
            is_active = trade_id in self.active_monitors
            thread = self.active_monitors.get(trade_id)
            
            # Check if job is scheduled
            job_id = self.scheduled_jobs.get(trade_id)
            scheduled_job = None
            if job_id:
                try:
                    scheduled_job = self.scheduler.get_job(job_id)
                except Exception:
                    pass
            
            return {
                'trade_id': trade_id,
                'active_monitoring': is_active,
                'thread_status': 'running' if thread and thread.is_alive() else 'completed' if thread and not thread.is_alive() else 'none',
                'scheduled_job': {
                    'job_id': job_id,
                    'next_run': scheduled_job.next_run_time.isoformat() if scheduled_job and hasattr(scheduled_job, 'next_run_time') and scheduled_job.next_run_time else None,
                    'name': scheduled_job.name if scheduled_job else None
                } if scheduled_job else None,
                'status': 'active' if is_active or job_id else 'inactive'
            }
            
        except Exception as e:
            logger.error(f"Error getting comprehensive monitoring status for {trade_id}: {e}")
            return None

    def get_monitoring_config_status(self) -> Dict:
        """Get the current monitoring configuration status."""
        try:
            return {
                'polling_interval': self.config.polling_interval,
                'max_monitoring_time': self.config.max_monitoring_time,
                'entry_slippage_protection': self.config.entry_slippage_protection,
                'exit_slippage_protection': self.config.exit_slippage_protection,
                'market_order_monitoring_time': self.config.market_order_monitoring_time,
                'active_monitors_count': len(self.active_monitors),
                'scheduled_jobs_count': len(self.scheduled_jobs),
                'scheduler_running': self.scheduler.running if hasattr(self.scheduler, 'running') else False
            }
        except Exception as e:
            logger.error(f"Error getting monitoring config status: {e}")
            return {'error': str(e)}

    def force_start_monitoring(self, trade_id: str, order_id: str, symbol: str,
                              short_exp: str, long_exp: str, quantity: int = 1) -> bool:
        """Force start monitoring for a trade (useful for debugging)."""
        try:
            logger.info(f"🔄 Force starting monitoring for {symbol} (Trade: {trade_id})")
            
            # Cancel any existing monitoring
            if trade_id in self.active_monitors:
                self.stop_order_monitoring(trade_id)
            
            # Schedule new monitoring immediately
            success = self.schedule_comprehensive_monitoring(
                trade_id=trade_id,
                execution_result={
                    'symbol': symbol,
                    'order_id': order_id,
                    'short_expiration': short_exp,
                    'long_expiration': long_exp,
                    'quantity': quantity
                },
                monitoring_type='entry'
            )
            
            if success:
                logger.info(f"✅ Force started monitoring for {symbol}")
            else:
                logger.error(f"❌ Failed to force start monitoring for {symbol}")
            
            return success
            
        except Exception as e:
            logger.error(f"❌ Error force starting monitoring for {symbol}: {e}")
            return False
