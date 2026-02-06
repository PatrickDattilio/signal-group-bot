"""
Entrypoint: load config from config.yaml and run the invite-queue bot.
Usage:
  python main.py               Run the bot (poll and message requesting members).
  python main.py list-requesting  List requesting members (join requests via group link).
"""
import logging
import os
import sys

import yaml

from src.bot import run_bot
from src.signal_cli_client import SignalCliClient, SignalCliError, SignalCliConnectionError

def setup_logging(verbose: bool = False) -> None:
    """Configure logging with optional verbose mode."""
    level = logging.DEBUG if verbose else logging.INFO
    
    # Create formatter
    formatter = logging.Formatter(
        fmt="%(asctime)s [%(levelname)-8s] %(name)-20s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    
    # Console handler
    console_handler = logging.StreamHandler()
    console_handler.setLevel(level)
    console_handler.setFormatter(formatter)
    
    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    root_logger.addHandler(console_handler)
    
    # Optionally add file handler
    log_file = os.environ.get("SIGNALBOT_LOG_FILE")
    if log_file:
        try:
            file_handler = logging.FileHandler(log_file, encoding="utf-8")
            file_handler.setLevel(logging.DEBUG)  # Always debug in file
            file_handler.setFormatter(formatter)
            root_logger.addHandler(file_handler)
            logging.info("Logging to file: %s", log_file)
        except OSError as e:
            logging.warning("Could not create log file %s: %s", log_file, e)

CONFIG_PATH = os.environ.get("SIGNALBOT_CONFIG", "config.yaml")
STORE_PATH = os.environ.get("SIGNALBOT_STORE", "messaged.json")
METRICS_PATH = os.environ.get("SIGNALBOT_METRICS", "metrics.json")


def validate_config(cfg: dict) -> list[str]:
    """Validate configuration and return list of errors."""
    errors = []
    
    # Required fields
    required_fields = {
        "account": "Signal account phone number (E.164 format, e.g. +12025551234)",
        "group_id": "Signal group ID (base64-encoded)",
        "message": "Message to send to requesting members",
    }
    
    for field, description in required_fields.items():
        if not cfg.get(field):
            errors.append(f"Missing required field '{field}': {description}")
        elif not str(cfg[field]).strip():
            errors.append(f"Field '{field}' cannot be empty: {description}")
    
    # Validate account format (basic E.164 check)
    account = cfg.get("account", "")
    if account and not account.startswith("+"):
        errors.append(f"Account '{account}' should be in E.164 format (start with +)")
    if account and (len(account) < 8 or len(account) > 16):
        errors.append(f"Account '{account}' has invalid length (expected 8-16 chars)")
    
    # Validate approval_mode
    approval_mode = cfg.get("approval_mode", "manual")
    if approval_mode not in ("manual", "automatic"):
        errors.append(f"Invalid approval_mode '{approval_mode}' (must be 'manual' or 'automatic')")
    
    # Validate numeric fields
    numeric_fields = {
        "auto_approve_delay_seconds": (0, 3600),
        "cooldown_seconds": (0, 86400 * 7),
        "poll_interval_seconds": (10, 3600),
    }
    
    for field, (min_val, max_val) in numeric_fields.items():
        value = cfg.get(field)
        if value is not None:
            try:
                num_val = int(value)
                if num_val < min_val or num_val > max_val:
                    errors.append(f"Field '{field}' value {num_val} out of range ({min_val}-{max_val})")
            except (ValueError, TypeError):
                errors.append(f"Field '{field}' must be a number, got: {value}")
    
    # Validate signal_cli (or legacy signald) configuration
    backend_cfg = cfg.get("signal_cli", cfg.get("signald", {}))
    if backend_cfg:
        if "max_retries" in backend_cfg:
            try:
                retries = int(backend_cfg["max_retries"])
                if retries < 0 or retries > 10:
                    errors.append(f"signal_cli.max_retries {retries} out of range (0-10)")
            except (ValueError, TypeError):
                errors.append("signal_cli.max_retries must be a number")
        if "timeout" in backend_cfg:
            try:
                timeout = int(backend_cfg["timeout"])
                if timeout < 5 or timeout > 300:
                    errors.append(f"signal_cli.timeout {timeout} out of range (5-300)")
            except (ValueError, TypeError):
                errors.append("signal_cli.timeout must be a number")
    
    return errors


def load_config() -> dict:
    """Load and validate configuration from YAML file."""
    if not os.path.isfile(CONFIG_PATH):
        print(f"Config not found: {CONFIG_PATH}. Copy config.example.yaml to config.yaml and edit.", file=sys.stderr)
        sys.exit(1)
    
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = yaml.safe_load(f)
    except yaml.YAMLError as e:
        print(f"Invalid YAML in {CONFIG_PATH}: {e}", file=sys.stderr)
        sys.exit(1)
    except OSError as e:
        print(f"Cannot read {CONFIG_PATH}: {e}", file=sys.stderr)
        sys.exit(1)
    
    if not cfg:
        print("Config is empty.", file=sys.stderr)
        sys.exit(1)
    
    # Validate configuration
    errors = validate_config(cfg)
    if errors:
        print(f"Configuration validation failed ({len(errors)} error(s)):", file=sys.stderr)
        for i, error in enumerate(errors, 1):
            print(f"  {i}. {error}", file=sys.stderr)
        sys.exit(1)
    
    return cfg


def run_web_ui() -> None:
    """Start the web UI (view requesters, approve, add to second group)."""
    import web_ui
    web_ui.main()


def cmd_list_requesting(config: dict) -> None:
    """Print requesting members (users who requested to join via the group link) for the configured group."""
    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
    )
    try:
        requesting = client.list_pending_members(config["account"], config["group_id"])
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        if "Group not found" in str(e):
            print("Tip: Run 'python main.py list-groups' to see group IDs, then set group_id in config.yaml.", file=sys.stderr)
        sys.exit(1)
    if not requesting:
        print("No requesting members.")
        return
    print(f"Requesting members ({len(requesting)}):")
    for i, m in enumerate(requesting, 1):
        uuid_val = m.get("uuid", "")
        num = m.get("number", "")
        print(f"  {i}. uuid={uuid_val} number={num}")


