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

    def daily_scan_job(self):
        """Perform daily earnings scan and filtering."""
        try:
            logger.info("Starting daily earnings scan...")
            
            # Get upcoming earnings (next 2 days)
            end_date = datetime.now() + timedelta(days=2)
            
            logger.info("Scanning for upcoming earnings...")
            earnings_data = self.earnings_scanner.get_upcoming_earnings(
                start_date=datetime.now(),
                end_date=end_date
            )
            
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
                    scan_data = {
                        'ticker': symbol,
                        'earnings_date': earning.get('date', ''),
                        'earnings_time': earning.get('time', 'amc'),
                        'recommendation_score': recommendation.get('score', 0),
                        'filters': recommendation.get('filters', {}),
                        'reasoning': recommendation.get('reasoning', ''),
                        'scanned_at': datetime.now().isoformat()
                    }
                    
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

    def scan_specific_symbols(self, symbols: List[str], earnings_date: str = None) -> Dict:
        """Scan specific symbols for trading opportunities."""
        try:
            logger.info(f"Scanning {len(symbols)} specific symbols...")
            
            results = {
                'scanned': [],
                'selected': [],
                'failed': [],
                'total_scanned': 0,
                'total_selected': 0
            }
            
            for symbol in symbols:
                try:
                    logger.info(f"Scanning {symbol}...")
                    
                    # Get earnings data if not provided
                    if not earnings_date:
                        # Try to find upcoming earnings for this symbol
                        upcoming_earnings = self.earnings_scanner.get_upcoming_earnings(
                            start_date=datetime.now(),
                            end_date=datetime.now() + timedelta(days=7)
                        )
                        
                        symbol_earnings = [e for e in upcoming_earnings if e.get('symbol') == symbol]
                        if not symbol_earnings:
                            logger.warning(f"No upcoming earnings found for {symbol}")
                            results['failed'].append({'symbol': symbol, 'reason': 'No upcoming earnings'})
                            continue
                        
                        earnings_date = symbol_earnings[0].get('date', '')
                    
                    # Compute recommendation
                    recommendation = compute_recommendation(symbol)
                    
                    if not recommendation:
                        logger.warning(f"No recommendation generated for {symbol}")
                        results['failed'].append({'symbol': symbol, 'reason': 'No recommendation'})
                        continue
                    
                    # Store scan result
                    scan_data = {
                        'ticker': symbol,
                        'earnings_date': earnings_date,
                        'earnings_time': 'amc',  # Default
                        'recommendation_score': recommendation.get('score', 0),
                        'filters': recommendation.get('filters', {}),
                        'reasoning': recommendation.get('reasoning', ''),
                        'scanned_at': datetime.now().isoformat()
                    }
                    
                    success = self.database.add_scan_result(scan_data)
                    if success:
                        results['scanned'].append(scan_data)
                        results['total_scanned'] += 1
                        
                        # Auto-select if score is high enough
                        if recommendation.get('score', 0) >= config.AUTO_SELECT_THRESHOLD:
                            self.database.set_trade_selection(symbol, earnings_date, True)
                            results['selected'].append(scan_data)
                            results['total_selected'] += 1
                            logger.info(f"Auto-selected {symbol} (score: {recommendation.get('score', 0)})")
                    
                except Exception as e:
                    logger.error(f"Error scanning {symbol}: {e}")
                    results['failed'].append({'symbol': symbol, 'reason': str(e)})
                    continue
            
            logger.info(f"Specific symbol scan completed: {results['total_scanned']} scanned, {results['total_selected']} selected")
            return results
            
        except Exception as e:
            logger.error(f"Error in specific symbol scan: {e}")
            return {'error': str(e), 'total_scanned': 0, 'total_selected': 0}

    def rescan_symbol(self, symbol: str, earnings_date: str = None) -> Optional[Dict]:
        """Rescan a specific symbol and update its recommendation."""
        try:
            logger.info(f"Rescanning {symbol}...")
            
            # Get earnings date if not provided
            if not earnings_date:
                # Try to get from existing scan results
                latest_scan = self.database.get_latest_scan_result(symbol)
                if latest_scan:
                    earnings_date = latest_scan.get('earnings_date', '')
                
                if not earnings_date:
                    # Try to find upcoming earnings
                    upcoming_earnings = self.earnings_scanner.get_upcoming_earnings(
                        start_date=datetime.now(),
                        end_date=datetime.now() + timedelta(days=7)
                    )
                    
                    symbol_earnings = [e for e in upcoming_earnings if e.get('symbol') == symbol]
                    if symbol_earnings:
                        earnings_date = symbol_earnings[0].get('date', '')
                    else:
                        logger.warning(f"No earnings date found for {symbol}")
                        return None
            
            # Compute fresh recommendation
            recommendation = compute_recommendation(symbol)
            
            if not recommendation:
                logger.warning(f"No recommendation generated for {symbol}")
                return None
            
            # Store updated scan result
            scan_data = {
                'ticker': symbol,
                'earnings_date': earnings_date,
                'earnings_time': 'amc',  # Default
                'recommendation_score': recommendation.get('score', 0),
                'filters': recommendation.get('filters', {}),
                'reasoning': recommendation.get('reasoning', ''),
                'scanned_at': datetime.now().isoformat(),
                'rescan': True
            }
            
            success = self.database.add_scan_result(scan_data)
            if success:
                logger.info(f"Successfully rescanned {symbol} (score: {recommendation.get('score', 0)})")
                return scan_data
            else:
                logger.error(f"Failed to store rescan result for {symbol}")
                return None
            
        except Exception as e:
            logger.error(f"Error rescanning {symbol}: {e}")
            return None

    def get_scan_summary(self, days: int = 7) -> Dict:
        """Get summary of recent scan results."""
        try:
            # Get recent scan results
            scan_results = self.database.get_recent_scan_results(days=days)
            
            summary = {
                'total_scans': len(scan_results),
                'unique_symbols': len(set(r.get('ticker') for r in scan_results)),
                'selected_trades': 0,
                'average_score': 0,
                'score_distribution': {'high': 0, 'medium': 0, 'low': 0},
                'top_recommendations': [],
                'recent_scans': scan_results[:10]  # Latest 10
            }
            
            if scan_results:
                # Calculate statistics
                scores = [r.get('recommendation_score', 0) for r in scan_results]
                summary['average_score'] = sum(scores) / len(scores)
                
                # Score distribution
                for score in scores:
                    if score >= 80:
                        summary['score_distribution']['high'] += 1
                    elif score >= 60:
                        summary['score_distribution']['medium'] += 1
                    else:
                        summary['score_distribution']['low'] += 1
                
                # Top recommendations
                sorted_results = sorted(scan_results, key=lambda x: x.get('recommendation_score', 0), reverse=True)
                summary['top_recommendations'] = sorted_results[:5]
            
            # Get selected trades count
            selections = self.database.get_trade_selections()
            summary['selected_trades'] = len([s for s in selections if s.get('is_selected', False)])
            
            return summary
            
        except Exception as e:
            logger.error(f"Error getting scan summary: {e}")
            return {}

    def cleanup_old_scans(self, days_to_keep: int = 30) -> int:
        """Clean up old scan results."""
        try:
            cutoff_date = datetime.now() - timedelta(days=days_to_keep)
            cleaned_count = self.database.cleanup_old_scan_results(cutoff_date)
            
            logger.info(f"Cleaned {cleaned_count} old scan results (older than {days_to_keep} days)")
            return cleaned_count
            
        except Exception as e:
            logger.error(f"Error cleaning old scans: {e}")
            return 0

    def get_earnings_calendar(self, days_ahead: int = 7) -> List[Dict]:
        """Get earnings calendar for the next N days."""
        try:
            end_date = datetime.now() + timedelta(days=days_ahead)
            
            earnings_data = self.earnings_scanner.get_upcoming_earnings(
                start_date=datetime.now(),
                end_date=end_date
            )
            
            # Enhance with existing scan data
            enhanced_earnings = []
            for earning in earnings_data:
                symbol = earning.get('symbol')
                if symbol:
                    # Get latest scan result
                    latest_scan = self.database.get_latest_scan_result(symbol)
                    if latest_scan:
                        earning['recommendation_score'] = latest_scan.get('recommendation_score', 0)
                        earning['last_scanned'] = latest_scan.get('scanned_at')
                    
                    # Check if selected
                    selections = self.database.get_trade_selections()
                    is_selected = any(
                        s.get('ticker') == symbol and s.get('is_selected', False)
                        for s in selections
                    )
                    earning['is_selected'] = is_selected
                
                enhanced_earnings.append(earning)
            
            return enhanced_earnings
            
        except Exception as e:
            logger.error(f"Error getting earnings calendar: {e}")
            return []
