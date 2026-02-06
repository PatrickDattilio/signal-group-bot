"""Unit tests for configuration validation."""
import unittest
import sys
import os

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import validate_config


class TestConfigValidation(unittest.TestCase):
    """Test configuration validation."""
    
    def test_valid_config(self):
        """Valid config should pass validation."""
        config = {
            "account": "+12025551234",
            "group_id": "base64encodedgroupid",
            "message": "Welcome message",
            "approval_mode": "manual",
            "poll_interval_seconds": 120,
        }
        
        errors = validate_config(config)
        self.assertEqual(len(errors), 0)
    
    def test_missing_required_fields(self):
        """Should report missing required fields."""
        config = {}
        
        errors = validate_config(config)
        
        self.assertGreater(len(errors), 0)
        self.assertTrue(any("account" in err for err in errors))
        self.assertTrue(any("group_id" in err for err in errors))
        self.assertTrue(any("message" in err for err in errors))
    
    def test_empty_required_fields(self):
        """Should report empty required fields."""
        config = {
            "account": "",
            "group_id": "",
            "message": "  ",
        }
        
        errors = validate_config(config)
        
        self.assertGreater(len(errors), 0)
        self.assertTrue(any("account" in err and "empty" in err for err in errors))
    
    def test_invalid_account_format(self):
        """Should validate account E.164 format."""
        config = {
            "account": "1234567890",  # Missing +
            "group_id": "group-id",
            "message": "message",
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("E.164" in err for err in errors))
    
    def test_invalid_account_length(self):
        """Should validate account length."""
        config = {
            "account": "+123",  # Too short
            "group_id": "group-id",
            "message": "message",
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("length" in err for err in errors))
    
    def test_invalid_approval_mode(self):
        """Should validate approval_mode values."""
        config = {
            "account": "+12025551234",
            "group_id": "group-id",
            "message": "message",
            "approval_mode": "invalid",
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("approval_mode" in err for err in errors))
    
    def test_numeric_field_validation(self):
        """Should validate numeric fields."""
        config = {
            "account": "+12025551234",
            "group_id": "group-id",
            "message": "message",
            "poll_interval_seconds": "not a number",
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("poll_interval_seconds" in err and "number" in err for err in errors))
    
    def test_numeric_field_range(self):
        """Should validate numeric field ranges."""
        config = {
            "account": "+12025551234",
            "group_id": "group-id",
            "message": "message",
            "poll_interval_seconds": 5,  # Too low (min is 10)
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("poll_interval_seconds" in err and "range" in err for err in errors))
    
    def test_signald_config_validation(self):
        """Should validate signald configuration."""
        config = {
            "account": "+12025551234",
            "group_id": "group-id",
            "message": "message",
            "signald": {
                "max_retries": 20,  # Too high (max is 10)
                "timeout": 500,  # Too high (max is 300)
            }
        }
        
        errors = validate_config(config)
        
        self.assertTrue(any("max_retries" in err for err in errors))
        self.assertTrue(any("timeout" in err for err in errors))


if __name__ == "__main__":
    unittest.main()
