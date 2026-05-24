# SignalBot

[← Back to project catalog](../PROJECTS.md)

| | |
| :--- | :--- |
| **Path** | `C:\Users\Patrick\SignalBot` |
| **Short description** | Signal Invite Queue Bot |
| **Last file activity (scan)** | 2026-04-27 14:22:05 |

---
## What it does

SignalBot is a **moderated Signal group invite-queue assistant** for groups that use a public link with **“Approve new members.”** It polls pending join requests (via **signal-cli**), can send **vetting DMs** from templates, applies **allow/block lists, rate limits, and cooldowns**, and supports **manual or automatic approval**. It exposes an **admin web UI** (approve/deny/welcome) plus a **CLI**, and persists runtime state in **SQLite** (`SIGNALBOT_DB`). The app is a **Kotlin/JVM** program that talks to the **signal-cli** daemon over **JSON-RPC** (TCP or Unix socket).

*Source: `C:\Users\Patrick\SignalBot\README.md` (lines 1–5 and project layout).*

## Tech stack and key dependencies

| Area | Details |
|------|--------|
| **Language / runtime** | Kotlin **2.0.21**, JVM toolchain **21** (`signalbot-kt/build.gradle.kts`) |
| **Build** | Gradle, **Shadow** fat JAR (`com.gradleup.shadow`), main class `com.signalbot.MainKt` |
| **HTTP / admin UI** | **Ktor 3.0.3** (`ktor-server-netty`, sessions, status pages, call logging, content negotiation, kotlinx-json) |
| **Concurrency** | **kotlinx-coroutines** 1.9.0 |
| **Config / serialization** | **kaml** (YAML), **kotlinx-serialization-json** |
| **Database** | **Exposed** 0.57.0 + **SQLite JDBC** |
| **Auth** | **bcrypt** (password hashing for UI) |
| **CLI** | **Clikt** 5.0.1 |
| **Logging** | **Logback**, **kotlin-logging-jvm** |
| **External** | **signal-cli** (daemon JSON-RPC); Docker builds bundle/patch signal-cli (`C:\Users\Patrick\SignalBot\Dockerfile`) |

*Primary dependency manifest: `C:\Users\Patrick\SignalBot\signalbot-kt\build.gradle.kts`.*

## Main entry points / modules

| Role | Path |
|------|------|
| **Application entry** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\Main.kt` — `main()`, registers **Clikt** subcommands: `run`, `DryRunCmd`, list/requesting/pending, `ui`, `validate`, `migrate-json`, etc. |
| **Bot loop** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\bot\Bot.kt` — `runBot()` |
| **signal-cli integration** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\signal\SignalCliClient.kt` (+ `SignalCliSubprocess.kt`, `Models.kt`) |
| **Web UI** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\web\Server.kt`, `Routes.kt`, `Auth.kt`, `Templates.kt` |
| **Persistence** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\store\Database.kt`, `MessagedStore.kt`, `MetricsStore.kt`, `JsonImport.kt` |
| **Configuration** | `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\kotlin\com\signalbot\config\Config.kt`, `ConfigLoader.kt`; example: `C:\Users\Patrick\SignalBot\signalbot-kt\src\main\resources\config.example.yaml` |
| **Docker / ops** | `C:\Users\Patrick\SignalBot\Dockerfile`, `C:\Users\Patrick\SignalBot\docker\entrypoint.sh`, `C:\Users\Patrick\SignalBot\docker-compose.yml` |

## Notable architecture

- **CLI shell**: Everything hangs off **Clikt** in `Main.kt`; `run` starts **`runBot`** in a coroutine and optionally starts the **Ktor/Netty** UI asynchronously (`startWebServerAsync`); `--headless` skips the web server.
- **Integration boundary**: **`SignalCliClient`** encapsulates **JSON-RPC 2.0** over **TCP or Unix domain socket** (with optional subprocess fallback paths documented in that file).
- **Bot logic**: **`runBot`** is a **polling loop** with configurable intervals; it uses **`MessagedStore`** / **`MetricsStore`** for deduplication, cooldowns, and metrics; **`MemberFilter`** + optional **`RateLimiter`** enforce lists and limits (`Bot.kt`).
- **Persistence**: Single **SQLite** file via **Exposed** schema creation/migrations in **`Database.kt`** (replacing legacy JSON import via **`migrate-json`**).
- **Web layer**: **`WebAppContext`** caches **`SignalCliClient`** per loaded config; Ktor modules wire routes, sessions, and JSON (`Server.kt`).

**completed_subtitle (for UI):** Surveyed SignalBot Kotlin layout
