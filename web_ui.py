"""
Web UI for SignalBot: view requesting members, their status, and approve (optionally add to second group).
Run: python web_ui.py   or   python main.py ui
"""
import json
import logging
import os
import subprocess
import sys
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    from flask import Flask, jsonify, request, render_template_string
except ImportError:
    print("Install Flask: pip install Flask", file=sys.stderr)
    sys.exit(1)

import yaml
from src.signal_cli_client import SignalCliClient, SignalCliError
from src.store import Store
from src.template import MessageTemplate

app = Flask(__name__)
# Resolve config path relative to project root so we always load the same file regardless of process cwd
_ROOT = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.environ.get("SIGNALBOT_CONFIG") or os.path.join(_ROOT, "config.yaml")
STORE_PATH = os.environ.get("SIGNALBOT_STORE") or os.path.join(_ROOT, "messaged.json")

# Cached on first use (config is re-read from disk each time so edits take effect without restart)
_client: Optional[SignalCliClient] = None
_store: Optional[Store] = None


def get_config() -> dict:
    """Load config from disk each time so edits to config.yaml take effect without restarting the UI."""
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def get_client() -> SignalCliClient:
    global _client
    if _client is None:
        cfg = get_config()
        sc = cfg.get("signal_cli", cfg.get("signald", {}))
        _client = SignalCliClient(
            socket_path=sc.get("socket_path") or cfg.get("signal_cli_socket_path") or cfg.get("signald_socket_path"),
            max_retries=sc.get("max_retries", 3),
            cli_path=sc.get("cli_path") or cfg.get("signal_cli_cli_path"),
            try_cli_fallback_for_approve=sc.get("try_cli_fallback_for_approve", False),
            cli_config_path=sc.get("cli_config_path") or cfg.get("signal_cli_cli_config_path"),
        )
    return _client


def get_store() -> Store:
    global _store
    if _store is None:
        _store = Store(path=STORE_PATH, backup_enabled=False)
    return _store


def member_to_address(member: dict) -> dict:
    """Build JsonAddress for signal-cli: only include non-empty uuid/number."""
    addr = {}
    u = (member.get("uuid") or "").strip()
    n = (member.get("number") or "").strip()
    if u:
        addr["uuid"] = u
    if n:
        addr["number"] = n
    return addr if addr else {"number": n or "", "uuid": u or ""}


@app.route("/")
def index():
    return render_template_string(INDEX_HTML)


def _normalize_gid(gid: str) -> str:
    """Normalize group id for comparison (strip, optional base64 padding)."""
    if not gid or not isinstance(gid, str):
        return ""
    return (gid.strip()).rstrip("=").strip()


def _get_group_names_for_config(
    client: SignalCliClient, main_id: str, add_to_group_ids: list[str]
) -> tuple[str, list[str]]:
    """Resolve main and additional group IDs to names via list_groups. Returns (main_name, [name for each add_to id])."""
    main_name = "?"
    add_names: list[str] = []
    try:
        groups = client.list_groups()
    except SignalCliError:
        return main_name, add_names
    want_main = _normalize_gid(main_id)
    want_add = [_normalize_gid(gid) for gid in add_to_group_ids]
    for g in groups:
        if not isinstance(g, dict):
            continue
        gid = g.get("id") or g.get("groupId") or ""
        if not isinstance(gid, str):
            continue
        n = _normalize_gid(gid)
        name = (g.get("name") or g.get("title") or g.get("groupName") or "?").strip() or "?"
        if n == want_main:
            main_name = name
    add_names = []
    for want in want_add:
        found = "?"
        for g in groups:
            if not isinstance(g, dict):
                continue
            gid = g.get("id") or g.get("groupId") or ""
            if not isinstance(gid, str):
                continue
            if _normalize_gid(gid) == want:
                found = (g.get("name") or g.get("title") or g.get("groupName") or "?").strip() or "?"
                break
        add_names.append(found)
    return main_name, add_names


