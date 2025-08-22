"""
Settings Repository
Handles all settings-related database operations.
"""

import logging
from datetime import datetime
from typing import List, Dict, Optional
from .base_repository import BaseRepository

logger = logging.getLogger(__name__)

class SettingsRepository(BaseRepository):
    """Repository for settings-related database operations."""
    
    def get_setting(self, key: str) -> Optional[str]:
        """Get a setting value."""
        try:
            logger.info(f"🔍 SettingsRepository: Getting {key}...")
            
            query = "SELECT value FROM settings WHERE key = ?"
            value = self.execute_scalar(query, (key,))
            
            if value:
                logger.info(f"   Found value: {value[:8] if key.endswith('_key') and value else value}")
                # CRITICAL: Log the exact length and content being retrieved
                logger.info(f"   🔍 RETRIEVED DETAILS:")
                logger.info(f"   Key: {key}")
                logger.info(f"   Value length: {len(value)}")
                if key.endswith('_key') and value:
                    logger.info(f"   Value content: {value[:8]}...{value[-4:]}")
                else:
                    logger.info(f"   Value content: {value}")
                return value
            else:
                logger.info(f"   No value found for {key}")
                return None
                
        except Exception as e:
            logger.error(f"❌ Failed to get setting {key}: {e}")
            import traceback
            logger.error(f"❌ Full traceback: {traceback.format_exc()}")
            return None
    
    def set_setting(self, key: str, value: str) -> bool:
        """Set a setting value."""
        try:
            logger.info(f"🔍 SettingsRepository: Setting {key} = {value[:8] if key.endswith('_key') and value else value}...")
            
            # CRITICAL: Log the exact length and content being saved
            logger.info(f"   🔍 SETTING DETAILS:")
            logger.info(f"   Key: {key}")
            logger.info(f"   Value length: {len(value) if value else 0}")
            # Don't log the preview format - just log the length to avoid confusion
            logger.info(f"   Value length: {len(value) if value else 0} characters")
            
            # Check if setting exists
            existing = self.get_setting(key)
            
            if existing:
                logger.info(f"   Setting exists, updating...")
                logger.info(f"   Old value length: {len(existing) if existing else 0}")
                if key.endswith('_key') and existing:
                    logger.info(f"   Old value content: {existing[:8]}...{existing[-4:]}")
                else:
                    logger.info(f"   Old value content: {existing}")
                
                # Use UPDATE
                query = "UPDATE settings SET value = ?, updated_at = CURRENT_TIMESTAMP WHERE key = ?"
                success = self.execute_update(query, (value, key))
            else:
                logger.info(f"   Setting doesn't exist, inserting...")
                # Use INSERT
                query = "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                success = self.execute_update(query, (key, value))
            
            if not success:
                logger.error(f"   ❌ Database operation failed")
                return False
            
            # CRITICAL: Verify what was actually saved
            saved_value = self.get_setting(key)
            if saved_value:
                logger.info(f"   ✅ Successfully saved {key}")
                logger.info(f"   Saved value length: {len(saved_value) if saved_value else 0}")
                # Don't log the preview format - just log the length
                logger.info(f"   Saved value length: {len(saved_value) if saved_value else 0} characters")
                
                # Check if truncation occurred
                if saved_value != value:
                    logger.error(f"   ❌ TRUNCATION DETECTED!")
                    logger.error(f"   Original length: {len(value)}")
                    logger.error(f"   Saved length: {len(saved_value)}")
                    logger.error(f"   Original: {value}")
                    logger.error(f"   Saved: {saved_value}")
                else:
                    logger.info(f"   ✅ No truncation detected - values match exactly")
            else:
                logger.error(f"   ❌ Failed to verify saved value")
            
            return True
            
        except Exception as e:
            logger.error(f"❌ Failed to set setting {key}: {e}")
            import traceback
            logger.error(f"❌ Full traceback: {traceback.format_exc()}")
            return False
    
    def get_all_settings(self) -> Dict[str, str]:
        """Get all settings as a dictionary."""
        try:
            query = "SELECT key, value FROM settings ORDER BY key"
            results = self.execute_query(query)
            
            settings = {}
            for row in results:
                settings[row['key']] = row['value']
            
            return settings
            
        except Exception as e:
            logger.error(f"❌ Failed to get all settings: {e}")
            return {}
    
    def delete_setting(self, key: str) -> bool:
        """Delete a setting by key."""
        try:
            query = "DELETE FROM settings WHERE key = ?"
            success = self.execute_update(query, (key,))
            
            if success:
                logger.info(f"✅ Deleted setting {key}")
            return success
            
        except Exception as e:
            logger.error(f"❌ Failed to delete setting {key}: {e}")
            return False
    
    def get_settings_by_pattern(self, pattern: str) -> Dict[str, str]:
        """Get settings that match a pattern (e.g., 'cache_%')."""
        try:
            query = "SELECT key, value FROM settings WHERE key LIKE ? ORDER BY key"
            results = self.execute_query(query, (pattern,))
            
            settings = {}
            for row in results:
                settings[row['key']] = row['value']
            
            return settings
            
        except Exception as e:
            logger.error(f"❌ Failed to get settings by pattern {pattern}: {e}")
            return {}
    
    def bulk_update_settings(self, settings: Dict[str, str]) -> bool:
        """Update multiple settings at once."""
        try:
            if not settings:
                return True
            
            # Use a transaction for bulk update
            with self._get_connection() as conn:
                cursor = conn.cursor()
                
                for key, value in settings.items():
                    cursor.execute("""
                        INSERT OR REPLACE INTO settings (key, value, updated_at) 
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, (key, value))
                
                conn.commit()
                logger.info(f"✅ Bulk updated {len(settings)} settings")
                return True
                
        except Exception as e:
            logger.error(f"❌ Failed to bulk update settings: {e}")
            return False
    
    def get_setting_with_default(self, key: str, default_value: str) -> str:
        """Get a setting value, returning default if not found."""
        value = self.get_setting(key)
        return value if value is not None else default_value
    
    def get_boolean_setting(self, key: str, default_value: bool = False) -> bool:
        """Get a boolean setting value."""
        value = self.get_setting(key)
        if value is None:
            return default_value
        
        return value.lower() in ('true', '1', 'yes', 'on')
    
    def get_int_setting(self, key: str, default_value: int = 0) -> int:
        """Get an integer setting value."""
        value = self.get_setting(key)
        if value is None:
            return default_value
        
        try:
            return int(value)
        except (ValueError, TypeError):
            logger.warning(f"⚠️ Could not convert setting {key}='{value}' to int, using default {default_value}")
            return default_value
    
    def get_float_setting(self, key: str, default_value: float = 0.0) -> float:
        """Get a float setting value."""
        value = self.get_setting(key)
        if value is None:
            return default_value
        
        try:
            return float(value)
        except (ValueError, TypeError):
            logger.warning(f"⚠️ Could not convert setting {key}='{value}' to float, using default {default_value}")
            return default_value
    
    def get_json_setting(self, key: str, default_value: dict = None) -> dict:
        """Get a JSON setting value."""
        if default_value is None:
            default_value = {}
        
        value = self.get_setting(key)
        if value is None:
            return default_value
        
        try:
            import json
            return json.loads(value)
        except (json.JSONDecodeError, TypeError):
            logger.warning(f"⚠️ Could not parse JSON setting {key}='{value}', using default")
            return default_value
    
    def set_json_setting(self, key: str, value: dict) -> bool:
        """Set a JSON setting value."""
        try:
            import json
            json_value = json.dumps(value)
            return self.set_setting(key, json_value)
            
        except Exception as e:
            logger.error(f"❌ Failed to set JSON setting {key}: {e}")
            return False
    
    def get_settings_metadata(self) -> List[Dict]:
        """Get metadata about all settings including last updated time."""
        try:
            query = """
                SELECT key, value, updated_at, 
                       LENGTH(value) as value_length
                FROM settings 
                ORDER BY updated_at DESC
            """
            return self.execute_query(query)
            
        except Exception as e:
            logger.error(f"❌ Failed to get settings metadata: {e}")
            return []
    
    def cleanup_old_settings(self, cutoff_date: datetime) -> int:
        """Clean up old settings (useful for temporary/cache settings)."""
        try:
            query = """
                DELETE FROM settings 
                WHERE updated_at < ?
            """
            success = self.execute_update(query, (cutoff_date.isoformat(),))
            
            if success:
                # Get count of deleted records
                count_query = """
                    SELECT COUNT(*) FROM settings 
                    WHERE updated_at < ?
                """
                count = self.execute_scalar(count_query, (cutoff_date.isoformat(),))
                deleted_count = count if count else 0
                logger.info(f"✅ Cleaned up {deleted_count} old settings")
                return deleted_count
            else:
                return 0
                
        except Exception as e:
            logger.error(f"❌ Failed to cleanup old settings: {e}")
            return 0
    
    def _get_connection(self):
        """Get a database connection for transaction operations."""
        import sqlite3
        return sqlite3.connect(self.db_path)
