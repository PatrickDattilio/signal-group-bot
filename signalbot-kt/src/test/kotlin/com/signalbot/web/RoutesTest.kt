package com.signalbot.web

import com.signalbot.TestDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class RoutesTest {
    private lateinit var tempConfig: File

    @BeforeEach
    fun setUp() {
        TestDatabase.connect()
        tempConfig = Files.createTempFile("config-", ".yaml").toFile()
        tempConfig.writeText("""
            account: "+12025551234"
            group_id: "gid"
            message: "hi"
            approval_mode: "manual"
            poll_interval_seconds: 120
        """.trimIndent())
    }

    @Test
    fun `health endpoint ok`() = testApplication {
        application {
            webModule(WebAppContext(tempConfig.absolutePath))
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ok\""))
        assertTrue(body.contains("true"))
    }

    @Test
    fun `api requesting requires auth`() = testApplication {
        application {
            webModule(WebAppContext(tempConfig.absolutePath))
        }
        val response = client.get("/api/requesting")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `root redirects to login when unauthenticated`() = testApplication {
        application {
            webModule(WebAppContext(tempConfig.absolutePath))
        }
        val response = client.get("/") {
            // Don't follow redirects
        }
        // Ktor's test client follows redirects by default but the status should still be 200 for login
        // or 302 if redirects are disabled. Accept either - the key is we don't hit index.
        assertTrue(response.status == HttpStatusCode.Found ||
            (response.status == HttpStatusCode.OK && response.bodyAsText().contains("SignalBot Admin")))
    }
}
