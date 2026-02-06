"""
Filtering and approval logic for pending members.
"""
import logging
import re
from typing import Optional

logger = logging.getLogger(__name__)


class MemberFilter:
    """Filter for pending members based on allowlist/blocklist."""
    
    def __init__(
        self,
        allowlist: Optional[list[str]] = None,
        blocklist: Optional[list[str]] = None,
        allowlist_enabled: bool = False,
    ):
        """Initialize filter.
        
        Args:
            allowlist: List of allowed phone numbers or UUIDs (patterns supported)
            blocklist: List of blocked phone numbers or UUIDs (patterns supported)
            allowlist_enabled: If True, only allowlisted members are approved
        """
        self.allowlist = allowlist or []
        self.blocklist = blocklist or []
        self.allowlist_enabled = allowlist_enabled
        
        # Compile patterns
        self._allowlist_patterns = [self._compile_pattern(p) for p in self.allowlist]
        self._blocklist_patterns = [self._compile_pattern(p) for p in self.blocklist]
    
    def _compile_pattern(self, pattern: str) -> re.Pattern:
        """Compile a pattern string to regex.
        
        Supports wildcards:
        - * matches any characters
        - ? matches single character
        """
        # Escape special regex chars except * and ?
        escaped = re.escape(pattern)
        # Convert wildcards to regex
        regex_pattern = escaped.replace(r'\*', '.*').replace(r'\?', '.')
        return re.compile(f'^{regex_pattern}$')
    
    def _matches_any_pattern(self, value: str, patterns: list[re.Pattern]) -> bool:
        """Check if value matches any pattern."""
        return any(pattern.match(value) for pattern in patterns)
    
    def should_approve(self, member: dict) -> tuple[bool, str]:
        """Check if member should be approved.
        
        Args:
            member: Member dictionary with uuid and/or number
            
        Returns:
            Tuple of (should_approve, reason)
        """
        uuid_val = member.get("uuid", "")
        number = member.get("number", "")
        
        # Check blocklist first (highest priority)
        if uuid_val and self._matches_any_pattern(uuid_val, self._blocklist_patterns):
            return False, f"UUID {uuid_val} is blocklisted"
        
        if number and self._matches_any_pattern(number, self._blocklist_patterns):
            return False, f"Number {number} is blocklisted"
        
        # If allowlist is enabled, check allowlist
        if self.allowlist_enabled:
            uuid_allowed = uuid_val and self._matches_any_pattern(uuid_val, self._allowlist_patterns)
            number_allowed = number and self._matches_any_pattern(number, self._allowlist_patterns)
            
            if uuid_allowed or number_allowed:
                return True, "Member is allowlisted"
            else:
                return False, "Member is not on allowlist"
        
        # Default: approve
        return True, "No restrictions"
    
    def get_stats(self) -> dict:
        """Get filter statistics."""
        return {
            "allowlist_enabled": self.allowlist_enabled,
            "allowlist_count": len(self.allowlist),
            "blocklist_count": len(self.blocklist),
        }


class RateLimiter:
    """Rate limiter for join requests."""
    
    def __init__(self, max_requests: int = 10, window_seconds: int = 3600):
        """Initialize rate limiter.
        
        Args:
            max_requests: Maximum requests allowed in window
            window_seconds: Time window in seconds
        """
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._requests: dict[str, list[float]] = {}
    
    def _get_key(self, member: dict) -> str:
        """Get unique key for member."""
        return member.get("uuid") or member.get("number") or ""
    
    def check_rate_limit(self, member: dict, current_time: float) -> tuple[bool, str]:
        """Check if member exceeds rate limit.
        
        Args:
            member: Member dictionary
            current_time: Current timestamp
            
        Returns:
            Tuple of (allowed, reason)
        """
        key = self._get_key(member)
        if not key:
            return True, "No identifier"
        
        # Get request history
        if key not in self._requests:
            self._requests[key] = []
        
        # Clean old requests
        cutoff = current_time - self.window_seconds
        self._requests[key] = [ts for ts in self._requests[key] if ts > cutoff]
        
        # Check limit
        if len(self._requests[key]) >= self.max_requests:
            return False, f"Rate limit exceeded ({self.max_requests} requests in {self.window_seconds}s)"
        
        # Record request
        self._requests[key].append(current_time)
        
        return True, "Within rate limit"
