package com.signalbot.signal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Wrapper for running the signal-cli CLI as a subprocess (with duplicated-config path),
 * used for approve, bulk add, and send operations. Mirrors the CLI branches in
 * src/signal_cli_client.py.
 */
class SignalCliSubprocess(
    private val cliPath: String?,
    val cliConfigPath: String?,
) {
    private val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun resolveExe(): String {
        val base = cliPath ?: which("signal-cli")
        ?: throw SignalCliException("CLI requires signal-cli on PATH or signal_cli.cli_path in config.")
        if (!isWindows) return base
        val upper = base.uppercase()
        if (!(upper.endsWith(".CMD") || upper.endsWith(".BAT"))) return base
        val parent = File(base).parentFile ?: return base
        val candidates = listOf(
            File(parent, "signal-cli.exe"),
            File(File(parent.parentFile ?: parent, "apps"), "signal-cli/current/signal-cli.exe"),
        )
        return candidates.firstOrNull { it.isFile }?.absolutePath ?: base
    }

    private fun which(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val exts = if (isWindows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
        for (dir in path.split(File.pathSeparator)) {
            for (ext in exts) {
                val f = File(dir, name + ext)
                if (f.isFile) return f.absolutePath
            }
        }
        return null
    }

    data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(account: String, args: List<String>, input: String? = null, timeoutSec: Int = 30): CliResult {
        val exe = resolveExe()
        val cmd = mutableListOf(exe)
        if (!cliConfigPath.isNullOrBlank()) {
            cmd += "-c"
            cmd += cliConfigPath
        }
        cmd += "-a"
        cmd += account.trim()
        cmd += args

        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        val proc = pb.start()
        if (input != null) {
            proc.outputStream.use { os -> os.write(input.toByteArray(StandardCharsets.UTF_8)) }
        } else {
            proc.outputStream.close()
        }
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw SignalCliException("signal-cli command timed out after ${timeoutSec}s")
        }
        val stdout = proc.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val stderr = proc.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)
        return CliResult(proc.exitValue(), stdout, stderr)
    }

    fun sendMessage(account: String, recipient: Member, body: String): Map<String, Any?> {
        val rcpt = recipient.number.trim().ifBlank { recipient.uuid.trim() }
        if (rcpt.isBlank()) throw SignalCliException("send_message: recipient number or uuid required")
        val res = try {
            run(account, listOf("send", rcpt, "--message-from-stdin"), input = body, timeoutSec = 30)
        } catch (e: java.io.IOException) {
            throw SignalCliException("signal-cli send failed: ${e.message}")
        }
        if (res.exitCode != 0) {
            val err = (res.stderr.ifBlank { res.stdout }).ifBlank { "exit code ${res.exitCode}" }
            throw SignalCliException("signal-cli send failed: ${err.trim()}")
        }
        return emptyMap()
    }

    fun listGroups(account: String): List<JsonObject> {
        if (cliConfigPath.isNullOrBlank()) return emptyList()
        val attempts = listOf(
            listOf("--output=json", "listGroups"),
            listOf("listGroups"),
        )
        var lastProc: CliResult? = null
        for (args in attempts) {
            val res = try {
                run(account, args, timeoutSec = 25)
            } catch (_: Exception) {
                continue
            }
            lastProc = res
            if (res.exitCode != 0) continue
            val out = res.stdout.trim()
            if (out.isBlank()) continue
            val parsed = try {
                Json.parseToJsonElement(out)
            } catch (_: Exception) {
                if (args[0].startsWith("--output")) continue
                null
            }
            if (parsed != null) {
                return when (parsed) {
                    is JsonArray -> parsed.mapNotNull { it as? JsonObject }
                    is JsonObject -> {
                        for (key in listOf("groups", "groupList", "data")) {
                            (parsed[key] as? JsonArray)?.let { arr ->
                                return arr.mapNotNull { it as? JsonObject }
                            }
                        }
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            break
        }
        val groups = mutableListOf<JsonObject>()
        if (lastProc != null && lastProc.exitCode == 0 && lastProc.stdout.isNotBlank()) {
            val pattern = Regex("""Id:\s*(\S+)""")
            for (line in lastProc.stdout.lineSequence()) {
                val m = pattern.find(line) ?: continue
                val gid = m.groupValues[1].trim()
                groups += buildJsonObject {
                    put("id", gid)
                    put("groupId", gid)
                    put("name", "")
                    put("title", "")
                }
            }
        }
        return groups
    }

    fun getGroup(account: String, groupId: String): JsonObject? {
        if (cliConfigPath.isNullOrBlank()) return null
        val want = normalize(groupId)
        for (g in listGroups(account)) {
            val gid = (g["id"]?.jsonPrimitive?.contentOrNull ?: g["groupId"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            if (normalize(gid) == want || gid == groupId.trim()) return g
        }
        return null
    }

    fun hasGroup(account: String, groupId: String): Boolean {
        if (groupId.trim().isEmpty()) return false
        val ids = listGroups(account).mapNotNull { g ->
            val gid = g["id"]?.jsonPrimitive?.contentOrNull ?: g["groupId"]?.jsonPrimitive?.contentOrNull
            gid?.let { normalize(it) }
        }.filter { it.isNotBlank() }
        val want = normalize(groupId)
        return ids.isNotEmpty() && want in ids
    }

    fun updateGroupAddMembers(account: String, groupId: String, members: List<Member>, logPrefix: String = "updateGroup"): JsonElement? {
        if (groupId.trim().isEmpty()) throw SignalCliException("group_id is required for updateGroup.")
        if (!cliConfigPath.isNullOrBlank() && !hasGroup(account, groupId)) {
            throw SignalCliException(
                "Duplicate config does not contain this group. Re-run 'duplicate-signal-cli-config' (with daemon stopped), then try again."
            )
        }
        val accountNormalized = account.trim()
        val memberIds = members.mapNotNull { m ->
            val n = m.number.trim()
            val u = m.uuid.trim()
            val id = n.ifBlank { u }
            if (id.isBlank() || id == accountNormalized) null else id
        }
        if (memberIds.isEmpty()) throw SignalCliException("No member uuid/number for updateGroup.")

        val args = mutableListOf("updateGroup", "-g", groupId.trim())
        for (mid in memberIds) {
            args += "-m"
            args += mid
        }
        val res = try {
            run(account, args, timeoutSec = 30)
        } catch (e: java.io.IOException) {
            throw SignalCliException("signal-cli executable not found: ${e.message}")
        }
        if (res.exitCode != 0) {
            val err = (res.stderr.ifBlank { res.stdout }).ifBlank { "exit code ${res.exitCode}" }
            throw SignalCliException("signal-cli updateGroup failed: ${err.trim()}")
        }
        logger.debug { "$logPrefix: ok ($accountNormalized -> $groupId, ${memberIds.size} member(s))" }
        return null
    }

    private fun normalize(gid: String?): String {
        if (gid.isNullOrBlank()) return ""
        return gid.trim().trimEnd('=').trim()
    }
}
