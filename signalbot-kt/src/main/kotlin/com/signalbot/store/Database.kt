package com.signalbot.store

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

/**
 * SQLite-backed persistence (replaces the JSON files messaged.json and metrics.json).
 * A single database file; one connection is shared process-wide via Exposed.
 */
object Database {
    private var connected = false

    fun connect(dbPath: String) {
        if (connected) return
        org.jetbrains.exposed.sql.Database.connect(
            url = "jdbc:sqlite:${dbPath.replace('\\', '/')}",
            driver = "org.sqlite.JDBC",
        )
        transaction {
            SchemaUtils.create(MessagedTable, MetricsTable, MetricsErrorsTable)
            SchemaUtils.createMissingTablesAndColumns(MessagedTable, MetricsTable, MetricsErrorsTable, withLogs = false)
            legacyMessagedBackfillIfNeeded()
            MetricsStore.ensureStartTimeInitialized()
        }
        connected = true
    }
}

/**
 * One-time copy of legacy [MessagedTable.lastMessagedAt] into [MessagedTable.vettingSentAt]
 * for rows that predate the intake columns.
 */
private fun legacyMessagedBackfillIfNeeded() {
    val jdbc = TransactionManager.current().connection.connection as Connection
    val ver = jdbc.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
    }
    if (ver >= 1) return
    jdbc.createStatement().use { st ->
        st.executeUpdate(
            "UPDATE messaged SET vetting_sent_at = last_messaged_at " +
                "WHERE vetting_sent_at IS NULL AND last_messaged_at > 0.0",
        )
    }
    jdbc.createStatement().use { it.execute("PRAGMA user_version = 1") }
}

object MessagedTable : Table("messaged") {
    val memberKey = varchar("member_key", 256)
    val lastMessagedAt = double("last_messaged_at")
    val vettingSentAt = double("vetting_sent_at").nullable()
    val vettingFollowupSentAt = double("vetting_followup_sent_at").nullable()
    val welcomeSentAt = double("welcome_sent_at").nullable()
    val userRepliedAt = double("user_replied_at").nullable()
    val filterSkippedAt = double("filter_skipped_at").nullable()
    val deniedAt = double("denied_at").nullable()
    val approvedMainAt = double("approved_main_at").nullable()
    val extraGroup1At = double("extra_group_1_at").nullable()
    val extraGroup2At = double("extra_group_2_at").nullable()
    override val primaryKey = PrimaryKey(memberKey)
}

/** Scalar metrics and timestamps (keyed by name). */
object MetricsTable : Table("metrics") {
    val key = varchar("key", 64)
    val value = double("value")
    override val primaryKey = PrimaryKey(key)
}

/** Error counters, keyed by error type name. */
object MetricsErrorsTable : Table("metrics_errors") {
    val errorType = varchar("error_type", 128)
    val count = long("count")
    override val primaryKey = PrimaryKey(errorType)
}
