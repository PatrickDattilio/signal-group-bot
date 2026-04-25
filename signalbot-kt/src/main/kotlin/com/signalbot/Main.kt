package com.signalbot

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.signalbot.bot.MessageTemplate
import com.signalbot.bot.runBot
import com.signalbot.config.Config
import com.signalbot.config.ConfigLoader
import com.signalbot.signal.Member
import com.signalbot.signal.SignalCliClient
import com.signalbot.signal.SignalCliException
import com.signalbot.store.Database
import com.signalbot.store.JsonImport
import com.signalbot.store.MessagedStore
import com.signalbot.store.MetricsStore
import com.signalbot.web.WebAppContext
import com.signalbot.web.startWebServerAsync
import com.signalbot.web.startWebServerBlocking
import java.util.concurrent.atomic.AtomicBoolean
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    Database.connect(ConfigLoader.defaultDbPath())
    SignalBot().subcommands(
        RunCmd(),
        DryRunCmd(),
        ListRequestingCmd(),
        ListPendingCmd(),
        MarkRequestingAsMessagedCmd(),
        UiCmd(),
        ListGroupsCmd(),
        DebugGroupCmd(),
        CompareGroupsCmd(),
        AddMergedToGroupCmd(),
        StatsCmd(),
        ValidateCmd(),
        MigrateJsonCmd(),
    ).main(args)
}

class SignalBot : CliktCommand(name = "signalbot") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Signal invite-queue bot (Kotlin port)"

    override fun run() {}
}

private fun buildClient(config: Config) = SignalCliClient(config.signalCli)

private fun memberSummary(m: Member): String {
    val parts = mutableListOf<String>()
    if (m.uuid.isNotBlank()) parts += "uuid=${m.uuid}"
    if (m.number.isNotBlank()) parts += "number=${m.number}"
    return parts.joinToString(" ")
}

class RunCmd : CliktCommand(name = "run") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Run the bot (default action)"

    private val headless by option("--headless").flag(default = false)

    override fun run() {
        val config = ConfigLoader.load()
        runBlocking {
            val botJob = launch(Dispatchers.Default) {
                runBot(config)
            }
            val webServer = if (!headless) {
                val host = System.getenv("SIGNALBOT_UI_HOST") ?: "127.0.0.1"
                val port = (System.getenv("SIGNALBOT_UI_PORT") ?: System.getenv("PORT") ?: "5000").toInt()
                val ctx = WebAppContext(ConfigLoader.defaultConfigPath())
                startWebServerAsync(ctx, host, port)
            } else {
                null
            }
            val stopped = AtomicBoolean(false)
            fun shutdown() {
                if (!stopped.compareAndSet(false, true)) return
                logger.info { "Shutting down SignalBot (web server + bot job)" }
                runCatching {
                    webServer?.stop(1000, 5000)
                }.onFailure { e -> logger.warn(e) { "Error stopping web server" } }
                botJob.cancel()
            }
            val hook = Thread {
                shutdown()
            }
            Runtime.getRuntime().addShutdownHook(hook)
            try {
                botJob.join()
            } finally {
                runCatching {
                    Runtime.getRuntime().removeShutdownHook(hook)
                }
                shutdown()
            }
        }
    }
}

class DryRunCmd : CliktCommand(name = "dry-run") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Run the bot in dry-run mode (no messages sent)"

    override fun run() {
        val config = ConfigLoader.load()
        echo("Starting in DRY RUN mode (no messages will be sent)")
        runBlocking { runBot(config, dryRun = true) }
    }
}

class ListRequestingCmd : CliktCommand(name = "list-requesting") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "List requesting members (join requests via group link)"

    private val jsonOut by option("--json").flag(default = false)

    override fun run() {
        val config = ConfigLoader.load()
        val client = buildClient(config)
        val requesting = try {
            client.listPendingMembers(config.account, config.groupId)
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            if ("Group not found" in (e.message ?: "")) {
                System.err.println("Tip: Run 'signalbot list-groups' to see group IDs, then set group_id in config.yaml.")
            }
            exitProcess(1)
        }
        if (jsonOut) {
            val arr = JsonArray(requesting.map {
                buildJsonObject {
                    put("uuid", it.uuid)
                    put("number", it.number)
                }
            })
            println(arr.toString())
            return
        }
        if (requesting.isEmpty()) {
            println("No requesting members.")
            return
        }
        println("Requesting members (${requesting.size}):")
        requesting.forEachIndexed { i, m ->
            println("  ${i + 1}. ${memberSummary(m)}")
        }
    }
}

class ListPendingCmd : CliktCommand(name = "list-pending") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Alias for list-requesting"
    private val jsonOut by option("--json").flag(default = false)
    override fun run() {
        val delegate = ListRequestingCmd()
        val args = mutableListOf<String>()
        if (jsonOut) args += "--json"
        delegate.main(args)
    }
}

