"""
signal-cli daemon client: connect via JSON-RPC over socket/TCP.
Provides same interface as the former signald client: get_group, list_pending_members,
send_message, approve_membership.
Approve and add-to-group use JSON-RPC updateGroup on the daemon; optional CLI fallback
when try_cli_fallback_for_approve and cli_config_path are set.
"""
import json
import logging
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
from typing import Any, List, Optional, Tuple

logger = logging.getLogger(__name__)


class SignalCliError(Exception):
    """Error from signal-cli or client."""
    def __init__(self, message: str, data: Optional[dict] = None):
        self.message = message
        self.data = data or {}
        super().__init__(message)

    def __str__(self) -> str:
        if self.data:
            return f"{self.message} (details: {self.data})"
        return self.message


class SignalCliConnectionError(SignalCliError):
    """Connection error to signal-cli daemon."""

    def __init__(self, message: str):
        super().__init__(message, {"type": "connection_error"})


class SignalCliClient:
    """Client for signal-cli daemon (JSON-RPC 2.0 over socket/TCP)."""

    def __init__(
        self,
        socket_path: Optional[str] = None,
        max_retries: int = 3,
        retry_delay: float = 1.0,
        timeout: int = 30,
        cli_path: Optional[str] = None,
        try_cli_fallback_for_approve: bool = False,
        cli_config_path: Optional[str] = None,
    ):
        self.socket_path = socket_path or os.environ.get(
            "SIGNAL_CLI_SOCKET",
            os.environ.get("SIGNALD_SOCKET", "localhost:7583"),
        )
        self._request_id = 0
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.timeout = timeout
        self.cli_path = (cli_path or os.environ.get("SIGNAL_CLI_PATH") or "").strip() or None
        self.try_cli_fallback_for_approve = try_cli_fallback_for_approve
        self.cli_config_path = (cli_config_path or os.environ.get("SIGNAL_CLI_CONFIG_PATH") or "").strip() or None

    def _next_id(self) -> str:
        self._request_id += 1
        return str(uuid.uuid4())

    def _connect(self) -> socket.socket:
        """Connect to signal-cli daemon via Unix socket or TCP."""
        if sys.platform == "win32":
            is_tcp = ":" in self.socket_path and not self.socket_path.startswith("/")
            if not is_tcp:
                raise SignalCliConnectionError(
                    "Unix sockets are not supported on Windows. "
                    "Set signal_cli.socket_path in config.yaml to a TCP address (e.g. localhost:7583), "
                    "or set SIGNAL_CLI_SOCKET=localhost:7583. "
                    "Run: signal-cli -u +NUMBER daemon --socket (and expose TCP if needed)."
                )
        try:
            if ":" in self.socket_path and not self.socket_path.startswith("/"):
                host, port = self.socket_path.rsplit(":", 1)
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(self.timeout)
                sock.connect((host.strip(), int(port)))
                logger.debug("Connected to signal-cli via TCP: %s:%s", host, port)
            else:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.settimeout(self.timeout)
                sock.connect(self.socket_path)
                logger.debug("Connected to signal-cli via Unix socket: %s", self.socket_path)
            return sock
        except (socket.error, OSError, ValueError) as e:
            raise SignalCliConnectionError(f"Failed to connect to signal-cli at {self.socket_path}: {e}") from e

    def _call(self, method: str, params: Optional[dict] = None, retries: Optional[int] = None) -> Any:
        """Send JSON-RPC 2.0 request and return result. Raises SignalCliError on error."""
        if retries is None:
            retries = self.max_retries
        req_id = self._next_id()
        body = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
            "params": params or {},
        }
        msg = (json.dumps(body) + "\n").encode("utf-8")
        last_error = None

        for attempt in range(retries + 1):
            try:
                with self._connect() as sock:
                    sock.sendall(msg)
                    buf = b""
                    while b"\n" not in buf:
                        chunk = sock.recv(4096)
                        if not chunk:
                            raise SignalCliConnectionError("Connection closed before response")
                        buf += chunk
                    line = buf.split(b"\n", 1)[0].decode("utf-8")

                out = json.loads(line)
                if out.get("id") != req_id:
                    logger.warning("Response id %s != request id %s", out.get("id"), req_id)

                if "error" in out:
                    err = out["error"]
                    code = err.get("code", -1)
                    msg_err = err.get("message", str(err))
                    raise SignalCliError(msg_err, err)

                return out.get("result")

            except (socket.error, socket.timeout, OSError) as e:
                last_error = SignalCliConnectionError(f"Connection error: {e}")
                if attempt < retries:
                    delay = self.retry_delay * (2 ** attempt)
                    logger.warning("Request failed (attempt %d/%d): %s. Retrying in %.1fs...", attempt + 1, retries + 1, e, delay)
                    time.sleep(delay)
                else:
                    logger.error("Request failed after %d attempts", retries + 1)
            except (json.JSONDecodeError, ValueError) as e:
                last_error = SignalCliError(f"Invalid response: {e}")
                break
            except SignalCliError:
                raise

        raise last_error or SignalCliError("Request failed")

    def _normalize_group_id(self, gid: str) -> str:
        """Normalize group id for comparison (strip, optional base64 padding)."""
        if not gid or not isinstance(gid, str):
            return ""
        s = gid.strip()
        return s.rstrip("=").strip()

    def get_account_uuid(self, account_number: str) -> Optional[str]:
        """Resolve account phone number to account UUID. Returns None if not found."""
        account_number = (account_number or "").strip().replace(" ", "")
        if not account_number:
            return None
        try:
            result = self._call("listAccounts", {}, retries=0)
        except SignalCliError:
            return None
        if not result:
            return None
        accounts = result if isinstance(result, list) else (result.get("accounts") or result.get("accountList") or [])
        if not isinstance(accounts, list):
            return None
        for acc in accounts:
            if not isinstance(acc, dict):
                continue
            num = (acc.get("number") or "").strip().replace(" ", "")
            uid = (acc.get("uuid") or acc.get("accountUUID") or "").strip()
            if num == account_number and uid:
                return uid
        return None

    def list_groups(self, account: Optional[str] = None) -> list[dict]:
        """List all groups from the signal-cli daemon (JSON-RPC)."""
        _ = account  # retained for API compatibility
        result = self._call("listGroups", {})
        if result is None:
            return []
        if isinstance(result, list):
            return result
        if isinstance(result, dict):
            for key in ("groups", "groupList", "data"):
                val = result.get(key)
                if isinstance(val, list):
                    return val
        return []

    def get_group(self, account: str, group_id: str, use_daemon: bool = False) -> dict:
        """Fetch group state from the signal-cli daemon. use_daemon is retained for API compatibility."""
        for param_name in ("groupId", "group_id"):
            try:
                result = self._call("getGroup", {param_name: group_id})
                if result is not None:
                    return result
            except SignalCliError:
                continue
        try:
            groups = self.list_groups(account)
            want = self._normalize_group_id(group_id or "")
            for g in groups:
                if not isinstance(g, dict):
                    continue
                gid = g.get("id") or g.get("groupId") or ""
                if not isinstance(gid, str):
                    continue
                if self._normalize_group_id(gid) == want:
                    return g
                if (gid or "").strip() == (group_id or "").strip():
                    return g
        except SignalCliError:
            pass
        raise SignalCliError(
            f"Group not found: {group_id}. "
            "Run 'python main.py list-groups' to see available group IDs and use one as group_id in config.yaml."
        )

    def list_pending_members(self, account: str, group_id: str) -> list[dict]:
        """Return list of members who requested to join via the group link (requestingMembers). Uses daemon so requestingMembers is present."""
        group = self.get_group(account, group_id, use_daemon=True)
        requesting = group.get("requestingMembers") or group.get("requesting_members") or []
        result = []
        for addr in requesting:
            if isinstance(addr, dict):
                entry = dict(addr)
            else:
                entry = {}
            result.append(entry)
        return result

    def list_group_members(self, account: str, group_id: str) -> list[dict]:
        """Return full list of group members (JsonAddress-like dicts). Excludes the bot account."""
        group = self.get_group(account, group_id)
        data = group if isinstance(group, dict) else {}
        if isinstance(data.get("data"), dict):
            data = {**data, **data["data"]}
        raw = data.get("members") or data.get("member_list") or []
        if not raw and data.get("memberDetail"):
            raw = []
            for detail in data.get("memberDetail") or []:
                if isinstance(detail, dict):
                    raw.append({
                        "uuid": (detail.get("uuid") or "").strip(),
                        "number": (detail.get("number") or "").strip(),
                    })
        result = []
        account_normalized = (account or "").strip().replace(" ", "")
        account_uuid = (self.get_account_uuid(account) or "").strip()
        for addr in raw:
            if not isinstance(addr, dict):
                continue
            entry = {
                "uuid": (addr.get("uuid") or "").strip(),
                "number": (addr.get("number") or "").strip(),
            }
            if not entry["uuid"] and not entry["number"]:
                continue
            if account_uuid and entry["uuid"] == account_uuid:
                continue
            if account_normalized and (entry["number"] or "").replace(" ", "") == account_normalized:
                continue
            result.append(entry)
        return result

    def _send_message_via_cli(self, account: str, recipient_address: dict, message_body: str) -> dict:
        """Send a direct message via CLI (secondary client). recipient_address: number or uuid. Uses --message-from-stdin so multiline messages are preserved."""
        number = (recipient_address.get("number") or "").strip()
        uuid_val = (recipient_address.get("uuid") or "").strip()
        recipient = number or uuid_val
        if not recipient:
            raise SignalCliError("send_message: recipient number or uuid required")
        exe = self.cli_path or shutil.which("signal-cli")
        if not exe:
            raise SignalCliError("CLI requires signal-cli on PATH or signal_cli.cli_path in config.")
        exe = self._resolve_cli_exe(exe)
        cmd = [exe]
        if self.cli_config_path:
            cmd.extend(["-c", self.cli_config_path])
        cmd.extend(["-a", (account or "").strip()])
        cmd.extend(["send", recipient, "--message-from-stdin"])
        use_shell = sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT"))
        timeout_sec = 30
        try:
            if use_shell:
                cmd_str = subprocess.list2cmdline(cmd)
                proc = subprocess.run(
                    cmd_str, shell=True, input=message_body, capture_output=True, text=True,
                    timeout=timeout_sec, encoding="utf-8", errors="replace",
                )
            else:
                proc = subprocess.run(
                    cmd, input=message_body, capture_output=True, text=True,
                    timeout=timeout_sec, encoding="utf-8", errors="replace",
                )
        except subprocess.TimeoutExpired:
            raise SignalCliError("signal-cli send timed out")
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip() or f"exit code {proc.returncode}"
            raise SignalCliError(f"signal-cli send failed: {err}")
        return {}

    def send_message(self, account: str, recipient_address: dict, message_body: str) -> dict:
        """Send a direct message via the signal-cli daemon (JSON-RPC)."""
        number = (recipient_address.get("number") or "").strip()
        uuid_val = (recipient_address.get("uuid") or "").strip()
        recipient = number or uuid_val or ""
        if not isinstance(recipient, list):
            recipient = [recipient] if recipient else []
        result = self._call("send", {
            "recipient": recipient,
            "message": message_body,
        })
        if result is None:
            result = self._call("sendMessage", {
                "recipient": recipient,
                "message": message_body,
            })
        return result or {}

    def _resolve_cli_exe(self, exe: str) -> str:
        """On Windows, prefer .exe over .CMD shim."""
        if not (sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT"))):
            return exe
        base = os.path.dirname(os.path.abspath(exe))
        for candidate in (
            os.path.join(base, "signal-cli.exe"),
            os.path.join(os.path.dirname(base), "apps", "signal-cli", "current", "signal-cli.exe"),
        ):
            if os.path.isfile(candidate):
                return candidate
        return exe

    def _run_cli(self, account: str, args: List[str], timeout_sec: int = 30) -> subprocess.CompletedProcess:
        """Run signal-cli CLI with duplicate config (secondary client). Returns CompletedProcess."""
        exe = self.cli_path or shutil.which("signal-cli")
        if not exe:
            raise SignalCliError("CLI requires signal-cli on PATH or signal_cli.cli_path in config.")
        exe = self._resolve_cli_exe(exe)
        cmd = [exe]
        if self.cli_config_path:
            cmd.extend(["-c", self.cli_config_path])
        cmd.extend(["-a", (account or "").strip()])
        cmd.extend(args)
        use_shell = sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT"))
        if use_shell:
            cmd_str = subprocess.list2cmdline(cmd)
            return subprocess.run(cmd_str, shell=True, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")

    def _list_groups_via_cli(self, account: str) -> list[dict]:
        """Run signal-cli listGroups with duplicate config (secondary client). Returns list of group dicts. Tries JSON first if supported."""
        if not self.cli_config_path:
            return []
        for args in (["--output=json", "listGroups"], ["listGroups"]):
            try:
                proc = self._run_cli(account, args, timeout_sec=25)
            except (SignalCliError, FileNotFoundError, subprocess.TimeoutExpired):
                continue
            if proc.returncode != 0:
                continue
            out = (proc.stdout or "").strip()
            if not out:
                continue
            try:
                data = json.loads(out)
                if isinstance(data, list):
                    return data
                if isinstance(data, dict):
                    for key in ("groups", "groupList", "data"):
                        val = data.get(key)
                        if isinstance(val, list):
                            return val
                return []
            except json.JSONDecodeError:
                if args[0].startswith("--output"):
                    continue
            break
        groups = []
        if proc.returncode == 0 and proc.stdout:
            for line in (proc.stdout or "").splitlines():
                m = re.search(r"Id:\s*([^\s]+)", line)
                if m:
                    gid = m.group(1).strip()
                    groups.append({"id": gid, "groupId": gid, "name": "", "title": ""})
        return groups

    def _get_group_via_cli(self, account: str, group_id: str) -> Optional[dict]:
        """Fetch one group via CLI (secondary client). Returns group dict or None. Used to avoid daemon when cli_config_path is set."""
        if not self.cli_config_path:
            return None
        want = self._normalize_group_id(group_id or "")
        groups = self._list_groups_via_cli(account)
        for g in groups:
            if not isinstance(g, dict):
                continue
            gid = (g.get("id") or g.get("groupId") or "").strip()
            if self._normalize_group_id(gid) == want or (gid == (group_id or "").strip()):
                return g
        return None

    def _list_group_ids_via_cli(self, account: str) -> List[str]:
        """Run signal-cli listGroups with duplicate config; return list of normalized group IDs."""
        if not self.cli_config_path:
            return []
        groups = self._list_groups_via_cli(account)
        ids = []
        for g in groups:
            if isinstance(g, dict):
                gid = g.get("id") or g.get("groupId") or ""
                if gid:
                    ids.append(self._normalize_group_id(str(gid)))
        if ids:
            return ids
        exe = self.cli_path or shutil.which("signal-cli")
        if not exe:
            return []
        exe = self._resolve_cli_exe(exe)
        cmd = [exe, "-c", self.cli_config_path, "-a", (account or "").strip(), "listGroups"]
        use_shell = sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT"))
        timeout_sec = 15
        try:
            if use_shell:
                cmd_str = subprocess.list2cmdline(cmd)
                proc = subprocess.run(cmd_str, shell=True, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")
            else:
                proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return []
        if proc.returncode != 0:
            return []
        for line in (proc.stdout or "").splitlines():
            m = re.search(r"Id:\s*([^\s]+)", line)
            if m:
                gid = self._normalize_group_id(m.group(1))
                if gid:
                    ids.append(gid)
        return ids

    def _cli_has_group(self, account: str, group_id: str) -> bool:
        """Return True if the duplicate config (CLI) knows this group."""
        if not (group_id or "").strip():
            return False
        ids = self._list_group_ids_via_cli(account)
        want = self._normalize_group_id(group_id)
        return want in ids if ids else False

    def _run_update_group_cli(
        self, account: str, group_id: str, members: list[dict], log_prefix: str = "updateGroup"
    ) -> dict:
        """Run signal-cli CLI: updateGroup -g GROUP_ID -m MEMBER ... (add members to a group)."""
        group_id = (group_id or "").strip()
        if not group_id:
            raise SignalCliError("group_id is required for updateGroup.")
        if self.cli_config_path and not self._cli_has_group(account, group_id):
            raise SignalCliError(
                "Duplicate config does not contain this group. Re-run: python main.py duplicate-signal-cli-config (with daemon stopped), then try again."
            )
        exe = self.cli_path or shutil.which("signal-cli")
        if not exe:
            raise SignalCliError("CLI fallback requires signal-cli on PATH or signal_cli.cli_path in config.")
        exe = self._resolve_cli_exe(exe)
        account_normalized = (account or "").strip()
        member_ids = []
        for m in members:
            n = (m.get("number") or "").strip()
            u = (m.get("uuid") or "").strip()
            member_ids.append(n or u)
        member_ids = [x for x in member_ids if x]
        member_ids = [mid for mid in member_ids if mid != account_normalized]
        if not member_ids:
            raise SignalCliError("No member uuid/number for updateGroup.")
        cmd = [exe]
        if self.cli_config_path:
            cmd.extend(["-c", self.cli_config_path])
        cmd.extend(["-a", account, "updateGroup", "-g", group_id])
        for mid in member_ids:
            cmd.extend(["-m", mid])
        return self._run_update_group_cli_impl(exe, account, group_id, cmd, use_shell=(sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT"))), log_prefix=log_prefix)

    def _run_update_group_cli_impl(
        self, exe: str, account: str, group_id: str, cmd: List[str], use_shell: bool = False, log_prefix: str = "updateGroup"
    ) -> dict:
        """Execute updateGroup CLI command. cmd must already include -g and -a."""
        use_shell = use_shell or (sys.platform == "win32" and exe and (exe.upper().endswith(".CMD") or exe.upper().endswith(".BAT")))
        timeout_sec = 30
        try:
            if use_shell:
                cmd_str = subprocess.list2cmdline(cmd)
                proc = subprocess.run(cmd_str, shell=True, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")
            else:
                proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_sec, encoding="utf-8", errors="replace")
        except FileNotFoundError as e:
            raise SignalCliError(f"signal-cli executable not found: {e}") from e
        except subprocess.TimeoutExpired:
            raise SignalCliError("signal-cli updateGroup timed out after 30s")
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip() or f"exit code {proc.returncode}"
            raise SignalCliError(f"signal-cli updateGroup failed: {err}")
        return {}

    def approve_membership(self, account: str, group_id: str, members: list[dict]) -> dict:
        """Approve pending join requests via JSON-RPC updateGroup, same signal-cli process as the daemon."""
        group_id = (group_id or "").strip()
        if not group_id:
            raise SignalCliError("group_id is required for approve.")
        account_normalized = (account or "").strip()
        member_ids: List[str] = []
        for m in members:
            n = (m.get("number") or "").strip()
            u = (m.get("uuid") or "").strip()
            mid = n or u
            if mid and mid != account_normalized:
                member_ids.append(mid)
        if not member_ids:
            raise SignalCliError("No member uuid/number for approve.")
        params: dict = {
            "groupId": group_id,
            "group_id": group_id,
            "members": member_ids,
        }
        if account:
            params["account"] = account
        try:
            return self._call("updateGroup", params, retries=0) or {}
        except SignalCliError as e:
            if not self.try_cli_fallback_for_approve or not self.cli_config_path:
                raise SignalCliError(
                    f"updateGroup (daemon) failed: {e}. "
                    "If the group uses link-based join requests, ensure signal-cli is current. "
                    "Optional: set try_cli_fallback_for_approve: true and signal_cli.cli_config_path to enable CLI fallback."
                ) from e
            logger.warning("approve_membership: JSON-RPC updateGroup failed, using CLI: %s", e)
            return self._run_update_group_cli(account, group_id, members, log_prefix="approve_membership")

    def deny_membership(self, account: str, group_id: str, members: list[dict]) -> dict:
        """Deny pending join requests. Uses daemon updateGroup (avoids CLI second-process SQLite conflict on Windows).
        Tries refuseMembership first if available; otherwise updateGroup with ban. Ban can fail for requesting members
        with 'multiple membership lists' - in that case we try removeMember to remove from requesting list."""
        group_id = (group_id or "").strip()
        if not group_id:
            raise SignalCliError("group_id is required for deny.")
        member_addrs = []
        for m in members:
            addr = {}
            u = (m.get("uuid") or "").strip()
            n = (m.get("number") or "").strip()
            if u:
                addr["uuid"] = u
            if n:
                addr["number"] = n
            if addr:
                member_addrs.append(addr)
        member_ids = []
        for m in members:
            n = (m.get("number") or "").strip()
            u = (m.get("uuid") or "").strip()
            mid = n or u
            if mid and mid != (account or "").strip():
                member_ids.append(mid)
        if not member_ids:
            raise SignalCliError("No member uuid/number for deny.")
        params = {"groupId": group_id, "ban": member_ids}
        if account:
            params["account"] = account
        for method in ("refuseMembership", "refuse_membership"):
            try:
                refuse_params = {"group_id": group_id, "groupId": group_id, "members": member_addrs}
                if account:
                    refuse_params["account"] = account
                self._call(method, refuse_params, retries=0)
                return {}
            except SignalCliError:
                continue
        try:
            self._call("updateGroup", params)
            return {}
        except SignalCliError as e:
            if "multiple membership lists" in str(e).lower() or "GroupPatchNotAcceptedException" in str(e):
                remove_params = {"groupId": group_id, "removeMember": member_ids}
                if account:
                    remove_params["account"] = account
                try:
                    self._call("updateGroup", remove_params)
                    return {}
                except SignalCliError:
                    pass
            raise

    def add_members_to_group(self, account: str, group_id: str, members: list[dict]) -> dict:
        """Add members to an existing group via JSON-RPC updateGroup; optional CLI fallbacks (see try_cli_fallback_for_approve)."""
        logger.info("add_members_to_group: called with group_id=%s", group_id)
        group_id = (group_id or "").strip()
        if not group_id:
            raise SignalCliError("group_id is required for add to group.")
        account_normalized = (account or "").strip()
        member_ids: List[str] = []
        for m in members:
            n = (m.get("number") or "").strip()
            u = (m.get("uuid") or "").strip()
            mid = n or u
            if mid and mid != account_normalized:
                member_ids.append(mid)
        if not member_ids:
            raise SignalCliError("No member uuid/number for add to group.")
        params: dict = {
            "groupId": group_id,
            "group_id": group_id,
            "members": member_ids,
        }
        if account:
            params["account"] = account
        try:
            return self._call("updateGroup", params, retries=0) or {}
        except SignalCliError as e:
            if not self.try_cli_fallback_for_approve or not self.cli_config_path:
                raise SignalCliError(
                    f"updateGroup (daemon) failed: {e}. "
                    "Ensure your account is an admin of the target group. "
                    "Optional: set try_cli_fallback_for_approve: true and signal_cli.cli_config_path to enable CLI fallback."
                ) from e
            if not self._cli_has_group(account, group_id):
                raise SignalCliError(
                    "Duplicate config does not contain this group. Re-run: python main.py duplicate-signal-cli-config (with daemon stopped), then try again."
                )
            logger.warning("add_members_to_group: JSON-RPC updateGroup failed, using CLI: %s", e)
            delay_seconds = 2
            retry_delay_seconds = 5
            for i, m in enumerate(members):
                try:
                    self._run_update_group_cli(account, group_id, [m], log_prefix="add_members_to_group")
                except SignalCliError as e2:
                    logger.warning("add_members_to_group: failed for member %d/%d, retrying once in %ds: %s", i + 1, len(members), retry_delay_seconds, e2)
                    time.sleep(retry_delay_seconds)
                    self._run_update_group_cli(account, group_id, [m], log_prefix="add_members_to_group")
                if (i + 1) % 10 == 0 or i + 1 == len(members):
                    logger.info("add_members_to_group: added %d/%d", i + 1, len(members))
                if i + 1 < len(members):
                    time.sleep(delay_seconds)
            return {}

    def list_contacts(self, all_recipients: bool = True) -> list[dict]:
        """Return list of contacts (for name lookup). all_recipients=True includes UUID-only recipients. Empty list on error or unsupported."""
        params = {"allRecipients": True} if all_recipients else {}
        for method in ("listContacts", "list_contacts"):
            try:
                result = self._call(method, params, retries=0)
                if result is None:
                    continue
                if isinstance(result, list):
                    return result
                if isinstance(result, dict):
                    for key in ("contacts", "contactList", "data"):
                        val = result.get(key)
                        if isinstance(val, list):
                            return val
            except SignalCliError:
                continue
        return []

    def _first_last_from_dict(self, d: dict) -> Optional[str]:
        first_keys = ("givenName", "firstName", "first_name")
        last_keys = ("familyName", "lastName", "last_name", "surname")
        first = None
        last = None
        for k in first_keys:
            v = d.get(k)
            if isinstance(v, str) and v.strip():
                first = v.strip()
                break
        for k in last_keys:
            v = d.get(k)
            if isinstance(v, str) and v.strip():
                last = v.strip()
                break
        if first and last:
            return f"{first} {last}"
        if first:
            return first
        if last:
            return last
        return None

    def _name_from_contact(self, contact: dict) -> Optional[str]:
        if not isinstance(contact, dict):
            return None
        full = self._first_last_from_dict(contact)
        if full:
            return full
        for key in ("name", "profileName", "displayName"):
            val = contact.get(key)
            if isinstance(val, str) and val.strip():
                return val.strip()
        profile = contact.get("profile") or contact.get("profileKey")
        if isinstance(profile, dict):
            full = self._first_last_from_dict(profile)
            if full:
                return full
        return None

    def _is_name_same_as_identifier(self, name: str, uid: str, num: str) -> bool:
        """Return True if name is just the uuid or number (no real display name)."""
        if not name or not isinstance(name, str):
            return False
        n = name.strip()
        return n == (uid or "").strip() or n == (num or "").strip()

    def _lookup_recipient_name(self, recipient_id: str) -> Optional[str]:
        """Fetch profile/name for a single recipient (uuid or number) via listContacts. Returns None on error or if no name found."""
        if not (recipient_id or "").strip():
            return None
        for method in ("listContacts", "list_contacts"):
            try:
                result = self._call(method, {"recipient": [recipient_id.strip()]}, retries=0)
                if result is None:
                    continue
                contacts = result if isinstance(result, list) else (result.get("contacts") or result.get("contactList") or [])
                if not isinstance(contacts, list) or not contacts:
                    continue
                for c in contacts:
                    name = self._name_from_contact(c)
                    if name and not self._is_name_same_as_identifier(name, recipient_id, recipient_id):
                        return name
                    if name and self._is_name_same_as_identifier(name, recipient_id, recipient_id):
                        continue
            except SignalCliError:
                continue
        return None

    def get_recipient_names(
        self, account: str, members: list[dict], return_debug: bool = False
    ) -> Tuple[List[Optional[str]], Optional[dict]]:
        """Return (display name for each member in same order, optional debug dict)."""
        debug = {} if return_debug else None
        name_by_id = {}
        contacts = self.list_contacts()
        if return_debug and contacts and isinstance(contacts[0], dict):
            debug["list_contacts_sample_keys"] = list(contacts[0].keys())
        for c in contacts:
            name = self._name_from_contact(c)
            if name:
                num = (c.get("number") or "").strip()
                uid = (c.get("uuid") or "").strip()
                if self._is_name_same_as_identifier(name, uid, num):
                    continue
                if num:
                    name_by_id[num] = name
                if uid:
                    name_by_id[uid] = name
        names_out = []
        for m in members:
            uid = (m.get("uuid") or "").strip()
            num = (m.get("number") or "").strip()
            name = name_by_id.get(uid) or name_by_id.get(num)
            if name:
                names_out.append(name)
                continue
            recipient = uid or num
            if not recipient:
                names_out.append(None)
                continue
            fetched = self._lookup_recipient_name(recipient)
            if fetched:
                names_out.append(fetched)
            else:
                names_out.append(recipient)
        return names_out, debug
