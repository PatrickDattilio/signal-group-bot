package com.signalbot.web

import com.signalbot.config.Config
import com.signalbot.config.ConfigLoader
import com.signalbot.signal.SignalCliClient
import com.signalbot.store.MessagedStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

class WebAppContext(
    val configPath: String,
    val clientFactory: (Config) -> SignalCliClient = { cfg -> SignalCliClient(cfg.signalCli) },
    val store: MessagedStore = MessagedStore(),
) {
    private var cachedClient: SignalCliClient? = null
    private val lock = Any()

    fun loadConfig(): Config = ConfigLoader.load(configPath)

    fun client(): SignalCliClient {
        synchronized(lock) {
            val existing = cachedClient
            if (existing != null) return existing
            val cfg = loadConfig()
            val c = clientFactory(cfg)
            cachedClient = c
            return c
        }
    }
}

private fun createNettyServer(context: WebAppContext, host: String, port: Int) =
    embeddedServer(Netty, host = host, port = port) {
        webModule(context)
    }

/**
 * Blocks the current thread until the server stops (e.g. SIGTERM stops the engine).
 * Used by [com.signalbot.UiCmd].
 */
fun startWebServerBlocking(context: WebAppContext, host: String, port: Int) {
    val server = createNettyServer(context, host, port)
    logger.info { "Starting UI at http://$host:$port" }
    server.start(wait = true)
}

/**
 * Starts the Netty server in the background. Caller must call [io.ktor.server.engine.EmbeddedServer.stop] on shutdown.
 * Used by [com.signalbot.RunCmd] together with the bot loop.
 */
fun startWebServerAsync(context: WebAppContext, host: String, port: Int) =
    createNettyServer(context, host, port).also { server ->
        logger.info { "Starting UI at http://$host:$port" }
        server.start(wait = false)
    }

fun Application.webModule(context: WebAppContext) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging)

    val secretKey = (System.getenv("SIGNALBOT_SECRET_KEY") ?: "change-this-in-production").toByteArray()
    val cookieSecure = (System.getenv("SIGNALBOT_COOKIE_SECURE") ?: "1")
        .trim().lowercase() !in setOf("0", "false", "no")

    install(Sessions) {
        cookie<AdminSession>("signalbot_session") {
            cookie.httpOnly = true
            cookie.extensions["SameSite"] = "Lax"
            cookie.secure = cookieSecure
            cookie.maxAgeInSeconds = 60L * 60 * 12
            transform(SessionTransportTransformerMessageAuthentication(secretKey))
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.local.uri
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if (path == "/" || path.startsWith("/api/") || path.startsWith("/login")) {
            call.response.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            call.response.header("Pragma", "no-cache")
            call.response.header("Expires", "0")
        }
    }

    installRoutes(context)
}
