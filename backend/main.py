"""
Main entry point for the trading application.
Uses the organized package structure.
"""

import logging
from api.app import app
from services.scheduler import TradingScheduler

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

def main():
    """Main application entry point."""
    try:
        logger.info("🚀 Starting Trading Application...")
        
        # Initialize scheduler
        scheduler = TradingScheduler()
        
        # Start the scheduler
        scheduler.start()
        
        logger.info("✅ Trading Application started successfully")
        logger.info("📊 Scheduler status: %s", scheduler.get_scheduler_status())
        
        return app
        
    except Exception as e:
        logger.error(f"❌ Failed to start Trading Application: {e}")
        raise

if __name__ == "__main__":
    main()
