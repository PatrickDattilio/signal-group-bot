package com.signalbot.web

import com.signalbot.bot.MessageTemplate
import com.signalbot.signal.Member
import com.signalbot.signal.SignalCliClient
import com.signalbot.signal.SignalCliException
import com.signalbot.store.IntakeState
import com.signalbot.store.deriveIntakeStateInQueue
import com.signalbot.store.displayLabel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

private val UTC_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

private fun formatOptionalTs(ts: Double?): String? = ts?.let { UTC_FMT.format(Instant.ofEpochSecond(it.toLong())) }

fun Application.installRoutes(context: WebAppContext) {
    routing {
        get("/health") {
            call.respond(buildJsonObject { put("ok", true) })
        }

        get("/login") {
            if (call.sessions.get<AdminSession>()?.authenticated == true) {
                call.respondRedirect("/")
                return@get
            }
            call.respondText(Templates.loginHtml(), ContentType.Text.Html)
        }

        post("/login") {
            val bodyText = call.receiveText()
            val form = parseForm(bodyText)
            val username = (form["username"] ?: "").trim()
            val password = form["password"] ?: ""
            val ip = Auth.clientIp(call)

            if (Auth.isLoginRateLimited(ip)) {
                call.respondText(
                    Templates.loginHtml("Too many login attempts. Please try again later."),
                    ContentType.Text.Html,
                )
                return@post
            }

            val adminUser = System.getenv("SIGNALBOT_ADMIN_USERNAME")?.trim().orEmpty()
            val adminHash = System.getenv("SIGNALBOT_ADMIN_PASSWORD_HASH")?.trim().orEmpty()

            if (adminUser.isEmpty() || adminHash.isEmpty()) {
                call.respondText(
                    Templates.loginHtml("Admin credentials are not configured on the server."),
                    ContentType.Text.Html,
                )
                return@post
            }

            if (username == adminUser && Auth.verifyAdminPassword(password, adminHash)) {
                call.sessions.set(AdminSession(username = username))
                Auth.clearLoginFailures(ip)
                val next = call.request.queryParameters["next"]?.takeIf { it.startsWith("/") } ?: "/"
                call.respondRedirect(next)
            } else {
                Auth.recordLoginFailure(ip)
                call.respondText(
                    Templates.loginHtml("Invalid username or password."),
                    ContentType.Text.Html,
                )
            }
        }

        get("/logout") {
            call.sessions.clear<AdminSession>()
            call.respondRedirect("/login")
        }
        post("/logout") {
            call.sessions.clear<AdminSession>()
            call.respondRedirect("/login")
        }

        get("/") {
            if (!requireAuthOrRedirect(call)) return@get
            call.respondText(Templates.INDEX_HTML, ContentType.Text.Html)
        }

        get("/api/requesting") {
            if (!requireAuthOrJson(call)) return@get
            val cfg = context.loadConfig()
            val debug = call.request.queryParameters["debug"]?.lowercase() in setOf("1", "true", "yes")
            val client = context.client()
            val addToGroupIds = listOfNotNull(
                cfg.approveAddToGroupId?.takeIf { it.isNotBlank() },
                cfg.approveAddToGroupId2?.takeIf { it.isNotBlank() },
            )

            val members = try {
                withContext(Dispatchers.IO) { client.listPendingMembers(cfg.account, cfg.groupId) }
            } catch (e: SignalCliException) {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject { put("error", e.message ?: "error") })
                return@get
            }
            val (mainName, addNames) = withContext(Dispatchers.IO) {
                getGroupNamesForConfig(client, cfg.groupId, addToGroupIds)
            }
            val namesResult = withContext(Dispatchers.IO) {
                client.getRecipientNames(
                    cfg.account,
                    members,
                    returnDebug = debug,
                    refreshGroupId = cfg.groupId,
                )
            }
            val requesting = members.mapIndexed { i, m ->
                context.store.clearStaleTerminalStateForRequester(m)
                val row = context.store.getRow(m)
                val ts = context.store.getMessagedAt(m)
                val status = if (ts != null) "messaged" else "not_messaged"
                val messagedAt = ts?.let { UTC_FMT.format(Instant.ofEpochSecond(it.toLong())) }
                val intakeState = if (row != null) deriveIntakeStateInQueue(row) else IntakeState.PENDING
                val intakeLabel = intakeState.displayLabel()
                buildJsonObject {
                    put("uuid", m.uuid)
                    put("number", m.number)
                    put("name", namesResult.names.getOrNull(i))
                    put("status", status)
                    put("messaged_at", messagedAt)
                    put("intake_state", intakeState.apiName)
                    put("intake_label", intakeLabel)
                    put("vetting_sent_at", formatOptionalTs(row?.vettingSentAt))
                    put("vetting_followup_sent_at", formatOptionalTs(row?.vettingFollowupSentAt))
                    put("welcome_sent_at", formatOptionalTs(row?.welcomeSentAt))
                    put("user_replied_at", formatOptionalTs(row?.userRepliedAt))
                }
            }
            val out = buildJsonObject {
                put("requesting", JsonArray(requesting))
                put("main_group_name", mainName)
                put("add_group_names", JsonArray(addNames.map { JsonPrimitive(it) }))
                if (debug && namesResult.debug != null) {
                    put("name_lookup_debug", namesResult.debug.toJson())
                }
                if (debug) {
                    // Dump the raw requestingMembers JSON signal-cli returned
                    // so we can see exactly which fields are populated (and
                    // which aren't) for join-request strangers.
                    try {
                        val (raw, _) = withContext(Dispatchers.IO) {
                            client.listPendingMembersRaw(cfg.account, cfg.groupId)
                        }
                        put("raw_requesting_members", raw)
                    } catch (e: SignalCliException) {
                        put("raw_requesting_members_error", e.message ?: "error")
                    }
                }
            }
            call.respond(out)
        }

        post("/api/send-welcome") {
            if (!requireAuthOrJson(call)) return@post
            val cfg = context.loadConfig()
            val rulesMessage = cfg.approveRulesMessage?.trim()?.takeIf { it.isNotBlank() }
            if (rulesMessage == null) {
                call.respond(HttpStatusCode.BadRequest, jsonError("approve_rules_message is not configured in config.yaml"))
                return@post
            }
            val member = parseMemberFromBody(call) ?: run {
                call.respond(HttpStatusCode.BadRequest, jsonError("member.uuid or member.number required"))
                return@post
            }
            logger.info { "Send welcome: member uuid=${member.uuid.ifBlank { "(none)" }} number=${member.number.ifBlank { "(none)" }}" }
            val client = context.client()
            try {
                val body = MessageTemplate(rulesMessage).render(member)
                withContext(Dispatchers.IO) { client.sendMessage(cfg.account, member, body) }
            } catch (e: SignalCliException) {
                call.respond(HttpStatusCode.InternalServerError, jsonError(e.message ?: "error"))
                return@post
            }
            context.store.markWelcomeSent(member)
            val row = context.store.getRow(member)!!
            val st = deriveIntakeStateInQueue(row)
            call.respond(buildJsonObject {
                put("ok", true)
                put("welcome_sent", true)
                put("intake_state", st.apiName)
                put("intake_label", st.displayLabel())
            })
        }

        post("/api/deny") {
            if (!requireAuthOrJson(call)) return@post
            val cfg = context.loadConfig()
            val member = parseMemberFromBody(call) ?: run {
                call.respond(HttpStatusCode.BadRequest, jsonError("member.uuid or member.number required"))
                return@post
            }
            logger.info { "Deny: member uuid=${member.uuid.ifBlank { "(none)" }} number=${member.number.ifBlank { "(none)" }}" }
            val client = context.client()
            try {
                withContext(Dispatchers.IO) { client.denyMembership(cfg.account, cfg.groupId, listOf(member)) }
            } catch (e: SignalCliException) {
                call.respond(HttpStatusCode.InternalServerError, jsonError(e.message ?: "error"))
                return@post
            }
            context.store.markDenied(member)
            call.respond(buildJsonObject {
                put("ok", true)
                put("denied", true)
            })
        }

        post("/api/approve") {
            if (!requireAuthOrJson(call)) return@post
            val cfg = context.loadConfig()
            val member = parseMemberFromBody(call) ?: run {
                call.respond(HttpStatusCode.BadRequest, jsonError("member.uuid or member.number required"))
                return@post
            }
            val addToGroupIds = listOfNotNull(
                cfg.approveAddToGroupId?.takeIf { it.isNotBlank() },
                cfg.approveAddToGroupId2?.takeIf { it.isNotBlank() },
            )
            logger.info { "Approve: member uuid=${member.uuid.ifBlank { "(none)" }} number=${member.number.ifBlank { "(none)" }}, add_to=$addToGroupIds" }
            val client = context.client()
            val errors = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) { client.approveMembership(cfg.account, cfg.groupId, listOf(member)) }
            } catch (e: SignalCliException) {
                logger.error(e) { "POST /api/approve failed: ${e.message}" }
                errors += "Approve: ${e.message}"
                call.respond(HttpStatusCode.InternalServerError, jsonError(errors.joinToString("; ")))
                return@post
            } catch (e: Exception) {
                logger.error(e) { "POST /api/approve failed (unexpected)" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    jsonError(e.message ?: e.javaClass.simpleName),
                )
                return@post
            }
            context.store.markApprovedMain(member)
            for ((i, gid) in addToGroupIds.withIndex()) {
                try {
                    withContext(Dispatchers.IO) { client.addMembersToGroup(cfg.account, gid, listOf(member)) }
                } catch (e: SignalCliException) {
                    errors += "Add to group ${i + 2}: ${e.message}"
                    call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                        put("error", errors.joinToString("; "))
                        put("approved", true)
                    })
                    return@post
                }
                when (i) {
                    0 -> context.store.markExtraGroup1(member)
                    1 -> context.store.markExtraGroup2(member)
                }
            }
            call.respond(buildJsonObject {
                put("ok", true)
                put("approved", true)
                put("added_to_extra_groups", addToGroupIds.size)
            })
        }
    }
}

