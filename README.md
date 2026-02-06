# Signal Invite Queue Bot

A robust bot that manages join requests for moderated Signal groups. When users request to join via the **public group link** (with "Approve New Members" enabled), the bot sends each requester a customizable message, then you can approve them manually in the Signal app or have the bot approve them automatically.

## Features

- ✅ **Automated welcome messages** to pending members
- ✅ **Manual or automatic approval** modes
- ✅ **Message templates** with dynamic variables (timestamp, member info, etc.)
- ✅ **Allowlist/blocklist filtering** with wildcard support
- ✅ **Rate limiting** to prevent spam
- ✅ **Retry logic** with exponential backoff for reliability
- ✅ **Graceful shutdown** handling
- ✅ **Metrics tracking** (messages sent, approvals, success rates)
- ✅ **Automatic backups** of state files
- ✅ **Dry-run mode** for testing
- ✅ **Configuration validation**
- ✅ **Docker support** for easy deployment
- ✅ **Comprehensive logging** with verbose mode

## How it works

1. Your group has a public group link with **Approve New Members** turned on, so join requests go into a pending queue.
2. The bot (using the moderator account linked with [signal-cli](https://github.com/AsamK/signal-cli)) periodically fetches the list of pending members.
3. For each new pending member, the bot:
   - Checks filters (allowlist/blocklist, rate limits)
   - Sends a **direct message** with your configured text
   - Optionally approves them automatically
4. State is tracked to avoid duplicate messages and respect cooldown periods.

## Prerequisites

- **signal-cli** – The bot talks to Signal via [signal-cli](https://github.com/AsamK/signal-cli) in daemon mode (JSON-RPC over socket). You must install signal-cli, **link** your moderator Signal account, and run `signal-cli -u +NUMBER daemon --socket`.
- **Python 3.10+** – For running the bot.

### Running signal-cli

See **[SIGNAL_CLI_SETUP.md](SIGNAL_CLI_SETUP.md)** for step-by-step setup.

- **Link your account:** `signal-cli -u +NUMBER link` (scan QR in Signal app).
- **Start the daemon:** `signal-cli -u +NUMBER daemon --socket` (use `--socket`; DBus is not used).
- **On Windows:** The daemon uses a Unix socket by default. Use a TCP port (e.g. via WSL + socat) or a signal-cli build that supports `--tcp`, then set `signal_cli.socket_path: "localhost:7583"` in config.

Your linked account must be an **admin** of the group so the bot can see requesting members and approve them.

## Installation

```bash
cd SignalBot
python -m venv .venv
.venv\Scripts\activate   # Windows
# or: source .venv/bin/activate   # Linux/macOS
pip install -r requirements.txt
```

## Configuration

1. Copy the example config and edit it:

   ```bash
   copy config.example.yaml config.yaml
   ```

2. Edit **config.yaml**. Key settings:

   - **account** – The moderator account's phone number in E.164 form (e.g. `+12025551234`)
   - **group_id** – Your group's ID (base64-encoded; get via `python main.py debug-group`)
   - **message** – Welcome message template (supports variables like `{{datetime}}`, `{{member_number}}`)
   - **approval_mode** – `manual` or `automatic`
   - **poll_interval_seconds** – How often to check for new pending members (default 120)
   - **cooldown_seconds** – Cooldown before re-messaging same user (default 86400 = 24 hours)

3. Optional advanced settings:

   - **signal_cli.socket_path** – Path to signal-cli socket or `host:port` for TCP (e.g. `localhost:7583`)
   - **signal_cli.max_retries** – Number of retries on connection failure (default 3)
   - **signal_cli.timeout** – Socket timeout in seconds (default 30)
   - **filters.allowlist** – List of allowed phone numbers/UUIDs (supports wildcards)
   - **filters.blocklist** – List of blocked phone numbers/UUIDs
   - **filters.rate_limit** – Rate limiting configuration

See `config.example.yaml` for full documentation and examples.

## Running the bot

### Basic Usage

```bash
# Run the bot
python main.py

# Run in dry-run mode (no messages sent)
python main.py dry-run

# Enable verbose logging
python main.py -v

# Validate configuration
python main.py validate

# List pending members
python main.py list-pending

# Show statistics
python main.py stats

# Show help
python main.py help
```

### Docker Deployment

See [DOCKER.md](DOCKER.md) for detailed Docker deployment instructions.

Quick start:

```bash
docker-compose up -d
```

### Environment Variables

- **SIGNALBOT_CONFIG** – Config file path (default: `config.yaml`)
- **SIGNALBOT_STORE** – State file path (default: `messaged.json`)
- **SIGNALBOT_METRICS** – Metrics file path (default: `metrics.json`)
- **SIGNALBOT_LOG_FILE** – Log to file (optional)
- **SIGNAL_CLI_SOCKET** – Override signal-cli socket path (or **SIGNALD_SOCKET** as fallback)

## Message Templates

The bot supports dynamic message templates with variables:

```yaml
message: |
  Welcome! Your join request was received on {{datetime}}.
  
  Please read our rules before being approved.
  Your member ID: {{member_uuid}}
```

Available variables:
- `{{timestamp}}` - Unix timestamp
- `{{date}}` - Current date (YYYY-MM-DD)
- `{{time}}` - Current time (HH:MM:SS)
- `{{datetime}}` - Current date and time
- `{{member_uuid}}` - Member's UUID
- `{{member_number}}` - Member's phone number

## Filtering and Approval Rules

### Allowlist

Only allow specific members (with wildcard support):

```yaml
filters:
  allowlist_enabled: true
  allowlist:
    - "+1234*"        # Allow all numbers starting with +1234
    - "uuid-pattern*" # Allow UUIDs matching pattern
```

### Blocklist

Block specific members (takes precedence over allowlist):

```yaml
filters:
  blocklist:
    - "+15555551234"  # Block specific number
    - "*spam*"        # Block any UUID/number containing "spam"
```

### Rate Limiting

Prevent spam from the same user:

```yaml
filters:
  rate_limit:
    enabled: true
    max_requests: 10      # Max join requests
    window_seconds: 3600  # Within this time window
```

## Monitoring and Metrics

The bot tracks detailed metrics:

```bash
python main.py stats
```

Output includes:
- Total members messaged
- Messages sent/failed with success rate
- Approvals succeeded/failed
- Poll statistics
- Error breakdown by type

Metrics are automatically saved to `metrics.json` and displayed periodically in logs.

## Testing

Run the test suite:

```bash
python run_tests.py
```

Or with pytest (if installed):

```bash
pytest tests/ -v
```

## Getting the group ID

After signal-cli is running and your account is linked:

- Run `python main.py debug-group` to see the raw group object; use its `id` field (base64) as `group_id`
- Or use `python main.py list-pending` (any errors may show group-related info)
- The group `id` in signal-cli’s `listGroups` / `getGroup` response is the value you need (base64-encoded)

## Troubleshooting

### Connection Issues

- **"Could not connect to signal-cli"** – Ensure the signal-cli daemon is running (`signal-cli -u +NUMBER daemon --socket`) and socket path is correct
  - On Windows: Use TCP (e.g. `localhost:7583`) and a TCP bridge if needed; see [SIGNAL_CLI_SETUP.md](SIGNAL_CLI_SETUP.md)
  - Check firewall and that the port is listening

### Configuration Issues

- **"Configuration validation failed"** – Run `python main.py validate` to see specific errors
- **"get_group failed"** – Account must be admin of the group
- **"UnknownGroupError"** – Verify `group_id` is correct

### Message/Approval Issues

- **Messages not sending** – Check signal-cli output, verify account has a profile set in Signal
- **Rate limit errors** – Adjust `rate_limit` settings in config
- **Members being skipped** – Check filter rules (allowlist/blocklist)

### Debug Mode

Enable verbose logging for troubleshooting:

```bash
python main.py -v
```

Or set log file:

```bash
export SIGNALBOT_LOG_FILE=bot.log
python main.py -v
```

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

MIT License - see LICENSE file for details.
