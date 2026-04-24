package com.signalbot.store

import com.signalbot.signal.Member
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/** SQLite-backed replacement for src/store.py. */
class MessagedStore {

    fun wasMessaged(member: Member, cooldownSeconds: Int = 0): Boolean {
        val ts = getMessagedAt(member) ?: return false
        if (cooldownSeconds <= 0) return true
        return (System.currentTimeMillis() / 1000.0 - ts) < cooldownSeconds
    }

    fun getMessagedAt(member: Member): Double? {
        val key = member.key()
        return transaction {
            MessagedTable.selectAll()
                .where { MessagedTable.memberKey eq key }
                .firstOrNull()
                ?.get(MessagedTable.lastMessagedAt)
        }
    }

    fun markMessaged(member: Member, timestamp: Double = System.currentTimeMillis() / 1000.0) {
        val key = member.key()
        transaction {
            MessagedTable.upsert {
                it[memberKey] = key
                it[lastMessagedAt] = timestamp
            }
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

    /** Bulk import for migrate-json. Does not overwrite newer entries. */
    fun importAll(entries: Map<String, Double>) {
        transaction {
            for ((key, ts) in entries) {
                MessagedTable.upsert {
                    it[memberKey] = key
                    it[lastMessagedAt] = ts
                }
            }
        }
    }
}
