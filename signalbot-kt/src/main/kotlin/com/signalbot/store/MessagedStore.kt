package com.signalbot.store

import com.signalbot.signal.Member
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** SQLite-backed replacement for src/store.py. */
class MessagedStore {

    /**
     * Legacy: true if [getMessagedAt] is within [cooldownSeconds] of now.
     * For bot vetting behavior use [isWithinVettingCooldown] instead.
     */
    fun wasMessaged(member: Member, cooldownSeconds: Int = 0): Boolean {
        val ts = getMessagedAt(member) ?: return false
        if (cooldownSeconds <= 0) return true
        return (System.currentTimeMillis() / 1000.0 - ts) < cooldownSeconds
    }

    /** Cooldown for vetting DMs (intro + follow-up), not welcome or filter-only. */
    fun isWithinVettingCooldown(member: Member, cooldownSeconds: Int): Boolean {
        if (cooldownSeconds <= 0) return false
        val ref = maxVettingReferenceTime(getRow(member)) ?: return false
        return (System.currentTimeMillis() / 1000.0 - ref) < cooldownSeconds
    }

    fun isFilterSkipped(member: Member): Boolean =
        getRow(member)?.filterSkippedAt != null

    /**
     * After intro was sent, follow-up template exists, and we have not recorded follow-up yet.
     * When no follow-up template, always false (legacy matches intro every time after cooldown when only one template is configured).
     */
    fun shouldSendVettingFollowupTemplate(member: Member, followUpTemplateExists: Boolean): Boolean {
        if (!followUpTemplateExists) return false
        return needsVettingFollowupOnly(member)
    }

    fun needsVettingFollowupOnly(member: Member): Boolean {
        val row = getRow(member) ?: return false
        return row.vettingSentAt != null && row.vettingFollowupSentAt == null
    }

    fun getMessagedAt(member: Member): Double? {
        val row = getRow(member) ?: return null
        val maxEvent = row.maxEventTimestamp()
        if (maxEvent != null) return maxEvent
        if (row.lastMessagedAt > 0.0) return row.lastMessagedAt
        return null
    }

    fun getRow(member: Member): IntakeRow? {
        val key = member.key()
        return transaction {
            MessagedTable.selectAll()
                .where { MessagedTable.memberKey eq key }
                .firstOrNull()
                ?.toIntakeRow()
        }
    }

