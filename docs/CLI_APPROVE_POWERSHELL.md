# signal-cli CLI and daemon: config lock

**Only one process can use a given signal-cli config directory.** If you run the CLI with the *same* config as the daemon (e.g. SignalBot’s daemon), you get:

```
INFO  SignalAccount - Config file is in use by another instance, waiting…
```

The CLI will block until the daemon is stopped.

**Workaround: use a duplicate config for the CLI.** Copy the signal-cli config to a second directory and point the CLI at that copy. Then the daemon uses the original and the CLI uses the copy, so both can run.

**Important:** The copy is a snapshot. If the CLI runs with a copy that doesn’t have the same groups as the daemon (e.g. you joined groups after copying), `updateGroup -g <id>` can **create a new group** instead of updating the real one. So create the copy **with the daemon stopped** so the copy is complete, and **re-run the copy** if you join new groups later.

1. **Create a copy of the config** (run with daemon stopped so the copy is complete and has the same groups). Copy the whole data directory, e.g. on Windows from `%APPDATA%\signal-cli` to `%APPDATA%\signal-cli-cli` (PowerShell: `Copy-Item -Recurse` after stopping the daemon).

2. **In config.yaml** under `signal_cli` set:
   - `cli_config_path: "<path-to-the-copy>"` (the duplicate directory you created)

Approve in the Web UI uses the CLI with this copy; no other option is needed.

---

# Running signal-cli updateGroup in PowerShell (when daemon is stopped)

If you stop the daemon and want to run the CLI manually:

In PowerShell, a quoted path is not run as a program by default. Use the **call operator** `&`:

```powershell
& "C:\path\to\signal-cli.CMD" -a "+12025551234" updateGroup -g "YOUR_GROUP_ID_BASE64" -m "MEMBER_UUID"
```

In **cmd.exe**, you can paste the command from the log as-is (no `&`).

---

# Troubleshooting

**"Duplicate config does not contain this group"** — The CLI (using the duplicate config) does not see the main or second group. Recopy the signal-cli data directory **with the daemon stopped** so the copy includes all groups (main and second), then try again.
