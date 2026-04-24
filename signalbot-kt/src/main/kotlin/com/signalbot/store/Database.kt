package com.signalbot.store

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

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
            MetricsStore.ensureStartTimeInitialized()
        }
        connected = true
    }
}

object MessagedTable : Table("messaged") {
    val memberKey = varchar("member_key", 256)
    val lastMessagedAt = double("last_messaged_at")
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