    /**
     * Legacy: set vetting time to [timestamp] (replaces the old single "last_messaged" field behavior).
     */
    fun markMessaged(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            upsertRow(withLast(cur.copy(vettingSentAt = timestamp)))
        }
    }

    fun markVettingSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val v = cur.vettingSentAt ?: timestamp
            upsertRow(withLast(cur.copy(vettingSentAt = v)))
        }
    }

    fun markVettingFollowupSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val f = cur.vettingFollowupSentAt ?: timestamp
            upsertRow(withLast(cur.copy(vettingFollowupSentAt = f)))
        }
    }

    fun markWelcomeSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val w = maxOf(cur.welcomeSentAt ?: 0.0, timestamp)
            upsertRow(withLast(cur.copy(welcomeSentAt = w)))
        }
    }

    fun markFilterSkipped(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val f = cur.filterSkippedAt ?: timestamp
            upsertRow(withLast(cur.copy(filterSkippedAt = f)))
        }
    }

    fun clearFilterSkipped(member: Member) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: return@transaction
            if (cur.filterSkippedAt == null) return@transaction
            upsertRow(withLast(cur.copy(filterSkippedAt = null)))
        }
    }

    fun markDenied(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val d = cur.deniedAt ?: timestamp
            upsertRow(withLast(cur.copy(deniedAt = d)))
        }
    }

    fun markApprovedMain(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val a = cur.approvedMainAt ?: timestamp
            upsertRow(withLast(cur.copy(approvedMainAt = a)))
        }
    }

    fun markExtraGroup1(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val a = cur.extraGroup1At ?: timestamp
            upsertRow(withLast(cur.copy(extraGroup1At = a)))
        }
    }

    fun markExtraGroup2(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: emptyRow(key)
            val a = cur.extraGroup2At ?: timestamp
            upsertRow(withLast(cur.copy(extraGroup2At = a)))
        }
    }

    /**
     * When a member is back in the join queue, clear stale flags from a previous approve/deny cycle.
     */
    fun clearStaleTerminalStateForRequester(member: Member) {
        val key = member.key()
        transaction {
            val cur = readRow(key) ?: return@transaction
            if (cur.deniedAt == null && cur.approvedMainAt == null && cur.extraGroup1At == null && cur.extraGroup2At == null) {
                return@transaction
            }
            val cleared = cur.copy(
                deniedAt = null,
                approvedMainAt = null,
                extraGroup1At = null,
                extraGroup2At = null,
            )
            upsertRow(withLast(cleared))
        }
    }

    data class Stats(
        val totalMembers: Long,
        val last24h: Long,
        val last7d: Long,
        val last30d: Long,
    )

    fun getStats(): Stats {
        val now = System.currentTimeMillis() / 1000.0
        return transaction {
            val rows = MessagedTable.selectAll().toList()
            val total = rows.size.toLong()
            val last24h = rows.count { (now - it[MessagedTable.lastMessagedAt]) < 86_400.0 }.toLong()
            val last7d = rows.count { (now - it[MessagedTable.lastMessagedAt]) < 86_400.0 * 7 }.toLong()
            val last30d = rows.count { (now - it[MessagedTable.lastMessagedAt]) < 86_400.0 * 30 }.toLong()
            Stats(total, last24h, last7d, last30d)
        }
    }

    fun importAll(entries: Map<String, Double>) {
        transaction {
            for ((key, ts) in entries) {
                val cur = readRow(key) ?: emptyRow(key)
                upsertRow(withLast(cur.copy(vettingSentAt = ts)))
            }
        }
    }

    private fun readRow(key: String): IntakeRow? =
        MessagedTable.selectAll().where { MessagedTable.memberKey eq key }.firstOrNull()?.toIntakeRow()

    private fun emptyRow(key: String) = IntakeRow(
        memberKey = key,
        lastMessagedAt = 0.0,
        vettingSentAt = null,
        vettingFollowupSentAt = null,
        welcomeSentAt = null,
        userRepliedAt = null,
        filterSkippedAt = null,
        deniedAt = null,
        approvedMainAt = null,
        extraGroup1At = null,
        extraGroup2At = null,
    )

    private fun ResultRow.toIntakeRow() = IntakeRow(
        memberKey = this[MessagedTable.memberKey],
        lastMessagedAt = this[MessagedTable.lastMessagedAt],
        vettingSentAt = this[MessagedTable.vettingSentAt],
        vettingFollowupSentAt = this[MessagedTable.vettingFollowupSentAt],
        welcomeSentAt = this[MessagedTable.welcomeSentAt],
        userRepliedAt = this[MessagedTable.userRepliedAt],
        filterSkippedAt = this[MessagedTable.filterSkippedAt],
        deniedAt = this[MessagedTable.deniedAt],
        approvedMainAt = this[MessagedTable.approvedMainAt],
        extraGroup1At = this[MessagedTable.extraGroup1At],
        extraGroup2At = this[MessagedTable.extraGroup2At],
    )

    private fun upsertRow(row: IntakeRow) {
        MessagedTable.upsert {
            it[MessagedTable.memberKey] = row.memberKey
            it[MessagedTable.lastMessagedAt] = row.lastMessagedAt
            it[MessagedTable.vettingSentAt] = row.vettingSentAt
            it[MessagedTable.vettingFollowupSentAt] = row.vettingFollowupSentAt
            it[MessagedTable.welcomeSentAt] = row.welcomeSentAt
            it[MessagedTable.userRepliedAt] = row.userRepliedAt
            it[MessagedTable.filterSkippedAt] = row.filterSkippedAt
            it[MessagedTable.deniedAt] = row.deniedAt
            it[MessagedTable.approvedMainAt] = row.approvedMainAt
            it[MessagedTable.extraGroup1At] = row.extraGroup1At
            it[MessagedTable.extraGroup2At] = row.extraGroup2At
        }
    }

    private fun withLast(row: IntakeRow): IntakeRow =
        row.copy(lastMessagedAt = row.maxEventTimestamp() ?: 0.0)

    private fun maxVettingReferenceTime(row: IntakeRow?): Double? {
        if (row == null) return null
        return listOfNotNull(row.vettingSentAt, row.vettingFollowupSentAt).maxOrNull()
    }
}
