"""Unit tests for store module."""
import json
import os
import tempfile
import time
import unittest

from src.store import Store, _member_key


class TestMemberKey(unittest.TestCase):
    """Test _member_key function."""
    
    def test_uuid_preferred(self):
        """UUID should be preferred over number."""
        member = {"uuid": "test-uuid", "number": "+1234567890"}
        self.assertEqual(_member_key(member), "uuid:test-uuid")
    
    def test_number_fallback(self):
        """Number should be used if no UUID."""
        member = {"number": "+1234567890"}
        self.assertEqual(_member_key(member), "number:+1234567890")
    
    def test_empty_member(self):
        """Empty member should return JSON representation."""
        member = {}
        self.assertEqual(_member_key(member), "{}")


class TestStore(unittest.TestCase):
    """Test Store class."""
    
    def setUp(self):
        """Create temporary file for testing."""
        self.temp_fd, self.temp_path = tempfile.mkstemp(suffix=".json")
        os.close(self.temp_fd)
    
    def tearDown(self):
        """Clean up temporary files."""
        # Remove main file
        if os.path.exists(self.temp_path):
            os.remove(self.temp_path)
        
        # Remove backup files
        for suffix in [".backup", ".tmp"]:
            backup_path = f"{self.temp_path}{suffix}"
            if os.path.exists(backup_path):
                os.remove(backup_path)
        
        # Remove timestamped backups
        temp_dir = os.path.dirname(self.temp_path)
        temp_base = os.path.basename(self.temp_path)
        for filename in os.listdir(temp_dir):
            if filename.startswith(temp_base + ".backup."):
                os.remove(os.path.join(temp_dir, filename))
    
    def test_new_store_empty(self):
        """New store should be empty."""
        store = Store(path=self.temp_path, backup_enabled=False)
        self.assertEqual(len(store._data), 0)
    
    def test_mark_and_check_messaged(self):
        """Should track messaged members."""
        store = Store(path=self.temp_path, backup_enabled=False)
        member = {"uuid": "test-uuid"}
        
        # Not messaged initially
        self.assertFalse(store.was_messaged(member))
        
        # Mark as messaged
        store.mark_messaged(member)
        
        # Should be messaged now
        self.assertTrue(store.was_messaged(member))
    
    def test_cooldown(self):
        """Should respect cooldown period."""
        store = Store(path=self.temp_path, backup_enabled=False)
        member = {"uuid": "test-uuid"}
        
        # Mark as messaged
        store.mark_messaged(member)
        
        # Should be in cooldown
        self.assertTrue(store.was_messaged(member, cooldown_seconds=10))
        
        # Manually set timestamp to past
        key = _member_key(member)
        store._data[key] = time.time() - 20
        store._save()
        
        # Should be outside cooldown
        self.assertFalse(store.was_messaged(member, cooldown_seconds=10))
    
    def test_persistence(self):
        """Data should persist across store instances."""
        member = {"uuid": "test-uuid"}
        
        # Create store and mark member
        store1 = Store(path=self.temp_path, backup_enabled=False)
        store1.mark_messaged(member)
        
        # Create new store instance
        store2 = Store(path=self.temp_path, backup_enabled=False)
        
        # Should still be marked
        self.assertTrue(store2.was_messaged(member))
    
    def test_backup_creation(self):
        """Should create backup files."""
        store = Store(path=self.temp_path, backup_enabled=True)
        member = {"uuid": "test-uuid"}
        
        # Mark member (triggers save and backup)
        store.mark_messaged(member)
        
        # Backup should exist
        backup_path = f"{self.temp_path}.backup"
        self.assertTrue(os.path.exists(backup_path))
    
    def test_stats(self):
        """Should return correct statistics."""
        store = Store(path=self.temp_path, backup_enabled=False)
        
        # Add some members
        for i in range(5):
            member = {"uuid": f"uuid-{i}"}
            store.mark_messaged(member)
        
        stats = store.get_stats()
        self.assertEqual(stats["total_members"], 5)
        self.assertEqual(stats["last_24h"], 5)
    
    def test_corrupted_file_recovery(self):
        """Should recover from corrupted main file using backup."""
        store = Store(path=self.temp_path, backup_enabled=True)
        member = {"uuid": "test-uuid"}
        store.mark_messaged(member)
        
        # Corrupt main file
        with open(self.temp_path, "w") as f:
            f.write("invalid json{{{")
        
        # Create new store - should recover from backup
        store2 = Store(path=self.temp_path, backup_enabled=True)
        self.assertTrue(store2.was_messaged(member))


if __name__ == "__main__":
    unittest.main()
