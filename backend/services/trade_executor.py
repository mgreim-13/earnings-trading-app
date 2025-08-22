"""
Trade Execution Module
Handles trade preparation, execution, and position management for calendar spreads.
"""

import logging
import asyncio
from datetime import datetime, timedelta
from typing import List, Dict, Optional
import pytz

from core.earnings_scanner import EarningsScanner
from utils.filters import compute_recommendation
from core.alpaca_client import AlpacaClient
from core.database import Database
import config

logger = logging.getLogger(__name__)

# Constants for better maintainability
DEFAULT_EARNINGS_TIME = 'amc'
DEFAULT_POSITION_SIZE = 1

class TradeExecutor:
    """Handles trade preparation and execution logic."""
    
    def __init__(self, alpaca_client: AlpacaClient, database: Database, earnings_scanner: EarningsScanner):
        self.alpaca_client = alpaca_client
        self.database = database
        self.earnings_scanner = earnings_scanner
        self.et_tz = pytz.timezone('US/Eastern')
    
    def calculate_position_size(self, trade_data: Dict) -> int:
        """Calculate position size based on account balance and risk management."""
        try:
            # Get account info
            account = self.alpaca_client.get_account_info()
            if not account:
                logger.error("Could not get account info for position sizing")
                return DEFAULT_POSITION_SIZE
            
            # Get available buying power
            buying_power = account.get('buying_power', 0)
            
            # Risk management parameters
            max_risk_per_trade = config.MAX_RISK_PER_TRADE  # e.g., 2% of account
            max_position_size = config.MAX_POSITION_SIZE    # e.g., 5 contracts max
            
            # Calculate max risk amount
            max_risk_amount = buying_power * max_risk_per_trade
            
            # Get estimated cost per contract from trade data
            estimated_cost = trade_data.get('estimated_cost', 0)
            if estimated_cost <= 0:
                symbol = trade_data.get('symbol', 'unknown')
                logger.warning(f"No estimated cost available for {symbol}, using default position size of {DEFAULT_POSITION_SIZE}")
                return DEFAULT_POSITION_SIZE
            
            # Calculate position size based on risk
            # Each options contract represents 100 shares
            cost_per_contract = estimated_cost * 100
            
            if cost_per_contract <= 0:
                return DEFAULT_POSITION_SIZE
            
            # Calculate max contracts based on risk
            max_contracts_by_risk = int(max_risk_amount / cost_per_contract)
            
            # Apply position size limits
            position_size = min(max_contracts_by_risk, max_position_size)
            position_size = max(position_size, DEFAULT_POSITION_SIZE)  # Minimum 1 contract
            
            logger.info(f"Position sizing for {trade_data.get('symbol', 'unknown')}:")
            logger.info(f"  - Buying power: ${buying_power:,.2f}")
            logger.info(f"  - Max risk amount: ${max_risk_amount:,.2f}")
            logger.info(f"  - Cost per contract: ${cost_per_contract:,.2f}")
            logger.info(f"  - Position size: {position_size} contracts")
            
            return position_size
            
        except Exception as e:
            logger.error(f"Error calculating position size: {e}")
            return DEFAULT_POSITION_SIZE  # Default to 1 contract

    def prepare_calendar_spread_trade(self, symbol: str, earning: Dict, recommendation: Dict) -> Optional[Dict]:
        """Prepare a calendar spread trade with all necessary data."""
        try:
            logger.info(f"Preparing calendar spread trade for {symbol}")
            
            # Get current price
            try:
                current_price = self.alpaca_client.get_current_price(symbol)
                if not current_price:
                    logger.warning(f"Could not get current price for {symbol}")
                    return None
            except Exception as e:
                logger.warning(f"Error getting current price for {symbol}: {e}")
                return None
            
            # Calculate ATM strike
            atm_strike = round(current_price)
            
            # Get earnings date and time
            earnings_date = earning.get('date', '')
            earnings_time = earning.get('time', DEFAULT_EARNINGS_TIME)  # Use constant
            
            if not earnings_date:
                logger.warning(f"No earnings date for {symbol}")
                return None
            
            # Find suitable options for calendar spread
            try:
                calendar_options = self.alpaca_client.find_calendar_spread_options(
                    symbol=symbol,
                    target_strike=atm_strike,
                    earnings_date=earnings_date,
                    earnings_time=earnings_time
                )
                
                if not calendar_options or not calendar_options.get('best_spread'):
                    logger.warning(f"No suitable calendar spread options found for {symbol}")
                    return None
                    
            except Exception as e:
                logger.warning(f"Error finding calendar spread options for {symbol}: {e}")
                return None
            
            best_spread = calendar_options['best_spread']
            
            # Prepare trade data
            trade_data = {
                'symbol': symbol,
                'current_price': current_price,
                'atm_strike': atm_strike,
                'earnings_date': earnings_date,
                'earnings_time': earnings_time,
                'short_expiration': best_spread['short_option']['expiration'],
                'long_expiration': best_spread['long_option']['expiration'],
                'strike_price': best_spread['strike'],
                'estimated_cost': best_spread['estimated_cost'],
                'days_between': best_spread['days_between'],
                'recommendation': recommendation,
                'calendar_options': calendar_options,
                'prepared_at': datetime.now(self.et_tz).isoformat()
            }
            
            # Calculate position size
            position_size = self.calculate_position_size(trade_data)
            trade_data['position_size'] = position_size
            
            logger.info(f"Trade prepared for {symbol}:")
            logger.info(f"  - Strike: {atm_strike}")
            logger.info(f"  - Short exp: {best_spread['short_option']['expiration']}")
            logger.info(f"  - Long exp: {best_spread['long_option']['expiration']}")
            logger.info(f"  - Estimated cost: ${best_spread['estimated_cost']:.2f}")
            logger.info(f"  - Position size: {position_size} contracts")
            
            return trade_data
            
        except Exception as e:
            logger.error(f"Error preparing calendar spread trade for {symbol}: {e}")
            return None

    async def prepare_calendar_spread_trade_async(self, symbol: str, earning: Dict, recommendation: Dict) -> Optional[Dict]:
        """Async version of prepare_calendar_spread_trade."""
        try:
            # Run the synchronous method in a thread pool
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(
                None, 
                self.prepare_calendar_spread_trade, 
                symbol, 
                earning, 
                recommendation
            )
        except Exception as e:
            logger.error(f"Error in async trade preparation for {symbol}: {e}")
            return None

    async def prepare_trades_parallel(self, selected_trades: List[Dict]) -> List[Dict]:
        """Prepare multiple trades in parallel for better performance."""
        try:
            logger.info(f"Preparing {len(selected_trades)} trades in parallel")
            
            # Create tasks for parallel execution
            tasks = []
            for trade in selected_trades:
                symbol = trade.get('ticker')
                if not symbol:
                    continue
                
                # Get earnings data
                earning = {
                    'date': trade.get('earnings_date'),
                    'time': trade.get('earnings_time', DEFAULT_EARNINGS_TIME)  # Use constant
                }
                
                # Get recommendation data
                recommendation = {
                    'score': trade.get('recommendation_score', 0),
                    'filters': trade.get('filters', {}),
                    'reasoning': trade.get('reasoning', '')
                }
                
                task = self.prepare_calendar_spread_trade_async(symbol, earning, recommendation)
                tasks.append((symbol, task))
            
            # Execute all tasks in parallel
            results = []
            completed_tasks = await asyncio.gather(*[task for _, task in tasks], return_exceptions=True)
            
            for i, result in enumerate(completed_tasks):
                symbol = tasks[i][0]
                if isinstance(result, Exception):
                    logger.error(f"Error preparing trade for {symbol}: {result}")
                    continue
                
                if result:
                    results.append(result)
                    logger.info(f"Successfully prepared trade for {symbol}")
                else:
                    logger.warning(f"Failed to prepare trade for {symbol}")
            
            logger.info(f"Prepared {len(results)} trades out of {len(selected_trades)} selected")
            return results
            
        except Exception as e:
            logger.error(f"Error in parallel trade preparation: {e}")
            return []

    async def execute_trades_with_parallel_preparation(self, selected_trades: List[Dict]) -> Dict:
        """Execute trades with parallel preparation for improved performance."""
        start_time = datetime.now(self.et_tz)  # Initialize at the beginning
        try:
            logger.info(f"Starting parallel trade execution for {len(selected_trades)} trades")
            
            # Prepare trades in parallel
            prepared_trades = await self.prepare_trades_parallel(selected_trades)
            
            if not prepared_trades:
                logger.warning("No trades were successfully prepared")
                execution_time = (datetime.now(self.et_tz) - start_time).total_seconds()
                return {
                    'success': False,
                    'message': 'No trades could be prepared for execution',
                    'executed_trades': [],
                    'failed_trades': selected_trades,
                    'execution_time': execution_time
                }
            
            # Execute prepared trades
            executed_trades = []
            failed_trades = []
            
            for trade_data in prepared_trades:
                try:
                    symbol = trade_data['symbol']
                    logger.info(f"Executing calendar spread for {symbol}")
                    
                    # Place the calendar spread order
                    order_result = self.alpaca_client.place_calendar_spread_order(
                        symbol=symbol,
                        short_exp=trade_data['short_expiration'],
                        long_exp=trade_data['long_expiration'],
                        option_type='call',
                        quantity=trade_data['position_size'],
                        order_type='limit'
                    )
                    
                    if order_result:
                        # Update trade data with order information
                        trade_data['order_id'] = order_result['order_id']
                        trade_data['order_status'] = order_result['status']
                        trade_data['executed_at'] = datetime.now(self.et_tz).isoformat()
                        
                        executed_trades.append(trade_data)
                        logger.info(f"Successfully executed trade for {symbol} - Order ID: {order_result['order_id']}")
                    else:
                        failed_trades.append(trade_data)
                        logger.error(f"Failed to execute trade for {symbol}")
                        
                except Exception as e:
                    logger.error(f"Error executing trade for {trade_data.get('symbol', 'unknown')}: {e}")
                    failed_trades.append(trade_data)
            
            execution_time = (datetime.now(self.et_tz) - start_time).total_seconds()
            
            result = {
                'success': len(executed_trades) > 0,
                'message': f'Executed {len(executed_trades)} out of {len(prepared_trades)} prepared trades',
                'executed_trades': executed_trades,
                'failed_trades': failed_trades,
                'preparation_count': len(prepared_trades),
                'execution_time': execution_time
            }
            
            logger.info(f"Trade execution completed in {execution_time:.2f} seconds")
            logger.info(f"Results: {len(executed_trades)} executed, {len(failed_trades)} failed")
            
            return result
            
        except Exception as e:
            logger.error(f"Error in parallel trade execution: {e}")
            execution_time = (datetime.now(self.et_tz) - start_time).total_seconds()
            return {
                'success': False,
                'message': f'Trade execution failed: {str(e)}',
                'executed_trades': [],
                'failed_trades': selected_trades,
                'execution_time': execution_time
            }

    def _is_calendar_spread_position(self, symbol: str) -> bool:
        """Check if a symbol has an active calendar spread position."""
        try:
            positions = self.alpaca_client.get_positions()
            
            # Look for options positions for this symbol
            option_positions = []
            for pos in positions:
                if symbol in pos['symbol'] and ('C' in pos['symbol'] or 'P' in pos['symbol']):
                    option_positions.append(pos)
            
            # Calendar spread should have exactly 2 positions (short and long)
            return len(option_positions) == 2
            
        except Exception as e:
            logger.error(f"Error checking calendar spread position for {symbol}: {e}")
            return False

    def _get_calendar_spread_trade_info(self, symbol: str, cached_trades: List[Dict] = None) -> Optional[Dict]:
        """Get trade information for a calendar spread position."""
        try:
            # Use cached trades if provided, otherwise query database
            if cached_trades is None:
                cached_trades = self.database.get_selected_trades_by_status('executed')
            
            for trade in cached_trades:
                if trade.get('ticker') == symbol:
                    return {
                        'trade_id': trade.get('id'),
                        'symbol': symbol,
                        'short_expiration': trade.get('short_expiration'),
                        'long_expiration': trade.get('long_expiration'),
                        'strike_price': trade.get('strike_price'),
                        'position_size': trade.get('position_size', DEFAULT_POSITION_SIZE)
                    }
            
            return None
            
        except Exception as e:
            logger.error(f"Error getting calendar spread trade info for {symbol}: {e}")
            return None
