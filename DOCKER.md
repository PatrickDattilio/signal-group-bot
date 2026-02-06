# Docker Deployment Guide

This guide explains how to run SignalBot using Docker.

## Prerequisites

1. **Docker and Docker Compose** installed
2. **signald** running and accessible (see options below)
3. **Configuration file** (`config.yaml`) ready

## Quick Start

### 1. Build the Image

```bash
docker build -t signalbot .
```

### 2. Prepare Configuration

Copy and edit the configuration file:

```bash
cp config.example.yaml config.yaml
# Edit config.yaml with your settings
```

### 3. Run with Docker Compose

The easiest way to run the bot:

```bash
docker-compose up -d
```

View logs:

```bash
docker-compose logs -f signalbot
```

Stop the bot:

```bash
docker-compose down
```

## signald Connection Options

### Option 1: Unix Socket (Linux/macOS)

If signald is running on the host, mount the socket:

```yaml
volumes:
  - /var/run/signald/signald.sock:/var/run/signald/signald.sock
```

Update `config.yaml`:

```yaml
signald:
  socket_path: /var/run/signald/signald.sock
```

### Option 2: TCP Bridge

If signald is not accessible via socket, use a TCP bridge:

1. Set up socat to bridge Unix socket to TCP:

```bash
socat TCP-LISTEN:7583,fork,reuseaddr UNIX-CONNECT:/var/run/signald/signald.sock
```

2. Update `config.yaml`:

```yaml
signald:
  socket_path: host.docker.internal:7583  # On Windows/Mac
  # or: socket_path: 172.17.0.1:7583     # On Linux
```

### Option 3: signald in Docker

Run both signald and the bot in Docker:

```yaml
version: '3.8'

services:
  signald:
    image: signald/signald
    container_name: signald
    volumes:
      - signald-data:/var/lib/signald
      - signald-socket:/var/run/signald
    restart: unless-stopped

  signalbot:
    build: .
    container_name: signalbot
    depends_on:
      - signald
    volumes:
      - ./config.yaml:/data/config.yaml:ro
      - signalbot-data:/data
      - signald-socket:/var/run/signald
    environment:
      - SIGNALD_SOCKET=/var/run/signald/signald.sock
    restart: unless-stopped

volumes:
  signald-data:
  signald-socket:
  signalbot-data:
```

## Running Commands

### Validate Configuration

```bash
docker-compose run --rm signalbot python main.py validate
```

### List Pending Members

```bash
docker-compose run --rm signalbot python main.py list-pending
```

### View Statistics

```bash
docker-compose run --rm signalbot python main.py stats
```

### Dry Run

```bash
docker-compose run --rm signalbot python main.py dry-run
```

## Data Persistence

Bot data is stored in a Docker volume (`signalbot-data`):

- `messaged.json` - Tracked members
- `metrics.json` - Bot metrics
- `bot.log` - Log file (if enabled)

To backup data:

```bash
docker cp signalbot:/data ./backup
```

## Environment Variables

You can override settings via environment variables in `docker-compose.yml`:

```yaml
environment:
  - SIGNALBOT_CONFIG=/data/config.yaml
  - SIGNALBOT_STORE=/data/messaged.json
  - SIGNALBOT_METRICS=/data/metrics.json
  - SIGNALBOT_LOG_FILE=/data/bot.log
  - SIGNALD_SOCKET=/var/run/signald/signald.sock
```

## Troubleshooting

### Bot can't connect to signald

1. Check signald is running:
   ```bash
   ls -la /var/run/signald/signald.sock
   ```

2. Verify socket path in config matches mounted path

3. Check container can access socket:
   ```bash
   docker-compose exec signalbot ls -la /var/run/signald/
   ```

### Permission issues

Ensure the signald socket has proper permissions:

```bash
chmod 666 /var/run/signald/signald.sock
```

Or run the container with the same user as signald.

### View detailed logs

Enable verbose logging:

```bash
docker-compose run --rm signalbot python main.py -v
```

Or edit `docker-compose.yml`:

```yaml
command: python main.py -v
```

## Production Deployment

For production:

1. **Use secrets management** for sensitive config
2. **Set up log rotation** (configured in docker-compose.yml)
3. **Monitor metrics** regularly
4. **Backup data volume** periodically
5. **Set resource limits**:

```yaml
services:
  signalbot:
    # ... other config ...
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 256M
        reservations:
          cpus: '0.1'
          memory: 128M
```

## Updating

To update the bot:

```bash
# Pull latest code
git pull

# Rebuild image
docker-compose build

# Restart with new image
docker-compose up -d
```

Data in the volume persists across updates.
