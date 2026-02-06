"""
Signald daemon client: connect via socket, get_group (pending members), send DM, approve_membership.
Protocol: newline-terminated JSON over Unix socket or TCP.
On Windows, only TCP (host:port) is supported; Unix sockets are not available.
"""
import json
import logging
import os
import socket
import sys
import time
import uuid
from typing import Optional

logger = logging.getLogger(__name__)


class SignaldClient:
    """Client for signald socket API (newline-terminated JSON)."""

    def __init__(
        self,
        socket_path: Optional[str] = None,
        max_retries: int = 3,
        retry_delay: float = 1.0,
        timeout: int = 30,
    ):
        self.socket_path = socket_path or os.environ.get(
            "SIGNALD_SOCKET",
            os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/var/run"), "signald", "signald.sock"),
        )
        self._request_id = 0
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.timeout = timeout

    def _next_id(self) -> str:
        self._request_id += 1
        return str(uuid.uuid4())

    def _connect(self) -> socket.socket:
        """Connect to signald via Unix socket or TCP."""
        # On Windows, Unix sockets (AF_UNIX) are not supported; require TCP (host:port)
        if sys.platform == "win32":
            is_tcp = ":" in self.socket_path and not self.socket_path.startswith("/")
            if not is_tcp:
                raise SignaldConnectionError(
                    "Unix sockets are not supported on Windows. "
                    "Set signald.socket_path in config.yaml to a TCP address (e.g. localhost:7583), "
                    "or set SIGNALD_SOCKET=localhost:7583. "
                    "Run signald in Docker/WSL and expose a TCP port, or use a TCP bridge to the socket."
                )
        try:
            if ":" in self.socket_path and not self.socket_path.startswith("/"):
                host, port = self.socket_path.rsplit(":", 1)
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(self.timeout)
                sock.connect((host.strip(), int(port)))
                logger.debug("Connected to signald via TCP: %s:%s", host, port)
            else:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.settimeout(self.timeout)
                sock.connect(self.socket_path)
                logger.debug("Connected to signald via Unix socket: %s", self.socket_path)
            return sock
        except (socket.error, OSError, ValueError) as e:
            raise SignaldConnectionError(f"Failed to connect to signald at {self.socket_path}: {e}") from e

    def _request(self, body: dict, retries: Optional[int] = None) -> dict:
        """Send request to signald with retry logic."""
        if retries is None:
            retries = self.max_retries
        
        req_id = self._next_id()
        body["id"] = req_id
        msg = (json.dumps(body) + "\n").encode("utf-8")
        
        last_error = None
        for attempt in range(retries + 1):
            try:
                with self._connect() as sock:
                    sock.sendall(msg)
                    # signald may send multiple JSON lines (e.g. "version" on connect, then our response).
                    # Read lines until we get a response that matches our request id.
                    buf = b""
                    out = None
                    while True:
                        while b"\n" not in buf:
                            chunk = sock.recv(4096)
                            if not chunk:
                                raise SignaldConnectionError("Connection closed by signald before response received")
                            buf += chunk
                        line, buf = buf.split(b"\n", 1)
                        line_str = line.decode("utf-8").strip()
                        if not line_str:
                            continue
                        try:
                            out = json.loads(line_str)
                        except json.JSONDecodeError:
                            continue
                        # Skip messages that don't match our request (e.g. unsolicited "version")
                        if out.get("id") == req_id:
                            break
                        logger.debug("Skipping signald message type=%s (id=%s)", out.get("type"), out.get("id"))
                
                if out is None:
                    raise SignaldError("No matching response from signald")
                
                if "error" in out:
                    error_data = out["error"]
                    error_msg = error_data.get("message") or error_data.get("data", str(error_data))
                    raise SignaldError(error_msg, error_data)
                
                return out
                
            except (socket.error, socket.timeout, OSError) as e:
                last_error = SignaldConnectionError(f"Connection error: {e}")
                if attempt < retries:
                    delay = self.retry_delay * (2 ** attempt)  # Exponential backoff
                    logger.warning(
                        "Request failed (attempt %d/%d): %s. Retrying in %.1fs...",
                        attempt + 1, retries + 1, e, delay
                    )
                    time.sleep(delay)
                else:
                    logger.error("Request failed after %d attempts", retries + 1)
                    
            except (json.JSONDecodeError, ValueError) as e:
                last_error = SignaldError(f"Invalid response from signald: {e}")
                logger.error("Failed to parse signald response: %s", e)
                break  # Don't retry on parse errors
                
            except SignaldError:
                raise  # Don't retry on application-level errors
        
        raise last_error or SignaldError("Request failed")

    def get_group(self, account: str, group_id: str) -> dict:
        """Fetch group state including pending members. Returns JsonGroupV2Info."""
        resp = self._request(
            {
                "type": "get_group",
                "version": "v1",
                "account": account,
                "groupID": group_id,
            }
        )
        return resp

    def list_pending_members(self, account: str, group_id: str) -> list[dict]:
        """Return list of members who requested to join via the group link (requestingMembers).
        Note: pendingMembers = invited users; requestingMembers = users who requested via link.
        """
        resp = self.get_group(account, group_id)
        # Protocol: JsonGroupV2Info is often the direct response (top-level). Some versions
        # wrap it in "data"; some put version info in "data" and group at top level.
        # Prefer top-level if it has group fields; else use resp["data"].
        data = resp
        if isinstance(resp.get("data"), dict):
            inner = resp["data"]
            # If "data" has group fields, use it; else "data" might be version info, keep resp
            if any(k in inner for k in ("requestingMembers", "pendingMembers", "members")):
                data = inner
        # Users who requested to join via the group link (approval queue)
        requesting = data.get("requestingMembers") or []
        result = []
        for addr in requesting:
            if isinstance(addr, dict):
                entry = dict(addr)
            else:
                entry = {}
            result.append(entry)
        return result

    def send_message(self, account: str, recipient_address: dict, message_body: str) -> dict:
        """Send a direct message. recipient_address: { \"number\": \"+1...\" } or { \"uuid\": \"...\" }."""
        return self._request(
            {
                "type": "send",
                "version": "v1",
                "account": account,
                "recipientAddress": recipient_address,
                "messageBody": message_body,
            }
        )

    def approve_membership(
        self, account: str, group_id: str, members: list[dict]
    ) -> dict:
        """Approve pending join requests. members: list of JsonAddress, e.g. [{\"number\": \"+1...\"}] or [{\"uuid\": \"...\"}]."""
        return self._request(
            {
                "type": "approve_membership",
                "version": "v1",
                "account": account,
                "groupID": group_id,
                "members": members,
            }
        )


class SignaldError(Exception):
    """Error returned by signald."""
    def __init__(self, message: str, data: Optional[dict] = None):
        self.message = message
        self.data = data or {}
        super().__init__(message)
    
    def __str__(self) -> str:
        if self.data:
            return f"{self.message} (details: {self.data})"
        return self.message


class SignaldConnectionError(SignaldError):
    """Connection error to signald daemon."""
    def __init__(self, message: str):
        super().__init__(message, {"type": "connection_error"})
