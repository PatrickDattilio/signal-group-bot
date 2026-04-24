package com.signalbot.web

import at.favre.lib.crypto.bcrypt.BCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Serializable
data class AdminSession(val username: String, val authenticated: Boolean = true)

/**
 * Login rate limiting, bcrypt (and Werkzeug pbkdf2) hash verification, IP extraction.
 * Mirrors the auth helpers in web_ui.py.
 */
object Auth {
    val loginWindowSeconds: Int = (System.getenv("SIGNALBOT_LOGIN_WINDOW_SECONDS") ?: "900").toInt()
    val loginMaxAttempts: Int = (System.getenv("SIGNALBOT_LOGIN_MAX_ATTEMPTS") ?: "5").toInt()
    val loginLockoutSeconds: Int = (System.getenv("SIGNALBOT_LOGIN_LOCKOUT_SECONDS") ?: "900").toInt()

    private val attempts = ConcurrentHashMap<String, MutableList<Double>>()
    private val locks = ConcurrentHashMap<String, Double>()

    fun clientIp(call: ApplicationCall): String {
        val xff = call.request.header("X-Forwarded-For")?.trim()
        if (!xff.isNullOrEmpty()) return xff.substringBefore(",").trim()
        return call.request.origin.remoteHost
    }

    fun isLoginRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis() / 1000.0
        val unlockAt = locks[ip] ?: 0.0
        if (unlockAt > now) return true
        if (unlockAt > 0.0) locks.remove(ip)
        val recent = (attempts[ip] ?: mutableListOf()).filter { (now - it) <= loginWindowSeconds }.toMutableList()
        attempts[ip] = recent
        return false
    }

    fun recordLoginFailure(ip: String) {
        val now = System.currentTimeMillis() / 1000.0
        val recent = (attempts[ip] ?: mutableListOf()).filter { (now - it) <= loginWindowSeconds }.toMutableList()
        recent += now
        attempts[ip] = recent
        if (recent.size >= loginMaxAttempts) {
            locks[ip] = now + loginLockoutSeconds
            attempts[ip] = mutableListOf()
        }
    }

    fun clearLoginFailures(ip: String) {
        attempts.remove(ip)
        locks.remove(ip)
    }

    fun verifyAdminPassword(rawPassword: String, configuredHash: String): Boolean {
        if (configuredHash.isBlank()) return false
        val hash = configuredHash.trim()
        return when {
            hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$") -> {
                try {
                    val res = BCrypt.verifyer().verify(rawPassword.toCharArray(), hash.toCharArray())
                    res.verified
                } catch (e: IllegalArgumentException) {
                    logger.error { "Invalid bcrypt hash format: ${e.message}" }
                    false
                }
            }
            hash.startsWith("pbkdf2:") -> verifyWerkzeugPbkdf2(rawPassword, hash)
            else -> false
        }
    }

    /** Minimal Werkzeug pbkdf2 compat: pbkdf2:sha256:<iter>$<salt>$<hex> */
    private fun verifyWerkzeugPbkdf2(password: String, hash: String): Boolean {
        return try {
            val colonParts = hash.split(":", limit = 3)
            val dollarParts = colonParts.getOrNull(2)?.split("$") ?: return false
            if (colonParts.size != 3 || dollarParts.size != 3) return false
            val method = colonParts[1].substringBefore(":")
            val iterations = dollarParts[0].toInt()
            val salt = dollarParts[1]
            val expected = dollarParts[2]
            val algo = when (method) {
                "sha256" -> "PBKDF2WithHmacSHA256"
                "sha512" -> "PBKDF2WithHmacSHA512"
                "sha1" -> "PBKDF2WithHmacSHA1"
                else -> return false
            }
            val spec = javax.crypto.spec.PBEKeySpec(
                password.toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                iterations,
                expected.length * 4,
            )
            val factory = javax.crypto.SecretKeyFactory.getInstance(algo)
            val computed = factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
            // constant-time compare
            if (computed.length != expected.length) return false
            var r = 0
            for (i in expected.indices) r = r or (computed[i].code xor expected[i].code)
            r == 0
        } catch (e: Exception) {
            logger.error { "Werkzeug hash verify failed: ${e.message}" }
            false
        }
    }
}
