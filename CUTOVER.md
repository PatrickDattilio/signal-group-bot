# Python → Kotlin cutover checklist

This document describes how to move an existing production SignalBot install
from the Python implementation (root of this repo) to the Kotlin port
([`signalbot-kt/`](signalbot-kt/)) with **zero message loss** and a clean
rollback path. The wire behaviour (config schema, JSON API, UI, signal-cli
integration) is identical; only the persistence format changes
(`messaged.json` + `metrics.json` → a single SQLite file).

## 1. Prepare

- Check out this branch/commit on the prod host.
- Install JDK 21 (`eclipse-temurin-21-jre` is enough at runtime; the JDK is
  only needed on machines that build the JAR).
- Build the fat jar once:
  ```bash
  cd signalbot-kt
  ./gradlew shadowJar        # Windows: .\gradlew.bat shadowJar
  ls build/libs/signalbot.jar
  ```
  Copy `signalbot.jar` to the prod host (or build it there).

## 2. Dry run in parallel

Keep the Python bot running. In a **second** shell on the same host, run the
Kotlin bot in dry-run mode against the same config, but with a **separate**
database so it can't interfere with Python's `messaged.json`:

```bash
export SIGNALBOT_CONFIG=/data/config.yaml
export SIGNALBOT_DB=/tmp/signalbot-kt-dryrun.db
java -jar signalbot.jar validate
java -jar signalbot.jar dry-run --verbose
java -jar signalbot.jar list-requesting
java -jar signalbot.jar stats
```

Confirm the same set of requesting members appears and that the bot logs look
identical to the Python version. Hit the `/health` endpoint and log in to the
Ktor UI on a scratch port to verify the pages render.

```bash
SIGNALBOT_UI_PORT=5001 java -jar signalbot.jar ui
# in another shell:
curl -fsS localhost:5001/health
```

## 3. Migrate state

Stop the Python bot and UI. With Python offline, import the existing JSON
state into the new SQLite store (idempotent — safe to re-run):

```bash
export SIGNALBOT_STORE=/data/messaged.json
export SIGNALBOT_METRICS=/data/metrics.json
export SIGNALBOT_DB=/data/signalbot.db
java -jar signalbot.jar migrate-json
```

Spot-check:

```bash
java -jar signalbot.jar stats
sqlite3 /data/signalbot.db 'select count(*) from messaged; select key,value from metrics;'
```

## 4. Swap the runner

Replace the Python service unit / container / Procfile entry with the Kotlin
one. The deployment files at the repo root have already been rewritten:

- `Dockerfile` (multi-stage: builds the fat jar, runs on `eclipse-temurin:21-jre`)
- `Procfile` (`java -jar signalbot-kt/build/libs/signalbot.jar ui --host 0.0.0.0 --port ${PORT:-5000}`)
- `railway.toml`, `render.yaml` (updated start commands)

Start the Kotlin process (or redeploy on your PaaS) and watch logs.

## 5. Verify in prod

- `/health` returns `{"ok": true}`.
- `/api/requesting` returns the expected list.
- Send a test approval / welcome from the UI end-to-end.
- `java -jar signalbot.jar stats` shows non-zero counters after the first poll
  cycle.

Keep `messaged.json` / `metrics.json` on disk for **at least one week** as a
rollback artefact. If anything misbehaves you can always re-point the Python
bot at those files.

## 6. Remove Python sources (after validation)

Once you've run on Kotlin for a week without issues and are happy:

```bash
git rm -r main.py web_ui.py wsgi.py src/ tests/ requirements.txt
git rm config.example.yaml     # duplicated under signalbot-kt/src/main/resources/
git commit -m "Drop Python sources after Kotlin cutover"
```

(Leave `messaged.json` / `metrics.json` behind on disk; the Kotlin build
ignores them.) Keep `SIGNAL_CLI_SETUP.md`, `DEPLOY_CLOUD.md`, `QUICKSTART.md`,
and the `README.md` — they describe concerns (signal-cli setup, PaaS config)
that are still relevant. Update any step that references `python main.py …`
to `java -jar signalbot.jar …`.