def _fetch_requesting_via_list_requesting(cfg: dict) -> list[dict]:
    """Run python main.py list-requesting --json and return the parsed list (no daemon in this process)."""
    root = os.path.dirname(os.path.abspath(__file__))
    main_py = os.path.join(root, "main.py")
    env = os.environ.copy()
    env["SIGNALBOT_CONFIG"] = CONFIG_PATH
    try:
        proc = subprocess.run(
            [sys.executable, main_py, "list-requesting", "--json"],
            cwd=root,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except subprocess.TimeoutExpired:
        return []
    except FileNotFoundError:
        return []
    if proc.returncode != 0:
        raise SignalCliError(proc.stderr.strip() or proc.stdout.strip() or f"exit code {proc.returncode}")
    out = (proc.stdout or "").strip()
    if not out:
        return []
    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        raise SignalCliError(f"Invalid list-requesting output: {e}") from e


@app.route("/api/requesting", methods=["GET"])
def api_requesting():
    """Return list of requesting members with status (messaged_at, status label) and name. Fetches list via main.py list-requesting --json (no daemon in UI process)."""
    cfg = get_config()
    account = cfg["account"]
    group_id = cfg["group_id"]
    add_to_group_ids = [
        (str(cfg.get("approve_add_to_group_id") or "").strip()) or None,
        (str(cfg.get("approve_add_to_group_id_2") or "").strip()) or None,
    ]
    add_to_group_ids = [gid for gid in add_to_group_ids if gid]
    store = get_store()
    debug = request.args.get("debug", "").lower() in ("1", "true", "yes")
    try:
        members = _fetch_requesting_via_list_requesting(cfg)
    except SignalCliError as e:
        return jsonify({"error": str(e)}), 500
    client = get_client()
    main_group_name, add_group_names = _get_group_names_for_config(client, group_id, add_to_group_ids)
    names, name_debug = client.get_recipient_names(account, members, return_debug=debug)
    result = []
    for i, m in enumerate(members):
        ts = store.get_messaged_at(m)
        if ts:
            status = "messaged"
            messaged_at = datetime.utcfromtimestamp(ts).strftime("%Y-%m-%d %H:%M UTC")
        else:
            status = "not_messaged"
            messaged_at = None
        name = names[i] if i < len(names) else None
        result.append({
            "uuid": m.get("uuid", ""),
            "number": m.get("number", ""),
            "name": name,
            "status": status,
            "messaged_at": messaged_at,
        })
    out = {
        "requesting": result,
        "main_group_name": main_group_name,
        "add_group_names": add_group_names,
    }
    if debug and name_debug:
        out["name_lookup_debug"] = name_debug
    resp = jsonify(out)
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    resp.headers["Pragma"] = "no-cache"
    resp.headers["Expires"] = "0"
    return resp


@app.route("/api/send-welcome", methods=["POST"])
def api_send_welcome():
    """Send the welcome/rules message only (no approval). Uses approve_rules_message from config."""
    cfg = get_config()
    account = cfg["account"]
    rules_message = (cfg.get("approve_rules_message") or "").strip() or None
    if not rules_message:
        return jsonify({"error": "approve_rules_message is not configured in config.yaml"}), 400
    data = request.get_json() or {}
    member = data.get("member") or {}
    uuid_val = member.get("uuid", "")
    number = member.get("number", "")
    if not uuid_val and not number:
        return jsonify({"error": "member.uuid or member.number required"}), 400
    addr = member_to_address(member)
    logger.info("Send welcome: member uuid=%s number=%s -> addr=%s", uuid_val or "(none)", number or "(none)", addr)
    client = get_client()
    try:
        tmpl = MessageTemplate(rules_message)
        body = tmpl.render(member)
        client.send_message(account, addr, body)
    except SignalCliError as e:
        return jsonify({"error": str(e)}), 500
    return jsonify({"ok": True, "welcome_sent": True})


@app.route("/api/approve", methods=["POST"])
def api_approve():
    """Approve member (main group) and optionally add to second/third groups (approve_add_to_group_id, approve_add_to_group_id_2). Does not send welcome message."""
    cfg = get_config()
    account = cfg["account"]
    group_id = cfg["group_id"]
    add_to_group_ids = [
        (str(cfg.get("approve_add_to_group_id") or "").strip()) or None,
        (str(cfg.get("approve_add_to_group_id_2") or "").strip()) or None,
    ]
    add_to_group_ids = [gid for gid in add_to_group_ids if gid]
    logger.info("Approve: config=%s add_to_groups=%s", os.path.abspath(CONFIG_PATH), add_to_group_ids or "(none)")
    data = request.get_json() or {}
    member = data.get("member") or {}
    uuid_val = member.get("uuid", "")
    number = member.get("number", "")
    if not uuid_val and not number:
        return jsonify({"error": "member.uuid or member.number required"}), 400
    addr = member_to_address(member)
    logger.info("Approve: member uuid=%s number=%s -> addr=%s", uuid_val or "(none)", number or "(none)", addr)
    client = get_client()
    errors = []
    try:
        client.approve_membership(account, group_id, [addr])
    except SignalCliError as e:
        errors.append(f"Approve: {e}")
        return jsonify({"error": "; ".join(errors)}), 500
    for i, add_to_group_id in enumerate(add_to_group_ids):
        try:
            client.add_members_to_group(account, add_to_group_id, [addr])
        except SignalCliError as e:
            errors.append(f"Add to group {i + 2}: {e}")
            return jsonify({"error": "; ".join(errors), "approved": True}), 500
    return jsonify({
        "ok": True,
        "approved": True,
        "added_to_extra_groups": len(add_to_group_ids),
    })


INDEX_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SignalBot – Requesting Members</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: system-ui, -apple-system, sans-serif; max-width: 900px; margin: 0 auto; padding: 1.5rem; background: #1a1a2e; color: #eee; }
    h1 { margin-top: 0; }
    .refresh { margin-bottom: 1rem; }
    .refresh button { padding: 0.5rem 1rem; cursor: pointer; background: #4361ee; color: #fff; border: none; border-radius: 6px; }
    .refresh button:hover { background: #3a56d4; }
    table { width: 100%; border-collapse: collapse; background: #16213e; border-radius: 8px; overflow: hidden; }
    th, td { padding: 0.75rem 1rem; text-align: left; }
    th { background: #0f3460; }
    tr:nth-child(even) { background: #1a2a4a; }
    .status { display: inline-block; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.85rem; }
    .status.messaged { background: #2e7d32; color: #fff; }
    .status.not_messaged { background: #666; color: #fff; }
    .welcome-btn { padding: 0.4rem 0.8rem; cursor: pointer; background: #4361ee; color: #fff; border: none; border-radius: 6px; font-weight: 500; margin-right: 0.4rem; }
    .welcome-btn:hover { background: #3a56d4; }
    .welcome-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .approve-btn { padding: 0.4rem 0.8rem; cursor: pointer; background: #06d6a0; color: #111; border: none; border-radius: 6px; font-weight: 600; }
    .approve-btn:hover { background: #05c090; }
    .approve-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .actions-cell { white-space: nowrap; }
    .error { color: #ff6b6b; margin-top: 0.5rem; }
    .message { margin-bottom: 1rem; padding: 0.75rem; border-radius: 6px; }
    .message.info { background: #0f3460; }
    .message.error { background: #5c1010; }
  </style>
</head>
<body>
  <h1>Requesting Members</h1>
  <p class="message info">Send welcome: sends the rules message (approve_rules_message) only. Approve: approves in the main group and optionally adds to additional groups (approve_add_to_group_id, approve_add_to_group_id_2). Names are loaded from Signal when available; add <code>?debug=1</code> to the URL for name lookup debug.</p>
  <p id="group-names" class="message info" style="display:none;"></p>
  <div class="refresh">
    <button type="button" id="refresh">Refresh list</button>
  </div>
  <div id="error" class="error" style="display:none;"></div>
  <div id="debug-wrap"></div>
  <table>
    <thead>
      <tr>
        <th>Name</th>
        <th>Number / UUID</th>
        <th>Status</th>
        <th>Messaged at</th>
        <th>Actions</th>
      </tr>
    </thead>
    <tbody id="tbody">
      <tr><td colspan="5">Loading…</td></tr>
    </tbody>
  </table>

  <script>
    const tbody = document.getElementById('tbody');
    const errEl = document.getElementById('error');

    function showError(msg) {
      errEl.textContent = msg;
      errEl.style.display = msg ? 'block' : 'none';
    }

    function load() {
      showError('');
      const debugWrap = document.getElementById('debug-wrap');
      if (debugWrap) debugWrap.innerHTML = '';
      tbody.innerHTML = '<tr><td colspan="5">Loading…</td></tr>';
      const debug = new URLSearchParams(window.location.search).get('debug');
      const url = '/api/requesting?_=' + Date.now() + (debug ? '&debug=1' : '');
      fetch(url, { cache: 'no-store' })
        .then(r => r.ok ? r.json() : Promise.reject(r))
        .then(data => {
          if (data.error) {
            showError(data.error);
            tbody.innerHTML = '<tr><td colspan="5">Error loading list.</td></tr>';
            return;
          }
          const groupNamesEl = document.getElementById('group-names');
          if (groupNamesEl && (data.main_group_name !== undefined || (data.add_group_names && data.add_group_names.length))) {
            let msg = 'Approve adds to main group: <strong>' + escapeHtml(data.main_group_name || '?') + '</strong>';
            if (data.add_group_names && data.add_group_names.length) {
              data.add_group_names.forEach(function(name, i) {
                msg += ' · Then adds to group ' + (i + 2) + ': <strong>' + escapeHtml(name || '?') + '</strong>';
              });
            }
            msg += '. Check config.yaml (group_id, approve_add_to_group_id, approve_add_to_group_id_2) if these are wrong.';
            groupNamesEl.innerHTML = msg;
            groupNamesEl.style.display = 'block';
          }
          const list = data.requesting || [];
          if (list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5">No requesting members.</td></tr>';
            return;
          }
          function postMember(url, member, btn, onSuccess) {
            btn.disabled = true;
            fetch(url, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ member: { uuid: member.uuid || undefined, number: member.number || undefined } })
            })
              .then(r => r.json())
              .then(data => {
                if (data.error) {
                  showError(data.error);
                  btn.disabled = false;
                  return;
                }
                showError('');
                if (onSuccess) onSuccess(data);
                btn.disabled = false;
              })
              .catch(() => { showError('Request failed'); btn.disabled = false; });
          }
          tbody.innerHTML = list.map(m => `
            <tr data-uuid="${escapeHtml(m.uuid || '')}" data-number="${escapeHtml(m.number || '')}">
              <td>${escapeHtml(m.name || '—')}</td>
              <td>${escapeHtml(m.number || m.uuid || '—')}</td>
              <td><span class="status ${m.status}">${m.status === 'messaged' ? 'Messaged' : 'Not messaged'}</span></td>
              <td>${escapeHtml(m.messaged_at || '—')}</td>
              <td class="actions-cell">
                <button class="welcome-btn" type="button">Send welcome</button>
                <button class="approve-btn" type="button">Approve</button>
              </td>
            </tr>
          `).join('');
          tbody.querySelectorAll('.welcome-btn').forEach(btn => {
            btn.addEventListener('click', function() {
              const row = this.closest('tr');
              postMember('/api/send-welcome', { uuid: row.dataset.uuid, number: row.dataset.number }, this);
            });
          });
          tbody.querySelectorAll('.approve-btn').forEach(btn => {
            btn.addEventListener('click', function() {
              const row = this.closest('tr');
              postMember('/api/approve', { uuid: row.dataset.uuid, number: row.dataset.number }, this, function() {
                row.remove();
              });
            });
          });
          if (data.name_lookup_debug) {
            const pre = document.createElement('pre');
            pre.className = 'message';
            pre.style.marginTop = '1rem';
            pre.style.fontSize = '0.85rem';
            pre.textContent = 'Name lookup debug: ' + JSON.stringify(data.name_lookup_debug, null, 2);
            const wrap = document.getElementById('debug-wrap');
            if (wrap) { wrap.appendChild(pre); }
          }
        })
        .catch(() => { showError('Failed to load'); tbody.innerHTML = '<tr><td colspan="5">Failed to load.</td></tr>'; });
    }

    function escapeHtml(s) {
      const div = document.createElement('div');
      div.textContent = s;
      return div.innerHTML;
    }

    document.getElementById('refresh').addEventListener('click', load);
    load();
  </script>
</body>
</html>
"""


def main():
    port = int(os.environ.get("SIGNALBOT_UI_PORT", "5000"))
    host = os.environ.get("SIGNALBOT_UI_HOST", "127.0.0.1")
    print(f"Starting UI at http://{host}:{port}")
    print("Configure approve_add_to_group_id and optionally approve_add_to_group_id_2 in config.yaml to add approved members to additional groups.")
    app.run(host=host, port=port, debug=False)


if __name__ == "__main__":
    main()
