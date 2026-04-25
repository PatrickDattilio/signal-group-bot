package com.signalbot.bot

import com.signalbot.config.Config
import com.signalbot.signal.SignalCliClient
import com.signalbot.signal.SignalCliConnectionException
import com.signalbot.signal.SignalCliException
import com.signalbot.signal.Member
import com.signalbot.store.MessagedStore
import com.signalbot.store.MetricsStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Bot polling loop (coroutine-based delay between polls).
 */
suspend fun runBot(
    config: Config,
    client: SignalCliClient = SignalCliClient(config.signalCli),
    store: MessagedStore = MessagedStore(),
    metrics: MetricsStore = MetricsStore(enabled = true),
    dryRun: Boolean = false,
) {
    val account = config.account
    val groupId = config.groupId
    val messageTemplate = MessageTemplate(config.message.trim())
    val followUpTemplate = config.messageFollowUp?.trim()?.takeIf { it.isNotBlank() }?.let { MessageTemplate(it) }
    val approvalMode = config.approvalMode
    val autoApproveDelay = config.autoApproveDelaySeconds
    val cooldown = config.cooldownSeconds
    val pollInterval = config.pollIntervalSeconds

    val memberFilter = MemberFilter(
        allowlist = config.filters.allowlist,
        blocklist = config.filters.blocklist,
        allowlistEnabled = config.filters.allowlistEnabled,
    )
    val rateLimiter = if (config.filters.rateLimitEnabled) RateLimiter(
        maxRequests = config.filters.rateLimitMaxRequests,
        windowSeconds = config.filters.rateLimitWindowSeconds,
    ) else null

    val modeStr = if (dryRun) "DRY RUN" else approvalMode.uppercase()
    val trimmedGid = if (groupId.length > 20) "${groupId.take(20)}..." else groupId
    logger.info { "Bot started [mode=$modeStr, account=$account, group=$trimmedGid, poll_interval=${pollInterval}s, cooldown=${cooldown}s]" }

    val maxConsecutiveErrors = 10
    var consecutiveErrors = 0
    var pollCount = 0

    coroutineScope {
        while (coroutineContext.isActive) {
            pollCount += 1
            val pending: List<Member> = try {
                val p = client.listPendingMembers(account, groupId)
                consecutiveErrors = 0
                metrics.recordPollCompleted()
                logger.debug { "Poll #$pollCount completed, found ${p.size} requesting members" }
                p
            } catch (e: SignalCliConnectionException) {
                consecutiveErrors += 1
                metrics.recordPollFailed("connection_error")
                logger.error { "Connection to signal-cli failed (poll #$pollCount, error $consecutiveErrors/$maxConsecutiveErrors): ${e.message}" }
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    logger.error { "Too many consecutive connection errors. Exiting." }
                    kotlin.system.exitProcess(1)
                }
                delaySeconds(minOf(pollInterval, 60))
                continue
            } catch (e: SignalCliException) {
                consecutiveErrors += 1
                metrics.recordPollFailed("signal_cli_error")
                logger.error { "get_group failed (poll #$pollCount, error $consecutiveErrors/$maxConsecutiveErrors): ${e.message}" }
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    logger.error { "Too many consecutive errors. Exiting." }
                    kotlin.system.exitProcess(1)
                }
                delaySeconds(pollInterval)
                continue
            }

            if (pending.isNotEmpty()) {
                logger.info { "Poll #$pollCount: Found ${pending.size} requesting member(s)" }
            }

            for (member in pending) {
                if (!coroutineContext.isActive) break

                if (member.uuid.isBlank() && member.number.isBlank()) {
                    logger.warn { "Skipping member with no uuid/number: $member" }
                    continue
                }

                if (rateLimiter != null) {
                    val rl = rateLimiter.check(member)
                    if (!rl.allowed) {
                        logger.warn { "Rate limit exceeded for ${member.toAddressMap()}: ${rl.reason}" }
                        continue
                    }
                }

                val decision = memberFilter.shouldApprove(member)
                if (store.isFilterSkipped(member)) {
                    if (decision.shouldApprove) {
                        store.clearFilterSkipped(member)
                    } else {
                        logger.debug { "Skipping member (filter / blocklist): ${member.toAddressMap()}" }
                        continue
                    }
                }

                if (store.isWithinVettingCooldown(member, cooldown)) {
                    logger.debug { "Skipping member (vetting within cooldown): ${member.toAddressMap()}" }
                    continue
                }

                if (!decision.shouldApprove) {
                    logger.info { "Skipping member ${member.toAddressMap()}: ${decision.reason}" }
                    store.markFilterSkipped(member)
                    continue
                }

                val isFollowUp = store.shouldSendVettingFollowupTemplate(member, followUpTemplate != null)
                val template = if (isFollowUp) followUpTemplate!! else messageTemplate
                val body = template.render(member)

                if (dryRun) {
                    val preview = if (body.length > 60) "${body.take(60)}..." else body
                    logger.info { "[DRY RUN] Would send ${if (isFollowUp) "follow-up" else "message"} to ${member.toAddressMap()}: $preview" }
                    if (approvalMode == "automatic") {
                        logger.info { "[DRY RUN] Would approve ${member.toAddressMap()} (filter: ${decision.reason})" }
                    }
                    continue
                }

                try {
                    client.sendMessage(account, member, body)
                    if (isFollowUp) {
                        store.markVettingFollowupSent(member)
                    } else {
                        store.markVettingSent(member)
                    }
                    metrics.recordMessageSent()
                    logger.info { "Sent ${if (isFollowUp) "follow-up" else "message"} to ${member.toAddressMap()} (filter: ${decision.reason})" }
                } catch (e: SignalCliException) {
                    metrics.recordMessageFailed(e.javaClass.simpleName)
                    logger.error { "Send failed for ${member.toAddressMap()}: ${e.message}" }
                    continue
                }

                if (approvalMode == "automatic") {
                    if (autoApproveDelay > 0) {
                        logger.debug { "Waiting ${autoApproveDelay}s before auto-approval" }
                        delaySeconds(autoApproveDelay)
                    }
                    if (!coroutineContext.isActive) break
                    try {
                        client.approveMembership(account, groupId, listOf(member))
                        metrics.recordApprovalSucceeded()
                        logger.info { "Approved ${member.toAddressMap()}" }
                    } catch (e: SignalCliException) {
                        metrics.recordApprovalFailed(e.javaClass.simpleName)
                        logger.error { "Approve failed for ${member.toAddressMap()}: ${e.message}" }
                    }
                }
            }

            if (!coroutineContext.isActive) break

            if (pollCount % 10 == 0) {
                val s = metrics.getStats()
                logger.info {
                    "Stats: polls=${s.pollsCompleted}, messages=${s.messagesSent}/${s.messagesSent + s.messagesFailed}" +
                        " (${"%.1f".format(s.messageSuccessRate ?: 0.0)}%), " +
                        "approvals=${s.approvalsSucceeded}/${s.approvalsSucceeded + s.approvalsFailed}"
                }
            }

            delaySeconds(pollInterval)
        }
    }

    val s = metrics.getStats()
    logger.info {
        "Bot shutdown complete. Final stats: uptime=${"%.1f".format(s.uptimeHours)}h, " +
            "polls=${s.pollsCompleted}, messages=${s.messagesSent}, approvals=${s.approvalsSucceeded}"
    }
}

private suspend fun delaySeconds(seconds: Int) {
    try {
        delay(seconds * 1000L)
    } catch (e: CancellationException) {
        throw e
    }
}