class MarkRequestingAsMessagedCmd : CliktCommand(name = "mark-requesting-as-messaged") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Mark current requesters as already messaged"

    override fun run() {
        val config = ConfigLoader.load()
        val client = buildClient(config)
        val requesting = try {
            client.listPendingMembers(config.account, config.groupId)
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        if (requesting.isEmpty()) {
            println("No requesting members to mark.")
            return
        }
        val store = MessagedStore()
        for (m in requesting) store.markMessaged(m)
        println("Marked ${requesting.size} requesting member(s) as already messaged.")
        println("When you run the bot, it will skip sending them the first message and will send follow-ups after cooldown if configured.")
    }
}

class UiCmd : CliktCommand(name = "ui") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Web UI: view requesters, status, approve (+ add to second group)"

    private val host by option("--host").default(System.getenv("SIGNALBOT_UI_HOST") ?: "127.0.0.1")
    private val port by option("--port").int().default(
        (System.getenv("SIGNALBOT_UI_PORT") ?: System.getenv("PORT") ?: "5000").toInt()
    )

    override fun run() {
        ConfigLoader.load()
        val ctx = WebAppContext(ConfigLoader.defaultConfigPath())
        echo("Starting UI at http://$host:$port")
        echo("Configure approve_add_to_group_id and optionally approve_add_to_group_id_2 in config.yaml to add approved members to additional groups.")
        startWebServerBlocking(ctx, host, port)
    }
}

class ListGroupsCmd : CliktCommand(name = "list-groups") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "List all groups (shows group IDs for config)"

    override fun run() {
        val config = ConfigLoader.load()
        val client = buildClient(config)
        val groups = try {
            client.listGroups()
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        if (groups.isEmpty()) {
            println("No groups found.")
            return
        }
        println("Groups (${groups.size}):")
        groups.forEachIndexed { i, g ->
            val gid = g["id"]?.jsonPrimitive?.contentOrNull
                ?: g["groupId"]?.jsonPrimitive?.contentOrNull ?: "(no id)"
            val name = g["name"]?.jsonPrimitive?.contentOrNull
                ?: g["title"]?.jsonPrimitive?.contentOrNull
                ?: g["groupName"]?.jsonPrimitive?.contentOrNull ?: "(no name)"
            println("  ${i + 1}. id=$gid")
            println("     name=$name")
        }
        println("\nUse the 'id' value as group_id in config.yaml")
    }
}

class DebugGroupCmd : CliktCommand(name = "debug-group") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Show raw group response (for troubleshooting)"

    override fun run() {
        val config = ConfigLoader.load()
        val client = buildClient(config)
        val resp = try {
            client.getGroup(config.account, config.groupId)
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        println("=== Top-level response keys ===")
        println("Keys: ${resp.keys.sorted()}")
        println()
        println("=== Full raw response (truncated to 6000 chars) ===")
        val raw = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), resp)
        println(if (raw.length > 6000) raw.substring(0, 6000) + "\n... (truncated)" else raw)
    }
}