def cmd_mark_requesting_as_messaged(config: dict, store_path: str) -> None:
    """Mark all current requesting members as already messaged (e.g. after you sent the first message manually).
    Run once before starting the bot so it won't send the first message again to existing requesters.
    """
    from src.store import Store

    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
    )
    try:
        requesting = client.list_pending_members(config["account"], config["group_id"])
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        if "Group not found" in str(e):
            print("Tip: Run 'python main.py list-groups' to see group IDs.", file=sys.stderr)
        sys.exit(1)
    if not requesting:
        print("No requesting members to mark.")
        return
    store = Store(path=store_path)
    marked = 0
    for member in requesting:
        store.mark_messaged(member)
        marked += 1
    print(f"Marked {marked} requesting member(s) as already messaged (stored in {store_path}).")
    print("When you run the bot, it will skip sending them the first message and will send follow-ups after cooldown if configured.")


def cmd_list_groups(config: dict) -> None:
    """List all groups from signal-cli (shows group IDs to use in config)."""
    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
    )
    try:
        groups = client.list_groups()
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    if not groups:
        print("No groups found.")
        return
    print(f"Groups ({len(groups)}):")
    for i, g in enumerate(groups, 1):
        gid = g.get("id") or g.get("groupId") or "(no id)"
        name = g.get("name") or g.get("title") or g.get("groupName") or "(no name)"
        print(f"  {i}. id={gid}")
        print(f"     name={name}")
    print("\nUse the 'id' value as group_id in config.yaml")


