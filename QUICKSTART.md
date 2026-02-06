# Quick Start Guide

Get SignalBot up and running in 5 minutes.

## Prerequisites Checklist

- [ ] Python 3.10+ installed
- [ ] signal-cli installed and daemon running (`signal-cli -u +NUMBER daemon --socket`)
- [ ] Signal account **linked** to signal-cli (`signal-cli -u +NUMBER link`)
- [ ] Account is admin of the target group
- [ ] Group has "Approve New Members" enabled

## Step 1: Install

```bash
# Clone or download the repository
cd SignalBot

# Create virtual environment
python -m venv .venv

# Activate virtual environment
.venv\Scripts\activate   # Windows
# source .venv/bin/activate   # Linux/macOS

# Install dependencies
pip install -r requirements.txt
```

## Step 2: Configure

```bash
# Copy example config
cp config.example.yaml config.yaml

# Edit config.yaml with your settings
# Minimum required:
#   - signal_cli.socket_path: e.g. localhost:7583 (see SIGNAL_CLI_SETUP.md)
#   - account: Your phone number (e.g., +12025551234)
#   - group_id: Your Signal group ID (base64)
#   - message: Welcome message to send
```

### Getting Your Group ID

With signal-cli daemon running and config set:

```bash
python main.py debug-group
```
Use the `id` field (base64) from the output as `group_id` in config.

Or run `python main.py list-pending`; errors may show group-related info.

## Step 3: Test

```bash
# Validate your configuration
python main.py validate

# Test in dry-run mode (no messages sent)
python main.py dry-run

# Check for pending members
python main.py list-pending
```

## Step 4: Run

```bash
# Start the bot
python main.py

# Or with verbose logging
python main.py -v

# Press Ctrl+C to stop gracefully
```

## Common Configuration Examples

### Manual Approval (Default)

Bot sends message, you approve manually in Signal app:

```yaml
account: "+12025551234"
group_id: "your-base64-group-id"
message: "Welcome! Please read our rules."
approval_mode: manual
poll_interval_seconds: 120
```

### Automatic Approval

Bot sends message and auto-approves:

```yaml
account: "+12025551234"
group_id: "your-base64-group-id"
message: "Welcome! You've been approved."
approval_mode: automatic
auto_approve_delay_seconds: 5  # Wait 5s after message
```

### With Blocklist

Block specific numbers:

```yaml
account: "+12025551234"
group_id: "your-base64-group-id"
message: "Welcome!"
approval_mode: manual

filters:
  blocklist:
    - "+15555551234"  # Block this number
    - "*spam*"        # Block any number/UUID containing "spam"
```

### With Rate Limiting

Prevent spam:

```yaml
account: "+12025551234"
group_id: "your-base64-group-id"
message: "Welcome!"

filters:
  rate_limit:
    enabled: true
    max_requests: 5       # Max 5 requests
    window_seconds: 3600  # Per hour
```

## Docker Quick Start

```bash
# Create config
cp config.example.yaml config.yaml
# Edit config.yaml

# Start with Docker Compose
docker-compose up -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

## Monitoring

```bash
# View statistics
python main.py stats

# Check logs (if logging to file)
tail -f bot.log
```

## Troubleshooting

### Can't connect to signald

```bash
# Check signald is running
ps aux | grep signald

# Check socket exists
ls -la /var/run/signald/signald.sock

# Try TCP connection instead
# In config.yaml:
signald:
  socket_path: "localhost:7583"
```

### Configuration errors

```bash
# Validate config
python main.py validate

# Common issues:
# - Account must start with + (E.164 format)
# - group_id must be base64 string
# - message cannot be empty
```

### No pending members showing

```bash
# Verify account is admin
# Check in Signal app: Group Info > Members > Your account should have "Admin" badge

# Verify group has join link enabled
# Check in Signal app: Group Info > Group link > "Approve new members" is ON
```

## Next Steps

- Read [README.md](README.md) for full documentation
- See [DOCKER.md](DOCKER.md) for production deployment
- Check [config.example.yaml](config.example.yaml) for all options
- Review [CHANGELOG.md](CHANGELOG.md) for version history

## Getting Help

1. Check the [Troubleshooting](README.md#troubleshooting) section
2. Enable verbose logging: `python main.py -v`
3. Review signald logs
4. Check configuration: `python main.py validate`

## Useful Commands Reference

```bash
python main.py              # Run bot
python main.py -v           # Run with verbose logging
python main.py dry-run      # Test without sending messages
python main.py validate     # Check configuration
python main.py list-pending # Show pending members
python main.py stats        # Show statistics
python main.py duplicate-signal-cli-config   # Copy signal-cli config so CLI can run alongside daemon (for approve fallback)
python main.py help         # Show help
```
