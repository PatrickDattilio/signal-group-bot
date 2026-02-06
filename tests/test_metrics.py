"""Unit tests for metrics module."""
import os
import tempfile
import unittest

from src.metrics import Metrics


class TestMetrics(unittest.TestCase):
    """Test Metrics class."""
    
    def setUp(self):
        """Create temporary file for testing."""
        self.temp_fd, self.temp_path = tempfile.mkstemp(suffix=".json")
        os.close(self.temp_fd)
    
    def tearDown(self):
        """Clean up temporary files."""
        if os.path.exists(self.temp_path):
            os.remove(self.temp_path)
    
    def test_new_metrics_initialized(self):
        """New metrics should be initialized to zero."""
        metrics = Metrics(path=self.temp_path)
        stats = metrics.get_stats()
        
        self.assertEqual(stats["messages_sent"], 0)
        self.assertEqual(stats["messages_failed"], 0)
        self.assertEqual(stats["approvals_succeeded"], 0)
        self.assertEqual(stats["approvals_failed"], 0)
        self.assertEqual(stats["polls_completed"], 0)
        self.assertEqual(stats["polls_failed"], 0)
    
    def test_record_message_sent(self):
        """Should increment messages_sent counter."""
        metrics = Metrics(path=self.temp_path)
        
        metrics.record_message_sent()
        metrics.record_message_sent()
        
        stats = metrics.get_stats()
        self.assertEqual(stats["messages_sent"], 2)
    
    def test_record_message_failed(self):
        """Should increment messages_failed counter and track error type."""
        metrics = Metrics(path=self.temp_path)
        
        metrics.record_message_failed("timeout")
        metrics.record_message_failed("timeout")
        metrics.record_message_failed("connection_error")
        
        stats = metrics.get_stats()
        self.assertEqual(stats["messages_failed"], 3)
        self.assertEqual(stats["errors"]["timeout"], 2)
        self.assertEqual(stats["errors"]["connection_error"], 1)
    
    def test_success_rate_calculation(self):
        """Should calculate success rates correctly."""
        metrics = Metrics(path=self.temp_path)
        
        # 3 success, 1 failure = 75%
        metrics.record_message_sent()
        metrics.record_message_sent()
        metrics.record_message_sent()
        metrics.record_message_failed()
        
        stats = metrics.get_stats()
        self.assertEqual(stats["message_success_rate"], 75.0)
    
    def test_success_rate_no_data(self):
        """Success rate should be None when no data."""
        metrics = Metrics(path=self.temp_path)
        stats = metrics.get_stats()
        
        self.assertIsNone(stats["message_success_rate"])
        self.assertIsNone(stats["approval_success_rate"])
        self.assertIsNone(stats["poll_success_rate"])
    
    def test_persistence(self):
        """Metrics should persist across instances."""
        # Create metrics and record some data
        metrics1 = Metrics(path=self.temp_path)
        metrics1.record_message_sent()
        metrics1.record_approval_succeeded()
        
        # Create new instance
        metrics2 = Metrics(path=self.temp_path)
        stats = metrics2.get_stats()
        
        self.assertEqual(stats["messages_sent"], 1)
        self.assertEqual(stats["approvals_succeeded"], 1)
    
    def test_reset(self):
        """Reset should clear all metrics."""
        metrics = Metrics(path=self.temp_path)
        
        # Record some data
        metrics.record_message_sent()
        metrics.record_approval_succeeded()
        metrics.record_poll_completed()
        
        # Reset
        metrics.reset()
        
        stats = metrics.get_stats()
        self.assertEqual(stats["messages_sent"], 0)
        self.assertEqual(stats["approvals_succeeded"], 0)
        self.assertEqual(stats["polls_completed"], 0)
    
    def test_disabled_metrics(self):
        """Disabled metrics should not save to file."""
        metrics = Metrics(path=self.temp_path, enabled=False)
        
        metrics.record_message_sent()
        
        # File should not be created
        self.assertFalse(os.path.exists(self.temp_path))
    
    def test_uptime_tracking(self):
        """Should track uptime."""
        import time
        
        metrics = Metrics(path=self.temp_path)
        time.sleep(0.1)  # Wait a bit
        
        stats = metrics.get_stats()
        self.assertGreater(stats["uptime_seconds"], 0)
        self.assertGreater(stats["uptime_hours"], 0)


if __name__ == "__main__":
    unittest.main()
