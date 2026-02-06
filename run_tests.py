#!/usr/bin/env python3
"""
Test runner for SignalBot.
Usage: python run_tests.py
"""
import sys
import unittest

if __name__ == "__main__":
    # Discover and run all tests
    loader = unittest.TestLoader()
    start_dir = "tests"
    suite = loader.discover(start_dir, pattern="test_*.py")
    
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    
    # Exit with error code if tests failed
    sys.exit(not result.wasSuccessful())
