package com.signalbot.signal

/** A Signal member address, uuid preferred but either field may be empty. */
data class Member(
    val uuid: String = "",
    val number: String = "",
    val name: String? = null,
) {
    /**
     * Canonical SQLite primary key fragment. UUIDs are normalized to lowercase trimmed;
     * numbers have surrounding/inner ASCII spaces removed so " +1 2 " matches "+12".
     * (signal-cli JSON may change UUID casing between polls; mismatch used to bypass cooldown.)
     */
    fun key(): String {
        val u = uuid.trim()
        if (u.isNotEmpty()) return "uuid:${u.lowercase()}"
        val n = number.trim().replace(" ", "")
        if (n.isNotEmpty()) return "number:$n"
        return "{}"
    }

    /**
     * Possible [messaged.member_key] values from older builds or varying RPC casing before upsert consolidation.
     */
    fun memberKeyLookupCandidates(): List<String> =
        buildList {
            val canonical = key()
            add(canonical)
            val u = uuid.trim()
            if (u.isNotEmpty()) {
                val legacyExact = "uuid:$u"
                if (legacyExact != canonical) add(legacyExact)
                val upper = "uuid:${u.uppercase()}"
                if (upper != canonical) add(upper)
            }
            val nRaw = number.trim()
            if (nRaw.isNotEmpty() && u.isEmpty()) {
                val legacyNum = "number:$nRaw"
                if (legacyNum != canonical) add(legacyNum)
            }
        }.distinct()

    /** In-memory rate limiter bucket identity (aligned with [key] semantics without the prefix). */
    fun rateLimiterIdentity(): String {
        val u = uuid.trim()
        if (u.isNotEmpty()) return u.lowercase()
        val n = number.trim().replace(" ", "")
        return if (n.isNotEmpty()) n else ""
    }

    fun identifier(): String = uuid.ifBlank { number }

    fun toAddressMap(): Map<String, String> {
        val m = linkedMapOf<String, String>()
        if (uuid.isNotBlank()) m["uuid"] = uuid
        if (number.isNotBlank()) m["number"] = number
        return m
    }
}

class SignalCliException(message: String, val data: Map<String, Any?> = emptyMap()) : RuntimeException(message) {
    override fun toString(): String {
        if (data.isEmpty()) return message ?: ""
        return "${message ?: ""} (details: $data)"
    }
}

class SignalCliConnectionException(message: String) : RuntimeException(message)
