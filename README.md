# Signal Invite Queue Bot

A bot for moderated **Signal** groups that use a public link with **Approve new members**. It can send vetting DMs, respect cooldowns and filters, optionally auto-approve, and includes an **admin web UI** (approve / deny / welcome) plus a **CLI**.

The implementation is **Kotlin/JVM** (`signalbot-kt/`), talking to **signal-cli** (JSON-RPC over TCP or socket). State is stored in **SQLite** (`SIGNALBOT_DB`).

## Features

- Automated messages to pending members (templates with `{{variables}}`)
- Manual or automatic approval
- Allowlist / blocklist and rate limiting
- Metrics and logging
- Dry-run mode
- Docker image (JRE + bundled `signal-bot` JAR + signal-cli)

## Prerequisites

- **signal-cli** — link the moderator account and run the daemon (see [SIGNAL_CLI_SETUP.md](SIGNAL_CLI_SETUP.md)).
- **JDK 21+** — to build locally; the Docker image ships a runtime JRE.

## Build and run (local)

```bash
cd signalbot-kt
./gradlew shadowJar
java -jar build/libs/signalbot.jar --help
```

Common commands:

```bash
# Web UI + bot loop in one process (default for interactive use)
java -jar build/libs/signalbot.jar run

# Admin UI only (no polling / auto-DMs)
java -jar build/libs/signalbot.jar ui --host 127.0.0.1 --port 5000

# Validate config.yaml
java -jar build/libs/signalbot.jar validate

# List pending join requests
java -jar build/libs/signalbot.jar list-requesting

# One-time import from legacy messaged.json / metrics.json into SQLite
java -jar build/libs/signalbot.jar migrate-json
```

Example config and env notes: `signalbot-kt/src/main/resources/config.example.yaml`.

## Configuration

1. Copy the example file to your config path (default: `config.yaml` next to the JAR, or set `SIGNALBOT_CONFIG`):

   `signalbot-kt/src/main/resources/config.example.yaml` → `config.yaml`

2. Set at least **account**, **group_id** (from `signal-cli listGroups` / `signalbot list-groups`), **message**, and **signal_cli** socket settings.

## Docker

```bash
docker build -t signalbot .
```

See [DOCKER.md](DOCKER.md) for env vars (`SIGNALBOT_MODE=run` to enable the polling bot in the container), volumes, and Railway.

## Cloud / Railway

Use [DEPLOY_CLOUD.md](DEPLOY_CLOUD.md) for hosted deployment. The production container entrypoint is `docker/entrypoint.sh` (signal-cli daemon + JAR). For bcrypt password hashing examples, that doc still has small `python3 -c` snippets; any Python 3 on your machine is fine (not part of this repo).

## Message templates, filters, env vars

Template variables, YAML for filters/rate limits, and environment variable names for the **Kotlin** app are documented in `signalbot-kt/src/main/resources/config.example.yaml` and [DEPLOY_CLOUD.md](DEPLOY_CLOUD.md) (auth / cookies).

## Tests

```bash
cd signalbot-kt
./gradlew test
```

## Getting the group ID

With signal-cli / the daemon running:

```bash
java -jar build/libs/signalbot.jar list-groups
# or
java -jar build/libs/signalbot.jar debug-group
```

Use the base64 `id` from the target group as `group_id` in config.

## Troubleshooting

- **Validation** — `signalbot validate` (Kotlin CLI)
- **signal-cli** — daemon running; socket path / TCP in config; account is a group admin
- **Verbose logging** — see comments in `docker/entrypoint.sh` and `SIGNALBOT_LOG_LEVEL`

## License

MIT — see [LICENSE](LICENSE).

## Contributing

Fork, branch, add tests in `signalbot-kt` (`./gradlew test`), and open a PR.
