package com.signalbot

import com.signalbot.store.MessagedTable
import com.signalbot.store.MetricsErrorsTable
import com.signalbot.store.MetricsTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID

/** Connect to a unique temp-file SQLite database for each test. */
object TestDatabase {
    private val createdPaths = mutableListOf<java.io.File>()

    fun connect(): java.io.File {
        val dir = Files.createTempDirectory("signalbot-test-").toFile()
        val file = java.io.File(dir, "test-${UUID.randomUUID()}.db")
        createdPaths.add(file)
        org.jetbrains.exposed.sql.Database.connect(
            url = "jdbc:sqlite:${file.absolutePath.replace('\\', '/')}",
            driver = "org.sqlite.JDBC",
        )
        transaction {
            SchemaUtils.drop(MessagedTable, MetricsTable, MetricsErrorsTable)
            SchemaUtils.create(MessagedTable, MetricsTable, MetricsErrorsTable)
        }
        return file
    }
}
