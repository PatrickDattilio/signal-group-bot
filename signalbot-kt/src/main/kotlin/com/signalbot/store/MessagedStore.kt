package com.signalbot.store

import com.signalbot.signal.Member
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** SQLite-backed replacement for src/store.py. */
class MessagedStore {

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
        return transaction {
            val rows = member.memberKeyLookupCandidates().mapNotNull { readRow(it) }
            if (rows.isEmpty()) null
            else rows.reduce(::mergeIntakeRows).copy(memberKey = member.key())
        }
    }

    /**
     * Legacy: set vetting time to [timestamp] (replaces the old single "last_messaged" field behavior).
     */
    fun markMessaged(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            upsertRow(withLast(cur.copy(vettingSentAt = timestamp)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    /**
     * Record a successful vetting **intro** DM (primary template from the polling bot).
     * Always stores [timestamp] as [IntakeRow.vettingSentAt] so [isWithinVettingCooldown] measures time since the
     * **last** intro. (Older behavior kept the first timestamp forever, so once cooldown expired relative to that
     * stale value the bot resent intro every poll while the member stayed pending.)
     */
    fun markVettingSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            upsertRow(withLast(cur.copy(vettingSentAt = timestamp)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    /**
     * Record a successful vetting **follow-up** DM. Uses [timestamp] each time so cooldown tracks last follow-up too.
     */
    fun markVettingFollowupSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            upsertRow(withLast(cur.copy(vettingFollowupSentAt = timestamp)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markWelcomeSent(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val w = maxOf(cur.welcomeSentAt ?: 0.0, timestamp)
            upsertRow(withLast(cur.copy(welcomeSentAt = w)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markFilterSkipped(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val f = cur.filterSkippedAt ?: timestamp
            upsertRow(withLast(cur.copy(filterSkippedAt = f)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun clearFilterSkipped(member: Member) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            if (cur.filterSkippedAt == null) return@transaction
            upsertRow(withLast(cur.copy(filterSkippedAt = null)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markDenied(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val d = cur.deniedAt ?: timestamp
            upsertRow(withLast(cur.copy(deniedAt = d)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markApprovedMain(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val a = cur.approvedMainAt ?: timestamp
            upsertRow(withLast(cur.copy(approvedMainAt = a)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markExtraGroup1(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val a = cur.extraGroup1At ?: timestamp
            upsertRow(withLast(cur.copy(extraGroup1At = a)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    fun markExtraGroup2(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
            val a = cur.extraGroup2At ?: timestamp
            upsertRow(withLast(cur.copy(extraGroup2At = a)))
            deleteStaleMemberKeys(member, canonical)
        }
    }

    /**
     * When a member is back in the join queue, clear stale flags from a previous approve/deny cycle.
     */
    fun clearStaleTerminalStateForRequester(member: Member) {
        val canonical = member.key()
        transaction {
            val cur = mergeMemberRows(member)
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
            deleteStaleMemberKeys(member, canonical)
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

    /** Merge duplicate SQLite rows for [member], keyed under canonical [Member.key]. */
    private fun mergeMemberRows(member: Member): IntakeRow {
        val canonical = member.key()
        val rows = member.memberKeyLookupCandidates().mapNotNull { readRow(it) }
        if (rows.isEmpty()) return emptyRow(canonical)
        return rows.reduce(::mergeIntakeRows).copy(memberKey = canonical)
    }

    private fun deleteStaleMemberKeys(member: Member, keepCanonical: String) {
        val stale = member.memberKeyLookupCandidates().filter { it != keepCanonical }
        if (stale.isEmpty()) return
        MessagedTable.deleteWhere { MessagedTable.memberKey inList stale }
    }

    private fun mergeIntakeRows(a: IntakeRow, b: IntakeRow): IntakeRow =
        IntakeRow(
            memberKey = a.memberKey,
            lastMessagedAt = maxOf(a.lastMessagedAt, b.lastMessagedAt),
            vettingSentAt = maxOfNullable(a.vettingSentAt, b.vettingSentAt),
            vettingFollowupSentAt = maxOfNullable(a.vettingFollowupSentAt, b.vettingFollowupSentAt),
            welcomeSentAt = maxOfNullable(a.welcomeSentAt, b.welcomeSentAt),
            userRepliedAt = maxOfNullable(a.userRepliedAt, b.userRepliedAt),
            filterSkippedAt = maxOfNullable(a.filterSkippedAt, b.filterSkippedAt),
            deniedAt = maxOfNullable(a.deniedAt, b.deniedAt),
            approvedMainAt = maxOfNullable(a.approvedMainAt, b.approvedMainAt),
            extraGroup1At = maxOfNullable(a.extraGroup1At, b.extraGroup1At),
            extraGroup2At = maxOfNullable(a.extraGroup2At, b.extraGroup2At),
        )

    private fun maxOfNullable(a: Double?, b: Double?): Double? =
        when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

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
