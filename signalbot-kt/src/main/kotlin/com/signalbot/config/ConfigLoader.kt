package com.signalbot.config

import java.io.File

object ConfigLoader {
    fun defaultConfigPath(): String = System.getenv("SIGNALBOT_CONFIG") ?: "config.yaml"
    fun defaultDbPath(): String = System.getenv("SIGNALBOT_DB") ?: "signalbot.db"

    /**
     * Load and validate config. Prints to stderr and returns null on failure (matches Python exit-on-error shape,
     * but leaves actual exit behavior to the caller).
     */
    fun load(path: String = defaultConfigPath()): Config {
        val file = File(path)
        if (!file.isFile) {
            System.err.println("Config not found: $path. Copy config.example.yaml to config.yaml and edit.")
            kotlin.system.exitProcess(1)
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("Cannot read $path: ${e.message}")
            kotlin.system.exitProcess(1)
        }
        if (text.isBlank()) {
            System.err.println("Config is empty.")
            kotlin.system.exitProcess(1)
        }

        val errors = ConfigValidator.validate(text)
        if (errors.isNotEmpty()) {
            System.err.println("Configuration validation failed (${errors.size} error(s)):")
            errors.forEachIndexed { i, err -> System.err.println("  ${i + 1}. $err") }
            kotlin.system.exitProcess(1)
        }

        return try {
            Config.fromYaml(text)
        } catch (e: Exception) {
            System.err.println("Failed to parse config: ${e.message}")
            kotlin.system.exitProcess(1)
        }
    }

    fun loadOrNull(path: String = defaultConfigPath()): Config? {
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (ConfigValidator.validate(text).isNotEmpty()) return null
            Config.fromYaml(text)
        } catch (_: Exception) {
            null
        }
    }
}
