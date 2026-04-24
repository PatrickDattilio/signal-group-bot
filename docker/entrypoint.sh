#!/usr/bin/env bash
# SignalBot container entrypoint.
#
# Responsibilities, in order:
#   1. Make sure /data/signal-cli exists and find the linked account (if any).
#   2. Optionally seed /data/messaged.json + /data/metrics.json from base64
#      env vars, then import them into /data/signalbot.db via migrate-json.
#   3. Start the signal-cli daemon on the loopback TCP port.
#   4. Exec the Kotlin UI (and bot polling loop) as PID 1's child.
#
# Designed to be idempotent: re-running it on a populated /data is safe.

set -euo pipefail

DB="${SIGNALBOT_DB:-/data/signalbot.db}"
PORT="${SIGNALBOT_UI_PORT:-${PORT:-5000}}"
SIGNAL_DATA="${SIGNAL_CLI_DATA:-/data/signal-cli}"
TCP="${SIGNAL_CLI_TCP:-127.0.0.1:7583}"
TCP_PORT="${TCP##*:}"

log() { printf '[entrypoint] %s\n' "$*"; }

mkdir -p "$SIGNAL_DATA"

# ---------------------------------------------------------------------------
# 1. Find the linked signal-cli account, if one has been uploaded.
#    signal-cli stores each account as a file under <config>/data/<E164>.
# ---------------------------------------------------------------------------
ACCOUNT=""
if [ -d "$SIGNAL_DATA/data" ]; then
  ACCOUNT="$(find "$SIGNAL_DATA/data" -maxdepth 1 -type f -name '+*' -printf '%f\n' 2>/dev/null \
             | head -n1 || true)"
fi

# ---------------------------------------------------------------------------
# 2. One-shot seed for messaged.json / metrics.json.
#    Only runs if the DB does not yet exist. Env vars are base64-encoded
#    so they fit inside Railway's variable size limit.
# ---------------------------------------------------------------------------
if [ ! -f "$DB" ]; then
  log "$DB not present - checking for seed variables"
  if [ -n "${SIGNALBOT_SEED_STORE_B64:-}" ]; then
    log "writing /data/messaged.json from SIGNALBOT_SEED_STORE_B64"
    printf '%s' "$SIGNALBOT_SEED_STORE_B64" | base64 -d > /data/messaged.json
  fi
  if [ -n "${SIGNALBOT_SEED_METRICS_B64:-}" ]; then
    log "writing /data/metrics.json from SIGNALBOT_SEED_METRICS_B64"
    printf '%s' "$SIGNALBOT_SEED_METRICS_B64" | base64 -d > /data/metrics.json
  fi
  if [ -f /data/messaged.json ] || [ -f /data/metrics.json ]; then
    log "running migrate-json to populate $DB"
    (cd /data && java -jar /app/signalbot.jar migrate-json)
  else
    log "no seed data found; starting with an empty DB"
  fi
fi

# ---------------------------------------------------------------------------
# 3. Start signal-cli daemon on loopback TCP, if an account is linked.
#    Without a linked account we still boot the UI so /login works and the
#    operator can see the warning in the logs.
# ---------------------------------------------------------------------------
DAEMON_PID=""
if [ -z "$ACCOUNT" ]; then
  log "WARNING: no linked signal-cli account under $SIGNAL_DATA/data/"
  log "upload your linked signal-cli data dir to $SIGNAL_DATA and restart."
  log "until then, any call that needs Signal will fail."
else
  log "linked account: $ACCOUNT"
  log "starting signal-cli daemon on $TCP"

  signal-cli --config "$SIGNAL_DATA" -a "$ACCOUNT" daemon --tcp "$TCP" &
  DAEMON_PID=$!

  # Wait up to ~60s for the port to accept connections.
  for _ in $(seq 1 60); do
    if bash -c "exec 3<>/dev/tcp/127.0.0.1/$TCP_PORT" 2>/dev/null; then
      log "signal-cli daemon is up (pid $DAEMON_PID)"
      break
    fi
    if ! kill -0 "$DAEMON_PID" 2>/dev/null; then
      log "ERROR: signal-cli daemon exited during startup"
      DAEMON_PID=""
      break
    fi
    sleep 1
  done
fi

# Propagate termination to the daemon so a Railway stop / SIGTERM is clean.
cleanup() {
  if [ -n "$DAEMON_PID" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
    log "stopping signal-cli daemon (pid $DAEMON_PID)"
    kill -TERM "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
}
trap cleanup TERM INT

# ---------------------------------------------------------------------------
# 4. Exec the Kotlin UI. SIGNAL_CLI_SOCKET is picked up by SignalCliClient.
# ---------------------------------------------------------------------------
export SIGNAL_CLI_SOCKET="$TCP"

log "launching SignalBot UI on port $PORT"
exec java -jar /app/signalbot.jar ui --host 0.0.0.0 --port "$PORT"
