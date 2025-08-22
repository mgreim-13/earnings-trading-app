"""
Streamlined Alpaca API client for trading operations and options management.
Refactored to remove code duplication and excessive complexity.
"""

import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple
from alpaca.trading.client import TradingClient
from alpaca.trading.requests import MarketOrderRequest, StopOrderRequest
from alpaca.trading.enums import OrderSide, TimeInForce, OrderType
import yfinance as yf
import pandas as pd
import config
from utils.yfinance_cache import yf_cache
from trading_safety import prevent_live_trading_in_tests, safe_trading_mode, TradingSafetyError
import pytz
import time
import requests
import re

logger = logging.getLogger(__name__)

class AlpacaClient:
    def __init__(self):
        logger.info("🔄 Initializing Alpaca client...")
        logger.info("   ==========================================")
        logger.info("   🔍 CREDENTIAL FLOW DEBUGGING")
        logger.info("   ==========================================")
        
        credentials = config.get_current_alpaca_credentials()
        logger.info(f"   Credentials retrieved: {credentials['paper_trading']}")
        logger.info(f"   Base URL: {credentials['base_url']}")
        logger.info(f"   API Key: {credentials['api_key'][:8] if credentials['api_key'] else 'None'}...")
        logger.info(f"   Secret Key: {credentials['secret_key'][:8] if credentials['secret_key'] else 'None'}...")
        
        # CRITICAL: Log the exact lengths of credentials received from config
        logger.info("   🔍 CREDENTIAL LENGTHS RECEIVED FROM CONFIG:")
        logger.info(f"      API Key length: {len(credentials['api_key']) if credentials['api_key'] else 0}")
        logger.info(f"      Secret Key length: {len(credentials['secret_key']) if credentials['secret_key'] else 0}")
        
        # CRITICAL: Log the full content of credentials received from config
        logger.info("   🔍 CREDENTIAL FULL CONTENT RECEIVED FROM CONFIG:")
        logger.info(f"      API Key: '{credentials['api_key']}'")
        logger.info(f"      Secret Key: '{credentials['secret_key']}'")
        
        # Log the full credentials object
        logger.info(f"   FULL CREDENTIALS OBJECT: {credentials}")
        
        # Log what's in the config module right now
        logger.info("   🔍 Current config module state:")
        logger.info(f"      config.LIVE_ALPACA_API_KEY: {config.LIVE_ALPACA_API_KEY[:8] if config.LIVE_ALPACA_API_KEY else 'None'}...")
        logger.info(f"      config.PAPER_ALPACA_API_KEY: {config.PAPER_ALPACA_API_KEY[:8] if config.PAPER_ALPACA_API_KEY else 'None'}...")
        
        # CRITICAL: Log the exact lengths in config module
        logger.info("   🔍 CREDENTIAL LENGTHS IN CONFIG MODULE:")
        logger.info(f"      config.LIVE_ALPACA_API_KEY length: {len(config.LIVE_ALPACA_API_KEY) if config.LIVE_ALPACA_API_KEY else 0}")
        logger.info(f"      config.PAPER_ALPACA_API_KEY length: {len(config.PAPER_ALPACA_API_KEY) if config.PAPER_ALPACA_API_KEY else 0}")
        
        # Log environment variables
        import os
        logger.info("   🔍 Current environment variables:")
        logger.info(f"      ENV LIVE_ALPACA_API_KEY: {os.environ.get('LIVE_ALPACA_API_KEY', 'Not set')[:8] if os.environ.get('LIVE_ALPACA_API_KEY') else 'Not set'}...")
        logger.info(f"      ENV PAPER_ALPACA_API_KEY: {os.environ.get('PAPER_ALPACA_API_KEY', 'Not set')[:8] if os.environ.get('PAPER_ALPACA_API_KEY') else 'Not set'}...")
        
        # CRITICAL: Log the exact lengths in environment variables
        logger.info("   🔍 CREDENTIAL LENGTHS IN ENVIRONMENT:")
        env_live_key = os.environ.get('LIVE_ALPACA_API_KEY')
        env_paper_key = os.environ.get('PAPER_ALPACA_API_KEY')
        logger.info(f"      ENV LIVE_ALPACA_API_KEY length: {len(env_live_key) if env_live_key else 0}")
        logger.info(f"      ENV PAPER_ALPACA_API_KEY length: {len(env_paper_key) if env_paper_key else 0}")
        
        if not credentials['api_key'] or not credentials['secret_key']:
            logger.error("❌ Missing API credentials")
            raise ValueError("Alpaca API credentials not configured")
        
        base_url = credentials['base_url'].replace('/v2', '')
        paper_trading = credentials['paper_trading']
        
        logger.info("   ==========================================")
        logger.info("   🚀 CREATING TRADING CLIENT")
        logger.info("   ==========================================")
        logger.info(f"   Using base URL: {base_url}")
        logger.info(f"   Paper trading mode: {paper_trading}")
        logger.info(f"   Final API Key: {credentials['api_key'][:8]}...")
        logger.info(f"   Final Secret Key: {credentials['secret_key'][:8]}...")
        
        try:
            # CRITICAL: Log exactly what we're passing to TradingClient
            logger.info("   🔍 CREATING TRADING CLIENT WITH THESE VALUES:")
            logger.info(f"      api_key: '{credentials['api_key']}'")
            logger.info(f"      secret_key: '{credentials['secret_key']}'")
            logger.info(f"      paper: {paper_trading}")
            logger.info(f"      base_url: {base_url}")
            
            self.trading_client = TradingClient(
                api_key=credentials['api_key'],
                secret_key=credentials['secret_key'],
                paper=paper_trading
            )
            trading_mode = "PAPER" if paper_trading else "LIVE"
            logger.info(f"✅ Alpaca client initialized in {trading_mode} trading mode")
            
            # Test the connection
            try:
                logger.info("   🔍 Testing connection...")
                account = self.trading_client.get_account()
                logger.info(f"   ✅ Connection test successful - Account ID: {account.id}")
            except Exception as test_error:
                logger.warning(f"⚠️ Connection test failed: {test_error}")
                logger.warning(f"   This might indicate credential or permission issues")
                
        except Exception as e:
            logger.error(f"❌ Failed to create TradingClient: {e}")
            import traceback
            logger.error(f"❌ Full traceback: {traceback.format_exc()}")
            raise

    # Core account and position methods
    def get_account_info(self) -> Optional[Dict]:
        """Get current account information."""
        try:
            logger.info("🔍 AlpacaClient: Getting account info...")
            logger.info(f"   Trading client type: {type(self.trading_client)}")
            
            account = self.trading_client.get_account()
            logger.info(f"   Account retrieved: {account.id if account else 'None'}")
            
            if not account:
                logger.warning("⚠️ Account is None")
                return None
            
            result = {
                'id': account.id,
                'buying_power': float(account.buying_power),
                'portfolio_value': float(account.portfolio_value),
                'cash': float(account.cash),
                'equity': float(account.equity),
                'status': account.status,
                'pattern_day_trader': account.pattern_day_trader,
                'trading_blocked': account.trading_blocked
            }
            
            logger.info(f"✅ Account info retrieved successfully: {result['id']}")
            return result
            
        except Exception as e:
            logger.error(f"❌ Failed to get account info: {e}")
            import traceback
            logger.error(f"❌ Full traceback: {traceback.format_exc()}")
            return None

    def get_positions(self) -> List[Dict]:
        """Get current open positions."""
        try:
            logger.info("🔍 AlpacaClient: Getting positions...")
            logger.info(f"   Trading client type: {type(self.trading_client)}")
            
            positions = self.trading_client.get_all_positions()
            logger.info(f"   Raw positions count: {len(positions) if positions else 0}")
            
            if not positions:
                logger.info("   No positions found")
                return []
            
            result = [
                {
                    'symbol': pos.symbol,
                    'qty': float(pos.qty),
                    'side': pos.side,
                    'market_value': float(pos.market_value),
                    'avg_entry_price': float(pos.avg_entry_price),
                    'unrealized_pl': float(pos.unrealized_pl)
                }
                for pos in positions
            ]
            
            logger.info(f"✅ Positions retrieved successfully: {len(result)} positions")
            return result
            
        except Exception as e:
            logger.error(f"❌ Failed to get positions: {e}")
            import traceback
            logger.error(f"❌ Full traceback: {traceback.format_exc()}")
            return []

    def get_orders(self, limit: int = 50, status: str = None) -> List[Dict]:
        """Get recent orders, optionally filtered by status."""
        try:
            orders = self.trading_client.get_orders(status=status) if status else self.trading_client.get_orders()
            if limit and len(orders) > limit:
                orders = orders[:limit]
            
            return [
                {
                    'id': order.id,
                    'symbol': order.symbol,
                    'qty': float(order.qty) if order.qty else None,
                    'side': order.side,
                    'status': order.status,
                    'type': order.type,
                    'limit_price': float(order.limit_price) if order.limit_price else None,
                    'created_at': order.created_at.isoformat() if order.created_at else None
                }
                for order in orders
            ]
        except Exception as e:
            logger.error(f"Failed to get orders: {e}")
            return []

    def get_open_orders(self) -> List[Dict]:
        """Get all open orders."""
        return self.get_orders(status='open')

    # Market data methods
    def get_current_price(self, symbol: str) -> Optional[float]:
        """Get current market price for a symbol."""
        try:
            # Verify symbol availability on Alpaca
            options_data = self.discover_available_options(symbol)
            if not options_data or not options_data.get('expirations'):
                raise Exception(f"Symbol {symbol} not available on Alpaca")
            
            # Get price from yfinance
            ticker = yf.Ticker(symbol)
            hist = ticker.history(period='1d')
            
            if hist is None or hist.empty:
                raise Exception(f"No price data available for {symbol}")
            
            current_price = float(hist['Close'].iloc[-1])
            logger.info(f"Got current price for {symbol}: ${current_price}")
            return current_price
                
        except Exception as e:
            logger.error(f"Failed to get current price for {symbol}: {e}")
            raise

    def get_options_chain(self, symbol: str, expiration_date: str) -> Optional[Dict]:
        """Get options chain for a specific symbol and expiration date."""
        try:
            ticker = yf.Ticker(symbol)
            chain = ticker.option_chain(expiration_date)
            
            if not chain or chain.calls.empty:
                raise Exception(f"No call options data available for {symbol} {expiration_date}")
            
            underlying_price = self.get_current_price(symbol)
            
            # Find ATM call option
            call_diffs = (chain.calls['strike'] - underlying_price).abs()
            atm_call_idx = call_diffs.idxmin()
            atm_call = chain.calls.loc[atm_call_idx]
            
            return {
                'expiration_date': expiration_date,
                'underlying_price': underlying_price,
                'atm_call': {
                    'strike': float(atm_call['strike']),
                    'bid': float(atm_call['bid']) if pd.notna(atm_call['bid']) else None,
                    'ask': float(atm_call['ask']) if pd.notna(atm_call['ask']) else None,
                    'last': float(atm_call['lastPrice']) if pd.notna(atm_call['lastPrice']) else None,
                    'volume': int(atm_call['volume']) if pd.notna(atm_call['volume']) else 0,
                    'openInterest': int(atm_call['openInterest']) if pd.notna(atm_call['openInterest']) else 0
                }
            }
        except Exception as e:
            logger.error(f"Failed to get options chain for {symbol} {expiration_date}: {e}")
            raise

    # Calendar spread calculation methods
    def calculate_calendar_spread_cost(self, symbol: str, short_exp: str, long_exp: str, 
                                     option_type: str = 'call') -> Optional[Dict]:
        """Calculate the cost of a calendar spread using call options."""
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            short_chain = self.get_options_chain(symbol, short_exp)
            long_chain = self.get_options_chain(symbol, long_exp)
            
            if not short_chain or not long_chain:
                return None
            
            short_opt = short_chain.get('atm_call')
            long_opt = long_chain.get('atm_call')
            
            if not short_opt or not long_opt:
                logger.warning(f"Could not find ATM call options for {symbol}")
                return None
            
            short_cost = short_opt['ask'] if short_opt['ask'] else short_opt['last']
            long_cost = long_opt['bid'] if long_opt['bid'] else long_opt['last']
            
            if not short_cost or not long_cost:
                logger.warning(f"Missing pricing data for {symbol} options")
                return None
            
            debit_cost = long_cost - short_cost
            
            # Calculate days between expirations
            short_date = datetime.strptime(short_exp, '%Y-%m-%d')
            long_date = datetime.strptime(long_exp, '%Y-%m-%d')
            days_between = (long_date - short_date).days
            
            return {
                'symbol': symbol,
                'option_type': 'call',
                'short_expiration': short_exp,
                'long_expiration': long_exp,
                'strike_price': short_opt['strike'],
                'short_cost': short_cost,
                'long_cost': long_cost,
                'debit_cost': debit_cost,
                'underlying_price': short_chain['underlying_price'],
                'days_between': days_between
            }
        except Exception as e:
            logger.error(f"Failed to calculate calendar spread cost: {e}")
            return None

    def calculate_calendar_spread_limit_price(self, long_symbol: str, short_symbol: str, 
                                            order_type: str = 'entry') -> Optional[Dict]:
        """Calculate the limit price for calendar spread orders using the midpoint method."""
        try:
            # Fetch real-time bid and ask prices for both legs
            long_quotes = self.get_option_quotes(long_symbol)
            short_quotes = self.get_option_quotes(short_symbol)
            
            if not long_quotes or not short_quotes:
                logger.error(f"Could not fetch quotes for one or both legs")
                return None
            
            # Extract bid and ask prices
            long_bid, long_ask = long_quotes.get('bid'), long_quotes.get('ask')
            short_bid, short_ask = short_quotes.get('bid'), short_quotes.get('ask')
            
            # Validate prices
            if not all([long_bid, long_ask, short_bid, short_ask]) or any(price <= 0 for price in [long_bid, long_ask, short_bid, short_ask]):
                logger.error(f"Invalid pricing data for options")
                return None
            
            # Calculate midpoints
            long_midpoint = round((long_bid + long_ask) / 2, 2)
            short_midpoint = round((short_bid + short_ask) / 2, 2)
            
            # Calculate net limit price
            if order_type.lower() == 'entry':
                net_limit_price = round(long_midpoint - short_midpoint, 2)
                price_description = "debit"
            elif order_type.lower() == 'exit':
                net_limit_price = round(short_midpoint - long_midpoint, 2)
                price_description = "credit"
                if net_limit_price <= 0:
                    net_limit_price = 0.05
            else:
                logger.error(f"Invalid order_type: {order_type}")
                return None
            
            return {
                'limit_price': net_limit_price,
                'limit_price_string': f"{net_limit_price:.2f}",
                'long_symbol': long_symbol,
                'short_symbol': short_symbol,
                'long_bid': long_bid,
                'long_ask': long_ask,
                'long_midpoint': long_midpoint,
                'short_bid': short_bid,
                'short_ask': short_ask,
                'short_midpoint': short_midpoint,
                'order_type': order_type,
                'price_description': price_description,
                'timestamp': datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error calculating limit price: {e}")
            return None

    def get_option_quotes(self, option_symbol: str) -> Optional[Dict]:
        """Fetch real-time bid and ask prices for a specific option contract."""
        try:
            # This would use Alpaca's options data API in production
            # For now, return mock data structure
            return {
                'bid': 1.0,
                'ask': 1.1,
                'last': 1.05,
                'timestamp': datetime.now().isoformat()
            }
        except Exception as e:
            logger.error(f"Failed to fetch option quotes for {option_symbol}: {e}")
            return None

    # Options discovery methods
    def discover_available_options(self, symbol: str, target_expiration: str = None) -> Optional[Dict]:
        """Discover available options for a given stock symbol using Alpaca's snapshots endpoint."""
        try:
            # Get current credentials and data URL
            credentials = config.get_current_alpaca_credentials()
            data_url = config.get_current_data_url()
            
            base_url = f"{data_url}/v1beta1/options/snapshots/{symbol}"
            
            params = {
                'limit': 1000,
                'type': 'call'
            }
            
            if target_expiration:
                params['expiration_date'] = target_expiration
            
            headers = {
                'APCA-API-KEY-ID': credentials['api_key'],
                'APCA-API-SECRET-KEY': credentials['secret_key']
            }
            
            response = requests.get(base_url, headers=headers, params=params)
            response.raise_for_status()
            
            chain_data = response.json()
            
            if 'snapshots' not in chain_data:
                logger.warning(f"No options snapshots found for {symbol}")
                return None
            
            return self._process_options_chain(chain_data['snapshots'], symbol)
            
        except Exception as e:
            logger.error(f"Error discovering options for {symbol}: {e}")
            return None

    def _process_options_chain(self, chain: list, symbol: str) -> Dict:
        """Process the options chain data to extract strikes and expirations."""
        available_options = {
            'symbol': symbol,
            'strikes': set(),
            'expirations': set(),
            'call_options': []
        }
        
        for option in chain:
            occ_symbol = option.get('symbol', '')
            if occ_symbol:
                match = re.match(r'^([A-Z]+)(\d{6})([CP])(\d{8})$', occ_symbol)
                if match:
                    underlying, date_str, option_type, strike_str = match.groups()
                    
                    # Convert date from YYMMDD to YYYY-MM-DD
                    year = int('20' + date_str[:2])
                    month = int(date_str[2:4])
                    day = int(date_str[4:6])
                    expiration = f"{year:04d}-{month:02d}-{day:02d}"
                    
                    # Convert strike from thousandths to decimal
                    strike = float(strike_str) / 1000.0
                    
                    available_options['strikes'].add(strike)
                    available_options['expirations'].add(expiration)
                    
                    if option_type == 'C':
                        option_info = {
                            'occ_symbol': occ_symbol,
                            'expiration': expiration,
                            'strike': strike,
                            'type': 'call',
                            'bid': option.get('bid', 0),
                            'ask': option.get('ask', 0),
                            'last': option.get('last', 0)
                        }
                        available_options['call_options'].append(option_info)
        
        # Convert sets to sorted lists
        available_options['strikes'] = sorted(list(available_options['strikes']))
        available_options['expirations'] = sorted(list(available_options['expirations']))
        
        return available_options

    # Calendar spread execution methods
    @safe_trading_mode
    def place_calendar_spread_order(self, symbol: str, short_exp: str, long_exp: str, 
                                   option_type: str = "call", quantity: int = 1, order_type: str = "limit") -> Optional[Dict]:
        """Place a calendar spread order using Alpaca's multi-leg order support."""
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            # Calculate spread cost
            spread_info = self.calculate_calendar_spread_cost(symbol, short_exp, long_exp, option_type)
            if not spread_info:
                logger.error(f"Failed to calculate calendar spread cost")
                return None
            
            # Check buying power
            account = self.get_account_info()
            if not account:
                logger.error(f"Failed to get account info")
                return None
            
            required_capital = spread_info['debit_cost'] * quantity * 100
            if account['buying_power'] < required_capital:
                logger.error(f"Insufficient buying power. Required: {required_capital}, Available: {account['buying_power']}")
                return None
            
            # Get option symbols
            short_options = self.discover_available_options(symbol, short_exp)
            long_options = self.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.error(f"Could not find options for {symbol}")
                return None
            
            # Find ATM options
            target_strike = spread_info['strike_price']
            short_call_symbol = self._find_closest_strike_option(short_options, target_strike)
            long_call_symbol = self._find_closest_strike_option(long_options, target_strike)
            
            if not short_call_symbol or not long_call_symbol:
                logger.error(f"Could not find suitable call options for strike {target_strike}")
                return None
            
            # Calculate limit price
            price_info = self.calculate_calendar_spread_limit_price(
                long_symbol=long_call_symbol, 
                short_symbol=short_call_symbol, 
                order_type='entry'
            )
            if not price_info:
                logger.error(f"Failed to calculate limit price")
                return None
            
            # Create order data
            order_data = self._create_calendar_spread_order_data(
                short_call_symbol, long_call_symbol, quantity, order_type, price_info['limit_price']
            )
            
            # Place order
            order = self.trading_client.submit_order(order_data)
            
            if order:
                logger.info(f"Calendar spread order placed successfully for {symbol}")
                return {
                    'order_id': order.id,
                    'status': order.status,
                    'symbol': symbol,
                    'strategy': 'Call Calendar Spread',
                    'short_expiration': short_exp,
                    'long_expiration': long_exp,
                    'strike': spread_info['strike_price'],
                    'quantity': quantity,
                    'debit_cost': spread_info['debit_cost'],
                    'order_type': order_type
                }
            else:
                logger.error(f"Failed to place calendar spread order for {symbol}")
                return None
                
        except Exception as e:
            logger.error(f"Failed to place calendar spread order for {symbol}: {e}")
            return None

    def _find_closest_strike_option(self, options_data: Dict, target_strike: float) -> Optional[str]:
        """Find the option with the closest strike to the target."""
        min_diff = float('inf')
        closest_option = None
        
        for option in options_data.get('call_options', []):
            strike_diff = abs(option['strike'] - target_strike)
            if strike_diff < min_diff:
                min_diff = strike_diff
                closest_option = option['occ_symbol']
        
        return closest_option

    def _create_calendar_spread_order_data(self, short_symbol: str, long_symbol: str, 
                                         quantity: int, order_type: str, limit_price: float = None) -> Dict:
        """Create the order data structure for calendar spread orders."""
        order_data = {
            "order_class": "mleg",
            "qty": quantity,
            "type": order_type,
            "time_in_force": "day",
            "legs": [
                {
                    "symbol": short_symbol,
                    "ratio_qty": "1",
                    "side": "sell",
                    "position_intent": "sell_to_open"
                },
                {
                    "symbol": long_symbol,
                    "ratio_qty": "1",
                    "side": "buy",
                    "position_intent": "buy_to_open"
                }
            ]
        }
        
        if order_type == "limit" and limit_price:
            order_data["limit_price"] = limit_price
        
        return order_data

    # Position management methods
    @prevent_live_trading_in_tests
    def close_position(self, symbol: str, quantity: int = None) -> bool:
        """Close a position for a given symbol."""
        try:
            positions = self.get_positions()
            position = next((p for p in positions if p['symbol'] == symbol), None)
            
            if not position:
                logger.warning(f"No position found for {symbol}")
                return False
            
            if quantity is None:
                quantity = abs(position['qty'])
            
            side = OrderSide.SELL if position['qty'] > 0 else OrderSide.BUY
            
            order_data = MarketOrderRequest(
                symbol=symbol,
                qty=quantity,
                side=side,
                time_in_force=TimeInForce.DAY
            )
            
            order = self.trading_client.submit_order(order_data)
            logger.info(f"Submitted order to close position: {order.id}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to close position for {symbol}: {e}")
            raise

    def get_order_status(self, order_id: str) -> Optional[Dict]:
        """Get the current status of an order."""
        try:
            order = self.trading_client.get_order(order_id)
            return {
                'id': order.id,
                'status': order.status,
                'filled_qty': float(order.filled_qty) if order.filled_qty else 0,
                'filled_avg_price': float(order.filled_avg_price) if order.filled_avg_price else None,
                'submitted_at': order.submitted_at.isoformat() if order.submitted_at else None
            }
        except Exception as e:
            logger.error(f"Failed to get order status for {order_id}: {e}")
            raise

    @prevent_live_trading_in_tests
    def cancel_order(self, order_id: str) -> bool:
        """Cancel an open order."""
        try:
            self.trading_client.cancel_order(order_id)
            logger.info(f"Canceled order: {order_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to cancel order {order_id}: {e}")
            raise

    # Market utility methods
    def is_market_open(self) -> bool:
        """Check if the market is currently open."""
        try:
            clock = self.trading_client.get_clock()
            return clock.is_open
        except Exception as e:
            logger.error(f"Failed to check market status: {e}")
            raise

    def get_next_trading_day(self, date: datetime = None) -> Optional[datetime]:
        """Get the next trading day after the given date."""
        try:
            if date is None:
                date = datetime.now()
            
            calendar = self.trading_client.get_calendar()
            
            for session in calendar:
                if session.date > date.date():
                    if isinstance(session.date, datetime):
                        return session.date
                    else:
                        return datetime.combine(session.date, datetime.min.time().replace(hour=9, minute=30))
            
            return None
        except Exception as e:
            logger.error(f"Failed to get next trading day: {e}")
            raise

    # Calendar spread strategy methods
    def find_calendar_spread_options(self, symbol: str, target_strike: float, 
                                   earnings_date: str, earnings_time: str = 'amc') -> Optional[Dict]:
        """Find available options for a calendar spread based on earnings timing."""
        try:
            from datetime import datetime, timedelta
            import pytz
            
            # Parse earnings date and calculate target expirations
            earnings_dt = datetime.strptime(earnings_date, '%Y-%m-%d')
            eastern_tz = pytz.timezone('US/Eastern')
            
            if earnings_time == 'amc':
                next_trading_day = earnings_dt + timedelta(days=1)
            else:
                next_trading_day = earnings_dt
            
            # Adjust for weekends
            while next_trading_day.weekday() >= 5:
                next_trading_day += timedelta(days=1)
            
            target_short_exp = next_trading_day.strftime('%Y-%m-%d')
            target_long_exp = (next_trading_day + timedelta(days=30)).strftime('%Y-%m-%d')
            
            # Get available options
            available_options = self.discover_available_options(symbol)
            if not available_options:
                return None
            
            # Find closest strike
            available_strikes = available_options['strikes']
            if not available_strikes:
                return None
            
            closest_strike = min(available_strikes, key=lambda x: abs(x - target_strike))
            
            # Find options at this strike
            strike_options = []
            for option in available_options['call_options']:
                if abs(option['strike'] - closest_strike) < 0.01:
                    strike_options.append(option)
            
            if len(strike_options) < 2:
                return None
            
            # Group by expiration and find best calendar spread
            expirations = {}
            for option in strike_options:
                exp_date = datetime.strptime(option['expiration'], '%Y-%m-%d')
                days_from_short_target = abs((exp_date - next_trading_day).days)
                if option['expiration'] not in expirations:
                    expirations[option['expiration']] = {
                        'options': [],
                        'days_from_short_target': days_from_short_target
                    }
                expirations[option['expiration']]['options'].append(option)
            
            if len(expirations) < 2:
                return None
            
            # Find best short expiration
            short_candidates = []
            for exp, exp_data in expirations.items():
                exp_date = datetime.strptime(exp, '%Y-%m-%d')
                if exp_date > earnings_dt:
                    short_candidates.append({
                        'expiration': exp,
                        'days_from_target': exp_data['days_from_short_target'],
                        'options': exp_data['options']
                    })
            
            if not short_candidates:
                return None
            
            short_candidates.sort(key=lambda x: x['days_from_target'])
            best_short_exp = short_candidates[0]
            
            # Find best long expiration
            short_exp_date = datetime.strptime(best_short_exp['expiration'], '%Y-%m-%d')
            target_long_date = short_exp_date + timedelta(days=30)
            
            long_candidates = []
            for exp, exp_data in expirations.items():
                exp_date = datetime.strptime(exp, '%Y-%m-%d')
                if exp_date > short_exp_date:
                    days_from_target = abs((exp_date - target_long_date).days)
                    long_candidates.append({
                        'expiration': exp,
                        'days_from_target': days_from_target,
                        'options': exp_data['options']
                    })
            
            if not long_candidates:
                return None
            
            long_candidates.sort(key=lambda x: x['days_from_target'])
            best_long_exp = long_candidates[0]
            
            # Get option contracts
            short_option = best_short_exp['options'][0]
            long_option = best_long_exp['options'][0]
            
            actual_days_between = (datetime.strptime(best_long_exp['expiration'], '%Y-%m-%d') - 
                                 datetime.strptime(best_short_exp['expiration'], '%Y-%m-%d')).days
            
            estimated_cost = long_option['ask'] - short_option['bid']
            
            return {
                'symbol': symbol,
                'strike': closest_strike,
                'calendar_spreads': [{
                    'short_option': short_option,
                    'long_option': long_option,
                    'days_between': actual_days_between,
                    'strike': closest_strike,
                    'estimated_cost': estimated_cost,
                    'target_short_exp': target_short_exp,
                    'target_long_exp': target_long_exp,
                    'actual_short_exp': best_short_exp['expiration'],
                    'actual_long_exp': best_long_exp['expiration']
                }],
                'best_spread': {
                    'short_option': short_option,
                    'long_option': long_option,
                    'days_between': actual_days_between,
                    'strike': closest_strike,
                    'estimated_cost': estimated_cost
                },
                'earnings_date': earnings_date,
                'earnings_time': earnings_time
            }
            
        except Exception as e:
            logger.error(f"Error finding calendar spread options for {symbol}: {e}")
            return None

    # Emergency and utility methods
    def _get_atm_strike(self, symbol: str) -> float:
        """Get ATM strike price for a symbol based on current market price."""
        try:
            current_price = self.get_current_price(symbol)
            if current_price:
                return round(current_price)
            else:
                logger.error(f"Could not get current price for {symbol}")
                return 0
        except Exception as e:
            logger.error(f"Error calculating ATM strike for {symbol}: {e}")
            return 0

    def validate_calendar_spread_position(self, symbol: str, short_exp: str, long_exp: str, 
                                        option_type: str = 'call') -> Optional[Dict]:
        """Validate that a calendar spread position exists and can be closed."""
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            positions = self.get_positions()
            if not positions:
                return None
            
            target_strike = self._get_atm_strike(symbol)
            if not target_strike:
                return None
            
            short_symbol = f"{symbol}{short_exp.replace('-', '')}C{int(target_strike * 1000)}"
            long_symbol = f"{symbol}{long_exp.replace('-', '')}C{int(target_strike * 1000)}"
            
            short_position = None
            long_position = None
            
            for position in positions:
                if position['symbol'] == short_symbol:
                    short_position = position
                elif position['symbol'] == long_symbol:
                    long_position = position
            
            if not short_position or not long_position:
                return None
            
            if short_position['qty'] != long_position['qty']:
                return None
            
            return {
                'symbol': symbol,
                'option_type': option_type,
                'short_expiration': short_exp,
                'long_expiration': long_exp,
                'strike_price': target_strike,
                'quantity': abs(short_position['qty']),
                'short_position': short_position,
                'long_position': long_position,
                'status': 'valid'
            }
            
        except Exception as e:
            logger.error(f"Failed to validate calendar spread position for {symbol}: {e}")
            return None

    def get_calendar_spread_current_value(self, symbol: str, short_exp: str, long_exp: str, 
                                        option_type: str = 'call') -> Optional[Dict]:
        """Get the current market value of an existing calendar spread position."""
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            short_chain = self.get_options_chain(symbol, short_exp)
            long_chain = self.get_options_chain(symbol, long_exp)
            
            if not short_chain or not long_chain:
                return None
            
            short_opt = short_chain.get('atm_call')
            long_opt = long_chain.get('atm_call')
            
            if not short_opt or not long_opt:
                return None
            
            short_bid = short_opt['bid'] if short_opt['bid'] else short_opt['last']
            long_ask = long_opt['ask'] if long_opt['ask'] else long_opt['last']
            
            if not short_bid or not long_ask:
                return None
            
            current_value = short_bid - long_ask
            
            short_date = datetime.strptime(short_exp, '%Y-%m-%d')
            days_remaining = (short_date - datetime.now()).days
            
            return {
                'symbol': symbol,
                'option_type': 'call',
                'short_expiration': short_exp,
                'long_expiration': long_exp,
                'strike_price': short_opt['strike'],
                'short_bid': short_bid,
                'long_ask': long_ask,
                'current_value': current_value,
                'underlying_price': short_chain['underlying_price'],
                'days_remaining': days_remaining
            }
            
        except Exception as e:
            logger.error(f"Failed to get current calendar spread value for {symbol}: {e}")
            return None

    @safe_trading_mode
    def close_calendar_spread(self, symbol: str, short_exp: str, long_exp: str, 
                             option_type: str = 'call', quantity: int = 1) -> Optional[Dict]:
        """Close a calendar spread position."""
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            position_info = self.validate_calendar_spread_position(symbol, short_exp, long_exp, option_type)
            if not position_info:
                logger.error(f"Calendar spread position validation failed for {symbol}")
                return None
            
            current_spread_info = self.get_calendar_spread_current_value(symbol, short_exp, long_exp, option_type)
            if not current_spread_info:
                logger.error(f"Could not get current spread value for {symbol}")
                return None
            
            current_value = current_spread_info['current_value']
            exit_price = current_value if current_value > 0 else 0.05
            
            # Get option symbols
            short_options = self.discover_available_options(symbol, short_exp)
            long_options = self.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.error(f"Could not find options for {symbol}")
                return None
            
            current_price = self.get_current_price(symbol)
            if not current_price:
                return None
            
            target_strike = round(current_price)
            
            short_call_symbol = self._find_closest_strike_option(short_options, target_strike)
            long_call_symbol = self._find_closest_strike_option(long_options, target_strike)
            
            if not short_call_symbol or not long_call_symbol:
                logger.error(f"Could not find suitable call options")
                return None
            
            # Create close order
            close_order_data = {
                "order_class": "mleg",
                "qty": quantity,
                "type": "limit",
                "time_in_force": "day",
                "limit_price": exit_price,
                "legs": [
                    {
                        "symbol": short_call_symbol,
                        "side": "buy",
                        "intent": "buy_to_close"
                    },
                    {
                        "symbol": long_call_symbol,
                        "side": "sell",
                        "intent": "sell_to_close"
                    }
                ]
            }
            
            order = self.trading_client.submit_order(close_order_data)
            
            if order:
                logger.info(f"Calendar spread close order submitted successfully for {symbol}")
                return {
                    'order_id': order.id,
                    'symbol': symbol,
                    'option_type': option_type,
                    'short_expiration': short_exp,
                    'long_expiration': long_exp,
                    'strike_price': current_spread_info['strike_price'],
                    'quantity': quantity,
                    'exit_price': exit_price,
                    'current_market_value': current_value,
                    'days_remaining': current_spread_info['days_remaining'],
                    'status': 'submitted'
                }
            else:
                logger.error(f"Failed to submit close order for {symbol}")
                return None
                
        except Exception as e:
            logger.error(f"Failed to close calendar spread for {symbol}: {e}")
            raise

    # Account activities methods
    def get_account_activities(self, activity_types: List[str] = None, limit: int = 50) -> List[Dict]:
        """Get account activities including trades from Alpaca."""
        try:
            # Get current credentials
            credentials = config.get_current_alpaca_credentials()
            base_url = credentials['base_url'].replace('/v2', '')
            url = f"{base_url}/v2/account/activities"
            
            params = {}
            if activity_types:
                params['activity_types'] = ','.join(activity_types)
            if limit:
                params['page_size'] = min(limit, 100)
            
            headers = {
                'APCA-API-KEY-ID': credentials['api_key'],
                'APCA-API-SECRET-KEY': credentials['secret_key']
            }
            
            response = requests.get(url, headers=headers, params=params)
            
            if response.status_code == 404:
                logger.warning("Account activities endpoint returned 404 - might not be available in paper trading")
                return []
            
            response.raise_for_status()
            activities = response.json()
            
            return activities
            
        except Exception as e:
            logger.error(f"Failed to get account activities: {e}")
            return []

    def get_trade_activities(self, limit: int = 50) -> List[Dict]:
        """Get trade-related activities from Alpaca account activities."""
        try:
            trade_activity_types = ['FILL']
            activities = self.get_account_activities(activity_types=trade_activity_types, limit=limit)
            
            trades = []
            for activity in activities:
                if activity.get('activity_type') == 'FILL':
                    trade = {
                        'id': activity.get('id'),
                        'ticker': activity.get('symbol'),
                        'trade_type': activity.get('side', 'unknown'),
                        'entry_time': activity.get('transaction_time'),
                        'entry_price': activity.get('price'),
                        'quantity': activity.get('qty'),
                        'status': 'closed',
                        'order_type': 'market',
                        'asset_class': 'us_option',
                        'description': f"Option {activity.get('side')} {activity.get('qty')} @ ${activity.get('price')}"
                    }
                    trades.append(trade)
            
            return trades
            
        except Exception as e:
            logger.error(f"Failed to get trade activities: {e}")
            return []
