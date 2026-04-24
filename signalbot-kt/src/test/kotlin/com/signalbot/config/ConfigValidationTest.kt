package com.signalbot.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ConfigValidationTest {

    private fun asYaml(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        for ((k, v) in map) {
            when (v) {
                null -> sb.append("$k: null\n")
                is String -> {
                    if (v.isEmpty()) sb.append("$k: \"\"\n")
                    else sb.append("$k: \"$v\"\n")
                }
                is Map<*, *> -> {
                    sb.append("$k:\n")
                    for ((sk, sv) in v) sb.append("  $sk: $sv\n")
                }
                else -> sb.append("$k: $v\n")
            }
        }
        return sb.toString()
    }

    @Test
    fun `valid config passes validation`() {
        val yaml = asYaml(mapOf(
            "account" to "+12025551234",
            "group_id" to "base64encodedgroupid",
            "message" to "Welcome message",
            "approval_mode" to "manual",
            "poll_interval_seconds" to 120,
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.isEmpty(), "Expected no errors, got: $errors")
    }

    @Test
    fun `missing required fields reported`() {
        val errors = ConfigValidator.validate("{}\n")
        assertTrue(errors.any { "account" in it })
        assertTrue(errors.any { "group_id" in it })
        assertTrue(errors.any { "message" in it })
    }

    @Test
    fun `empty required fields reported`() {
        val yaml = asYaml(mapOf(
            "account" to "",
            "group_id" to "",
            "message" to "  ",
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "account" in it && "empty" in it })
    }

    @Test
    fun `invalid account format`() {
        val yaml = asYaml(mapOf(
            "account" to "1234567890",
            "group_id" to "group-id",
            "message" to "message",
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "E.164" in it })
    }

    @Test
    fun `invalid account length`() {
        val yaml = asYaml(mapOf(
            "account" to "+123",
            "group_id" to "group-id",
            "message" to "message",
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "length" in it })
    }

    @Test
    fun `invalid approval mode`() {
        val yaml = asYaml(mapOf(
            "account" to "+12025551234",
            "group_id" to "group-id",
            "message" to "message",
            "approval_mode" to "invalid",
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "approval_mode" in it })
    }

    @Test
    fun `numeric field must be number`() {
        val yaml = asYaml(mapOf(
            "account" to "+12025551234",
            "group_id" to "group-id",
            "message" to "message",
            "poll_interval_seconds" to "notanumber",
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "poll_interval_seconds" in it && "number" in it })
    }

    @Test
    fun `numeric field range`() {
        val yaml = asYaml(mapOf(
            "account" to "+12025551234",
            "group_id" to "group-id",
            "message" to "message",
            "poll_interval_seconds" to 5,
        ))
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "poll_interval_seconds" in it && "range" in it })
    }

    @Test
    fun `signald config validation`() {
        val yaml = """
            account: "+12025551234"
            group_id: "group-id"
            message: "message"
            signald:
              max_retries: 20
              timeout: 500
        """.trimIndent()
        val errors = ConfigValidator.validate(yaml)
        assertTrue(errors.any { "max_retries" in it })
        assertTrue(errors.any { "timeout" in it })
    }
}
