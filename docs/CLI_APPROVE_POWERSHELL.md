# signal-cli CLI and daemon: config lock

**Only one process can use a given signal-cli config directory.** If you run the CLI with the *same* config as the daemon (e.g. SignalBot’s daemon), you get:

```
INFO  SignalAccount - Config file is in use by another instance, waiting…
```

The CLI will block until the daemon is stopped.

**Workaround: use a duplicate config for the CLI.** Copy the signal-cli config to a second directory and point the CLI at that copy. Then the daemon uses the original and the CLI uses the copy, so both can run.

**Important:** The copy is a snapshot. If the CLI runs with a copy that doesn’t have the same groups as the daemon (e.g. you joined groups after copying), `updateGroup -g <id>` can **create a new group** instead of updating the real one. So create the copy **with the daemon stopped** so the copy is complete, and **re-run the copy** if you join new groups later.

1. **Create a copy of the config** (run with daemon stopped so the copy is complete and has the same groups):
   ```bash
   python main.py duplicate-signal-cli-config
   ```
   This copies the default signal-cli data dir (e.g. `%APPDATA%\signal-cli` on Windows) to a second path (e.g. `%APPDATA%\signal-cli-cli`). Optional: `python main.py duplicate-signal-cli-config <dest>` or `python main.py duplicate-signal-cli-config <source> <dest>`.

2. **In config.yaml** under `signal_cli` set:
   - `cli_config_path: "<path-to-the-copy>"` (the path printed by the command)

Approve in the Web UI uses the CLI with this copy; no other option is needed.

---

# Running signal-cli updateGroup in PowerShell (when daemon is stopped)

If you stop the daemon and want to run the CLI manually:

In PowerShell, a quoted path is not run as a program by default. Use the **call operator** `&`:

```powershell
& "C:\Users\Patrick\scoop\shims\signal-cli.CMD" -a "+13013318644" updateGroup -g "wwL5M+LvsBYiVSSCYZvgxAG4FPwfGNwpEJPHo5Wk9Xg=" -m "24295876-dc2c-4d43-9843-8f5e76904337"
```

In **cmd.exe**, you can paste the command from the log as-is (no `&`).

---

# Troubleshooting

**"Duplicate config does not contain this group"** — The CLI (using the duplicate config) does not see the main or second group. Re-run `python main.py duplicate-signal-cli-config` **with the daemon stopped** so the copy includes all groups (main and second), then try again.
