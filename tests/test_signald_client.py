"""Unit tests for signald_client module."""
import json
import socket
import unittest
from unittest.mock import MagicMock, patch, mock_open

from src.signald_client import SignaldClient, SignaldError, SignaldConnectionError


class TestSignaldClient(unittest.TestCase):
    """Test SignaldClient class."""
    
    def setUp(self):
        """Set up test client."""
        self.client = SignaldClient(
            socket_path="/tmp/test.sock",
            max_retries=2,
            retry_delay=0.1,
            timeout=5,
        )
    
    def test_initialization(self):
        """Should initialize with correct parameters."""
        self.assertEqual(self.client.socket_path, "/tmp/test.sock")
        self.assertEqual(self.client.max_retries, 2)
        self.assertEqual(self.client.retry_delay, 0.1)
        self.assertEqual(self.client.timeout, 5)
    
    def test_default_socket_path(self):
        """Should use default socket path if not specified."""
        with patch.dict('os.environ', {'XDG_RUNTIME_DIR': '/run/user/1000'}):
            client = SignaldClient()
            self.assertEqual(client.socket_path, "/run/user/1000/signald/signald.sock")
    
    @patch('socket.socket')
    def test_connect_unix_socket(self, mock_socket_class):
        """Should connect via Unix socket."""
        mock_sock = MagicMock()
        mock_socket_class.return_value = mock_sock
        
        result = self.client._connect()
        
        mock_socket_class.assert_called_once_with(socket.AF_UNIX, socket.SOCK_STREAM)
        mock_sock.settimeout.assert_called_once_with(5)
        mock_sock.connect.assert_called_once_with("/tmp/test.sock")
        self.assertEqual(result, mock_sock)
    
    @patch('socket.socket')
    def test_connect_tcp(self, mock_socket_class):
        """Should connect via TCP."""
        client = SignaldClient(socket_path="localhost:1234")
        mock_sock = MagicMock()
        mock_socket_class.return_value = mock_sock
        
        result = client._connect()
        
        mock_socket_class.assert_called_once_with(socket.AF_INET, socket.SOCK_STREAM)
        mock_sock.connect.assert_called_once_with(("localhost", 1234))
    
    @patch('socket.socket')
    def test_connect_failure(self, mock_socket_class):
        """Should raise SignaldConnectionError on connection failure."""
        mock_socket_class.return_value.connect.side_effect = socket.error("Connection refused")
        
        with self.assertRaises(SignaldConnectionError) as ctx:
            self.client._connect()
        
        self.assertIn("Connection refused", str(ctx.exception))
    
    @patch.object(SignaldClient, '_connect')
    def test_request_success(self, mock_connect):
        """Should send request and parse response."""
        # Mock socket
        mock_sock = MagicMock()
        response = {"id": "test-id", "data": {"result": "success"}}
        mock_sock.recv.return_value = (json.dumps(response) + "\n").encode()
        mock_sock.__enter__ = MagicMock(return_value=mock_sock)
        mock_sock.__exit__ = MagicMock(return_value=False)
        mock_connect.return_value = mock_sock
        
        # Make request
        result = self.client._request({"type": "test"})
        
        # Verify
        self.assertEqual(result["data"]["result"], "success")
        mock_sock.sendall.assert_called_once()
    
    @patch.object(SignaldClient, '_connect')
    def test_request_error_response(self, mock_connect):
        """Should raise SignaldError on error response."""
        mock_sock = MagicMock()
        response = {"id": "test-id", "error": {"message": "Test error"}}
        mock_sock.recv.return_value = (json.dumps(response) + "\n").encode()
        mock_sock.__enter__ = MagicMock(return_value=mock_sock)
        mock_sock.__exit__ = MagicMock(return_value=False)
        mock_connect.return_value = mock_sock
        
        with self.assertRaises(SignaldError) as ctx:
            self.client._request({"type": "test"})
        
        self.assertIn("Test error", str(ctx.exception))
    
    @patch.object(SignaldClient, '_connect')
    def test_request_retry_on_connection_error(self, mock_connect):
        """Should retry on connection errors."""
        # First two attempts fail, third succeeds
        mock_sock = MagicMock()
        response = {"id": "test-id", "data": {"result": "success"}}
        mock_sock.recv.return_value = (json.dumps(response) + "\n").encode()
        mock_sock.__enter__ = MagicMock(return_value=mock_sock)
        mock_sock.__exit__ = MagicMock(return_value=False)
        
        mock_connect.side_effect = [
            socket.error("Connection failed"),
            socket.error("Connection failed"),
            mock_sock,
        ]
        
        # Should succeed after retries
        result = self.client._request({"type": "test"})
        self.assertEqual(result["data"]["result"], "success")
        self.assertEqual(mock_connect.call_count, 3)
    
    @patch.object(SignaldClient, '_connect')
    def test_request_max_retries_exceeded(self, mock_connect):
        """Should raise error after max retries."""
        mock_connect.side_effect = socket.error("Connection failed")
        
        with self.assertRaises(SignaldConnectionError):
            self.client._request({"type": "test"})
        
        # Should try initial + 2 retries = 3 total
        self.assertEqual(mock_connect.call_count, 3)
    
    @patch.object(SignaldClient, '_request')
    def test_list_pending_members(self, mock_request):
        """Should extract pending members from group response."""
        mock_request.return_value = {
            "data": {
                "pendingMembers": [
                    {"uuid": "uuid1", "number": "+1111111111"},
                    {"uuid": "uuid2", "number": "+2222222222"},
                ]
            }
        }
        
        result = self.client.list_pending_members("+1234567890", "group-id")
        
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["uuid"], "uuid1")
        self.assertEqual(result[1]["uuid"], "uuid2")
    
    @patch.object(SignaldClient, '_request')
    def test_send_message(self, mock_request):
        """Should send message with correct parameters."""
        mock_request.return_value = {"data": {"timestamp": 123456}}
        
        result = self.client.send_message(
            "+1234567890",
            {"uuid": "recipient-uuid"},
            "Test message"
        )
        
        # Verify request was made
        mock_request.assert_called_once()
        call_args = mock_request.call_args[0][0]
        self.assertEqual(call_args["type"], "send")
        self.assertEqual(call_args["account"], "+1234567890")
        self.assertEqual(call_args["messageBody"], "Test message")
    
    @patch.object(SignaldClient, '_request')
    def test_approve_membership(self, mock_request):
        """Should approve membership with correct parameters."""
        mock_request.return_value = {"data": {"success": True}}
        
        result = self.client.approve_membership(
            "+1234567890",
            "group-id",
            [{"uuid": "member-uuid"}]
        )
        
        # Verify request was made
        mock_request.assert_called_once()
        call_args = mock_request.call_args[0][0]
        self.assertEqual(call_args["type"], "approve_membership")
        self.assertEqual(call_args["account"], "+1234567890")
        self.assertEqual(call_args["groupID"], "group-id")


class TestSignaldError(unittest.TestCase):
    """Test SignaldError exception."""
    
    def test_error_with_message(self):
        """Should create error with message."""
        error = SignaldError("Test error")
        self.assertEqual(str(error), "Test error")
        self.assertEqual(error.message, "Test error")
    
    def test_error_with_data(self):
        """Should include data in string representation."""
        error = SignaldError("Test error", {"code": 123})
        self.assertIn("Test error", str(error))
        self.assertIn("123", str(error))


class TestSignaldConnectionError(unittest.TestCase):
    """Test SignaldConnectionError exception."""
    
    def test_connection_error(self):
        """Should create connection error."""
        error = SignaldConnectionError("Connection failed")
        self.assertEqual(str(error), "Connection failed")
        self.assertEqual(error.data["type"], "connection_error")


if __name__ == "__main__":
    unittest.main()
