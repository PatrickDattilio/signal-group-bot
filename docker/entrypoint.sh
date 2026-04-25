#!/usr/bin/env bash
# SignalBot container entrypoint.
#
# Responsibilities, in order:
#   0. If started as root (typical on Railway / compose / k8s because the
#      bind-mounted /data volume is root-owned), chown /data to the
#      signalbot user and re-exec ourselves via gosu. The JVM + signal-cli
#      never run as root.
#   1. Make sure /data/signal-cli exists and find the linked account.
#   2. Optionally seed /data/messaged.json + /data/metrics.json from base64
#      env vars, then import them into /data/signalbot.db via migrate-json.
#   3. Start the signal-cli daemon on the loopback TCP port.
#   4. Exec the Kotlin process: UI only, or UI + polling bot (see SIGNALBOT_MODE).
#
# Designed to be idempotent: re-running it on a populated /data is safe.

set -euo pipefail

log() { printf '[entrypoint] %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 0. Fix volume permissions and drop privileges.
#    Railway mounts persistent volumes root-owned on every boot; the image
#    chown in the Dockerfile only covers the build-time /data placeholder,
#    not the real mount. Do the chown here as root, then re-exec ourselves
#    as signalbot so the rest of this script (and everything it forks) runs
#    unprivileged.
# ---------------------------------------------------------------------------
if [ "$(id -u)" = "0" ]; then
  log "running as root; chowning /data to signalbot and dropping privileges"
  chown -R signalbot:signalbot /data
  exec gosu signalbot "$0" "$@"
fi

CONFIG="${SIGNALBOT_CONFIG:-/data/config.yaml}"
DB="${SIGNALBOT_DB:-/data/signalbot.db}"
PORT="${SIGNALBOT_UI_PORT:-${PORT:-5000}}"
SIGNAL_DATA="${SIGNAL_CLI_DATA:-/data/signal-cli}"
TCP="${SIGNAL_CLI_TCP:-127.0.0.1:7583}"
TCP_PORT="${TCP##*:}"

log "running as $(id -un) (uid=$(id -u))"
mkdir -p "$SIGNAL_DATA"

# ---------------------------------------------------------------------------
# Seed /data/config.yaml from the bundled example on first boot. The UI
# command exits immediately if the config file is missing, which would
# crash-loop the container before the operator can railway-shell in to
# write one. The seeded file is the example template with placeholder
# account + group_id; the UI boots and the operator replaces it in place.
# ---------------------------------------------------------------------------
if [ ! -f "$CONFIG" ]; then
  if [ -f /app/config.example.yaml ]; then
    log "WARNING: $CONFIG missing; seeding from /app/config.example.yaml"
    log "         edit it via 'railway shell' and redeploy before production use."
    cp /app/config.example.yaml "$CONFIG"
  else
    log "ERROR: $CONFIG missing and /app/config.example.yaml not in image"
  fi
fi

# ---------------------------------------------------------------------------
# 1. Find the linked signal-cli account, if one has been uploaded.
#
#    Layout changed across signal-cli versions:
#      * <= 0.11 stored each account as a file named by its E.164 number,
#        e.g. /data/signal-cli/data/+15551234567
#      * >= 0.12 stores accounts under opaque numeric IDs (e.g. 515778) and
#        keeps the E.164 -> ID mapping in /data/signal-cli/data/accounts.json.
#        The daemon's -a flag still expects the E.164 number, so we have to
#        read it out of accounts.json.
#
#    We try the modern layout first (accounts.json), then fall back to the
#    legacy filename-based layout so old snapshots still boot.
# ---------------------------------------------------------------------------
ACCOUNT=""
ACCOUNTS_JSON="$SIGNAL_DATA/data/accounts.json"
if [ -f "$ACCOUNTS_JSON" ]; then
  # Pull the first "number": "+..." value without pulling in jq. `tr` flattens
  # the JSON so the regex works on pretty-printed and minified files alike.
  ACCOUNT="$(tr -d '\n\r' < "$ACCOUNTS_JSON" \
             | grep -oE '"number"[[:space:]]*:[[:space:]]*"\+[0-9]+"' \
             | head -n1 \
             | grep -oE '\+[0-9]+' || true)"
fi
if [ -z "$ACCOUNT" ] && [ -d "$SIGNAL_DATA/data" ]; then
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
# 4. Exec SignalBot. SIGNAL_CLI_SOCKET is picked up by SignalCliClient.
#
# SIGNALBOT_MODE:
#   ui  (default) — admin web UI only; no automatic polling / vetting DMs.
#   run — same UI plus runBot (poll_interval_seconds, vetting messages, etc.).
#
# For "run", the JVM subcommand does not take --host; bind address comes from
# SIGNALBOT_UI_HOST (default 0.0.0.0 here so Railway's health check can reach /health).
# ---------------------------------------------------------------------------
export SIGNAL_CLI_SOCKET="$TCP"
export SIGNALBOT_UI_PORT="$PORT"
MODE="${SIGNALBOT_MODE:-ui}"
export SIGNALBOT_UI_HOST="${SIGNALBOT_UI_HOST:-0.0.0.0}"

case "$MODE" in
  run)
    log "launching SignalBot (mode=run: UI + polling bot) on ${SIGNALBOT_UI_HOST}:$PORT"
    exec java -jar /app/signalbot.jar run
    ;;
  ui|*)
    log "launching SignalBot (mode=ui: admin UI only) on 0.0.0.0:$PORT"
    exec java -jar /app/signalbot.jar ui --host 0.0.0.0 --port "$PORT"
    ;;
esac
