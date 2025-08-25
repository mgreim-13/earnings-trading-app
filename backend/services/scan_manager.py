"""
Scan Management Module
Handles earnings scanning, filtering, and trade selection operations.
"""

import logging
from datetime import datetime, timedelta
from typing import List, Dict, Optional
import pytz

from core.earnings_scanner import EarningsScanner
from utils.filters import compute_recommendation
from core.database import Database
import config

logger = logging.getLogger(__name__)

class ScanManager:
    """Handles earnings scanning and filtering operations."""
    
    def __init__(self, earnings_scanner: EarningsScanner, database: Database):
        self.earnings_scanner = earnings_scanner
        self.database = database
        self.et_tz = pytz.timezone('US/Eastern')

    def _create_scan_data(self, symbol: str, earnings_date: str, recommendation: Dict, 
                         earnings_time: str = 'amc', rescan: bool = False) -> Dict:
        """Create standardized scan data structure."""
        scan_data = {
            'ticker': symbol,
            'earnings_date': earnings_date,
            'earnings_time': earnings_time,
            'recommendation_score': recommendation.get('score', 0),
            'filters': recommendation.get('filters', {}),
            'reasoning': recommendation.get('reasoning', ''),
            'scanned_at': datetime.now(self.et_tz).isoformat()
        }
        if rescan:
            scan_data['rescan'] = True
        return scan_data

    def daily_scan_job(self):
        """Perform daily earnings scan and filtering."""
        try:
            logger.info("Starting daily earnings scan...")
            
            # Get earnings for scanning (AMC today / BMO tomorrow)
            logger.info("Scanning for earnings for scanning...")
            earnings_data = self.earnings_scanner.get_earnings_for_scanning()
            
            if not earnings_data:
                logger.warning("No earnings data found for the next 2 days")
                return
            
            logger.info(f"Found {len(earnings_data)} earnings announcements")
            
            # Process each earnings announcement
            processed_count = 0
            selected_count = 0
            
            for earning in earnings_data:
                try:
                    symbol = earning.get('symbol')
                    if not symbol:
                        continue
                    
                    logger.info(f"Processing {symbol}...")
                    
                    # Compute recommendation
                    recommendation = compute_recommendation(symbol)
                    
                    if not recommendation:
                        logger.warning(f"No recommendation generated for {symbol}")
                        continue
                    
                    # Store scan result
                    scan_data = self._create_scan_data(
                        symbol=symbol,
                        earnings_date=earning.get('date', ''),
                        recommendation=recommendation,
                        earnings_time=earning.get('time', 'amc')
                    )
                    
                    success = self.database.add_scan_result(scan_data)
                    if success:
                        processed_count += 1
                        
                        # Auto-select high-scoring recommendations
                        if recommendation.get('score', 0) >= config.AUTO_SELECT_THRESHOLD:
                            self.database.set_trade_selection(
                                symbol, 
                                earning.get('date', ''), 
                                True
                            )
                            selected_count += 1
                            logger.info(f"Auto-selected {symbol} (score: {recommendation.get('score', 0)})")
                    
                except Exception as e:
                    logger.error(f"Error processing {earning.get('symbol', 'unknown')}: {e}")
                    continue
            
            logger.info("Daily scan completed:")
            logger.info(f"  - Earnings found: {len(earnings_data)}")
            logger.info(f"  - Successfully processed: {processed_count}")
            logger.info(f"  - Auto-selected: {selected_count}")
            
        except Exception as e:
            logger.error(f"Error in daily scan job: {e}")