class CompareGroupsCmd : CliktCommand(name = "compare-groups") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "List members in Chat but not in Rapid Response"

    private val showNames by option("--names").flag(default = false)

    override fun run() {
        val config = ConfigLoader.load()
        val chatGid = config.groupId.trim()
        val rapidGid = (config.approveAddToGroupId ?: "").trim()
        if (chatGid.isBlank() || rapidGid.isBlank()) {
            System.err.println("Set group_id (Chat) and approve_add_to_group_id (Rapid Response) in config.yaml for compare-groups.")
            exitProcess(1)
        }
        val client = buildClient(config)
        val chatMembers = try {
            client.listGroupMembers(config.account, chatGid)
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        val rapidMembers = try {
            client.listGroupMembers(config.account, rapidGid)
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        val rapidIds = buildSet {
            for (m in rapidMembers) {
                if (m.uuid.isNotBlank()) add(m.uuid)
                if (m.number.isNotBlank()) add(m.number)
            }
        }
        val onlyInChat = chatMembers.filter { m ->
            !(m.uuid.isNotBlank() && m.uuid in rapidIds || m.number.isNotBlank() && m.number in rapidIds)
        }
        val names: List<String?> = if (showNames && onlyInChat.isNotEmpty()) {
            client.getRecipientNames(config.account, onlyInChat, returnDebug = false).names
        } else emptyList()
        println("Members in Chat but not in Rapid Response (Chat = group_id, Rapid = approve_add_to_group_id): ${onlyInChat.size}")
        onlyInChat.forEachIndexed { i, m ->
            val namePart = if (showNames && i < names.size && names[i] != null) "  ${names[i]}" else ""
            val ident = m.number.ifBlank { m.uuid.ifBlank { "?" } }
            println("  $ident$namePart")
        }
    }
}

class AddMergedToGroupCmd : CliktCommand(name = "add-merged-to-group") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Add Chat+Rapid members to a target group"

    private val targetGroupId by argument().optional()
    private val dryRun by option("--dry-run", "-n").flag(default = false)
    private val onlyNew by option("--only-new").flag(default = false)

    override fun run() {
        val config = ConfigLoader.load()
        val chatGid = config.groupId.trim()
        val rapidGid = (config.approveAddToGroupId ?: "").trim()
        if (chatGid.isBlank() || rapidGid.isBlank()) {
            System.err.println("Set group_id (Chat) and approve_add_to_group_id (Rapid Response) in config.yaml.")
            exitProcess(1)
        }
        val targetId = targetGroupId?.trim().orEmpty()
        if (targetId.isBlank()) {
            System.err.println("Usage: signalbot add-merged-to-group <target_group_id> [--dry-run] [--only-new]")
            exitProcess(1)
        }
        val client = buildClient(config)
        val chatMembers = try { client.listGroupMembers(config.account, chatGid) }
            catch (e: SignalCliException) { System.err.println("Error: ${e.message}"); exitProcess(1) }
        val rapidMembers = try { client.listGroupMembers(config.account, rapidGid) }
            catch (e: SignalCliException) { System.err.println("Error: ${e.message}"); exitProcess(1) }

        val seen = mutableSetOf<String>()
        var merged = mutableListOf<Member>()
        for (m in chatMembers + rapidMembers) {
            val u = m.uuid
            val n = m.number
            if ((u.isNotBlank() && u in seen) || (n.isNotBlank() && n in seen)) continue
            if (u.isBlank() && n.isBlank()) continue
            if (u.isNotBlank()) seen.add(u)
            if (n.isNotBlank()) seen.add(n)
            merged.add(m)
        }

        if (onlyNew) {
            val existing = try { client.listGroupMembers(config.account, targetId) }
                catch (e: SignalCliException) {
                    System.err.println("Error listing target group members: ${e.message}")
                    exitProcess(1)
                }
            val existingIds = buildSet {
                for (m in existing) {
                    if (m.uuid.isNotBlank()) add(m.uuid)
                    if (m.number.isNotBlank()) add(m.number)
                }
            }
            val before = merged.size
            merged = merged.filter { m ->
                !((m.uuid.isNotBlank() && m.uuid in existingIds) || (m.number.isNotBlank() && m.number in existingIds))
            }.toMutableList()
            val skipped = before - merged.size
            if (skipped > 0) println("Skipping $skipped member(s) already in target group (${merged.size} to add).")
        }
        if (merged.isEmpty()) {
            println("No members to add.")
            return
        }
        if (dryRun) {
            println("Dry run: would add ${merged.size} member(s) to group ${targetId.take(24)}...")
            for (m in merged) println("  ${m.number.ifBlank { m.uuid.ifBlank { "?" } }}")
            return
        }
        try {
            client.addMembersToGroup(config.account, targetId, merged)
            println("Added ${merged.size} member(s) to target group.")
        } catch (e: SignalCliException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
    }
}

class StatsCmd : CliktCommand(name = "stats") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Show message statistics"

    override fun run() {
        println("=== Bot Statistics ===\n")
        val store = MessagedStore()
        val ss = store.getStats()
        println("Members Messaged:")
        println("  Total: ${ss.totalMembers}")
        println("  Last 24 hours: ${ss.last24h}")
        println("  Last 7 days: ${ss.last7d}")
        println("  Last 30 days: ${ss.last30d}")

        println()

        val metrics = MetricsStore(enabled = false) // read-only read of DB-backed counters
        val m = metrics.getStats()
        println("Bot Metrics:")
        println("  Uptime: ${"%.1f".format(m.uptimeHours)} hours")
        println("  Polls: ${m.pollsCompleted} completed, ${m.pollsFailed} failed")
        m.pollSuccessRate?.let { println("    Success rate: ${"%.1f".format(it)}%") }
        println("  Messages: ${m.messagesSent} sent, ${m.messagesFailed} failed")
        m.messageSuccessRate?.let { println("    Success rate: ${"%.1f".format(it)}%") }
        println("  Approvals: ${m.approvalsSucceeded} succeeded, ${m.approvalsFailed} failed")
        m.approvalSuccessRate?.let { println("    Success rate: ${"%.1f".format(it)}%") }
        if (m.errors.isNotEmpty()) {
            println("  Errors by type:")
            m.errors.entries.sortedByDescending { it.value }.forEach { (t, c) ->
                println("    $t: $c")
            }
        }
    }
}

class ValidateCmd : CliktCommand(name = "validate") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Validate configuration"

    override fun run() {
        val config = ConfigLoader.load()
        println("Configuration is valid!")
        println("  Account: ${config.account}")
        val gid = if (config.groupId.length > 20) "${config.groupId.take(20)}..." else config.groupId
        println("  Group ID: $gid")
        println("  Approval mode: ${config.approvalMode}")
        println("  Poll interval: ${config.pollIntervalSeconds}s")
    }
}

class MigrateJsonCmd : CliktCommand(name = "migrate-json") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Import legacy messaged.json and metrics.json into SQLite"

    private val storePath by option("--store")
        .default(System.getenv("SIGNALBOT_STORE") ?: "messaged.json")
    private val metricsPath by option("--metrics")
        .default(System.getenv("SIGNALBOT_METRICS") ?: "metrics.json")

    override fun run() {
        val result = JsonImport.run(storePath, metricsPath)
        println("Imported ${result.messagedImported} messaged entr(y/ies) and ${result.metricsImported} metrics value(s) into ${ConfigLoader.defaultDbPath()}.")
    }
}
