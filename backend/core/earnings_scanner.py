"""
Earnings scanner for fetching and filtering earnings announcements.
Uses Finnhub API to get earnings calendar data.
"""

import logging
import requests
from datetime import datetime, timedelta, timezone
import pytz
from typing import List, Dict, Optional
import config

logger = logging.getLogger(__name__)

class EarningsScanner:
    def __init__(self):
        """Initialize the earnings scanner with Finnhub API key."""
        self.api_key = config.FINNHUB_API_KEY
        if not self.api_key:
            raise ValueError("Finnhub API key not configured")
        
        self.base_url = "https://finnhub.io/api/v1"
        logger.info("Earnings scanner initialized")
    
    def get_earnings_calendar(self, start_date: str = None, end_date: str = None) -> List[Dict]:
        """
        Fetch earnings calendar from Finnhub API.
        
        Args:
            start_date: Start date in YYYY-MM-DD format (defaults to today)
            end_date: End date in YYYY-MM-DD format (defaults to today + 7 days)
        
        Returns:
            List of earnings announcements
        """
        try:
            if not start_date:
                # Use UTC time and convert to US Eastern timezone for market operations
                utc_now = datetime.utcnow()
                eastern_tz = pytz.timezone('US/Eastern')
                start_date = utc_now.astimezone(eastern_tz).strftime("%Y-%m-%d")
            if not end_date:
                # Use UTC time and convert to US Eastern timezone for market operations
                utc_now = datetime.utcnow()
                eastern_tz = pytz.timezone('US/Eastern')
                end_date = (utc_now.astimezone(eastern_tz) + timedelta(days=7)).strftime("%Y-%m-%d")
            
            url = f"{self.base_url}/calendar/earnings"
            params = {
                'from': start_date,
                'to': end_date,
                'token': self.api_key
            }
            
            response = requests.get(url, params=params, timeout=config.FINNHUB_TIMEOUT)
            
            response.raise_for_status()
            
            data = response.json()
            
            if 'earningsCalendar' not in data:
                logger.warning("No earnings calendar data in response")
                logger.warning(f"Full response: {data}")
                return []
            
            earnings_data = data['earningsCalendar']
            
            return earnings_data
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Failed to fetch earnings calendar: {e}")
            return []
        except Exception as e:
            logger.error(f"Unexpected error fetching earnings calendar: {e}")
            return []
    
    def filter_earnings_timing(self, earnings: List[Dict]) -> List[Dict]:
        """
        Filter earnings based on timing criteria:
        - After market close today (4:00 PM ET) but before market open tomorrow (9:30 AM ET)
        """
        try:
            # Use UTC time and convert to US Eastern timezone for market operations
            utc_now = datetime.utcnow()
            eastern_tz = pytz.timezone('US/Eastern')
            today = utc_now.replace(tzinfo=pytz.UTC).astimezone(eastern_tz).date()
            tomorrow = today + timedelta(days=1)
            
            filtered_earnings = []
            
            for earning in earnings:
                try:
                    # Parse earnings date
                    earning_date = datetime.strptime(earning['date'], "%Y-%m-%d").date()
                    hour = earning.get('hour', '')
                    
                    # Skip stocks without specific timing (TBA stocks)
                    if not hour or hour == '' or hour == 'tna':
                        continue
                    
                    # Check if earnings are today after market close or tomorrow before market open
                    if (earning_date == today and hour == 'amc') or \
                       (earning_date == tomorrow and hour == 'bmo'):
                        filtered_earnings.append(earning)
                        
                except (ValueError, KeyError) as e:
                    logger.warning(f"Invalid earnings data format: {e}")
                    continue
            
            return filtered_earnings
            
        except Exception as e:
            logger.error(f"Failed to filter earnings timing: {e}")
            return []
    
    def get_filtered_earnings(self, start_date: str = None, end_date: str = None) -> List[Dict]:
        """
        Get earnings calendar and apply timing filters.
        
        Returns:
            List of filtered earnings announcements
        """
        try:
            # Get all earnings
            all_earnings = self.get_earnings_calendar(start_date, end_date)
            
            # Apply timing filters
            filtered_earnings = self.filter_earnings_timing(all_earnings)
            
            return filtered_earnings
            
        except Exception as e:
            logger.error(f"Failed to get filtered earnings: {e}")
            return []
    
    def get_tomorrow_earnings(self) -> List[Dict]:
        """Get earnings for tomorrow (before market open)."""
        try:
            # Use UTC time and convert to US Eastern timezone for market operations
            utc_now = datetime.now(timezone.utc)
            eastern_tz = pytz.timezone('US/Eastern')
            tomorrow = (utc_now.astimezone(eastern_tz) + timedelta(days=1)).strftime("%Y-%m-%d")
            
            earnings = self.get_earnings_calendar(tomorrow, tomorrow)
            
            # Filter for before market open and exclude TBA stocks
            bmo_earnings = [e for e in earnings if e.get('hour') == 'bmo' and e.get('hour') not in ['', 'tna']]
            
            return bmo_earnings
            
        except Exception as e:
            logger.error(f"Failed to get tomorrow earnings: {e}")
            return []
    
    def get_today_post_market_earnings(self) -> List[Dict]:
        """Get today's earnings that occur after market close."""
        try:
            utc_now = datetime.now(timezone.utc)
            eastern_tz = pytz.timezone('US/Eastern')
            today = utc_now.astimezone(eastern_tz).strftime("%Y-%m-%d")
            
            earnings = self.get_earnings_calendar(today, today)
            
            # Filter for after market close and exclude TBA stocks
            amc_earnings = [e for e in earnings if e.get('hour') == 'amc' and e.get('hour') not in ['', 'tna']]
            
            return amc_earnings
            
        except Exception as e:
            logger.error(f"Failed to get today post-market earnings: {e}")
            return []
    
    def get_earnings_for_scanning(self) -> List[Dict]:
        """
        Get earnings that should be scanned for trading opportunities.
        Combines tomorrow pre-market and today post-market earnings.
        """
        try:
            tomorrow_earnings = self.get_tomorrow_earnings()
            today_earnings = self.get_today_post_market_earnings()
            
            all_scan_earnings = tomorrow_earnings + today_earnings
            
            # Remove duplicates based on symbol
            seen_symbols = set()
            unique_earnings = []
            
            for earning in all_scan_earnings:
                symbol = earning.get('symbol', '').upper()
                if symbol and symbol not in seen_symbols:
                    seen_symbols.add(symbol)
                    unique_earnings.append(earning)
            
            return unique_earnings
            
        except Exception as e:
            logger.error(f"Failed to get earnings for scanning: {e}")
            return []
    
    def validate_earnings_data(self, earnings: List[Dict]) -> List[Dict]:
        """
        Validate earnings data and filter out invalid entries.
        
        Args:
            earnings: List of earnings announcements
            
        Returns:
            List of validated earnings announcements
        """
        try:
            validated_earnings = []
            
            for earning in earnings:
                try:
                    # Check required fields
                    if not earning.get('symbol') or not earning.get('date'):
                        continue
                    
                    # Validate date format
                    datetime.strptime(earning['date'], "%Y-%m-%d")
                    
                    # Validate symbol format (basic check)
                    symbol = earning['symbol'].upper()
                    if not symbol.isalpha() or len(symbol) > 5:
                        continue
                    
                    validated_earnings.append(earning)
                    
                except (ValueError, KeyError) as e:
                    logger.warning(f"Invalid earnings entry: {e}")
                    continue
            
            return validated_earnings
            
        except Exception as e:
            logger.error(f"Failed to validate earnings data: {e}")
            return []