private suspend fun requireAuthOrRedirect(call: ApplicationCall): Boolean {
    if (call.sessions.get<AdminSession>()?.authenticated == true) return true
    val next = call.request.local.uri
    call.respondRedirect("/login?next=$next")
    return false
}

private suspend fun requireAuthOrJson(call: ApplicationCall): Boolean {
    if (call.sessions.get<AdminSession>()?.authenticated == true) return true
    call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "authentication required") })
    return false
}

private fun parseForm(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx == -1) null
        else {
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            k to v
        }
    }.toMap()
}

private fun jsonError(msg: String): JsonObject = buildJsonObject { put("error", msg) }

private suspend fun parseMemberFromBody(call: ApplicationCall): Member? {
    val text = try {
        call.receiveText()
    } catch (_: Exception) {
        return null
    }
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }
    val memberObj = root["member"] as? JsonObject ?: return null
    val uuid = memberObj["uuid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val number = memberObj["number"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (uuid.isBlank() && number.isBlank()) return null
    return Member(uuid = uuid, number = number)
}

private fun getGroupNamesForConfig(
    client: SignalCliClient,
    mainId: String,
    addToGroupIds: List<String>,
): Pair<String, List<String>> {
    var mainName = "?"
    val addNames = mutableListOf<String>()
    val groups = try {
        client.listGroups()
    } catch (_: SignalCliException) {
        return mainName to addNames
    }
    val wantMain = client.normalizeGroupId(mainId)
    val wantAdds = addToGroupIds.map { client.normalizeGroupId(it) }
    for (g in groups) {
        val gid = g["id"]?.jsonPrimitive?.contentOrNull ?: g["groupId"]?.jsonPrimitive?.contentOrNull ?: continue
        val name = (g["name"]?.jsonPrimitive?.contentOrNull
            ?: g["title"]?.jsonPrimitive?.contentOrNull
            ?: g["groupName"]?.jsonPrimitive?.contentOrNull
            ?: "?").trim().ifEmpty { "?" }
        if (client.normalizeGroupId(gid) == wantMain) mainName = name
    }
    for (want in wantAdds) {
        var found = "?"
        for (g in groups) {
            val gid = g["id"]?.jsonPrimitive?.contentOrNull ?: g["groupId"]?.jsonPrimitive?.contentOrNull ?: continue
            if (client.normalizeGroupId(gid) == want) {
                found = (g["name"]?.jsonPrimitive?.contentOrNull
                    ?: g["title"]?.jsonPrimitive?.contentOrNull
                    ?: g["groupName"]?.jsonPrimitive?.contentOrNull
                    ?: "?").trim().ifEmpty { "?" }
                break
            }
        }
        addNames.add(found)
    }
    return mainName to addNames
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toJson(): JsonElement {
    return buildJsonObject {
        for ((k, v) in this@toJson) {
            put(k, anyToJson(v))
        }
    }
}

private fun anyToJson(v: Any?): JsonElement {
    return when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { anyToJson(it) })
        is Map<*, *> -> buildJsonObject {
            for ((k, vv) in v) put(k.toString(), anyToJson(vv))
        }
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }
}
