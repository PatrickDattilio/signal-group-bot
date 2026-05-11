package com.signalbot.store

import com.signalbot.signal.Member
import com.signalbot.signal.SignalCliClient
import com.signalbot.signal.SignalCliException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

class MassDmJob(
    val id: String,
    val groupId: String,
    val groupName: String?,
    val message: String,
    val totalMembers: Int,
    val startedAt: Double,
) {
    val sent = AtomicInteger(0)
    val failed = AtomicInteger(0)

    @Volatile var status: String = "running"
    @Volatile var completedAt: Double? = null
}

class MassDmStore {
    private val jobs = ConcurrentHashMap<String, MassDmJob>()
    private val executor = Executors.newCachedThreadPool()

    fun startJob(
        groupId: String,
        groupName: String?,
        message: String,
        members: List<Member>,
        client: SignalCliClient,
        account: String,
        delayMs: Long = 500L,
    ): MassDmJob {
        val id = UUID.randomUUID().toString()
        val job = MassDmJob(
            id = id,
            groupId = groupId,
            groupName = groupName,
            message = message,
            totalMembers = members.size,
            startedAt = System.currentTimeMillis() / 1000.0,
        )
        jobs[id] = job
        executor.submit {
            try {
                for ((i, member) in members.withIndex()) {
                    try {
                        client.sendMessage(account, member, message)
                        job.sent.incrementAndGet()
                    } catch (e: SignalCliException) {
                        logger.warn { "mass-dm job $id: send failed for ${member.identifier()}: ${e.message}" }
                        job.failed.incrementAndGet()
                    }
                    if (i + 1 < members.size) Thread.sleep(delayMs)
                }
            } finally {
                job.completedAt = System.currentTimeMillis() / 1000.0
                job.status = "completed"
                logger.info { "mass-dm job $id complete: sent=${job.sent.get()} failed=${job.failed.get()} total=${job.totalMembers}" }
                persistJob(job)
            }
        }
        return job
    }

    fun getJob(id: String): MassDmJob? = jobs[id]

    private fun persistJob(job: MassDmJob) {
        try {
            transaction {
                MassDmHistoryTable.insert {
                    it[MassDmHistoryTable.id] = job.id
                    it[groupId] = job.groupId
                    it[groupName] = job.groupName
                    it[message] = job.message
                    it[startedAt] = job.startedAt
                    it[completedAt] = job.completedAt
                    it[totalMembers] = job.totalMembers
                    it[sent] = job.sent.get()
                    it[failed] = job.failed.get()
                }
            }
        } catch (e: Exception) {
            logger.error { "mass-dm: failed to persist job ${job.id}: ${e.message}" }
        }
    }
}