def cmd_debug_group(config: dict) -> None:
    """Fetch group from signal-cli and print raw response keys and member-related fields."""
    import json as json_mod

    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
    )
    try:
        resp = client.get_group(config["account"], config["group_id"])
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    if not isinstance(resp, dict):
        print("Response is not a dict:", type(resp))
        return

    print("=== Top-level response keys ===")
    print("Keys:", sorted(resp.keys()))
    print()

    # Check both top-level and resp["data"] for group fields
    for label, data in [("Top-level resp", resp), ("resp['data']", resp.get("data") if isinstance(resp.get("data"), dict) else {})]:
        if not isinstance(data, dict):
            continue
        print(f"=== {label} keys: {sorted(data.keys())} ===")
        for key in ("requestingMembers", "pendingMembers", "pendingMemberDetail", "requestingMemberDetail", "members", "memberDetail"):
            val = data.get(key)
            if val is None:
                print(f"  {key}: (not present)")
            elif isinstance(val, list):
                print(f"  {key}: list of length {len(val)}")
                if val:
                    print(f"    First: {json_mod.dumps(val[0], default=str)}")
        print()

    print("=== Full raw response (first 6000 chars) ===")
    raw = json_mod.dumps(resp, indent=2, default=str)
    print(raw[:6000])
    if len(raw) > 6000:
        print("... (truncated)")


def cmd_compare_groups(config: dict, show_names: bool = False) -> None:
    """List members that are in the Chat group but not in the Rapid Response group."""
    chat_group_id = (config.get("group_id") or "").strip()
    rapid_group_id = (config.get("approve_add_to_group_id") or "").strip()
    if not chat_group_id or not rapid_group_id:
        print("Set group_id (Chat) and approve_add_to_group_id (Rapid Response) in config.yaml for compare-groups.", file=sys.stderr)
        sys.exit(1)
    account = config.get("account", "").strip()
    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
    )
    try:
        chat_members = client.list_group_members(account, chat_group_id)
        rapid_members = client.list_group_members(account, rapid_group_id)
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    only_in_chat = list(chat_members)
    rapid_ids = set()
    for m in rapid_members:
        u = (m.get("uuid") or "").strip()
        n = (m.get("number") or "").strip()
        if u:
            rapid_ids.add(u)
        if n:
            rapid_ids.add(n)
    only_in_chat = [
        m for m in only_in_chat
        if not ((m.get("uuid") or "").strip() in rapid_ids or (m.get("number") or "").strip() in rapid_ids)
    ]
    names = None
    if show_names and only_in_chat:
        names, _ = client.get_recipient_names(account, only_in_chat, return_debug=False)
    print("Members in Chat but not in Rapid Response (Chat = group_id, Rapid = approve_add_to_group_id):", len(only_in_chat))
    for i, m in enumerate(only_in_chat):
        name_part = ""
        if names is not None and i < len(names) and names[i]:
            name_part = f"  {names[i]}"
        number_part = (m.get("number") or "").strip()
        uuid_part = (m.get("uuid") or "").strip()
        print(f"  {number_part or uuid_part or '?'}{name_part}")


