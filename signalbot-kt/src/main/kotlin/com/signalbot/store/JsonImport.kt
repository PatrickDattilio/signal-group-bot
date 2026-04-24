package com.signalbot.store

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Import legacy messaged.json and metrics.json into the SQLite store.
 * Safe to run multiple times (uses upserts).
 */
object JsonImport {
    data class Result(val messagedImported: Int, val metricsImported: Int)

    fun run(
        storeJsonPath: String? = System.getenv("SIGNALBOT_STORE") ?: "messaged.json",
        metricsJsonPath: String? = System.getenv("SIGNALBOT_METRICS") ?: "metrics.json",
        messaged: MessagedStore = MessagedStore(),
    ): Result {
        var msgCount = 0
        var metricsCount = 0

        if (storeJsonPath != null && File(storeJsonPath).isFile) {
            val text = File(storeJsonPath).readText(Charsets.UTF_8)
            if (text.isNotBlank()) {
                val root = try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    logger.warn { "Could not parse $storeJsonPath: ${e.message}" }
                    null
                }
                if (root != null) {
                    val entries = root.mapNotNull { (k, v) ->
                        val ts = (v as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        k to ts
                    }.toMap()
                    messaged.importAll(entries)
                    msgCount = entries.size
                    logger.info { "Imported $msgCount messaged entries from $storeJsonPath" }
                }
            }
        } else {
            logger.info { "No messaged JSON found at $storeJsonPath, skipping" }
        }

        if (metricsJsonPath != null && File(metricsJsonPath).isFile) {
            val text = File(metricsJsonPath).readText(Charsets.UTF_8)
            if (text.isNotBlank()) {
                val root = try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    logger.warn { "Could not parse $metricsJsonPath: ${e.message}" }
                    null
                }
                if (root != null) {
                    transaction {
                        for ((k, v) in root) {
                            when (k) {
                                "errors" -> {
                                    val obj = v as? JsonObject ?: continue
                                    for ((errType, count) in obj) {
                                        val c = (count as? JsonPrimitive)?.doubleOrNull?.toLong() ?: continue
                                        MetricsErrorsTable.upsert {
                                            it[errorType] = errType
                                            it[MetricsErrorsTable.count] = c
                                        }
                                        metricsCount++
                                    }
                                }
                                else -> {
                                    val num = (v as? JsonPrimitive)?.doubleOrNull ?: continue
                                    MetricsTable.upsert {
                                        it[key] = k
                                        it[value] = num
                                    }
                                    metricsCount++
                                }
                            }
                        }
                    }
                    logger.info { "Imported $metricsCount metrics entries from $metricsJsonPath" }
                }
            }
        } else {
            logger.info { "No metrics JSON found at $metricsJsonPath, skipping" }
        }

        return Result(msgCount, metricsCount)
    }
}
