package com.signalbot.bot

import com.signalbot.signal.Member
import java.util.concurrent.ConcurrentHashMap

/**
 * Filter for pending members based on allowlist/blocklist. Mirrors src/filters.py:MemberFilter.
 * Supports wildcards: * (any chars) and ? (single char).
 */
class MemberFilter(
    val allowlist: List<String> = emptyList(),
    val blocklist: List<String> = emptyList(),
    val allowlistEnabled: Boolean = false,
) {
    private val allowPatterns = allowlist.map { compile(it) }
    private val blockPatterns = blocklist.map { compile(it) }

    private fun compile(pattern: String): Regex {
        // Hand-built glob -> regex so * and ? remain meta while other regex chars are escaped.
        val specials = setOf('.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\')
        val sb = StringBuilder("^")
        for (c in pattern) {
            when {
                c == '*' -> sb.append(".*")
                c == '?' -> sb.append(".")
                c in specials -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    data class Decision(val shouldApprove: Boolean, val reason: String)

    fun shouldApprove(member: Member): Decision {
        val uuid = member.uuid
        val number = member.number

        if (uuid.isNotEmpty() && blockPatterns.any { it.matches(uuid) }) {
            return Decision(false, "UUID $uuid is blocklisted")
        }
        if (number.isNotEmpty() && blockPatterns.any { it.matches(number) }) {
            return Decision(false, "Number $number is blocklisted")
        }

        if (allowlistEnabled) {
            val uuidAllowed = uuid.isNotEmpty() && allowPatterns.any { it.matches(uuid) }
            val numberAllowed = number.isNotEmpty() && allowPatterns.any { it.matches(number) }
            return if (uuidAllowed || numberAllowed) {
                Decision(true, "Member is allowlisted")
            } else {
                Decision(false, "Member is not on allowlist")
            }
        }

        return Decision(true, "No restrictions")
    }
}

/** Rate limiter for join requests. Mirrors src/filters.py:RateLimiter. */
class RateLimiter(
    val maxRequests: Int = 10,
    val windowSeconds: Int = 3600,
) {
    private val requests = ConcurrentHashMap<String, MutableList<Double>>()

    data class Result(val allowed: Boolean, val reason: String)

    fun check(member: Member, currentTimeSec: Double = System.currentTimeMillis() / 1000.0): Result {
        val key = member.uuid.ifBlank { member.number }
        if (key.isBlank()) return Result(true, "No identifier")
        val list = requests.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            val cutoff = currentTimeSec - windowSeconds
            list.removeAll { it <= cutoff }
            if (list.size >= maxRequests) {
                return Result(false, "Rate limit exceeded ($maxRequests requests in ${windowSeconds}s)")
            }
            list.add(currentTimeSec)
        }
        return Result(true, "Within rate limit")
    }
}