def cmd_add_merged_to_group(config: dict, target_group_id: str, dry_run: bool = False, only_new: bool = False) -> None:
    """Add the combined set of Chat + Rapid Response members to the target group. Use --only-new to skip members already in the target."""
    chat_group_id = (config.get("group_id") or "").strip()
    rapid_group_id = (config.get("approve_add_to_group_id") or "").strip()
    if not chat_group_id or not rapid_group_id:
        print("Set group_id (Chat) and approve_add_to_group_id (Rapid Response) in config.yaml.", file=sys.stderr)
        sys.exit(1)
    target_group_id = (target_group_id or "").strip()
    if not target_group_id:
        print("Usage: python main.py add-merged-to-group <target_group_id> [--dry-run] [--only-new]", file=sys.stderr)
        sys.exit(1)
    account = config.get("account", "").strip()
    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    client = SignalCliClient(
        socket_path=signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path"),
        max_retries=signal_cli_config.get("max_retries", 3),
        cli_path=signal_cli_config.get("cli_path") or config.get("signal_cli_cli_path"),
        try_cli_fallback_for_approve=signal_cli_config.get("try_cli_fallback_for_approve", False),
        cli_config_path=signal_cli_config.get("cli_config_path") or config.get("signal_cli_cli_config_path"),
    )
    try:
        chat_members = client.list_group_members(account, chat_group_id)
        rapid_members = client.list_group_members(account, rapid_group_id)
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    seen_ids = set()
    merged = []
    for m in chat_members + rapid_members:
        u = (m.get("uuid") or "").strip()
        n = (m.get("number") or "").strip()
        if u and u in seen_ids or n and n in seen_ids:
            continue
        if not u and not n:
            continue
        if u:
            seen_ids.add(u)
        if n:
            seen_ids.add(n)
        merged.append(m)
    if only_new:
        try:
            existing = client.list_group_members(account, target_group_id)
        except SignalCliError as e:
            print(f"Error listing target group members: {e}", file=sys.stderr)
            sys.exit(1)
        existing_ids = set()
        for m in existing:
            u = (m.get("uuid") or "").strip()
            n = (m.get("number") or "").strip()
            if u:
                existing_ids.add(u)
            if n:
                existing_ids.add(n)
        before = len(merged)
        merged = [
            m for m in merged
            if not ((m.get("uuid") or "").strip() in existing_ids or (m.get("number") or "").strip() in existing_ids)
        ]
        skipped = before - len(merged)
        if skipped:
            print(f"Skipping {skipped} member(s) already in target group ({len(merged)} to add).")
    if not merged:
        print("No members to add.")
        return
    if dry_run:
        print(f"Dry run: would add {len(merged)} member(s) to group {target_group_id[:24]}...")
        for m in merged:
            print(f"  {(m.get('number') or m.get('uuid') or '?')}")
        return
    try:
        client.add_members_to_group(account, target_group_id, merged)
        print(f"Added {len(merged)} member(s) to target group.")
    except SignalCliError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


def cmd_stats(store_path: str, metrics_path: str) -> None:
    """Print statistics about messaged members and bot metrics."""
    from src.store import Store
    from src.metrics import Metrics
    
    print("=== Bot Statistics ===\n")
    
    # Store stats
    if os.path.isfile(store_path):
        store = Store(path=store_path, backup_enabled=False)
        store_stats = store.get_stats()
        
        print("Members Messaged:")
        print(f"  Total: {store_stats['total_members']}")
        print(f"  Last 24 hours: {store_stats['last_24h']}")
        print(f"  Last 7 days: {store_stats['last_7d']}")
        print(f"  Last 30 days: {store_stats['last_30d']}")
    else:
        print("Members Messaged:")
        print("  No data available (store file not found)")
    
    print()
    
    # Metrics stats
    if os.path.isfile(metrics_path):
        metrics = Metrics(path=metrics_path, enabled=False)
        m_stats = metrics.get_stats()
        
        print("Bot Metrics:")
        print(f"  Uptime: {m_stats['uptime_hours']:.1f} hours")
        print(f"  Polls: {m_stats['polls_completed']} completed, {m_stats['polls_failed']} failed")
        if m_stats['poll_success_rate'] is not None:
            print(f"    Success rate: {m_stats['poll_success_rate']:.1f}%")
        
        print(f"  Messages: {m_stats['messages_sent']} sent, {m_stats['messages_failed']} failed")
        if m_stats['message_success_rate'] is not None:
            print(f"    Success rate: {m_stats['message_success_rate']:.1f}%")
        
        print(f"  Approvals: {m_stats['approvals_succeeded']} succeeded, {m_stats['approvals_failed']} failed")
        if m_stats['approval_success_rate'] is not None:
            print(f"    Success rate: {m_stats['approval_success_rate']:.1f}%")
        
        if m_stats['errors']:
            print(f"  Errors by type:")
            for error_type, count in sorted(m_stats['errors'].items(), key=lambda x: x[1], reverse=True):
                print(f"    {error_type}: {count}")
    else:
        print("Bot Metrics:")
        print("  No data available (metrics file not found)")


