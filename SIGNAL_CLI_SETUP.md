# signal-cli Setup Guide

SignalBot uses **signal-cli** (not signald) to talk to Signal. This guide gets the signal-cli daemon running so the bot can connect.

---

## 1. Install signal-cli

- **Windows**: Download from [signal-cli releases](https://github.com/AsamK/signal-cli/releases) or use Scoop: `scoop install signal-cli`
- **Linux**: Use your package manager or download the release
- **macOS**: `brew install signal-cli` or download the release

---

## 2. Link your Signal account

One-time setup:

```bash
signal-cli -u +12025551234 link
```

In the Signal app on your phone: **Settings → Linked devices → Link new device**, then scan the QR code (or use the link) shown in the terminal.

---

## 3. Start the signal-cli daemon (socket mode)

**On Windows** (no DBus), use socket mode:

```powershell
signal-cli -u +12025551234 daemon --socket
```

The daemon listens on a **Unix socket** by default. On Windows you need a **TCP** port so the bot can connect.

### Option A: signal-cli with TCP (if your build supports it)

Some signal-cli builds allow binding to TCP. Check:

```powershell
signal-cli daemon --help
```

Look for `--tcp` or `--port`. If available:

```powershell
signal-cli -u +12025551234 daemon --socket --tcp 7583
```

Then in `config.yaml` set:

```yaml
signal_cli:
  socket_path: "localhost:7583"
```

### Option B: Run signal-cli in WSL and expose TCP

1. In WSL, install signal-cli and link your account.
2. Start the daemon: `signal-cli -u +NUMBER daemon --socket`
3. In another WSL terminal, bridge the socket to TCP:

   ```bash
   socat TCP-LISTEN:7583,fork,reuseaddr UNIX-CONNECT:$HOME/.local/share/signal-cli/socket
   ```

   (Adjust the socket path if your signal-cli uses a different one.)

4. From Windows, use in `config.yaml`:

   ```yaml
   signal_cli:
     socket_path: "localhost:7583"
   ```

### Option C: Use signal-cli `jsonRpc` (no daemon socket)

You can run the bot by spawning signal-cli and talking over stdin/stdout (JSON-RPC). That requires a small wrapper script; the default bot expects a socket. So for the simplest path, use Option A or B.

---

## 4. Configure the bot

In `config.yaml`:

```yaml
signal_cli:
  socket_path: "localhost:7583"   # TCP (Windows) or your TCP host:port

account: "+12025551234"
group_id: "YOUR_GROUP_ID_BASE64"
# ... rest of config
```

Environment variable override:

```powershell
$env:SIGNAL_CLI_SOCKET = "localhost:7583"
java -jar signalbot.jar list-requesting
```

---

## 5. Get your group ID

With the daemon running and config set:

```powershell
java -jar signalbot.jar debug-group
```

That prints the raw group object. Use the `id` field (base64) as `group_id` in config. You can also run `java -jar signalbot.jar list-groups` and take the group `id` from there.

---

## 6. Run the bot

```powershell
java -jar signalbot.jar list-requesting   # list users requesting to join
java -jar signalbot.jar run               # run the bot (+ UI; use --headless to skip UI)
```

---

## Troubleshooting

| Issue | What to do |
|-------|------------|
| `Cannot Resolve Session Bus Address` | Don’t use default daemon (DBus). Use `daemon --socket` (and TCP if on Windows). |
| `Connection refused` to localhost:7583 | Start the daemon (and TCP bridge if using Option B). Check firewall. |
| `account not found` | Link the account with `signal-cli -u +NUMBER link` and use that number in config. |
| `Group not found` | Confirm `group_id` is base64 and matches the group (e.g. from `debug-group` or listGroups). |
| No requesting members | Use `requestingMembers` (join-by-link queue). The bot uses that field. |

---

## Summary

1. Install signal-cli and **link** your number: `signal-cli -u +NUMBER link`
2. Start the daemon in **socket** mode: `signal-cli -u +NUMBER daemon --socket`
3. On Windows, expose TCP (daemon `--tcp` or socat in WSL) and set `signal_cli.socket_path: "localhost:7583"`
4. Set `account` and `group_id` in `config.yaml`, then run `java -jar signalbot.jar list-requesting` or `java -jar signalbot.jar run`
