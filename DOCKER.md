# Docker deployment (Kotlin / signal-cli)

The root `Dockerfile` builds the app from `signalbot-kt/`, installs **signal-cli**, and sets the runtime user. The container entrypoint is `docker/entrypoint.sh`.

## Build

```bash
docker build -t signalbot .
```

## Config and data

- Mount your real config as **`/data/config.yaml`** (see `SIGNALBOT_CONFIG`).
- Persist **`/data`** for signal-cli state, SQLite (`SIGNALBOT_DB`), and logs.

Example `config.yaml` source: `signalbot-kt/src/main/resources/config.example.yaml`.

## Process model

1. Entrypoint starts **signal-cli daemon** on a loopback TCP port (default `127.0.0.1:7583`).
2. It sets **`SIGNAL_CLI_SOCKET`** for the JVM.
3. It runs **`java -jar /app/signalbot.jar`** — by default **UI only** (`ui`).

## Enable the polling bot on the same container

Set:

```bash
SIGNALBOT_MODE=run
```

That runs `signalbot run` (vetting loop + admin UI). See `docker/entrypoint.sh` for details. Set `SIGNALBOT_UI_HOST` if you override bind address (default is `0.0.0.0` in the entrypoint for PaaS).

## Health

The app exposes **`GET /health`**. The image `HEALTHCHECK` curls that path; set `PORT` / `SIGNALBOT_UI_PORT` consistently.

## Compose (minimal)

`docker-compose.yml` in this repo is a minimal example. For production, align environment with the entrypoint:

- `SIGNAL_CLI_DATA` — signal-cli data dir (default `/data/signal-cli`)
- `SIGNAL_CLI_TCP` — daemon listen address (default `127.0.0.1:7583`)

Do **not** use legacy **signald**-only compose snippets; this stack uses **signal-cli** per [SIGNAL_CLI_SETUP.md](SIGNAL_CLI_SETUP.md).

## Railway

See [DEPLOY_CLOUD.md](DEPLOY_CLOUD.md) and `railway.toml`. Mount a volume on `/data` and set env vars in the Railway dashboard.
