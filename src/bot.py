"""
Bot loop: poll group for requesting members (join requests via group link), send configured message to each (once), optionally approve.
"""
import logging
import signal
import sys
import time
from typing import Optional

from .signal_cli_client import SignalCliClient, SignalCliError, SignalCliConnectionError
from .store import Store
from .metrics import Metrics
from .template import MessageTemplate
from .filters import MemberFilter, RateLimiter

logger = logging.getLogger(__name__)

# Global flag for graceful shutdown
_shutdown_requested = False


def _signal_handler(signum, frame):
    """Handle shutdown signals gracefully."""
    global _shutdown_requested
    sig_name = signal.Signals(signum).name
    logger.info("Received signal %s, initiating graceful shutdown...", sig_name)
    _shutdown_requested = True


def _recipient_address(member: dict) -> dict:
    """Build JsonAddress for send_message from requesting member entry."""
    addr = {}
    if member.get("uuid"):
        addr["uuid"] = member["uuid"]
    if member.get("number"):
        addr["number"] = member["number"]
    if not addr:
        addr["uuid"] = member.get("uuid") or ""
    return addr


def run_bot(config: dict, store_path: str = "messaged.json", dry_run: bool = False, metrics_path: str = "metrics.json") -> None:
    """Run the invite-queue bot: poll, message new requesting members, optionally approve.
    
    Args:
        config: Bot configuration dictionary
        store_path: Path to store file for tracking messaged users
        dry_run: If True, don't actually send messages or approve members
        metrics_path: Path to metrics file
    """
    global _shutdown_requested
    
    # Register signal handlers for graceful shutdown
    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)
    
    # Extract configuration (signal-cli daemon)
    signal_cli_config = config.get("signal_cli", config.get("signald", {}))
    socket_path = signal_cli_config.get("socket_path") or config.get("signal_cli_socket_path") or config.get("signald_socket_path")
    max_retries = signal_cli_config.get("max_retries", 3)
    retry_delay = signal_cli_config.get("retry_delay", 1.0)
    timeout = signal_cli_config.get("timeout", 30)
    
    client = SignalCliClient(
        socket_path=socket_path,
        max_retries=max_retries,
        retry_delay=retry_delay,
        timeout=timeout,
    )
    store = Store(path=store_path)
    metrics = Metrics(path=metrics_path, enabled=not dry_run)
    
    # Initialize filters
    filter_config = config.get("filters", {})
    member_filter = MemberFilter(
        allowlist=filter_config.get("allowlist", []),
        blocklist=filter_config.get("blocklist", []),
        allowlist_enabled=filter_config.get("allowlist_enabled", False),
    )
    
    rate_limit_config = filter_config.get("rate_limit", {})
    rate_limiter = RateLimiter(
        max_requests=rate_limit_config.get("max_requests", 10),
        window_seconds=rate_limit_config.get("window_seconds", 3600),
    ) if rate_limit_config.get("enabled", False) else None
    
    account = config["account"]
    group_id = config["group_id"]
    message_template_str = config["message"].strip()
    message_template = MessageTemplate(message_template_str)
    message_follow_up_str = (config.get("message_follow_up") or "").strip()
    follow_up_template = MessageTemplate(message_follow_up_str) if message_follow_up_str else None
    approval_mode = config.get("approval_mode", "manual")
    auto_approve_delay = int(config.get("auto_approve_delay_seconds", 0))
    cooldown = int(config.get("cooldown_seconds", 0))
    poll_interval = int(config.get("poll_interval_seconds", 120))

    mode_str = "DRY RUN" if dry_run else approval_mode.upper()
    logger.info(
        "Bot started [mode=%s, account=%s, group=%s, poll_interval=%ds, cooldown=%ds]",
        mode_str,
        account,
        group_id[:20] + "..." if len(group_id) > 20 else group_id,
        poll_interval,
        cooldown,
    )
    
    consecutive_errors = 0
    max_consecutive_errors = 10
    poll_count = 0

    while not _shutdown_requested:
        poll_count += 1
        try:
            pending = client.list_pending_members(account, group_id)
            consecutive_errors = 0  # Reset error counter on success
            metrics.record_poll_completed()
            logger.debug("Poll #%d completed, found %d requesting members", poll_count, len(pending))
        except SignalCliConnectionError as e:
            consecutive_errors += 1
            metrics.record_poll_failed("connection_error")
            logger.error(
                "Connection to signal-cli failed (poll #%d, error %d/%d): %s", 
                poll_count, consecutive_errors, max_consecutive_errors, e
            )
            if consecutive_errors >= max_consecutive_errors:
                logger.critical("Too many consecutive connection errors. Exiting.")
                sys.exit(1)
            time.sleep(min(poll_interval, 60))  # Wait before retry, max 60s
            continue
        except SignalCliError as e:
            consecutive_errors += 1
            metrics.record_poll_failed("signal_cli_error")
            logger.error(
                "get_group failed (poll #%d, error %d/%d): %s", 
                poll_count, consecutive_errors, max_consecutive_errors, e
            )
            if consecutive_errors >= max_consecutive_errors:
                logger.critical("Too many consecutive errors. Exiting.")
                sys.exit(1)
            time.sleep(poll_interval)
            continue

        if pending:
            logger.info("Poll #%d: Found %d requesting member(s)", poll_count, len(pending))

        for member in pending:
            if _shutdown_requested:
                logger.info("Shutdown requested, stopping member processing")
                break
                
            if store.was_messaged(member, cooldown_seconds=cooldown):
                logger.debug("Skipping member (already messaged within cooldown): %s", 
                           _recipient_address(member))
                continue
            addr = _recipient_address(member)
            if not addr.get("uuid") and not addr.get("number"):
                logger.warning("Skipping member with no uuid/number: %s", member)
                continue
            
            # Check rate limit
            if rate_limiter:
                allowed, reason = rate_limiter.check_rate_limit(member, time.time())
                if not allowed:
                    logger.warning("Rate limit exceeded for %s: %s", addr, reason)
                    continue
            
            # Check filters (allowlist/blocklist)
            should_approve, filter_reason = member_filter.should_approve(member)
            if not should_approve:
                logger.info("Skipping member %s: %s", addr, filter_reason)
                store.mark_messaged(member)  # Mark to avoid re-checking
                continue
            
            # Use follow-up message if we messaged them before (cooldown passed) and follow-up is configured
            is_follow_up = store.was_messaged(member, cooldown_seconds=0) and follow_up_template is not None
            template_to_use = follow_up_template if is_follow_up else message_template
            message = template_to_use.render(member)
            
            if dry_run:
                logger.info("[DRY RUN] Would send %s to %s: %s", "follow-up" if is_follow_up else "message", addr, message[:60] + "..." if len(message) > 60 else message)
                if approval_mode == "automatic":
                    logger.info("[DRY RUN] Would approve %s (filter: %s)", addr, filter_reason)
                continue
                
            try:
                client.send_message(account, addr, message)
                store.mark_messaged(member)
                metrics.record_message_sent()
                logger.info("✓ Sent %s to %s (filter: %s)", "follow-up" if is_follow_up else "message", addr, filter_reason)
            except SignalCliError as e:
                error_type = type(e).__name__
                metrics.record_message_failed(error_type)
                logger.error("✗ Send failed for %s: %s", addr, e)
                continue

            if approval_mode == "automatic":
                if auto_approve_delay > 0:
                    logger.debug("Waiting %ds before auto-approval", auto_approve_delay)
                    for _ in range(auto_approve_delay):
                        if _shutdown_requested:
                            break
                        time.sleep(1)
                    if _shutdown_requested:
                        break
                        
                try:
                    client.approve_membership(account, group_id, [addr])
                    metrics.record_approval_succeeded()
                    logger.info("✓ Approved %s", addr)
                except SignalCliError as e:
                    error_type = type(e).__name__
                    metrics.record_approval_failed(error_type)
                    logger.error("✗ Approve failed for %s: %s", addr, e)

        if _shutdown_requested:
            break
        
        # Log periodic stats
        if poll_count % 10 == 0:
            stats = metrics.get_stats()
            logger.info(
                "Stats: polls=%d, messages=%d/%d (%.1f%%), approvals=%d/%d",
                stats["polls_completed"],
                stats["messages_sent"],
                stats["messages_sent"] + stats["messages_failed"],
                stats["message_success_rate"] or 0,
                stats["approvals_succeeded"],
                stats["approvals_succeeded"] + stats["approvals_failed"],
            )
            
        # Sleep with periodic checks for shutdown signal
        for _ in range(poll_interval):
            if _shutdown_requested:
                break
            time.sleep(1)
    
    # Final stats on shutdown
    stats = metrics.get_stats()
    logger.info(
        "Bot shutdown complete. Final stats: uptime=%.1fh, polls=%d, messages=%d, approvals=%d",
        stats["uptime_hours"],
        stats["polls_completed"],
        stats["messages_sent"],
        stats["approvals_succeeded"],
    )
    sys.exit(0)
