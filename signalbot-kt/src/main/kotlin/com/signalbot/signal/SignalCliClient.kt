package com.signalbot.signal

import com.signalbot.config.SignalCliConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * Client for signal-cli daemon (JSON-RPC 2.0 over TCP/Unix socket).
 * Mirrors src/signal_cli_client.py from the Python implementation.
 *
 * For approve/add/send fallbacks using the CLI subprocess, see SignalCliSubprocess.
 */
class SignalCliClient(
    socketPath: String? = null,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000,
    private val timeoutMs: Int = 30_000,
    cliPath: String? = null,
    val tryCliFallbackForApprove: Boolean = false,
    cliConfigPath: String? = null,
) {
    val socketPath: String = socketPath
        ?: System.getenv("SIGNAL_CLI_SOCKET")
        ?: System.getenv("SIGNALD_SOCKET")
        ?: "localhost:7583"

    val cliPath: String? = (cliPath ?: System.getenv("SIGNAL_CLI_PATH"))?.trim()?.ifEmpty { null }
    val cliConfigPath: String? = (cliConfigPath ?: System.getenv("SIGNAL_CLI_CONFIG_PATH"))?.trim()?.ifEmpty { null }

    private val subprocess by lazy { SignalCliSubprocess(this.cliPath, this.cliConfigPath) }

    constructor(cfg: SignalCliConfig) : this(
        socketPath = cfg.socketPath,
        maxRetries = cfg.maxRetries,
        retryDelayMs = (cfg.retryDelay * 1000).toLong(),
        timeoutMs = cfg.timeout * 1000,
        cliPath = cfg.cliPath,
        tryCliFallbackForApprove = cfg.tryCliFallbackForApprove,
        cliConfigPath = cfg.cliConfigPath,
    )

    private fun nextId(): String = UUID.randomUUID().toString()

    private fun isTcp(): Boolean = socketPath.contains(":") && !socketPath.startsWith("/")

    /** Open a connection, send one request, read one line, return the JSON response. */
    private fun sendOnce(body: String): String {
        val msg = (body + "\n").toByteArray(StandardCharsets.UTF_8)
        return if (isTcp()) {
            val (host, port) = socketPath.substringBeforeLast(":").trim() to
                socketPath.substringAfterLast(":").toInt()
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                logger.debug { "Connected to signal-cli via TCP: $host:$port" }
                sock.getOutputStream().write(msg)
                sock.getOutputStream().flush()
                readLine(BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)))
            }
        } else {
            val os = System.getProperty("os.name")?.lowercase() ?: ""
            if (os.contains("win")) {
                throw SignalCliConnectionException(
                    "Unix sockets are not supported on Windows. " +
                        "Set signal_cli.socket_path to a TCP address (e.g. localhost:7583), " +
                        "or set SIGNAL_CLI_SOCKET=localhost:7583."
                )
            }
            val addr = UnixDomainSocketAddress.of(socketPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { chan ->
                chan.connect(addr)
                logger.debug { "Connected to signal-cli via Unix socket: $socketPath" }
                val buf = ByteBuffer.wrap(msg)
                while (buf.hasRemaining()) chan.write(buf)
                val reader = BufferedReader(Channels.newReader(chan, StandardCharsets.UTF_8))
                readLine(reader)
            }
        }
    }

    private fun readLine(reader: BufferedReader): String {
        val line = reader.readLine()
            ?: throw SignalCliConnectionException("Connection closed before response")
        return line
    }

    /** Send JSON-RPC 2.0 request; returns the 'result' field. Throws SignalCliException on error. */
    fun call(method: String, params: JsonObject = buildJsonObject {}, retries: Int = maxRetries): JsonElement? {
        val reqId = nextId()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", reqId)
            put("method", method)
            put("params", params)
        }.toString()

        var lastError: RuntimeException? = null
        for (attempt in 0..retries) {
            try {
                val line = sendOnce(body)
                val parsed = Json.parseToJsonElement(line).jsonObject
                val respId = parsed["id"]?.jsonPrimitive?.contentOrNull
                if (respId != reqId) {
                    logger.warn { "Response id $respId != request id $reqId" }
                }
                parsed["error"]?.let { errEl ->
                    val errObj = errEl.jsonObject
                    val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: errObj.toString()
                    throw SignalCliException(msg, errObj.mapValues { it.value })
                }
                return parsed["result"]
            } catch (e: SignalCliConnectionException) {
                lastError = e
                if (attempt < retries) {
                    val delay = (retryDelayMs * 2.0.pow(attempt.toDouble())).toLong()
                    logger.warn { "Request failed (attempt ${attempt + 1}/${retries + 1}): ${e.message}. Retrying in ${delay}ms" }
                    Thread.sleep(delay)
                }
            } catch (e: java.net.SocketException) {
                lastError = SignalCliConnectionException("Connection error: ${e.message}")
                if (attempt < retries) {
                    val delay = (retryDelayMs * 2.0.pow(attempt.toDouble())).toLong()
                    Thread.sleep(delay)
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = SignalCliConnectionException("Connection timeout: ${e.message}")
                if (attempt < retries) {
                    val delay = (retryDelayMs * 2.0.pow(attempt.toDouble())).toLong()
                    Thread.sleep(delay)
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SignalCliException("Invalid response: ${e.message}")
            } catch (e: SignalCliException) {
                throw e
            } catch (e: java.io.IOException) {
                lastError = SignalCliConnectionException("IO error: ${e.message}")
                if (attempt < retries) {
                    val delay = (retryDelayMs * 2.0.pow(attempt.toDouble())).toLong()
                    Thread.sleep(delay)
                }
            }
        }
        throw (lastError ?: SignalCliException("Request failed"))
    }

    fun normalizeGroupId(gid: String?): String {
        if (gid.isNullOrBlank()) return ""
        return gid.trim().trimEnd('=').trim()
    }

    fun getAccountUuid(accountNumber: String): String? {
        val clean = accountNumber.trim().replace(" ", "")
        if (clean.isEmpty()) return null
        val result = try {
            call("listAccounts", retries = 0)
        } catch (_: Exception) {
            return null
        } ?: return null

        val accounts: JsonArray? = when (result) {
            is JsonArray -> result
            is JsonObject -> (result["accounts"] as? JsonArray) ?: (result["accountList"] as? JsonArray)
            else -> null
        }
        if (accounts == null) return null
        for (acc in accounts) {
            val obj = acc as? JsonObject ?: continue
            val num = obj["number"]?.jsonPrimitive?.contentOrNull?.trim()?.replace(" ", "") ?: ""
            val uid = (obj["uuid"]?.jsonPrimitive?.contentOrNull ?: obj["accountUUID"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            if (num == clean && uid.isNotEmpty()) return uid
        }
        return null
    }

    fun listGroups(account: String? = null): List<JsonObject> {
        if (cliConfigPath != null && account != null) {
            val cliGroups = subprocess.listGroups(account)
            if (cliGroups.isNotEmpty()) return cliGroups
        }
        val result = call("listGroups") ?: return emptyList()
        return when (result) {
            is JsonArray -> result.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                for (key in listOf("groups", "groupList", "data")) {
                    (result[key] as? JsonArray)?.let { arr ->
                        return arr.mapNotNull { it as? JsonObject }
                    }
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun getGroup(account: String, groupId: String, useDaemon: Boolean = false): JsonObject {
        if (cliConfigPath != null && !useDaemon) {
            val g = subprocess.getGroup(account, groupId)
            if (g != null) return g
        }
        for (paramName in listOf("groupId", "group_id")) {
            try {
                val result = call("getGroup", buildJsonObject { put(paramName, groupId) })
                if (result is JsonObject) return result
            } catch (_: SignalCliException) {
                continue
            }
        }
        try {
            val groups = listGroups(account)
            val want = normalizeGroupId(groupId)
            for (g in groups) {
                val gid = (g["id"]?.jsonPrimitive?.contentOrNull ?: g["groupId"]?.jsonPrimitive?.contentOrNull ?: "")
                if (normalizeGroupId(gid) == want) return g
                if (gid.trim() == groupId.trim()) return g
            }
        } catch (_: SignalCliException) {
        }
        throw SignalCliException(
            "Group not found: $groupId. " +
                "Run 'list-groups' to see available group IDs and set group_id in config.yaml."
        )
    }

    fun listPendingMembers(account: String, groupId: String): List<Member> {
        val group = getGroup(account, groupId, useDaemon = true)
        val requesting = (group["requestingMembers"] as? JsonArray)
            ?: (group["requesting_members"] as? JsonArray)
            ?: JsonArray(emptyList())
        return requesting.mapNotNull { addr ->
            val obj = addr as? JsonObject ?: return@mapNotNull null
            Member(
                uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                number = obj["number"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    fun listGroupMembers(account: String, groupId: String): List<Member> {
        val group = getGroup(account, groupId)
        val merged = buildMap {
            for ((k, v) in group) put(k, v)
            (group["data"] as? JsonObject)?.forEach { k, v -> put(k, v) }
        }
        var raw: JsonArray? = (merged["members"] as? JsonArray) ?: (merged["member_list"] as? JsonArray)
        if (raw == null || raw.isEmpty()) {
            val memberDetail = merged["memberDetail"] as? JsonArray
            if (memberDetail != null) {
                raw = JsonArray(memberDetail.mapNotNull { d ->
                    val obj = d as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        put("uuid", obj["uuid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
                        put("number", obj["number"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
                    }
                })
            }
        }
        if (raw == null) return emptyList()
        val accountNormalized = account.trim().replace(" ", "")
        val accountUuid = getAccountUuid(account)?.trim().orEmpty()
        return raw.mapNotNull { addr ->
            val obj = addr as? JsonObject ?: return@mapNotNull null
            val u = obj["uuid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val n = obj["number"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (u.isEmpty() && n.isEmpty()) return@mapNotNull null
            if (accountUuid.isNotEmpty() && u == accountUuid) return@mapNotNull null
            if (accountNormalized.isNotEmpty() && n.replace(" ", "") == accountNormalized) return@mapNotNull null
            Member(uuid = u, number = n)
        }
    }

    fun sendMessage(account: String, recipient: Member, body: String): JsonElement? {
        if (cliConfigPath != null && recipient.number.isNotBlank()) {
            subprocess.sendMessage(account, recipient, body)
            return null
        }
        val rid = recipient.number.ifBlank { recipient.uuid }
        val recipientArr = if (rid.isBlank()) JsonArray(emptyList()) else JsonArray(listOf(JsonPrimitive(rid)))
        val params = buildJsonObject {
            put("recipient", recipientArr)
            put("message", body)
        }
        var result = try {
            call("send", params)
        } catch (e: SignalCliException) {
            null
        }
        if (result == null) {
            result = call("sendMessage", params)
        }
        return result
    }

    fun approveMembership(account: String, groupId: String, members: List<Member>): JsonElement? {
        if (cliConfigPath == null) {
            throw SignalCliException(
                "Approve requires signal_cli.cli_config_path in config. " +
                    "Run 'duplicate-signal-cli-config' (with daemon stopped), then set cli_config_path in config.yaml."
            )
        }
        return subprocess.updateGroupAddMembers(account, groupId, members, logPrefix = "approve_membership")
    }

    fun denyMembership(account: String, groupId: String, members: List<Member>): JsonElement? {
        if (groupId.trim().isEmpty()) throw SignalCliException("group_id is required for deny.")
        val memberAddrs = members.mapNotNull { m ->
            val addr = m.toAddressMap()
            if (addr.isEmpty()) null else JsonObject(addr.mapValues { JsonPrimitive(it.value) })
        }
        val memberIds = members.mapNotNull { m ->
            val id = m.number.trim().ifBlank { m.uuid.trim() }
            if (id.isBlank() || id == account.trim()) null else id
        }
        if (memberIds.isEmpty()) throw SignalCliException("No member uuid/number for deny.")

        for (method in listOf("refuseMembership", "refuse_membership")) {
            try {
                val refuseParams = buildJsonObject {
                    put("group_id", groupId.trim())
                    put("groupId", groupId.trim())
                    put("members", JsonArray(memberAddrs))
                    if (account.isNotBlank()) put("account", account)
                }
                call(method, refuseParams, retries = 0)
                return null
            } catch (_: SignalCliException) {
                continue
            }
        }
        val banParams = buildJsonObject {
            put("groupId", groupId.trim())
            put("ban", JsonArray(memberIds.map { JsonPrimitive(it) }))
            if (account.isNotBlank()) put("account", account)
        }
        try {
            call("updateGroup", banParams)
            return null
        } catch (e: SignalCliException) {
            val msg = e.message.orEmpty().lowercase()
            if ("multiple membership lists" in msg || "GroupPatchNotAcceptedException" in (e.message ?: "")) {
                val removeParams = buildJsonObject {
                    put("groupId", groupId.trim())
                    put("removeMember", JsonArray(memberIds.map { JsonPrimitive(it) }))
                    if (account.isNotBlank()) put("account", account)
                }
                try {
                    call("updateGroup", removeParams)
                    return null
                } catch (_: SignalCliException) {
                }
            }
            throw e
        }
    }

    fun addMembersToGroup(account: String, groupId: String, members: List<Member>): JsonElement? {
        logger.info { "add_members_to_group: called with group_id=$groupId" }
        if (cliConfigPath == null) {
            throw SignalCliException(
                "Adding to second group requires signal_cli.cli_config_path in config. " +
                    "Run 'duplicate-signal-cli-config' (with daemon stopped), then set cli_config_path in config.yaml."
            )
        }
        if (!subprocess.hasGroup(account, groupId)) {
            throw SignalCliException(
                "Duplicate config does not contain this group. Re-run 'duplicate-signal-cli-config' (with daemon stopped), then try again."
            )
        }
        val delayMs = 2_000L
        val retryDelayMs = 5_000L
        for ((i, m) in members.withIndex()) {
            try {
                subprocess.updateGroupAddMembers(account, groupId, listOf(m), logPrefix = "add_members_to_group")
            } catch (e: SignalCliException) {
                logger.warn { "add_members_to_group: failed for member ${i + 1}/${members.size}, retrying once in ${retryDelayMs}ms: ${e.message}" }
                Thread.sleep(retryDelayMs)
                subprocess.updateGroupAddMembers(account, groupId, listOf(m), logPrefix = "add_members_to_group")
            }
            if ((i + 1) % 10 == 0 || i + 1 == members.size) {
                logger.info { "add_members_to_group: added ${i + 1}/${members.size}" }
            }
            if (i + 1 < members.size) Thread.sleep(delayMs)
        }
        return null
    }

    fun listContacts(allRecipients: Boolean = true): List<JsonObject> {
        val params = if (allRecipients) buildJsonObject { put("allRecipients", true) } else buildJsonObject {}
        for (method in listOf("listContacts", "list_contacts")) {
            try {
                val result = call(method, params, retries = 0) ?: continue
                when (result) {
                    is JsonArray -> return result.mapNotNull { it as? JsonObject }
                    is JsonObject -> {
                        for (key in listOf("contacts", "contactList", "data")) {
                            (result[key] as? JsonArray)?.let { arr ->
                                return arr.mapNotNull { it as? JsonObject }
                            }
                        }
                    }
                    else -> {}
                }
            } catch (_: SignalCliException) {
                continue
            }
        }
        return emptyList()
    }

    private fun firstLastFromDict(d: JsonObject): String? {
        val first = listOf("givenName", "firstName", "first_name")
            .firstNotNullOfOrNull { k -> d[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null } }
        val last = listOf("familyName", "lastName", "last_name", "surname")
            .firstNotNullOfOrNull { k -> d[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null } }
        return when {
            first != null && last != null -> "$first $last"
            first != null -> first
            last != null -> last
            else -> null
        }
    }

    private fun nameFromContact(contact: JsonObject): String? {
        firstLastFromDict(contact)?.let { return it }
        for (key in listOf("name", "profileName", "displayName")) {
            val v = contact[key]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrBlank()) return v
        }
        val profile = contact["profile"] as? JsonObject
        if (profile != null) firstLastFromDict(profile)?.let { return it }
        return null
    }

    private fun nameIsJustIdentifier(name: String?, uid: String, num: String): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim()
        return n == uid.trim() || n == num.trim()
    }

    private fun lookupRecipientName(recipientId: String): String? {
        if (recipientId.isBlank()) return null
        for (method in listOf("listContacts", "list_contacts")) {
            try {
                val result = call(method, buildJsonObject {
                    put("recipient", JsonArray(listOf(JsonPrimitive(recipientId.trim()))))
                }, retries = 0) ?: continue
                val contacts = when (result) {
                    is JsonArray -> result.mapNotNull { it as? JsonObject }
                    is JsonObject -> {
                        val arr = (result["contacts"] as? JsonArray) ?: (result["contactList"] as? JsonArray)
                        arr?.mapNotNull { it as? JsonObject } ?: emptyList()
                    }
                    else -> emptyList()
                }
                if (contacts.isEmpty()) continue
                for (c in contacts) {
                    val name = nameFromContact(c)
                    if (name != null && !nameIsJustIdentifier(name, recipientId, recipientId)) {
                        return name
                    }
                }
            } catch (_: SignalCliException) {
                continue
            }
        }
        return null
    }

    data class NamesResult(val names: List<String?>, val debug: Map<String, Any?>?)

    fun getRecipientNames(
        account: String,
        members: List<Member>,
        returnDebug: Boolean = false,
    ): NamesResult {
        val debug = if (returnDebug) mutableMapOf<String, Any?>() else null
        val nameById = mutableMapOf<String, String>()

        fun indexContact(c: JsonObject) {
            val name = nameFromContact(c) ?: return
            val num = c["number"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val uid = c["uuid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (nameIsJustIdentifier(name, uid, num)) return
            if (num.isNotEmpty()) nameById[num] = name
            if (uid.isNotEmpty()) nameById[uid] = name
        }

        // First pass: walk whatever signal-cli already has cached locally.
        val contacts = listContacts()
        if (debug != null) {
            debug["list_contacts_count"] = contacts.size
            if (contacts.isNotEmpty()) {
                debug["list_contacts_sample_keys"] = contacts.first().keys.toList()
            }
        }
        contacts.forEach(::indexContact)

        // Second pass: anyone still missing (typical for group-join requesters
        // who aren't in your contacts yet) - force a server-side profile
        // refresh by calling listContacts with their UUIDs as the `recipient`
        // filter. Per signal-cli docs: "When a specific recipient is given,
        // its profile will be refreshed." Doing it as one batched call cuts
        // round-trips vs. the old per-member fallback.
        val missing = members
            .filter { m ->
                val hasUuid = m.uuid.isNotBlank() && nameById.containsKey(m.uuid)
                val hasNum = m.number.isNotBlank() && nameById.containsKey(m.number)
                !hasUuid && !hasNum
            }
            .map { it.uuid.ifBlank { it.number } }
            .filter { it.isNotBlank() }
            .distinct()

        if (missing.isNotEmpty()) {
            if (debug != null) debug["profile_refresh_requested"] = missing
            val refreshed = try {
                val result = call("listContacts", buildJsonObject {
                    put("recipient", JsonArray(missing.map { JsonPrimitive(it) }))
                }, retries = 0)
                when (result) {
                    is JsonArray -> result.mapNotNull { it as? JsonObject }
                    is JsonObject -> {
                        (result["contacts"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                            ?: (result["contactList"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                            ?: emptyList()
                    }
                    else -> emptyList()
                }
            } catch (e: SignalCliException) {
                if (debug != null) debug["profile_refresh_error"] = e.message ?: "unknown"
                emptyList()
            } catch (e: SignalCliConnectionException) {
                if (debug != null) debug["profile_refresh_error"] = "connection: ${e.message}"
                emptyList()
            }
            if (debug != null) {
                debug["profile_refresh_returned"] = refreshed.size
                if (refreshed.isNotEmpty()) {
                    debug["profile_refresh_sample"] = refreshed.first().toString().take(500)
                }
            }
            refreshed.forEach(::indexContact)
        }

        val out = mutableListOf<String?>()
        for (m in members) {
            val name = nameById[m.uuid] ?: nameById[m.number]
            if (name != null) {
                out.add(name)
                continue
            }
            val recipient = m.uuid.ifBlank { m.number }
            if (recipient.isBlank()) {
                out.add(null)
                continue
            }
            // Per-recipient fallback kept for parity, but should rarely run
            // after the batch refresh above.
            val fetched = try { lookupRecipientName(recipient) } catch (_: Exception) { null }
            out.add(fetched ?: recipient)
        }
        return NamesResult(out, debug)
    }
}
