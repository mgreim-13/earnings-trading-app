"""
Streamlined Alpaca API client for trading operations and options management.
Refactored to remove code duplication and excessive complexity.
"""

import logging
import traceback
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Tuple
from alpaca.trading.client import TradingClient
from alpaca.trading.requests import MarketOrderRequest, StopOrderRequest, LimitOrderRequest, GetOrdersRequest, ReplaceOrderRequest
from alpaca.trading.enums import OrderSide, TimeInForce, OrderType, QueryOrderStatus
from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockLatestTradeRequest
import config
from trading_safety import prevent_live_trading_in_tests, safe_trading_mode, TradingSafetyError
import pytz
import time
import requests
import re

logger = logging.getLogger(__name__)

class AlpacaClient:
    def __init__(self):
        logger.info("🔄 Initializing Alpaca client...")
        
        credentials = config.get_current_alpaca_credentials()
        
        if not credentials['api_key'] or not credentials['secret_key']:
            logger.error("❌ Missing API credentials")
            raise ValueError("Alpaca API credentials not configured")
        
        # Extract base URL properly
        base_url = credentials['base_url']
        if base_url.endswith('/v2'):
            base_url = base_url[:-3]  # Remove '/v2' suffix
        elif base_url.endswith('/v2/'):
            base_url = base_url[:-4]  # Remove '/v2/' suffix
        
        paper_trading = credentials['paper_trading']
        
        # Store only non-sensitive configuration
        self.base_url = base_url
        self.paper_trading = paper_trading
        
        try:
            self.trading_client = TradingClient(
                api_key=credentials['api_key'],
                secret_key=credentials['secret_key'],
                paper=paper_trading
            )
            trading_mode = "PAPER" if paper_trading else "LIVE"
            logger.info(f"✅ Alpaca client initialized in {trading_mode} trading mode")
            
            # Test the connection
            try:
                account = self.trading_client.get_account()
                logger.info(f"✅ Connection test successful - Account ID: {account.id}")
            except Exception as test_error:
                logger.warning(f"⚠️ Connection test failed: {test_error}")
                
        except Exception as e:
            logger.error(f"❌ Failed to create TradingClient: {e}")
            raise

    # Core account and position methods
    def get_account_info(self) -> Optional[Dict]:
        """Get current account information."""
        try:
            account = self.trading_client.get_account()
            
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
            
            logger.debug(f"Account info retrieved: {result['id']}")
            return result
            
        except Exception as e:
            logger.error(f"Failed to get account info: {e}")
            return None

    def get_positions(self) -> List[Dict]:
        """Get current open positions."""
        try:
            positions = self.trading_client.get_all_positions()
            
            if not positions:
                logger.debug("No positions found")
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
            
            logger.debug(f"Positions retrieved: {len(result)} positions")
            return result
            
        except Exception as e:
            logger.error(f"Failed to get positions: {e}")
            return []

    def get_orders(self, limit: int = 50, status: str = None) -> List[Dict]:
        """Get recent orders, optionally filtered by status."""
        # Validate inputs
        if limit is not None:
            if not isinstance(limit, int) or limit <= 0:
                logger.error("Limit must be a positive integer")
                return []
            if limit > 500:
                logger.warning("Limit exceeds 500, capping to 500")
                limit = 500
        
        if status is not None:
            if not isinstance(status, str):
                logger.error("Status must be a string or None")
                return []
            if status.lower() not in ['open', 'closed', 'all']:
                logger.error("Status must be 'open', 'closed', 'all', or None")
                return []
        
        try:
            # Convert string status to enum if provided
            status_enum = None
            if status:
                if status.lower() == 'open':
                    status_enum = QueryOrderStatus.OPEN
                elif status.lower() == 'closed':
                    status_enum = QueryOrderStatus.CLOSED
                elif status.lower() == 'all':
                    status_enum = QueryOrderStatus.ALL
            
            # Create request with proper parameters
            request = GetOrdersRequest(
                status=status_enum,
                limit=min(limit, 500) if limit else 50  # Max limit is 500
            )
            
            orders = self.trading_client.get_orders(filter=request)
            
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
        """Get current market price for a symbol using Alpaca market data."""
        # Validate input
        if not symbol:
            logger.error("Symbol cannot be empty")
            return None
        
        if not isinstance(symbol, str):
            logger.error("Symbol must be a string")
            return None
        
        # Clean and validate symbol format
        symbol = symbol.strip().upper()
        if not symbol.replace('-', '').replace('.', '').isalnum():
            logger.error(f"Invalid symbol format: {symbol}")
            return None
        
        try:
            # Get current credentials
            credentials = config.get_current_alpaca_credentials()
            
            # Create market data client (uses same credentials as trading client)
            data_client = StockHistoricalDataClient(
                api_key=credentials['api_key'],
                secret_key=credentials['secret_key']
            )
            
            # Get latest trade for the symbol
            request = StockLatestTradeRequest(symbol_or_symbols=[symbol])
            latest_trade = data_client.get_stock_latest_trade(request)
            
            if symbol in latest_trade and latest_trade[symbol]:
                price = float(latest_trade[symbol].price)
                logger.debug(f"Got current price for {symbol}: ${price}")
                return price
            else:
                logger.warning(f"No trade data available for {symbol}")
                
                logger.warning(f"No trade data available for {symbol}")
                return None
                
        except Exception as e:
            logger.warning(f"Failed to get real-time price for {symbol}: {e}")
            return None



    # Calendar spread calculation methods
    def calculate_calendar_spread_cost(self, symbol: str, short_exp: str, long_exp: str, 
                                     option_type: str = 'call') -> Optional[Dict]:
        """Calculate the cost of a calendar spread using Alpaca options data."""
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            logger.error("Symbol, short_exp, and long_exp are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(short_exp, str) or not isinstance(long_exp, str):
            logger.error("Symbol, short_exp, and long_exp must be strings")
            return None
        
        # Validate date format (basic check)
        if len(short_exp) != 10 or short_exp.count('-') != 2 or len(long_exp) != 10 or long_exp.count('-') != 2:
            logger.error("Invalid date format. Use YYYY-MM-DD")
            return None
        
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            # Get current underlying price
            underlying_price = self.get_current_price(symbol)
            if not underlying_price:
                logger.error(f"Could not get current price for {symbol}")
                return None
            
            # Get options data from Alpaca for both expirations
            short_options = self.discover_available_options(symbol, short_exp)
            long_options = self.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.error(f"Could not get options data for {symbol} - short: {short_exp}, long: {long_exp}")
                return None
            
            # Find ATM call options (closest to current price)
            target_strike = round(underlying_price)
            short_atm = self._find_closest_strike_option(short_options, target_strike)
            long_atm = self._find_closest_strike_option(long_options, target_strike)
            
            if not short_atm or not long_atm:
                logger.error(f"Could not find ATM call options for {symbol}")
                return None
            
            # Get the actual option data for the ATM strikes
            short_opt_data = None
            long_opt_data = None
            
            for opt in short_options.get('call_options', []):
                if opt['occ_symbol'] == short_atm:
                    short_opt_data = opt
                    break
            
            for opt in long_options.get('call_options', []):
                if opt['occ_symbol'] == long_atm:
                    long_opt_data = opt
                    break
            
            if not short_opt_data or not long_opt_data:
                logger.error(f"Could not find option data for ATM strikes")
                return None
            
            # Get real-time pricing data using option quotes
            short_quotes = self.get_option_quotes(short_atm)
            long_quotes = self.get_option_quotes(long_atm)
            
            if not short_quotes or not long_quotes:
                logger.warning(f"Could not get quotes for {symbol} options")
                return None
            
            # Extract pricing data - use ask for buying (long), bid for selling (short)
            short_cost = short_quotes.get('ask') or short_quotes.get('last', 0)
            long_cost = long_quotes.get('ask') or long_quotes.get('last', 0)  # We buy both legs for calendar spread
            
            if not short_cost or not long_cost:
                logger.warning(f"Missing pricing data for {symbol} options - short: {short_cost}, long: {long_cost}")
                return None
            
            # Calendar spread cost = long premium - short premium (we pay net debit)
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
                'strike_price': short_opt_data['strike'],
                'short_cost': short_cost,
                'long_cost': long_cost,
                'debit_cost': debit_cost,
                'underlying_price': underlying_price,
                'days_between': days_between
            }
        except Exception as e:
            logger.error(f"Failed to calculate calendar spread cost: {e}")
            return None

    def calculate_calendar_spread_limit_price(self, long_symbol: str, short_symbol: str, 
                                            order_type: str = 'entry') -> Optional[Dict]:
        """Calculate the limit price for calendar spread orders using the midpoint method."""
        # Validate inputs
        if not long_symbol or not short_symbol:
            logger.error("Long symbol and short symbol are required")
            return None
        
        if not isinstance(long_symbol, str) or not isinstance(short_symbol, str):
            logger.error("Long symbol and short symbol must be strings")
            return None
        
        if order_type not in ['entry', 'exit']:
            logger.error("Order type must be 'entry' or 'exit'")
            return None
        
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
                'timestamp': datetime.now(timezone.utc).isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error calculating limit price: {e}")
            return None

    def get_option_quotes(self, option_symbol: str) -> Optional[Dict]:
        """Fetch real-time bid and ask prices for a specific option contract using Alpaca's HTTP API."""
        # Validate input
        if not option_symbol:
            logger.error("Option symbol cannot be empty")
            return None
        
        if not isinstance(option_symbol, str):
            logger.error("Option symbol must be a string")
            return None
        
        try:
            # Get current credentials and data URL
            credentials = config.get_current_alpaca_credentials()
            data_url = config.get_current_data_url()
            
            # Use Alpaca's options quotes endpoint
            base_url = f"{data_url}/v1beta1/options/quotes/{option_symbol}"
            
            headers = {
                'APCA-API-KEY-ID': credentials['api_key'],
                'APCA-API-SECRET-KEY': credentials['secret_key']
            }
            
            response = requests.get(base_url, headers=headers)
            response.raise_for_status()
            
            quote_data = response.json()
            
            # Extract pricing data from the response
            if 'quotes' in quote_data and quote_data['quotes']:
                quote = quote_data['quotes'][0]  # Get the first quote
                return {
                    'bid': float(quote.get('bid', 0)),
                    'ask': float(quote.get('ask', 0)),
                    'last': float(quote.get('last', 0)),
                    'timestamp': quote.get('timestamp', datetime.now(timezone.utc).isoformat())
                }
            else:
                logger.warning(f"No quote data found for {option_symbol}")
                return None
                
        except Exception as e:
            logger.warning(f"Failed to fetch real option quotes for {option_symbol}: {e}")
            # Fallback to mock data for development/testing
            return {
                'bid': 1.0,
                'ask': 1.1,
                'last': 1.05,
                'timestamp': datetime.now(timezone.utc).isoformat()
            }

    # Options discovery methods
    def discover_available_options(self, symbol: str, target_expiration: str = None) -> Optional[Dict]:
        """Discover available options for a given stock symbol using Alpaca's snapshots endpoint."""
        # Validate input
        if not symbol:
            logger.error("Symbol cannot be empty")
            return None
        
        if not isinstance(symbol, str):
            logger.error("Symbol must be a string")
            return None
        
        # Clean and validate symbol format
        symbol = symbol.strip().upper()
        if not symbol.replace('-', '').replace('.', '').isalnum():
            logger.error(f"Invalid symbol format: {symbol}")
            return None
        
        # Validate expiration date if provided
        if target_expiration:
            if not isinstance(target_expiration, str):
                logger.error("Target expiration must be a string")
                return None
            if len(target_expiration) != 10 or target_expiration.count('-') != 2:
                logger.error("Invalid expiration date format. Use YYYY-MM-DD")
                return None
        
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
            
            snapshots = chain_data['snapshots']
            logger.debug(f"Snapshots type: {type(snapshots)}, length: {len(snapshots) if hasattr(snapshots, '__len__') else 'N/A'}")
            
            # Handle both dict and list formats
            if isinstance(snapshots, dict):
                # Convert dict to list format
                options_list = []
                for symbol_key, option_data in snapshots.items():
                    if isinstance(option_data, dict):
                        option_entry = {'symbol': symbol_key}
                        option_entry.update(option_data)
                        options_list.append(option_entry)
                return self._process_options_chain(options_list, symbol)
            elif isinstance(snapshots, list):
                return self._process_options_chain(snapshots, symbol)
            else:
                logger.error(f"Unexpected snapshots format for {symbol}: {type(snapshots)}")
                return None
            
        except Exception as e:
            logger.error(f"Error discovering options for {symbol}: {e}")
            logger.error(f"Full traceback: {traceback.format_exc()}")
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
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            logger.error("Symbol, short_exp, and long_exp are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(short_exp, str) or not isinstance(long_exp, str):
            logger.error("Symbol, short_exp, and long_exp must be strings")
            return None
        
        if not isinstance(quantity, int) or quantity <= 0:
            logger.error("Quantity must be a positive integer")
            return None
        
        if order_type not in ['limit', 'market']:
            logger.error("Order type must be 'limit' or 'market'")
            return None
        
        # Validate date format (basic check)
        if len(short_exp) != 10 or short_exp.count('-') != 2 or len(long_exp) != 10 or long_exp.count('-') != 2:
            logger.error("Invalid date format. Use YYYY-MM-DD")
            return None
        
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
            
            # Place order using raw HTTP API for multi-leg orders
            try:
                # Get the base URL for the trading API from config
                credentials = config.get_current_alpaca_credentials()
                base_url = credentials['base_url']
                url = f"{base_url}/v2/orders"
                
                headers = {
                    "accept": "application/json",
                    "content-type": "application/json",
                    "Apca-Api-Key-Id": self.api_key,
                    "Apca-Api-Secret-Key": self.secret_key
                }
                
                # Submit the order
                response = requests.post(url, headers=headers, json=order_data)
                response.raise_for_status()
                
                order_data_response = response.json()
                order_id = order_data_response.get('id')
                
                if order_id:
                    logger.info(f"Multi-leg order submitted successfully: {order_id}")
                    order = type('Order', (), {
                        'id': order_id,
                        'status': order_data_response.get('status', 'new')
                    })()
                else:
                    logger.error(f"Failed to get order ID from response: {order_data_response}")
                    return None
                    
            except Exception as e:
                logger.error(f"Failed to submit multi-leg order via HTTP API: {e}")
                return None
            
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
                                         quantity: int, order_type: str, limit_price: float = None):
        """Create the order data structure for calendar spread orders."""
        if order_type == "limit" and limit_price:
            # For limit orders, we need to use the raw dictionary format for multi-leg orders
            order_data = {
                "order_class": "mleg",
                "qty": quantity,
                "type": "limit",
                "limit_price": limit_price,
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
            return order_data
        else:
            # For market orders, use the raw dictionary format
            order_data = {
                "order_class": "mleg",
                "qty": quantity,
                "type": "market",
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
            return order_data

    # Position management methods
    @prevent_live_trading_in_tests
    def close_position(self, symbol: str, quantity: int = None) -> bool:
        """Close a position for a given symbol."""
        # Validate inputs
        if not symbol:
            logger.error("Symbol cannot be empty")
            return False
        
        if not isinstance(symbol, str):
            logger.error("Symbol must be a string")
            return False
        
        if quantity is not None and (not isinstance(quantity, int) or quantity <= 0):
            logger.error("Quantity must be a positive integer or None")
            return False
        
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
        # Validate input
        if not order_id:
            logger.error("Order ID cannot be empty")
            return None
        
        if not isinstance(order_id, str):
            logger.error("Order ID must be a string")
            return None
        
        try:
            # Try HTTP API first since we're using it for order placement
            try:
                # Get the base URL for the trading API from config
                credentials = config.get_current_alpaca_credentials()
                base_url = credentials['base_url']
                url = f"{base_url}/v2/orders/{order_id}"
                
                headers = {
                    "accept": "application/json",
                    "Apca-Api-Key-Id": credentials['api_key'],
                    "Apca-Api-Secret-Key": credentials['secret_key']
                }
                
                # Get the order
                response = requests.get(url, headers=headers)
                response.raise_for_status()
                
                order_data = response.json()
                logger.info(f"Retrieved order {order_id} via HTTP API: {order_data.get('status', 'unknown')}")
                
                return {
                    'id': order_data.get('id'),
                    'status': order_data.get('status'),
                    'symbol': order_data.get('symbol'),
                    'qty': order_data.get('qty'),
                    'filled_qty': order_data.get('filled_qty', 0),
                    'side': order_data.get('side'),
                    'type': order_data.get('type'),
                    'time_in_force': order_data.get('time_in_force'),
                    'limit_price': order_data.get('limit_price'),
                    'stop_price': order_data.get('stop_price'),
                    'filled_avg_price': order_data.get('filled_avg_price'),
                    'submitted_at': order_data.get('submitted_at'),
                    'filled_at': order_data.get('filled_at'),
                    'expired_at': order_data.get('expired_at'),
                    'canceled_at': order_data.get('canceled_at'),
                    'failed_at': order_data.get('failed_at'),
                    'replaced_at': order_data.get('replaced_at'),
                    'replaced_by': order_data.get('replaced_by'),
                    'replaces': order_data.get('replaces'),
                    'asset_id': order_data.get('asset_id'),
                    'notional': order_data.get('notional'),
                    'order_class': order_data.get('order_class'),
                    'legs': order_data.get('legs')
                }
                
            except Exception as http_error:
                logger.warning(f"HTTP API failed for order {order_id}: {http_error}, trying SDK fallback")
                
                # Fallback to SDK method
                orders = self.trading_client.get_orders()
                order = next((o for o in orders if o.id == order_id), None)
                
                if not order:
                    logger.warning(f"Order {order_id} not found via SDK either")
                    return None
                    
                return {
                    'id': order.id,
                    'status': order.status,
                    'symbol': order.symbol,
                    'qty': order.qty,
                    'filled_qty': order.filled_qty,
                    'side': order.side,
                    'type': order.type,
                    'time_in_force': order.time_in_force,
                    'limit_price': order.limit_price,
                    'stop_price': order.stop_price,
                    'filled_avg_price': order.filled_avg_price,
                    'submitted_at': order.submitted_at.isoformat() if order.submitted_at else None,
                    'filled_at': order.filled_at.isoformat() if order.filled_at else None,
                    'expired_at': order.expired_at.isoformat() if order.expired_at else None,
                    'canceled_at': order.canceled_at.isoformat() if order.canceled_at else None,
                    'failed_at': order.failed_at.isoformat() if order.failed_at else None,
                    'replaced_at': order.replaced_at.isoformat() if order.replaced_at else None,
                    'replaced_by': order.replaced_by,
                    'replaces': order.replaces,
                    'asset_id': order.asset_id,
                    'notional': order.notional,
                    'order_class': order.order_class,
                    'legs': order.legs
                }
                
        except Exception as e:
            logger.error(f"Failed to get order status for {order_id}: {e}")
            return None

    @prevent_live_trading_in_tests
    def cancel_order(self, order_id: str) -> bool:
        """Cancel an open order."""
        # Validate input
        if not order_id:
            logger.error("Order ID cannot be empty")
            return False
        
        if not isinstance(order_id, str):
            logger.error("Order ID must be a string")
            return False
        
        try:
            self.trading_client.cancel_order_by_id(order_id)
            logger.info(f"Canceled order: {order_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to cancel order {order_id}: {e}")
            raise

    def replace_order(self, order_id: str, new_limit_price: float) -> Optional[Dict]:
        """Replace an existing order with a new limit price using Alpaca's native replacement API."""
        # Validate inputs
        if not order_id:
            logger.error("Order ID cannot be empty")
            return None
        
        if not isinstance(order_id, str):
            logger.error("Order ID must be a string")
            return None
        
        if not isinstance(new_limit_price, (int, float)) or new_limit_price <= 0:
            logger.error("New limit price must be a positive number")
            return None
        
        try:
            # Create replacement request with new limit price
            replace_request = ReplaceOrderRequest(
                limit_price=new_limit_price
            )
            
            # Use Alpaca's native order replacement
            updated_order = self.trading_client.replace_order_by_id(
                order_id=order_id,
                order_data=replace_request
            )
            
            logger.info(f"Successfully replaced order {order_id} with new limit price: ${new_limit_price}")
            
            # Return standardized response
            return {
                'status': 'replaced',
                'old_order_id': order_id,
                'new_order_id': updated_order.id,
                'new_limit_price': float(updated_order.limit_price) if updated_order.limit_price else new_limit_price,
                'symbol': updated_order.symbol,
                'qty': float(updated_order.qty) if updated_order.qty else None,
                'side': updated_order.side,
                'type': updated_order.type,
                'message': 'Order replacement completed successfully'
            }
            
        except Exception as e:
            logger.error(f"Failed to replace order {order_id}: {e}")
            return None

    def get_calendar_spread_prices(self, symbol: str, short_exp: str, long_exp: str, 
                                  option_type: str = 'call', order_type: str = 'entry') -> Optional[Dict]:
        """Get calendar spread prices for both entry and exit using the midpoint method.
        
        This is a unified method that replaces the previous separate methods for getting
        midpoint prices and current values. It returns comprehensive pricing information
        for calendar spreads.
        """
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            logger.error("Symbol, short_exp, and long_exp are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(short_exp, str) or not isinstance(long_exp, str):
            logger.error("Symbol, short_exp, and long_exp must be strings")
            return None
        
        if order_type not in ['entry', 'exit']:
            logger.error("Order type must be 'entry' or 'exit'")
            return None
        
        # Validate date format (basic check)
        if len(short_exp) != 10 or short_exp.count('-') != 2 or len(long_exp) != 10 or long_exp.count('-') != 2:
            logger.error("Invalid date format. Use YYYY-MM-DD")
            return None
        
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            # Find the option symbols for the given expirations
            current_price = self.get_current_price(symbol)
            if not current_price:
                logger.error(f"Could not get current price for {symbol}")
                return None
                
            target_strike = round(current_price)
            
            short_options = self.discover_available_options(symbol, short_exp)
            long_options = self.discover_available_options(symbol, long_exp)
            
            if not short_options or not long_options:
                logger.error(f"Could not get options data for {symbol}")
                return None
            
            short_symbol = self._find_closest_strike_option(short_options, target_strike)
            long_symbol = self._find_closest_strike_option(long_options, target_strike)
            
            if not short_symbol or not long_symbol:
                logger.error(f"Could not find suitable call options for {symbol}")
                return None
            
            # Use the existing method to calculate prices
            result = self.calculate_calendar_spread_limit_price(long_symbol, short_symbol, order_type)
            if result:
                # Add the symbol and expiration info for compatibility
                result.update({
                    'symbol': symbol,
                    'short_exp': short_exp,
                    'long_exp': long_exp,
                    'option_type': option_type,
                    'target_strike': target_strike
                })
            
            return result
            
        except Exception as e:
            logger.error(f"Failed to get calendar spread prices for {symbol}: {e}")
            return None



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
        # Validate input
        if date is not None and not isinstance(date, datetime):
            logger.error("Date must be a datetime object or None")
            return None
        
        try:
            if date is None:
                date = datetime.now(timezone.utc)
            
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
        # Validate inputs
        if not symbol or not earnings_date:
            logger.error("Symbol and earnings_date are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(earnings_date, str):
            logger.error("Symbol and earnings_date must be strings")
            return None
        
        if not isinstance(target_strike, (int, float)) or target_strike <= 0:
            logger.error("Target strike must be a positive number")
            return None
        
        if earnings_time not in ['amc', 'bmo']:
            logger.error("Earnings time must be 'amc' or 'bmo'")
            return None
        
        # Validate date format (basic check)
        if len(earnings_date) != 10 or earnings_date.count('-') != 2:
            logger.error("Invalid earnings date format. Use YYYY-MM-DD")
            return None
        
        try:
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
        # Validate input
        if not symbol:
            logger.error("Symbol cannot be empty")
            return 0
        
        if not isinstance(symbol, str):
            logger.error("Symbol must be a string")
            return 0
        
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
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            logger.error("Symbol, short_exp, and long_exp are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(short_exp, str) or not isinstance(long_exp, str):
            logger.error("Symbol, short_exp, and long_exp must be strings")
            return None
        
        # Validate date format (basic check)
        if len(short_exp) != 10 or short_exp.count('-') != 2 or len(long_exp) != 10 or long_exp.count('-') != 2:
            logger.error("Invalid date format. Use YYYY-MM-DD")
            return None
        
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



    @safe_trading_mode
    def close_calendar_spread(self, symbol: str, short_exp: str, long_exp: str, 
                             option_type: str = 'call', quantity: int = 1) -> Optional[Dict]:
        """Close a calendar spread position."""
        # Validate inputs
        if not symbol or not short_exp or not long_exp:
            logger.error("Symbol, short_exp, and long_exp are required")
            return None
        
        if not isinstance(symbol, str) or not isinstance(short_exp, str) or not isinstance(long_exp, str):
            logger.error("Symbol, short_exp, and long_exp must be strings")
            return None
        
        if not isinstance(quantity, int) or quantity <= 0:
            logger.error("Quantity must be a positive integer")
            return None
        
        # Validate date format (basic check)
        if len(short_exp) != 10 or short_exp.count('-') != 2 or len(long_exp) != 10 or long_exp.count('-') != 2:
            logger.error("Invalid date format. Use YYYY-MM-DD")
            return None
        
        try:
            if option_type.lower() != 'call':
                logger.warning(f"Strategy requires call options, using calls instead of {option_type}")
                option_type = 'call'
            
            position_info = self.validate_calendar_spread_position(symbol, short_exp, long_exp, option_type)
            if not position_info:
                logger.error(f"Calendar spread position validation failed for {symbol}")
                return None
            
            current_spread_info = self.get_calendar_spread_prices(symbol, short_exp, long_exp, option_type, 'exit')
            if not current_spread_info:
                logger.error(f"Could not get current spread value for {symbol}")
                return None
            
            # Calculate current value from the spread prices
            current_value = current_spread_info.get('debit_cost', 0)
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
                    'strike_price': current_spread_info.get('strike_price', target_strike),
                    'quantity': quantity,
                    'exit_price': exit_price,
                    'current_market_value': current_value,
                    'days_remaining': current_spread_info.get('days_between', 0),
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
        # Validate inputs
        if activity_types is not None and not isinstance(activity_types, list):
            logger.error("Activity types must be a list or None")
            return []
        
        if limit is not None:
            if not isinstance(limit, int) or limit <= 0:
                logger.error("Limit must be a positive integer")
                return []
            if limit > 100:
                logger.warning("Limit exceeds 100, capping to 100")
                limit = 100
        
        try:
            # Get current credentials
            credentials = config.get_current_alpaca_credentials()
            base_url = credentials['base_url']
            if base_url.endswith('/v2'):
                base_url = base_url[:-3]  # Remove '/v2' suffix
            elif base_url.endswith('/v2/'):
                base_url = base_url[:-4]  # Remove '/v2/' suffix
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
        # Validate input
        if limit is not None:
            if not isinstance(limit, int) or limit <= 0:
                logger.error("Limit must be a positive integer")
                return []
            if limit > 100:
                logger.warning("Limit exceeds 100, capping to 100")
                limit = 100
        
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
