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
    max_monitoring_time: int = 8 * 60  # 8 minutes total monitoring time before limit order fallback
    price_update_threshold: float = 0.01  # 1% threshold for price updates during 0-8 minute period
    entry_timeout_premium: float = 0.05  # 5% above midpoint for entry orders after 8 minutes
    exit_timeout_premium: float = 0.10   # 10% above midpoint for exit orders after 8 minutes
    market_order_monitoring_time: int = 2 * 60  # 2 minutes for timeout order monitoring

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

    # ==================== MONITORING METHODS ====================
    
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
            last_calculated_price = None  # Track the last calculated price for comparison
            
            logger.info(f"🔍 Starting {'exit' if is_exit else 'entry'} monitoring for {symbol}")
            logger.info(f"  - Max monitoring time: {self.config.max_monitoring_time // 60} minutes")
            logger.info(f"  - Price update threshold: {self.config.price_update_threshold * 100:.1f}%")
            logger.info(f"  - Polling interval: {self.config.polling_interval} seconds")
            logger.info(f"  - Order ID: {order_id}")
            
            while True:
                current_time = datetime.now(self.et_tz)
                poll_count += 1
                
                # Check if we've exceeded max monitoring time
                elapsed_time = (current_time - start_time).total_seconds()
                if elapsed_time > self.config.max_monitoring_time:
                    logger.warning(f"⏰ Max monitoring time exceeded for {symbol}, attempting to place timeout limit order")
                    timeout_order_result = await self._handle_monitoring_timeout(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                    
                    if timeout_order_result and timeout_order_result.get('order_id'):
                        # Continue monitoring the timeout order for 2 more minutes
                        logger.info(f"🔄 Continuing monitoring for timeout order: {timeout_order_result['order_id']}")
                        timeout_order_start_time = current_time
                        timeout_order_id = timeout_order_result['order_id']
                        
                        # Continue monitoring loop for timeout order
                        while True:
                            current_time = datetime.now(self.et_tz)
                            timeout_elapsed_time = (current_time - timeout_order_start_time).total_seconds()
                            
                            # Check if we've exceeded timeout order monitoring time
                            if timeout_elapsed_time > self.config.market_order_monitoring_time:
                                logger.warning(f"⏰ Timeout order monitoring timeout for {symbol}, executing final fallback")
                                await self._handle_market_order_timeout(symbol, short_exp, long_exp, option_type, is_exit, quantity, timeout_order_id)
                                break
                            
                            # Check timeout order status
                            timeout_order_status = await self._check_order_status_with_retry(timeout_order_id, symbol)
                            if timeout_order_status:
                                status = timeout_order_status.get('status', '').lower()
                                if status == 'filled':
                                    logger.info(f"✅ Timeout order filled for {symbol}!")
                                    await self._handle_order_filled(symbol, timeout_order_status, is_exit)
                                    break
                            
                            # Wait before next check
                            await asyncio.sleep(self.config.polling_interval)
                        break
                    else:
                        # Timeout order placement failed, continue monitoring original order
                        # The system will try again at the next 30-second interval
                        logger.info(f"⏳ Timeout order placement failed for {symbol}, continuing to monitor original order")
                        logger.info(f"⏳ Will attempt timeout order placement again at next 30-second interval")
                        # Continue with the main monitoring loop - don't break
                
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
                
                # Update prices periodically for price updates (0-8 minute period)
                if current_time - last_price_update > price_update_interval:
                    logger.debug(f"📊 Checking for price updates for {symbol}")
                    last_calculated_price = await self._check_and_update_price(symbol, short_exp, long_exp, option_type, 
                                                                           is_exit, quantity, order_id, start_time, last_calculated_price)
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

    async def _check_and_update_price(self, symbol: str, short_exp: str, long_exp: str,
                                     option_type: str, is_exit: bool, quantity: int,
                                     order_id: str, start_time: datetime, last_calculated_price: Optional[float]) -> Optional[float]:
        """Check if price has changed more than 1% and update order if needed (0-8 minute period)."""
        try:
            # Skip if no order_id provided (can't update order without ID)
            if not order_id:
                logger.debug(f"No order_id provided for price update check for {symbol}")
                return last_calculated_price
                
            # Get current order details to know the current limit price
            current_order = self.client.get_order_status(order_id)
            if not current_order:
                logger.debug(f"Could not get current order details for {order_id}, skipping price update check")
                return last_calculated_price
                
            # Skip if order is already filled, cancelled, or rejected
            order_status = current_order.get('status', '').lower()
            if order_status in ['filled', 'cancelled', 'rejected', 'expired']:
                logger.debug(f"Order {order_id} is {order_status}, skipping price update check")
                return last_calculated_price
                
            current_limit_price = current_order.get('limit_price')
            if not current_limit_price:
                logger.debug(f"Order {order_id} has no limit price, skipping price update check")
                return last_calculated_price
                
            # Get current market prices using existing method
            current_price = self.client.get_current_price(symbol)
            if not current_price:
                logger.debug(f"Could not get current price for {symbol}, skipping price update check")
                return last_calculated_price
                
            target_strike = round(current_price)
            
            # Get options data for both expirations
            short_options = self.client.discover_available_options(symbol, short_exp)
            long_options = self.client.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.debug(f"Could not get options data for {symbol}, skipping price update check")
                return last_calculated_price
            
            # Find the closest strike options
            short_symbol = self.client._find_closest_strike_option(short_options, target_strike)
            long_symbol = self.client._find_closest_strike_option(long_options, target_strike)
            
            if not short_symbol or not long_symbol:
                logger.debug(f"Could not find suitable options for {symbol}, skipping price update check")
                return last_calculated_price
            
            # Calculate current market prices for the spread
            order_type = 'exit' if is_exit else 'entry'
            current_prices = self.client.calculate_calendar_spread_limit_price(
                long_symbol, short_symbol, order_type
            )
            
            if not current_prices:
                logger.debug(f"Could not calculate current prices for {symbol}, skipping price update check")
                return last_calculated_price
            
            new_calculated_price = current_prices.get('limit_price')
            if not new_calculated_price:
                logger.debug(f"No current market price available for {symbol}, skipping price update check")
                return last_calculated_price
            
            # If this is the first calculation, just return the price
            if last_calculated_price is None:
                logger.debug(f"📊 First price calculation for {symbol}: ${new_calculated_price:.2f}")
                return new_calculated_price
            
            # Calculate price change as percentage difference from last calculation
            price_diff = abs(new_calculated_price - last_calculated_price)
            price_change_pct = price_diff / abs(last_calculated_price) if last_calculated_price != 0 else 0
            
            logger.debug(f"📊 Price update check for {symbol}: Last=${last_calculated_price:.2f}, Current=${new_calculated_price:.2f}, Change={price_change_pct:.1%}")
            
            # Check if price has changed more than 1% threshold
            if price_change_pct > self.config.price_update_threshold:
                logger.info(f"🔄 Price update triggered for {symbol}: {price_change_pct:.1%} > {self.config.price_update_threshold:.1%}")
                
                # Update the order with new calculated price (not the current limit price)
                result = self.client.replace_order(order_id, new_calculated_price)
                
                if result and result.get('status') == 'replaced':
                    logger.info(f"✅ Updated order {order_id} limit price: ${current_limit_price:.2f} → ${new_calculated_price:.2f}")
                else:
                    logger.warning(f"⚠️ Failed to update order {order_id} limit price")
            else:
                logger.debug(f"✅ Price change within threshold for {symbol}: {price_change_pct:.1%} <= {self.config.price_update_threshold:.1%}")
            
            return new_calculated_price
            
        except Exception as e:
            logger.debug(f"⚠️ Could not check price updates for {symbol}: {e}")
            return last_calculated_price

    async def _handle_monitoring_timeout(self, symbol: str, short_exp: str, long_exp: str,
                                       option_type: str, is_exit: bool, quantity: int) -> Optional[Dict]:
        """Handle monitoring timeout by switching to limit order with calculated price plus percentage."""
        try:
            logger.warning(f"⏰ Monitoring timeout for {symbol}, attempting to place limit order with calculated price")
            
            # Calculate current market price using the same method as price updates
            current_price = self.client.get_current_price(symbol)
            if not current_price:
                logger.warning(f"⚠️ Could not get current price for {symbol}, will retry at next interval")
                return None
            
            target_strike = round(current_price)
            
            # Get options data for both expirations
            short_options = self.client.discover_available_options(symbol, short_exp)
            long_options = self.client.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.warning(f"⚠️ Could not get options data for {symbol}, will retry at next interval")
                return None
            
            # Find the closest strike options
            short_symbol = self.client._find_closest_strike_option(short_options, target_strike)
            long_symbol = self.client._find_closest_strike_option(long_options, target_strike)
            
            if not short_symbol or not long_symbol:
                logger.warning(f"⚠️ Could not find suitable options for {symbol}, will retry at next interval")
                return None
            
            # Calculate current market prices for the spread
            order_type = 'exit' if is_exit else 'entry'
            current_prices = self.client.calculate_calendar_spread_limit_price(
                long_symbol, short_symbol, order_type
            )
            
            if not current_prices:
                logger.warning(f"⚠️ Could not calculate current prices for {symbol}, will retry at next interval")
                return None
            
            current_market_price = current_prices.get('limit_price')
            if not current_market_price:
                logger.warning(f"⚠️ No current market price available for {symbol}, will retry at next interval")
                return None
            
            # Calculate limit price with percentage above midpoint
            # Entry orders: 5% above midpoint, Exit orders: 10% above midpoint
            percentage_above = self.config.entry_timeout_premium if not is_exit else self.config.exit_timeout_premium
            limit_price = current_market_price * (1 + percentage_above)
            
            logger.info(f"📊 Calculated limit price for {symbol}: Market=${current_market_price:.2f}, Limit=${limit_price:.2f} (+{percentage_above:.1%})")
            
            if is_exit:
                # Close position with limit order
                result = self.client.close_calendar_spread(
                    symbol, short_exp, long_exp, option_type, quantity, 
                    order_type="limit", limit_price=limit_price
                )
            else:
                # Entry with limit order
                logger.info(f"📈 Placing limit order for calendar spread entry: {symbol}")
                result = self.client.place_calendar_spread_order(
                    symbol=symbol,
                    short_exp=short_exp,
                    long_exp=long_exp,
                    option_type=option_type,
                    quantity=quantity,
                    order_type="limit",
                    limit_price=limit_price
                )
            
            if result:
                logger.info(f"✅ Limit order placed successfully for {symbol} at ${limit_price:.2f}")
                return result
            else:
                logger.warning(f"⚠️ Failed to place limit order for {symbol}, will retry at next interval")
                return None
                
        except Exception as e:
            logger.warning(f"⚠️ Error handling monitoring timeout for {symbol}: {e}, will retry at next interval")
            return None

    async def _handle_market_order_timeout(self, symbol: str, short_exp: str, long_exp: str,
                                          option_type: str, is_exit: bool, quantity: int,
                                          market_order_id: str) -> None:
        """Handle final fallback after market order monitoring timeout with limit orders."""
        try:
            logger.warning(f"⏰ Final fallback for {symbol} - {'exit' if is_exit else 'entry'} with limit order")
            
            # Calculate current market price using the same method as slippage protection
            current_price = self.client.get_current_price(symbol)
            if not current_price:
                logger.error(f"❌ Could not get current price for {symbol}, using pure market order")
                await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                return
            
            target_strike = round(current_price)
            
            # Get options data for both expirations
            short_options = self.client.discover_available_options(symbol, short_exp)
            long_options = self.client.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.error(f"❌ Could not get options data for {symbol}, using pure market order")
                await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                return
            
            # Find the closest strike options
            short_symbol = self.client._find_closest_strike_option(short_options, target_strike)
            long_symbol = self.client._find_closest_strike_option(long_options, target_strike)
            
            if not short_symbol or not long_symbol:
                logger.error(f"❌ Could not find suitable options for {symbol}, using pure market order")
                await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                return
            
            # Calculate current market prices for the spread
            order_type = 'exit' if is_exit else 'entry'
            current_prices = self.client.calculate_calendar_spread_limit_price(
                long_symbol, short_symbol, order_type
            )
            
            if not current_prices:
                logger.error(f"❌ Could not calculate current prices for {symbol}, using pure market order")
                await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                return
            
            current_market_price = current_prices.get('limit_price')
            if not current_market_price:
                logger.error(f"❌ No current market price available for {symbol}, using pure market order")
                await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)
                return
            
            # Calculate limit price with percentage above midpoint
            # Entry orders: 5% above midpoint, Exit orders: 10% above midpoint
            percentage_above = self.config.entry_timeout_premium if not is_exit else self.config.exit_timeout_premium
            limit_price = current_market_price * (1 + percentage_above)
            
            logger.info(f"📊 Final fallback calculated limit price for {symbol}: Market=${current_market_price:.2f}, Limit=${limit_price:.2f} (+{percentage_above:.1%})")
            
            if is_exit:
                # Exit: Place limit order with calculated price
                logger.info(f"🚨 Force exit for {symbol} - placing limit order at ${limit_price:.2f}")
                result = self.client.close_calendar_spread(
                    symbol, short_exp, long_exp, option_type, quantity, 
                    order_type="limit", limit_price=limit_price
                )
                if result:
                    logger.info(f"✅ Final fallback limit exit order placed for {symbol}")
                else:
                    logger.error(f"❌ Failed to place final fallback limit exit order for {symbol}")
            else:
                # Entry: Cancel the market order and place limit order
                logger.info(f"🚫 Force cancel for {symbol} - cancelling market order and placing limit order")
                try:
                    self.client.cancel_order(market_order_id)
                    logger.info(f"✅ Market entry order cancelled for {symbol}")
                    
                    # Place limit order with calculated price
                    result = self.client.place_calendar_spread_order(
                        symbol=symbol,
                        short_exp=short_exp,
                        long_exp=long_exp,
                        option_type=option_type,
                        quantity=quantity,
                        order_type="limit",
                        limit_price=limit_price
                    )
                    
                    if result:
                        logger.info(f"✅ Final fallback limit entry order placed for {symbol} at ${limit_price:.2f}")
                    else:
                        logger.error(f"❌ Failed to place final fallback limit entry order for {symbol}")
                        
                except Exception as e:
                    logger.error(f"❌ Failed to cancel market order for {symbol}: {e}")
                    
        except Exception as e:
            logger.error(f"❌ Error in final fallback for {symbol}: {e}")
            logger.error(f"❌ Using pure market order fallback")
            await self._force_market_order_fallback(symbol, short_exp, long_exp, option_type, is_exit, quantity)

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

    # ==================== FALLBACK HELPER METHODS ====================
    
    async def _fallback_to_market_order(self, symbol: str, short_exp: str, long_exp: str,
                                       option_type: str, is_exit: bool, quantity: int) -> Dict:
        """Fallback to market order when limit order calculation fails."""
        try:
            logger.warning(f"🔄 Falling back to market order for {symbol}")
            
            if is_exit:
                result = self.client.close_calendar_spread(
                    symbol, short_exp, long_exp, option_type, quantity, order_type="market"
                )
            else:
                result = self.client.place_calendar_spread_order(
                    symbol=symbol,
                    short_exp=short_exp,
                    long_exp=long_exp,
                    option_type=option_type,
                    quantity=quantity,
                    order_type="market"
                )
            
            if result:
                logger.info(f"✅ Market order fallback successful for {symbol}")
                return result
            else:
                logger.error(f"❌ Market order fallback failed for {symbol}")
                return {}
                
        except Exception as e:
            logger.error(f"❌ Error in market order fallback for {symbol}: {e}")
            return {}
    
    async def _force_market_order_fallback(self, symbol: str, short_exp: str, long_exp: str,
                                          option_type: str, is_exit: bool, quantity: int) -> None:
        """Force fallback to market order when all else fails."""
        try:
            logger.warning(f"🚨 Force market order fallback for {symbol}")
            
            if is_exit:
                result = self.client.close_calendar_spread(
                    symbol, short_exp, long_exp, option_type, quantity, order_type="market"
                )
                if result:
                    logger.info(f"✅ Force market exit order placed for {symbol}")
                else:
                    logger.error(f"❌ Failed to place force market exit order for {symbol}")
            else:
                # For entry, we just log that we're not placing a market order
                logger.info(f"🚫 Force cancel for {symbol} - no market order placed for entry")
                    
        except Exception as e:
            logger.error(f"❌ Error in force market order fallback for {symbol}: {e}")






