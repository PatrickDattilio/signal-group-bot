package com.signalbot.store

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/** SQLite-backed replacement for src/metrics.py. */
class MetricsStore(val enabled: Boolean = true) {
    private var sessionStartTime: Double = System.currentTimeMillis() / 1000.0

    init {
        if (enabled) {
            sessionStartTime = transaction {
                val row = MetricsTable.selectAll().where { MetricsTable.key eq START_TIME_KEY }.firstOrNull()
                row?.get(MetricsTable.value) ?: run {
                    val now = System.currentTimeMillis() / 1000.0
                    MetricsTable.insertIgnore {
                        it[key] = START_TIME_KEY
                        it[value] = now
                    }
                    now
                }
            }
        }
    }

    companion object {
        const val START_TIME_KEY = "start_time"
        private const val MESSAGES_SENT = "messages_sent"
        private const val MESSAGES_FAILED = "messages_failed"
        private const val APPROVALS_SUCCEEDED = "approvals_succeeded"
        private const val APPROVALS_FAILED = "approvals_failed"
        private const val POLLS_COMPLETED = "polls_completed"
        private const val POLLS_FAILED = "polls_failed"
        private const val LAST_UPDATED = "last_updated"

        fun ensureStartTimeInitialized() {
            val existing = MetricsTable.selectAll().where { MetricsTable.key eq START_TIME_KEY }.firstOrNull()
            if (existing == null) {
                val now = System.currentTimeMillis() / 1000.0
                MetricsTable.insertIgnore {
                    it[key] = START_TIME_KEY
                    it[value] = now
                }
            }
        }
    }

    private fun increment(name: String) {
        if (!enabled) return
        transaction {
            val current = MetricsTable.selectAll().where { MetricsTable.key eq name }.firstOrNull()
                ?.get(MetricsTable.value) ?: 0.0
            MetricsTable.upsert {
                it[key] = name
                it[value] = current + 1.0
            }
            touchLastUpdated()
        }
    }

    private fun incrementError(errorType: String) {
        if (!enabled) return
        transaction {
            val current = MetricsErrorsTable.selectAll().where { MetricsErrorsTable.errorType eq errorType }.firstOrNull()
                ?.get(MetricsErrorsTable.count) ?: 0L
            MetricsErrorsTable.upsert {
                it[MetricsErrorsTable.errorType] = errorType
                it[count] = current + 1L
            }
        }
    }

    private fun touchLastUpdated() {
        MetricsTable.upsert {
            it[key] = LAST_UPDATED
            it[value] = System.currentTimeMillis() / 1000.0
        }
    }

    fun recordMessageSent() = increment(MESSAGES_SENT)
    fun recordMessageFailed(errorType: String = "unknown") {
        increment(MESSAGES_FAILED)
        incrementError(errorType)
    }

    fun recordApprovalSucceeded() = increment(APPROVALS_SUCCEEDED)
    fun recordApprovalFailed(errorType: String = "unknown") {
        increment(APPROVALS_FAILED)
        incrementError(errorType)
    }

    fun recordPollCompleted() = increment(POLLS_COMPLETED)
    fun recordPollFailed(errorType: String = "unknown") {
        increment(POLLS_FAILED)
        incrementError(errorType)
    }

    private fun loadAll(): Map<String, Double> {
        if (!enabled) return emptyMap()
        return transaction {
            MetricsTable.selectAll().associate { it[MetricsTable.key] to it[MetricsTable.value] }
        }
    }

    private fun loadErrors(): Map<String, Long> {
        if (!enabled) return emptyMap()
        return transaction {
            MetricsErrorsTable.selectAll().associate { it[MetricsErrorsTable.errorType] to it[MetricsErrorsTable.count] }
        }
    }

    data class Stats(
        val uptimeSeconds: Double,
        val uptimeHours: Double,
        val messagesSent: Long,
        val messagesFailed: Long,
        val messageSuccessRate: Double?,
        val approvalsSucceeded: Long,
        val approvalsFailed: Long,
        val approvalSuccessRate: Double?,
        val pollsCompleted: Long,
        val pollsFailed: Long,
        val pollSuccessRate: Double?,
        val errors: Map<String, Long>,
    )

    fun getStats(): Stats {
        val data = loadAll()
        val errors = loadErrors()
        val startTime = data[START_TIME_KEY] ?: sessionStartTime
        val uptime = (System.currentTimeMillis() / 1000.0) - startTime
        fun g(key: String): Long = (data[key] ?: 0.0).toLong()
        val msent = g(MESSAGES_SENT)
        val mfail = g(MESSAGES_FAILED)
        val asucc = g(APPROVALS_SUCCEEDED)
        val afail = g(APPROVALS_FAILED)
        val psucc = g(POLLS_COMPLETED)
        val pfail = g(POLLS_FAILED)
        return Stats(
            uptimeSeconds = uptime,
            uptimeHours = uptime / 3600.0,
            messagesSent = msent,
            messagesFailed = mfail,
            messageSuccessRate = rate(msent, msent + mfail),
            approvalsSucceeded = asucc,
            approvalsFailed = afail,
            approvalSuccessRate = rate(asucc, asucc + afail),
            pollsCompleted = psucc,
            pollsFailed = pfail,
            pollSuccessRate = rate(psucc, psucc + pfail),
            errors = errors,
        )
    }

    fun reset() {
        if (!enabled) return
        transaction {
            MetricsTable.deleteAll()
            MetricsErrorsTable.deleteAll()
            val now = System.currentTimeMillis() / 1000.0
            sessionStartTime = now
            MetricsTable.insertIgnore {
                it[key] = START_TIME_KEY
                it[value] = now
            }
        }
    }

    private fun rate(success: Long, total: Long): Double? =
        if (total == 0L) null else (success.toDouble() / total.toDouble()) * 100.0
}

