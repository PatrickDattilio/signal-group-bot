"""
Message template engine with variable substitution.
"""
import logging
import re
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)


class MessageTemplate:
    """Template engine for message formatting with variables."""
    
    # Available template variables
    VARIABLES = {
        "timestamp": "Current timestamp",
        "date": "Current date (YYYY-MM-DD)",
        "time": "Current time (HH:MM:SS)",
        "datetime": "Current date and time",
        "member_uuid": "Member's UUID",
        "member_number": "Member's phone number",
    }
    
    def __init__(self, template: str):
        """Initialize template.
        
        Args:
            template: Message template with variables in {{variable}} format
        """
        self.template = template
        self._validate_template()
    
    def _validate_template(self) -> None:
        """Validate template syntax and variables."""
        # Find all variables in template
        variables = re.findall(r'\{\{(\w+)\}\}', self.template)
        
        # Check for unknown variables
        unknown = [v for v in variables if v not in self.VARIABLES]
        if unknown:
            logger.warning("Template contains unknown variables: %s", unknown)
    
    def render(self, member: dict, extra_vars: Optional[dict] = None) -> str:
        """Render template with member data and current context.
        
        Args:
            member: Member dictionary with uuid and/or number
            extra_vars: Additional variables to substitute
            
        Returns:
            Rendered message string
        """
        now = datetime.now()
        
        # Build variable context
        context = {
            "timestamp": str(int(now.timestamp())),
            "date": now.strftime("%Y-%m-%d"),
            "time": now.strftime("%H:%M:%S"),
            "datetime": now.strftime("%Y-%m-%d %H:%M:%S"),
            "member_uuid": member.get("uuid", ""),
            "member_number": member.get("number", ""),
        }
        
        # Add extra variables
        if extra_vars:
            context.update(extra_vars)
        
        # Substitute variables
        result = self.template
        for var, value in context.items():
            pattern = r'\{\{' + var + r'\}\}'
            result = re.sub(pattern, str(value), result)
        
        return result
    
    @classmethod
    def get_available_variables(cls) -> dict:
        """Get dictionary of available template variables and their descriptions."""
        return cls.VARIABLES.copy()


def format_message(template_str: str, member: dict, **kwargs) -> str:
    """Convenience function to format a message.
    
    Args:
        template_str: Template string
        member: Member dictionary
        **kwargs: Additional template variables
        
    Returns:
        Formatted message
    """
    template = MessageTemplate(template_str)
    return template.render(member, extra_vars=kwargs)
