package com.signalbot.signal

/** A Signal member address, uuid preferred but either field may be empty. */
data class Member(
    val uuid: String = "",
    val number: String = "",
    val name: String? = null,
) {
    fun key(): String = when {
        uuid.isNotBlank() -> "uuid:$uuid"
        number.isNotBlank() -> "number:$number"
        else -> "{}"
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
