package com.signalbot.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar
import com.charleskorn.kaml.yamlList

/**
 * Configuration for SignalBot. Mirrors the shape of config.yaml from the Python implementation.
 * Parsing uses a permissive YAML-map-based approach so unknown/optional keys don't fail validation,
 * and legacy fields (signald, signal_cli_socket_path, signald_socket_path) still work.
 */
data class SignalCliConfig(
    val socketPath: String? = null,
    val cliPath: String? = null,
    val cliConfigPath: String? = null,
    val maxRetries: Int = 3,
    val retryDelay: Double = 1.0,
    val timeout: Int = 30,
    val tryCliFallbackForApprove: Boolean = false,
)

data class FilterConfig(
    val allowlistEnabled: Boolean = false,
    val allowlist: List<String> = emptyList(),
    val blocklist: List<String> = emptyList(),
    val rateLimitEnabled: Boolean = false,
    val rateLimitMaxRequests: Int = 10,
    val rateLimitWindowSeconds: Int = 3600,
)

data class Config(
    val account: String,
    val groupId: String,
    val message: String,
    val messageFollowUp: String? = null,
    val approveRulesMessage: String? = null,
    val approveAddToGroupId: String? = null,
    val approveAddToGroupId2: String? = null,
    val approvalMode: String = "manual",
    val autoApproveDelaySeconds: Int = 0,
    val cooldownSeconds: Int = 0,
    val pollIntervalSeconds: Int = 120,
    val signalCli: SignalCliConfig = SignalCliConfig(),
    val filters: FilterConfig = FilterConfig(),
) {
    companion object {
        fun fromYaml(yaml: String): Config {
            val root = Yaml.default.parseToYamlNode(yaml).yamlMap

            fun s(key: String): String? = (root.entries.entries.firstOrNull { it.key.content == key }?.value as? YamlNode)
                ?.let { runCatching { it.yamlScalar.content }.getOrNull() }

            fun map(key: String) = root.entries.entries.firstOrNull { it.key.content == key }?.value?.let { n ->
                runCatching { n.yamlMap }.getOrNull()
            }

            val signalCliMap = map("signal_cli") ?: map("signald")
            val filtersMap = map("filters")
            val rateLimitMap = filtersMap?.entries?.entries?.firstOrNull { it.key.content == "rate_limit" }?.value
                ?.let { runCatching { it.yamlMap }.getOrNull() }

            fun YamlNode.asScalar(): String? = runCatching { this.yamlScalar.content }.getOrNull()
            fun mapGet(m: com.charleskorn.kaml.YamlMap?, key: String): YamlNode? =
                m?.entries?.entries?.firstOrNull { it.key.content == key }?.value

            fun strList(key: String, within: com.charleskorn.kaml.YamlMap? = filtersMap): List<String> {
                val node = mapGet(within, key) ?: return emptyList()
                return runCatching {
                    node.yamlList.items.mapNotNull { it.asScalar() }
                }.getOrElse { emptyList() }
            }

            val signalCli = SignalCliConfig(
                socketPath = mapGet(signalCliMap, "socket_path")?.asScalar()
                    ?: s("signal_cli_socket_path")
                    ?: s("signald_socket_path"),
                cliPath = mapGet(signalCliMap, "cli_path")?.asScalar()
                    ?: s("signal_cli_cli_path"),
                cliConfigPath = mapGet(signalCliMap, "cli_config_path")?.asScalar()
                    ?: s("signal_cli_cli_config_path"),
                maxRetries = mapGet(signalCliMap, "max_retries")?.asScalar()?.toIntOrNull() ?: 3,
                retryDelay = mapGet(signalCliMap, "retry_delay")?.asScalar()?.toDoubleOrNull() ?: 1.0,
                timeout = mapGet(signalCliMap, "timeout")?.asScalar()?.toIntOrNull() ?: 30,
                tryCliFallbackForApprove = mapGet(signalCliMap, "try_cli_fallback_for_approve")
                    ?.asScalar()?.toBoolean() ?: false,
            )

            val filters = FilterConfig(
                allowlistEnabled = mapGet(filtersMap, "allowlist_enabled")?.asScalar()?.toBoolean() ?: false,
                allowlist = strList("allowlist"),
                blocklist = strList("blocklist"),
                rateLimitEnabled = mapGet(rateLimitMap, "enabled")?.asScalar()?.toBoolean() ?: false,
                rateLimitMaxRequests = mapGet(rateLimitMap, "max_requests")?.asScalar()?.toIntOrNull() ?: 10,
                rateLimitWindowSeconds = mapGet(rateLimitMap, "window_seconds")?.asScalar()?.toIntOrNull() ?: 3600,
            )

            return Config(
                account = s("account") ?: "",
                groupId = s("group_id") ?: "",
                message = s("message") ?: "",
                messageFollowUp = s("message_follow_up")?.takeIf { it.isNotBlank() },
                approveRulesMessage = s("approve_rules_message")?.takeIf { it.isNotBlank() },
                approveAddToGroupId = s("approve_add_to_group_id")?.takeIf { it.isNotBlank() },
                approveAddToGroupId2 = s("approve_add_to_group_id_2")?.takeIf { it.isNotBlank() },
                approvalMode = s("approval_mode") ?: "manual",
                autoApproveDelaySeconds = s("auto_approve_delay_seconds")?.toIntOrNull() ?: 0,
                cooldownSeconds = s("cooldown_seconds")?.toIntOrNull() ?: 0,
                pollIntervalSeconds = s("poll_interval_seconds")?.toIntOrNull() ?: 120,
                signalCli = signalCli,
                filters = filters,
            )
        }
    }
}

