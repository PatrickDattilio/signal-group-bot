package com.signalbot.bot

import com.signalbot.signal.Member
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Message template engine with {{variable}} substitution. Mirrors src/template.py.
 */
class MessageTemplate(val template: String) {

    companion object {
        val VARIABLES: Map<String, String> = linkedMapOf(
            "timestamp" to "Current timestamp",
            "date" to "Current date (YYYY-MM-DD)",
            "time" to "Current time (HH:MM:SS)",
            "datetime" to "Current date and time",
            "member_uuid" to "Member's UUID",
            "member_number" to "Member's phone number",
        )

        private val VAR_RE = Regex("""\{\{(\w+)\}\}""")
    }

    init {
        val found = VAR_RE.findAll(template).map { it.groupValues[1] }.toList()
        val unknown = found.filter { it !in VARIABLES }
        if (unknown.isNotEmpty()) {
            logger.warn { "Template contains unknown variables: $unknown" }
        }
    }

    fun render(member: Member, extraVars: Map<String, String> = emptyMap()): String {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val epoch = now.atZone(ZoneId.systemDefault()).toEpochSecond()
        val context = mutableMapOf(
            "timestamp" to epoch.toString(),
            "date" to now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            "datetime" to now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "member_uuid" to member.uuid,
            "member_number" to member.number,
        )
        context.putAll(extraVars)
        return VAR_RE.replace(template) { mr ->
            val name = mr.groupValues[1]
            context[name] ?: mr.value
        }
    }
}
