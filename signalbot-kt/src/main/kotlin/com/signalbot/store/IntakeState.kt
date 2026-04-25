package com.signalbot.store

/**
 * Pipeline state for someone currently in the join-request queue, derived from stored timestamps.
 * Terminal or historical rows may use this with extra context from [IntakeRow].
 */
enum class IntakeState {
    PENDING,
    VETTING_SENT,
    VETTING_FOLLOWUP_SENT,
    WELCOME_SENT,
    FILTER_SKIPPED,
    ;

    val apiName: String
        get() = name.lowercase()
}

data class IntakeRow(
    val memberKey: String,
    val lastMessagedAt: Double,
    val vettingSentAt: Double?,
    val vettingFollowupSentAt: Double?,
    val welcomeSentAt: Double?,
    val userRepliedAt: Double?,
    val filterSkippedAt: Double?,
    val deniedAt: Double?,
    val approvedMainAt: Double?,
    val extraGroup1At: Double?,
    val extraGroup2At: Double?,
) {
    fun maxEventTimestamp(): Double? =
        listOfNotNull(
            vettingSentAt,
            vettingFollowupSentAt,
            welcomeSentAt,
            userRepliedAt,
            filterSkippedAt,
            deniedAt,
            approvedMainAt,
            extraGroup1At,
            extraGroup2At,
        ).maxOrNull()
}

/**
 * For members shown in the pending queue, derive a single label from intake timestamps.
 * Order prefers the furthest step reached (welcome over vetting, etc.).
 */
fun deriveIntakeStateInQueue(row: IntakeRow): IntakeState {
    if (row.filterSkippedAt != null) return IntakeState.FILTER_SKIPPED
    if (row.welcomeSentAt != null) return IntakeState.WELCOME_SENT
    if (row.vettingFollowupSentAt != null) return IntakeState.VETTING_FOLLOWUP_SENT
    if (row.vettingSentAt != null) return IntakeState.VETTING_SENT
    return IntakeState.PENDING
}

fun IntakeState.displayLabel(): String = when (this) {
    IntakeState.PENDING -> "Pending"
    IntakeState.VETTING_SENT -> "Vetting sent"
    IntakeState.VETTING_FOLLOWUP_SENT -> "Follow-up sent"
    IntakeState.WELCOME_SENT -> "Welcome / rules sent"
    IntakeState.FILTER_SKIPPED -> "Filter skipped"
}