/**
 * Validate a Config. Mirrors main.validate_config from the Python implementation.
 * Accepts a map-like view so it works both on parsed Config and on raw parsed YAML maps.
 * For simplicity we validate the parsed Config's fields plus the raw map for numeric types.
 */
object ConfigValidator {
    fun validate(yaml: String): List<String> {
        val errors = mutableListOf<String>()
        val rootNode = try {
            Yaml.default.parseToYamlNode(yaml).yamlMap
        } catch (e: Exception) {
            return listOf("Invalid YAML: ${e.message}")
        }

        fun raw(key: String): YamlNode? =
            rootNode.entries.entries.firstOrNull { it.key.content == key }?.value

        fun scalar(key: String): String? = raw(key)?.let { runCatching { it.yamlScalar.content }.getOrNull() }

        val required = mapOf(
            "account" to "Signal account phone number (E.164 format, e.g. +12025551234)",
            "group_id" to "Signal group ID (base64-encoded)",
            "message" to "Message to send to requesting members",
        )
        for ((field, desc) in required) {
            val node = raw(field)
            val content = node?.let { runCatching { it.yamlScalar.content }.getOrNull() }
            if (node == null || content == null) {
                errors += "Missing required field '$field': $desc"
            } else if (content.isBlank()) {
                errors += "Field '$field' cannot be empty: $desc"
            }
        }

        val account = scalar("account") ?: ""
        if (account.isNotEmpty()) {
            if (!account.startsWith("+")) {
                errors += "Account '$account' should be in E.164 format (start with +)"
            }
            if (account.length < 8 || account.length > 16) {
                errors += "Account '$account' has invalid length (expected 8-16 chars)"
            }
        }

        val approvalMode = scalar("approval_mode") ?: "manual"
        if (approvalMode !in setOf("manual", "automatic")) {
            errors += "Invalid approval_mode '$approvalMode' (must be 'manual' or 'automatic')"
        }

        val numericFields = mapOf(
            "auto_approve_delay_seconds" to (0 to 3600),
            "cooldown_seconds" to (0 to 86400 * 7),
            "poll_interval_seconds" to (10 to 3600),
        )
        for ((field, range) in numericFields) {
            val (min, max) = range
            val raw = scalar(field) ?: continue
            val num = raw.toIntOrNull()
            if (num == null) {
                errors += "Field '$field' must be a number, got: $raw"
                continue
            }
            if (num < min || num > max) {
                errors += "Field '$field' value $num out of range ($min-$max)"
            }
        }

        val backendNode = raw("signal_cli") ?: raw("signald")
        val backendMap = backendNode?.let { runCatching { it.yamlMap }.getOrNull() }
        if (backendMap != null) {
            val mr = backendMap.entries.entries.firstOrNull { it.key.content == "max_retries" }
                ?.value?.let { runCatching { it.yamlScalar.content }.getOrNull() }
            if (mr != null) {
                val n = mr.toIntOrNull()
                if (n == null) errors += "signal_cli.max_retries must be a number"
                else if (n < 0 || n > 10) errors += "signal_cli.max_retries $n out of range (0-10)"
            }
            val to = backendMap.entries.entries.firstOrNull { it.key.content == "timeout" }
                ?.value?.let { runCatching { it.yamlScalar.content }.getOrNull() }
            if (to != null) {
                val n = to.toIntOrNull()
                if (n == null) errors += "signal_cli.timeout must be a number"
                else if (n < 5 || n > 300) errors += "signal_cli.timeout $n out of range (5-300)"
            }
        }

        return errors
    }
}
