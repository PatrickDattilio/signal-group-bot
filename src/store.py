"""
Lightweight persistence for "already messaged" state: avoid duplicate DMs and enforce cooldown.
Uses a JSON file keyed by member identifier (uuid or number).
"""
import json
import logging
import os
import shutil
import time
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)


def _member_key(member: dict) -> str:
    """Stable key for a pending member (uuid preferred, else number)."""
    uuid_val = member.get("uuid")
    if uuid_val:
        return f"uuid:{uuid_val}"
    num = member.get("number")
    if num:
        return f"number:{num}"
    return json.dumps(member, sort_keys=True)


class Store:
    """File-backed store of messaged members with timestamps."""

    def __init__(self, path: str = "messaged.json", backup_enabled: bool = True):
        self.path = path
        self.backup_enabled = backup_enabled
        self._data: dict[str, float] = {}
        self._load()

    def _load(self) -> None:
        """Load store from file, attempting backup if main file is corrupted."""
        if os.path.isfile(self.path):
            try:
                with open(self.path, "r", encoding="utf-8") as f:
                    self._data = json.load(f)
                logger.debug("Loaded store from %s (%d entries)", self.path, len(self._data))
            except (json.JSONDecodeError, OSError) as e:
                logger.warning("Could not load store %s: %s", self.path, e)
                
                # Try to load from backup
                backup_path = self._get_backup_path()
                if os.path.isfile(backup_path):
                    try:
                        with open(backup_path, "r", encoding="utf-8") as f:
                            self._data = json.load(f)
                        logger.info("Restored store from backup %s (%d entries)", 
                                   backup_path, len(self._data))
                        # Save restored data to main file
                        self._save()
                        return
                    except (json.JSONDecodeError, OSError) as backup_err:
                        logger.error("Backup also corrupted: %s", backup_err)
                
                self._data = {}
                logger.warning("Starting with empty store")
        else:
            logger.info("Store file %s does not exist, starting fresh", self.path)
            self._data = {}
    
    def _get_backup_path(self, timestamp: Optional[str] = None) -> str:
        """Get backup file path."""
        if timestamp:
            return f"{self.path}.backup.{timestamp}"
        return f"{self.path}.backup"
    
    def _create_backup(self) -> None:
        """Create a backup of the current store file."""
        if not self.backup_enabled or not os.path.isfile(self.path):
            return
        
        try:
            # Create timestamped backup
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            timestamped_backup = self._get_backup_path(timestamp)
            shutil.copy2(self.path, timestamped_backup)
            
            # Also maintain a "latest" backup
            latest_backup = self._get_backup_path()
            shutil.copy2(self.path, latest_backup)
            
            logger.debug("Created backup: %s", timestamped_backup)
            
            # Clean up old timestamped backups (keep last 5)
            self._cleanup_old_backups()
            
        except OSError as e:
            logger.warning("Could not create backup: %s", e)
    
    def _cleanup_old_backups(self, keep_count: int = 5) -> None:
        """Remove old backup files, keeping only the most recent ones."""
        try:
            backup_dir = os.path.dirname(self.path) or "."
            backup_base = os.path.basename(self.path) + ".backup."
            
            # Find all timestamped backups
            backups = []
            for filename in os.listdir(backup_dir):
                if filename.startswith(backup_base) and filename != os.path.basename(self._get_backup_path()):
                    full_path = os.path.join(backup_dir, filename)
                    backups.append((os.path.getmtime(full_path), full_path))
            
            # Sort by modification time (newest first) and remove old ones
            backups.sort(reverse=True)
            for _, backup_path in backups[keep_count:]:
                try:
                    os.remove(backup_path)
                    logger.debug("Removed old backup: %s", backup_path)
                except OSError as e:
                    logger.warning("Could not remove old backup %s: %s", backup_path, e)
                    
        except OSError as e:
            logger.warning("Error during backup cleanup: %s", e)

    def _save(self) -> None:
        """Save store to file with backup."""
        # Create backup before saving
        self._create_backup()
        
        try:
            # Write to temporary file first
            temp_path = f"{self.path}.tmp"
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self._data, f, indent=2)
            
            # Atomic rename
            if os.path.exists(self.path):
                os.replace(temp_path, self.path)
            else:
                os.rename(temp_path, self.path)
                
            logger.debug("Saved store to %s (%d entries)", self.path, len(self._data))
        except OSError as e:
            logger.error("Could not save store %s: %s", self.path, e)
            # Clean up temp file if it exists
            try:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
            except OSError:
                pass

    def was_messaged(self, member: dict, cooldown_seconds: int = 0) -> bool:
        """True if we already sent a message and cooldown has not elapsed."""
        key = _member_key(member)
        ts = self._data.get(key)
        if ts is None:
            return False
        if cooldown_seconds <= 0:
            return True
        return (time.time() - ts) < cooldown_seconds

    def get_messaged_at(self, member: dict) -> Optional[float]:
        """Return Unix timestamp when we messaged this member, or None if never."""
        key = _member_key(member)
        return self._data.get(key)

    def mark_messaged(self, member: dict) -> None:
        """Record that we sent the message to this member."""
        key = _member_key(member)
        self._data[key] = time.time()
        self._save()

    def mark_approved(self, member: dict) -> None:
        """Optionally remove from store after approval to keep file small (or keep for audit)."""
        # Keeping them in store so we don't re-message if they re-request; no-op for now
        pass
    
    def get_stats(self) -> dict:
        """Get statistics about the store."""
        now = time.time()
        total = len(self._data)
        
        # Count entries by age
        last_24h = sum(1 for ts in self._data.values() if (now - ts) < 86400)
        last_7d = sum(1 for ts in self._data.values() if (now - ts) < 86400 * 7)
        last_30d = sum(1 for ts in self._data.values() if (now - ts) < 86400 * 30)
        
        return {
            "total_members": total,
            "last_24h": last_24h,
            "last_7d": last_7d,
            "last_30d": last_30d,
        }
