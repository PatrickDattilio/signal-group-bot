# How to Set Up the signald Socket (Windows)

On Windows, the bot **cannot** use a Unix socket directly. You must connect to signald via **TCP** (host and port). This guide shows the simplest way.

---

## Option A: Docker – signald + TCP bridge (recommended)

This runs signald in Docker and exposes a **TCP port** so your bot on Windows can connect with `localhost:7583`.

### Step 1: Start signald and the TCP bridge

In PowerShell, from your SignalBot folder:

```powershell
cd C:\Users\Patrick\SignalBot
docker-compose -f docker-compose-signald.yml up -d
```

This starts:

- **signald** – stores its socket in a Docker volume
- **signald-tcp-bridge** – listens on port **7583** and forwards to that socket

### Step 2: Point the bot at the TCP address

Edit `config.yaml` and set the socket to the bridge:

```yaml
signald:
  socket_path: "localhost:7583"
```

### Step 3: Register or link your Signal account

First time only:

```powershell
# Link an existing Signal account (recommended)
docker exec -it signald signaldctl account link

# You'll see a QR code or link. Open Signal on your phone:
# Settings > Linked devices > Link new device
# Scan the QR code or use the link.
```

If you prefer to **register** a new number instead:

```powershell
docker exec -it signald signaldctl account register +1234567890
# Follow prompts (SMS verification, etc.)
```

### Step 4: Run the bot

```powershell
python main.py list-pending
# or
python main.py
```

---

## Option B: Use the “all-in-one” Docker Compose

If you want **both** signald and the bot inside Docker (no Python on Windows):

```powershell
docker-compose -f docker-compose-with-signald.yml up -d
```

Then the bot uses the socket inside Docker; you only need to set `socket_path` if you run the bot **outside** Docker (e.g. `python main.py` on Windows). For “bot in Docker” you keep the default socket path in the compose file.

---

## Option C: signald in WSL + TCP bridge

If you run signald in WSL instead of Docker:

1. In WSL, install and run signald (see signald docs).
2. In WSL, run a TCP bridge so Windows can connect:

   ```bash
   sudo apt install socat
   socat TCP-LISTEN:7583,fork UNIX-CONNECT:$XDG_RUNTIME_DIR/signald/signald.sock
   ```

   (Or use the actual socket path signald uses, e.g. `/var/run/signald/signald.sock`.)

3. From Windows, use in `config.yaml`:

   ```yaml
   signald:
     socket_path: "localhost:7583"
   ```

---

## Quick reference

| Where signald runs | What to put in config.yaml |
|--------------------|----------------------------|
| Docker + TCP bridge (Option A) | `socket_path: "localhost:7583"` |
| WSL + socat bridge (Option C)   | `socket_path: "localhost:7583"` |
| Bot and signald both in Docker | Use the compose file; socket path is set there |

---

## Troubleshooting

**“Connection refused” to localhost:7583**

- Check the bridge is running: `docker ps` (you should see `signald` and `signald-tcp-bridge`).
- Restart: `docker-compose -f docker-compose-signald.yml restart`

**“No such container: signald”**

- Start the stack first: `docker-compose -f docker-compose-signald.yml up -d`

**Getting your group ID**

After your account is linked:

```powershell
docker exec signald signaldctl group list +1XXXXXXXXXX
```

Use your number in E.164 format (with country code, e.g. +13013318644).