def main() -> None:
    # Check for verbose flag
    verbose = "-v" in sys.argv or "--verbose" in sys.argv
    if verbose:
        sys.argv = [arg for arg in sys.argv if arg not in ("-v", "--verbose")]
    
    setup_logging(verbose=verbose)
    
    # Parse command-line arguments
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        
        # Commands that don't need config
        if cmd in ("-h", "--help", "help"):
            print("Signal Invite Queue Bot")
            print("\nUsage:")
            print("  python main.py [options]         Run the bot")
            print("  python main.py dry-run [options] Run in dry-run mode (no messages sent)")
            print("  python main.py list-requesting   List requesting members (join requests via group link)")
            print("  python main.py list-pending      Alias for list-requesting")
            print("  python main.py mark-requesting-as-messaged   Mark current requesters as already messaged (run once if you messaged them manually)")
            print("  python main.py ui                Web UI: view requesters, status, approve (+ add to second group)")
            print("  python main.py list-groups       List all groups (shows group IDs for config)")
            print("  python main.py debug-group       Show raw group response (for troubleshooting)")
            print("  python main.py compare-groups [--names]   List members in Chat but not in Rapid Response")
            print("  python main.py add-merged-to-group <group_id> [--dry-run] [--only-new]   Add Chat+Rapid members to a group")
            print("  python main.py stats             Show message statistics")
            print("  python main.py validate          Validate configuration")
            print("  python main.py help              Show this help")
            print("\nOptions:")
            print("  -v, --verbose                    Enable verbose logging (DEBUG level)")
            print("\nEnvironment Variables:")
            print("  SIGNALBOT_CONFIG    Config file path (default: config.yaml)")
            print("  SIGNALBOT_STORE     Store file path (default: messaged.json)")
            print("  SIGNALBOT_METRICS   Metrics file path (default: metrics.json)")
            print("  SIGNALBOT_LOG_FILE  Log to file (optional)")
            print("  SIGNAL_CLI_SOCKET    Override signal-cli socket path (or SIGNALD_SOCKET)")
            return
        elif cmd == "stats":
            cmd_stats(STORE_PATH, METRICS_PATH)
            return
    
    # Load and validate config for all other commands
    config = load_config()
    
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        if cmd == "list-requesting" or cmd == "list-pending":
            cmd_list_requesting(config)
            return
        elif cmd == "mark-requesting-as-messaged":
            cmd_mark_requesting_as_messaged(config, STORE_PATH)
            return
        elif cmd == "ui":
            run_web_ui()
            return
        elif cmd == "list-groups":
            cmd_list_groups(config)
            return
        elif cmd == "debug-group":
            cmd_debug_group(config)
            return
        elif cmd == "compare-groups":
            show_names = "--names" in (sys.argv[2:] if len(sys.argv) > 2 else [])
            cmd_compare_groups(config, show_names=show_names)
            return
        elif cmd == "add-merged-to-group":
            args = [a for a in (sys.argv[2:] if len(sys.argv) > 2 else []) if a not in ("--dry-run", "-n", "--only-new")]
            dry_run = "--dry-run" in sys.argv or "-n" in sys.argv
            only_new = "--only-new" in sys.argv
            if not args:
                print("Usage: python main.py add-merged-to-group <target_group_id> [--dry-run] [--only-new]", file=sys.stderr)
                sys.exit(1)
            cmd_add_merged_to_group(config, args[0], dry_run=dry_run, only_new=only_new)
            return
        elif cmd == "validate":
            print("✓ Configuration is valid!")
            print(f"  Account: {config['account']}")
            print(f"  Group ID: {config['group_id'][:20]}...")
            print(f"  Approval mode: {config.get('approval_mode', 'manual')}")
            print(f"  Poll interval: {config.get('poll_interval_seconds', 120)}s")
            return
        elif cmd == "dry-run":
            print("Starting in DRY RUN mode (no messages will be sent)")
            run_bot(config, store_path=STORE_PATH, dry_run=True, metrics_path=METRICS_PATH)
            return
        else:
            print(f"Unknown command: {cmd}", file=sys.stderr)
            print("Run 'python main.py help' for usage", file=sys.stderr)
            sys.exit(1)
    
    run_bot(config, store_path=STORE_PATH, metrics_path=METRICS_PATH)


if __name__ == "__main__":
    main()
