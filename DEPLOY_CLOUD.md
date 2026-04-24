# Deploy SignalBot to Railway — full setup guide

A complete, opinionated walkthrough that takes you from zero to a running
SignalBot moderator on [Railway](https://railway.app). The result:

- The Kotlin bot (`signalbot.jar`) and signal-cli daemon running in one
  container, managed by `docker/entrypoint.sh`.
- A persistent volume at `/data` holding:
  - `config.yaml` — your bot settings
  - `signalbot.db` — SQLite state (messaged members, metrics, errors)
  - `signal-cli/` — your linked Signal account data (keys, databases)
- An HTTPS admin UI gated by username + bcrypt password hash.
- A `/health` endpoint Railway uses for zero-downtime rollouts.

> **Other hosts:** this repo also ships a `render.yaml` blueprint if you ever
> want to move off Railway. The steps below are Railway-specific but the
> environment variables and volume layout apply anywhere.

---

## 0. What you need before you start

- A **GitHub account** and this repo pushed to a fork you control.
- A **Railway account**. The free trial ($5 credit) is enough to validate the
  deploy; the bot idles comfortably in Railway's $5/mo starter plan.
- A **Signal account for the bot**. Recommended: a dedicated second number
  (cheap VoIP number works) that you can promote to admin of your target
  group. It cannot be the same number already running Signal on your main
  phone without re-linking.
- The bot account must be **admin** of the target group, and the group must
  have **Approve new members** turned on (Group Info → Group link).
- **signal-cli installed locally** (any OS) so you can link the account once.
  Local version does not need to match the containerized version; linking is
  forward-compatible.
- **Railway CLI** (recommended): `npm i -g @railway/cli`, or
  `scoop install railway` on Windows.

---

## 1. Link signal-cli locally

You will link the bot's phone number to a local signal-cli data directory,
then upload that directory to Railway's volume in step 7. The container
cannot do this itself — linking needs you to scan a QR code from your phone.

```bash
# Pick a directory outside the repo (and outside any synced cloud folder)
export SIGNAL_CLI_DATA="$HOME/signalbot-data/signal-cli"
mkdir -p "$SIGNAL_CLI_DATA"

signal-cli --config "$SIGNAL_CLI_DATA" link -n "SignalBot"
```

`link` prints a `sgnl://linkdevice?uuid=...` URL. On the phone that owns the
bot number:

1. Signal → **Settings** → **Linked devices** → **+** (iOS) or **Link new
   device** (Android).
2. Scan a QR code representing that URL. On Linux you can pipe the URL into
   `qrencode -t utf8`; on Windows any online QR generator works — the URL
   contains only ephemeral linking tokens.
3. Confirm on your phone. `signal-cli` exits with a success message.

After linking, `$SIGNAL_CLI_DATA/data/+YOURNUMBER` exists and contains the
session keys. Sanity-check:

```bash
signal-cli --config "$SIGNAL_CLI_DATA" -a "+YOURNUMBER" listGroups
```

Copy the **`id=`** of your target group — it is a base64 string roughly 44
characters long (example shape: `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`).
You'll need it in step 8.

> **Do not commit `signal-cli/` to git.** It contains Signal session keys.
> `.gitignore` already excludes it by default at the repo root, but don't
> copy it there.

---

## 2. Fork the repo and push to GitHub

Railway builds from a GitHub repository. If you're already working in your
own fork, skip the fork step.

```bash
git clone https://github.com/<you>/SignalBot.git
cd SignalBot
git push origin main
```

Confirm these files exist on the branch you'll deploy:

- `Dockerfile` (multi-stage, bundles signal-cli)
- `docker/entrypoint.sh`
- `railway.toml`
- `signalbot-kt/` (Kotlin sources and Gradle build)
- `.gitattributes` (ensures the shell script stays LF on checkout)

---

## 3. Create the Railway project

**Dashboard:**

1. [railway.app](https://railway.app) → **New Project** → **Deploy from
   GitHub repo**.
2. Pick your fork. Railway detects the `Dockerfile` and starts a build.
3. The first build will likely **fail health-check** because no volume or
   env vars exist yet — that's expected. Leave it; we fix it in the next
   steps.

**CLI equivalent:**

```bash
railway login
cd SignalBot
railway init        # creates a new project linked to this repo
```

### Service name — export `$SERVICE` once, reuse everywhere

Railway names the service **`web`** by default when you deploy from a
GitHub repo. Every `railway ssh` / `railway shell` / `railway logs` call
in the rest of this guide targets a service by name, so pick a shell
variable and use it:

```bash
# After `railway link` shows the service name (e.g. `web`):
export SERVICE=web
```

If you'd rather the name match the project, rename the service in the
dashboard under service → **Settings** → **Service Name** (your public
URL and volume survive the rename) and use `export SERVICE=signalbot`.

> Every subsequent `railway ... --service "$SERVICE"` command in this
> guide assumes `$SERVICE` is set in your shell.

### `railway run` vs `railway ssh` — which to use when

Railway has two superficially-similar commands that behave very
differently:

| Command | Runs where | Sees the volume? | Use for |
|---|---|---|---|
| `railway run -- cmd` | **On your laptop**, with Railway env vars injected | No | Local tooling that needs prod env vars (rare) |
| `railway ssh -- cmd` | **Inside the deployed container** | **Yes** | Anything that reads/writes `/data`, streams a tar in, runs `migrate-json`, etc. |

Everything below uses `railway ssh`, because every interesting command
touches `/data`. Don't swap it for `railway run` — you'll get confusing
`/data: No such file or directory` errors because your laptop doesn't
have `/data`.

---

## 4. Attach a persistent volume

Railway container filesystems are ephemeral. Without a volume, every redeploy
wipes your SQLite DB and signal-cli session keys (which would force you to
re-link on your phone each time).

Service → **Volumes** → **+ New Volume**:

| Field | Value |
|---|---|
| Mount path | `/data` |
| Size | `1 GB` (plenty; signal-cli data is typically ≤50 MB) |

Save. Railway remounts the volume on the next deploy.

---

## 5. Set environment variables

Service → **Variables** tab. Add the variables below. Railway supports
bulk-paste as `KEY=value` lines.

### 5a. Required

| Variable | Value | Purpose |
|---|---|---|
| `SIGNALBOT_SECRET_KEY` | `openssl rand -hex 32` output | Ktor session cookie signing. `FLASK_SECRET_KEY` is also accepted for legacy parity. |
| `SIGNALBOT_ADMIN_USERNAME` | e.g. `admin` | Username for the web UI login |
| `SIGNALBOT_ADMIN_PASSWORD_HASH` | bcrypt hash — see 5c | Password check |
| `SIGNALBOT_CONFIG` | `/data/config.yaml` | Path to bot YAML on the volume |
| `SIGNALBOT_DB` | `/data/signalbot.db` | SQLite file on the volume |
| `SIGNAL_CLI_DATA` | `/data/signal-cli` | Where the daemon reads the linked account |

The Dockerfile already sets sensible defaults for everything in this table,
but defining them explicitly in Railway makes the service self-documenting
and immune to image changes.

### 5b. Optional hardening

| Variable | Default | Notes |
|---|---|---|
| `SIGNALBOT_COOKIE_SECURE` | `1` | Enforces HTTPS-only session cookies. Keep `1` on Railway (Railway URLs are HTTPS). |
| `SIGNALBOT_LOGIN_MAX_ATTEMPTS` | `5` | Lockout threshold |
| `SIGNALBOT_LOGIN_WINDOW_SECONDS` | `900` | Attempt window |
| `SIGNALBOT_LOGIN_LOCKOUT_SECONDS` | `900` | Lockout duration |
| `SIGNALBOT_UI_HOST` | `0.0.0.0` (baked into the image) | Only change if you know what you're doing |

### 5c. Generate the admin password hash

The robust cross-platform way is to run the hash generator from a
scratch file — that avoids both bash's history-expansion trap (which
mangles passwords containing `!` or backticks in `-c` one-liners) and
MinTTY / Git Bash's broken `getpass` prompt:

```bash
cat > /tmp/hash.py <<'PY'
import bcrypt, getpass
pw = getpass.getpass("password: ")
print(bcrypt.hashpw(pw.encode(), bcrypt.gensalt(rounds=12)).decode())
PY
python3 /tmp/hash.py          # type password (hidden), get the hash
rm /tmp/hash.py
# -> $2b$12$abc... (paste the whole string into SIGNALBOT_ADMIN_PASSWORD_HASH)
```

If you prefer Werkzeug (PBKDF2) it's accepted by the same verifier —
swap the body of `/tmp/hash.py` for:

```python
from werkzeug.security import generate_password_hash
import getpass
print(generate_password_hash(getpass.getpass("password: ")))
```

#### One-liner variants per shell

If you'd rather not touch a scratch file:

- **Linux / macOS bash or zsh** — single quotes around `-c` to block
  history expansion:
  ```bash
  python3 -c 'import bcrypt, getpass; print(bcrypt.hashpw(getpass.getpass("password: ").encode(), bcrypt.gensalt(rounds=12)).decode())'
  ```

- **Git Bash on Windows (MinTTY)** — prefix with `winpty`, otherwise
  `getpass` appears to hang because MinTTY hides the prompt:
  ```bash
  winpty python3 -c 'import bcrypt, getpass; print(bcrypt.hashpw(getpass.getpass("password: ").encode(), bcrypt.gensalt(rounds=12)).decode())'
  ```
  If `winpty` is missing, drop `getpass` and use `input()` instead (the
  password will echo, so close the terminal afterwards):
  ```bash
  python3 -c 'import bcrypt; pw = input("password: "); print(bcrypt.hashpw(pw.encode(), bcrypt.gensalt(rounds=12)).decode())'
  ```

- **PowerShell** — quoting inverts; use double quotes outside, single
  inside:
  ```powershell
  python -c "import bcrypt, getpass; print(bcrypt.hashpw(getpass.getpass('password: ').encode(), bcrypt.gensalt(rounds=12)).decode())"
  ```

### 5d. Optional: migrating existing state (skip on a fresh install)

Only relevant if you already ran a previous SignalBot install and want
to carry forward `messaged.json` / `metrics.json` (so the bot doesn't
re-DM members you've already welcomed). Skip otherwise — the container
boots with an empty DB and builds state from Signal.

The current Railway CLI can't reliably stream stdin over `railway ssh`
(see §7 for the gory details), so the recommended route for the JSON
state is a one-shot env-var seed: `entrypoint.sh` decodes the variables
into `/data/messaged.json` and `/data/metrics.json` on first boot,
runs `migrate-json`, and never re-seeds once `/data/signalbot.db`
exists.

```bash
# In Git Bash / Linux / macOS:
base64 -w0 messaged.json > store.b64     # use -b0 on macOS
base64 -w0 metrics.json  > metrics.b64

wc -c store.b64 metrics.b64              # sanity-check sizes
```

| Variable | Value | Typical size |
|---|---|---|
| `SIGNALBOT_SEED_STORE_B64` | contents of `store.b64` | ≈ 4/3 × `messaged.json` |
| `SIGNALBOT_SEED_METRICS_B64` | contents of `metrics.b64` | usually <1 KB |

Paste each value into Railway → Variables. For values over a few KB,
click **Raw Editor** so you get a real textarea instead of a one-line
input.

> **Housekeeping:**
>
> - `.gitignore` covers `messaged.json` / `metrics.json` but not
>   `store.b64` / `metrics.b64`. Run `rm store.b64 metrics.b64` after
>   you've pasted them — they contain member identifiers.
> - **Delete both `SIGNALBOT_SEED_*_B64` variables from Railway after
>   the first successful boot** — they're no longer needed and the
>   service definition is cleaner without them.
>
> ```bash
> railway variables --service "$SERVICE" --remove SIGNALBOT_SEED_STORE_B64
> railway variables --service "$SERVICE" --remove SIGNALBOT_SEED_METRICS_B64
> ```

### 5e. Do NOT set

Railway auto-injects `PORT`. The Dockerfile's `CMD` honors it.

---

## 6. Bump the default signal-cli / Java version (optional)

The Dockerfile pins:

```
ARG SIGNAL_CLI_VERSION=0.14.3
FROM eclipse-temurin:25-jre
```

signal-cli v0.14+ requires Java 25; they move together. If you want a
different version, edit the `ARG` (and base image if needed) and commit —
Railway rebuilds automatically on push.

---

## 7. Upload the linked signal-cli data to the Railway volume

This is the step that derails most first-time setups. You're copying
`$SIGNAL_CLI_DATA` from step 1 into `/data/signal-cli` inside the container.

> **Why not just stream `tar` over `railway ssh`?**
> In theory this works:
> ```bash
> tar -czf - signal-cli | railway ssh --service "$SERVICE" "tar -xzf - -C /data"
> ```
> In practice, current Railway CLI builds (≤ 4.x as of Apr 2026)
> **don't forward stdin reliably over `ssh`**. The tarball pipe gets
> truncated and the remote `tar` errors with
> `You must specify one of the '-Acdtrux'` because it received no bytes.
> The base64-via-env-var route below avoids the stdin pipe entirely and
> is what actually works.

### Option A — base64 env var (works today)

For signal-cli data under ~45 KB linked (typical for a fresh-linked
device), this is the most reliable path. For larger payloads see
Option B.

```bash
# 1. On your laptop, encode the signal-cli dir to base64.
cd "$HOME/signalbot-data"          # signal-cli/ lives here as a sibling
ls                                 # should list: signal-cli
tar -czf - signal-cli | base64 -w0 > /tmp/signal-cli.tgz.b64
wc -c /tmp/signal-cli.tgz.b64      # Railway caps env vars at ~65 KB.
                                   # If bigger, go to Option B.
```

Paste the contents of `/tmp/signal-cli.tgz.b64` into a temporary Railway
variable named `SIGNAL_CLI_TARBALL_B64`:

- Railway UI → your service → **Variables** → **Raw Editor** →
  add `SIGNAL_CLI_TARBALL_B64=<paste the whole base64 string>`
- Or via CLI: `railway variables --service "$SERVICE" --set "SIGNAL_CLI_TARBALL_B64=$(cat /tmp/signal-cli.tgz.b64)"`

Railway will redeploy on the variable change. Once it's back up:

```bash
# 2. Attach to the container and unpack.
railway ssh --service "$SERVICE"
# inside the container:
echo "$SIGNAL_CLI_TARBALL_B64" | base64 -d | tar -xzf - -C /data
ls -la /data/signal-cli/data/      # expect +YOURNUMBER and +YOURNUMBER.d
exit
```

Then **delete the variable** so the tarball isn't sitting in Railway's
variable store indefinitely:

```bash
railway variables --service "$SERVICE" --remove SIGNAL_CLI_TARBALL_B64
# or: UI -> Variables -> trash the row
```

### Option B — transfer.sh / signed URL (for payloads > ~45 KB)

If your `signal-cli` directory has enough history to blow past Railway's
env-var cap, host the tarball at a short-lived URL and `curl` it from
inside the container:

```bash
# on your laptop: upload anywhere your container can curl from.
# transfer.sh is the easiest one-liner; S3 pre-signed URL is equivalent.
URL=$(tar -czf - signal-cli | curl --upload-file - https://transfer.sh/signal-cli.tgz)
echo "$URL"

# inside the container:
railway ssh --service "$SERVICE"
curl -fsSL "$URL" | tar -xzf - -C /data
ls -la /data/signal-cli/data/
exit

# transfer.sh expires in 14 days automatically; nothing to clean up.
```

> Common mistake: do **not** use `railway run` here. `railway run`
> executes the command on your laptop with Railway's env vars - not
> inside the container - so it has no `/data` and fails with
> `sh: cd: /data: No such file or directory`.

### Verify

```bash
railway ssh --service "$SERVICE" "ls -la /data/signal-cli/data/"
# expect a file named +YOURNUMBER and a per-account directory
```

---

## 8. Create `config.yaml` on the volume

Railway gives you a shell on the running container. Use a heredoc to write
your config directly onto `/data`:

```bash
railway shell --service "$SERVICE"
```

```bash
cat > /data/config.yaml <<'YAML'
signal_cli:
  # Matches SIGNAL_CLI_TCP baked into the image (127.0.0.1:7583).
  socket_path: "127.0.0.1:7583"

account: "+YOURNUMBER"              # the bot's linked number
group_id: "YOUR_GROUP_ID_BASE64"    # from step 1

message: |
  Welcome! Please read the group rules before posting.

  Request received: {{datetime}}

approval_mode: manual               # or: automatic
poll_interval_seconds: 120
cooldown_seconds: 86400

filters:
  blocklist: []
  rate_limit:
    enabled: false
YAML

cat /data/config.yaml               # sanity check
exit
```

See `signalbot-kt/src/main/resources/config.example.yaml` for the full
schema (allowlist, rate limits, follow-up messages, multi-group approve,
etc.).

---

## 9. Redeploy and watch logs

Push any no-op commit, or use the dashboard: service → **Deployments** →
**Redeploy**.

```bash
railway logs --service "$SERVICE" --follow
```

Healthy startup looks like:

```
[entrypoint] linked account: +YOURNUMBER
[entrypoint] starting signal-cli daemon on 127.0.0.1:7583
[entrypoint] signal-cli daemon is up (pid 42)
[entrypoint] launching SignalBot UI on port 5000
INFO  com.signalbot.web.Server - Listening on http://0.0.0.0:5000
INFO  com.signalbot.bot.Bot - Poll loop started, interval=120s
```

If you see `WARNING: no linked signal-cli account under /data/signal-cli/data/`,
your step-7 upload didn't land. Re-check `/data/signal-cli/data/+NUMBER`
exists.

---

## 10. Expose a domain and smoke-test

Service → **Settings** → **Networking** → **Generate Domain** gives you a
`*.up.railway.app` URL. Then:

```bash
curl -fsS https://<your-domain>/health
# -> {"ok":true}

open https://<your-domain>/login    # macOS; xdg-open on Linux
```

Log in → dashboard renders → click **Refresh requesting**. If your group has
pending members they show up here. Approve or deny one and confirm on your
phone that the action took effect.

If the bot already ran a poll cycle:

```bash
railway ssh --service "$SERVICE" -- java -jar /app/signalbot.jar stats
```

---

## 11. Lock it down

- **Delete scratch variables**: `SIGNALBOT_SEED_*_B64`, any
  `TARBALL_B64` you used in step 7.
- **Rotate the admin password** if you ever typed it into a terminal with
  history enabled (bash → `history -c`, zsh → `HISTFILE=/dev/null`).
- **Custom domain** (optional): Networking → **Custom Domain** → add your
  CNAME. Railway issues the TLS cert automatically.
- **Confirm `SIGNALBOT_COOKIE_SECURE=1`** is still set.

---

## 12. Day-2 operations

### Logs

```bash
railway logs --service "$SERVICE"           # last page
railway logs --service "$SERVICE" --follow  # tail
```

### Restart

Any redeploy (or commit) restarts the whole container. `tini` propagates
SIGTERM to both signal-cli and the bot, so there's no orphaned daemon.

```bash
railway redeploy --service "$SERVICE"
```

### Stats + dry runs

```bash
railway ssh --service "$SERVICE" -- java -jar /app/signalbot.jar stats
railway ssh --service "$SERVICE" -- java -jar /app/signalbot.jar list-requesting
railway ssh --service "$SERVICE" -- java -jar /app/signalbot.jar dry-run --verbose
```

### Volume backups

Railway doesn't auto-snapshot volumes. Once a week:

```bash
railway ssh --service "$SERVICE" -- \
  tar -czf - /data/signalbot.db /data/signal-cli \
  > signalbot-backup-$(date +%F).tgz
```

Keep the archive somewhere private — it contains Signal session keys.

### Upgrading signal-cli

Bump `ARG SIGNAL_CLI_VERSION=...` in the `Dockerfile` (and
`FROM eclipse-temurin:N-jre` if the minimum Java version changes — v0.14+
needs Java 25) and push. Railway rebuilds automatically.

### Re-linking Signal

Signal sometimes expires linked devices after long inactivity or on
server-side session rotations. Symptoms: signal-cli logs show
`UNREGISTERED` or `AuthorizationFailedException`.

To recover:

1. Re-run step 1 locally against the same `SIGNAL_CLI_DATA` directory (or a
   fresh one).
2. `railway shell --service "$SERVICE"` → `rm -rf /data/signal-cli/*`.
3. Re-upload per step 7.
4. Redeploy.

Your `signalbot.db` is preserved — only the Signal session is rebuilt, so
you don't re-DM anyone who was already messaged.

---

## 13. Troubleshooting

| Symptom | Likely cause → fix |
|---|---|
| `/health` never comes up; deploy marked unhealthy | First cold boot of signal-cli can take ~60s. `healthcheckTimeout` in `railway.toml` is 180s. If still failing, check logs for `signal-cli daemon exited during startup`. |
| `WARNING: no linked signal-cli account` in logs | `/data/signal-cli/data/+NUMBER` file missing — redo step 7. |
| `/api/requesting` returns 500 | signal-cli daemon crashed. `railway logs` shows the stack; `AuthorizationFailedException` → re-link (step 12). |
| UI returns "Account is not an admin" | In the Signal app, promote the bot's number to admin of the group. |
| Pending queue always empty | Group Info → Group link → **Approve new members** must be ON, and a join link must exist. |
| Bot connects but never sends messages | Check `approval_mode` in `config.yaml`; `manual` means the bot only DMs and waits for you to approve. |
| `config.yaml` edits ignored | Make sure `SIGNALBOT_CONFIG=/data/config.yaml` is set in Railway. The default in-image config is `/app/config.example.yaml`. |

Verbose logs from the bot itself:

```bash
railway variables --service "$SERVICE" --set LOG_LEVEL=DEBUG
railway redeploy  --service "$SERVICE"
```

---

## 14. Rollback

Railway keeps the last deploys indexed.

Service → **Deployments** → click a previous green build → **Redeploy**.

Your `/data` volume is not touched by rollbacks, so reverting is
zero-data-loss across every commit currently in the repo (no schema
migrations have shipped yet).

If a rollback *still* breaks, fall back to running locally from the same
commit — the container image is self-contained:

```bash
docker build -t signalbot:rollback .
docker run --rm -p 5000:5000 -v "$PWD/data:/data" \
  -e SIGNALBOT_SECRET_KEY=$(openssl rand -hex 32) \
  -e SIGNALBOT_ADMIN_USERNAME=admin \
  -e SIGNALBOT_ADMIN_PASSWORD_HASH='$2b$12$...' \
  signalbot:rollback
```

---

## 15. Directory / variable reference

### Paths inside the container

| Path | Contents |
|---|---|
| `/app/signalbot.jar` | The Kotlin fat jar |
| `/app/entrypoint.sh` | Launcher (signal-cli daemon + UI) |
| `/app/config.example.yaml` | Template only — **not** the live config |
| `/opt/signal-cli/` | signal-cli installation (from tarball) |
| `/data/config.yaml` | Your live config (on volume) |
| `/data/signalbot.db` | SQLite store (on volume) |
| `/data/signal-cli/` | Linked Signal account data (on volume) |

### All recognized environment variables

| Name | Default | Where it's read |
|---|---|---|
| `SIGNALBOT_CONFIG` | `/data/config.yaml` | `ConfigLoader` |
| `SIGNALBOT_DB` | `/data/signalbot.db` | `ConfigLoader`, `Database` |
| `SIGNALBOT_SECRET_KEY` / `FLASK_SECRET_KEY` | `change-this-in-production` | Ktor sessions (`Server.kt`) |
| `SIGNALBOT_ADMIN_USERNAME` | *empty* | Login gate (`Routes.kt`) |
| `SIGNALBOT_ADMIN_PASSWORD_HASH` | *empty* | Login gate |
| `SIGNALBOT_COOKIE_SECURE` | `1` | Cookie flags |
| `SIGNALBOT_LOGIN_MAX_ATTEMPTS` | `5` | Rate-limit login (`Auth.kt`) |
| `SIGNALBOT_LOGIN_WINDOW_SECONDS` | `900` | |
| `SIGNALBOT_LOGIN_LOCKOUT_SECONDS` | `900` | |
| `SIGNALBOT_UI_HOST` | `0.0.0.0` (image default) | UI bind |
| `SIGNALBOT_UI_PORT` / `PORT` | `5000` | UI bind |
| `SIGNAL_CLI_DATA` | `/data/signal-cli` | `entrypoint.sh` |
| `SIGNAL_CLI_TCP` | `127.0.0.1:7583` | `entrypoint.sh` (signal-cli daemon listen) |
| `SIGNAL_CLI_SOCKET` | set by `entrypoint.sh` to `$SIGNAL_CLI_TCP` | `SignalCliClient` |
| `SIGNAL_CLI_PATH` | *empty* | Overrides the CLI fallback binary path |
| `SIGNAL_CLI_CONFIG_PATH` | *empty* | Overrides CLI fallback config dir |
| `SIGNALBOT_SEED_STORE_B64` | *empty* | One-shot seed of `messaged.json` |
| `SIGNALBOT_SEED_METRICS_B64` | *empty* | One-shot seed of `metrics.json` |

---

## 16. Related docs

- [`CUTOVER.md`](CUTOVER.md) — moving an existing Python install to the
  Kotlin port without losing state.
- [`QUICKSTART.md`](QUICKSTART.md) — local developer setup (unit tests, dry
  run, validate).
- [`SIGNAL_CLI_SETUP.md`](SIGNAL_CLI_SETUP.md) — deeper signal-cli linking
  notes (Windows specifics, troubleshooting).
- [`signalbot-kt/src/main/resources/config.example.yaml`](signalbot-kt/src/main/resources/config.example.yaml)
  — the authoritative config schema with inline comments.
