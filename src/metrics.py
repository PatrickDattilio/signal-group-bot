"""
Simple metrics tracking for bot operations.
"""
import json
import logging
import os
import time
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)


class Metrics:
    """Track bot operation metrics."""
    
    def __init__(self, path: str = "metrics.json", enabled: bool = True):
        self.path = path
        self.enabled = enabled
        self._data = {
            "start_time": time.time(),
            "messages_sent": 0,
            "messages_failed": 0,
            "approvals_succeeded": 0,
            "approvals_failed": 0,
            "polls_completed": 0,
            "polls_failed": 0,
            "errors": {},
        }
        self._load()
    
    def _load(self) -> None:
        """Load existing metrics if available."""
        if not self.enabled:
            return
            
        if os.path.isfile(self.path):
            try:
                with open(self.path, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                    # Merge with defaults
                    self._data.update(loaded)
                logger.debug("Loaded metrics from %s", self.path)
            except (json.JSONDecodeError, OSError) as e:
                logger.warning("Could not load metrics %s: %s", self.path, e)
    
    def _save(self) -> None:
        """Save metrics to file."""
        if not self.enabled:
            return
            
        try:
            self._data["last_updated"] = time.time()
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self._data, f, indent=2)
        except OSError as e:
            logger.warning("Could not save metrics: %s", e)
    
    def record_message_sent(self) -> None:
        """Record a successful message send."""
        self._data["messages_sent"] += 1
        self._save()
    
    def record_message_failed(self, error_type: str = "unknown") -> None:
        """Record a failed message send."""
        self._data["messages_failed"] += 1
        self._data["errors"][error_type] = self._data["errors"].get(error_type, 0) + 1
        self._save()
    
    def record_approval_succeeded(self) -> None:
        """Record a successful approval."""
        self._data["approvals_succeeded"] += 1
        self._save()
    
    def record_approval_failed(self, error_type: str = "unknown") -> None:
        """Record a failed approval."""
        self._data["approvals_failed"] += 1
        self._data["errors"][error_type] = self._data["errors"].get(error_type, 0) + 1
        self._save()
    
    def record_poll_completed(self) -> None:
        """Record a successful poll."""
        self._data["polls_completed"] += 1
        self._save()
    
    def record_poll_failed(self, error_type: str = "unknown") -> None:
        """Record a failed poll."""
        self._data["polls_failed"] += 1
        self._data["errors"][error_type] = self._data["errors"].get(error_type, 0) + 1
        self._save()
    
    def get_stats(self) -> dict:
        """Get current metrics."""
        uptime = time.time() - self._data["start_time"]
        return {
            "uptime_seconds": uptime,
            "uptime_hours": uptime / 3600,
            "messages_sent": self._data["messages_sent"],
            "messages_failed": self._data["messages_failed"],
            "message_success_rate": self._calculate_rate(
                self._data["messages_sent"],
                self._data["messages_sent"] + self._data["messages_failed"]
            ),
            "approvals_succeeded": self._data["approvals_succeeded"],
            "approvals_failed": self._data["approvals_failed"],
            "approval_success_rate": self._calculate_rate(
                self._data["approvals_succeeded"],
                self._data["approvals_succeeded"] + self._data["approvals_failed"]
            ),
            "polls_completed": self._data["polls_completed"],
            "polls_failed": self._data["polls_failed"],
            "poll_success_rate": self._calculate_rate(
                self._data["polls_completed"],
                self._data["polls_completed"] + self._data["polls_failed"]
            ),
            "errors": self._data["errors"],
        }
    
    def _calculate_rate(self, success: int, total: int) -> Optional[float]:
        """Calculate success rate as percentage."""
        if total == 0:
            return None
        return (success / total) * 100
    
    def reset(self) -> None:
        """Reset all metrics."""
        self._data = {
            "start_time": time.time(),
            "messages_sent": 0,
            "messages_failed": 0,
            "approvals_succeeded": 0,
            "approvals_failed": 0,
            "polls_completed": 0,
            "polls_failed": 0,
            "errors": {},
        }
        self._save()
