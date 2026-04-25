package com.signalbot.signal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Response from the fake server: either a result JSON string, or a JSON-RPC error. */
private sealed class FakeResponse {
    data class Ok(val json: String) : FakeResponse()
    data class Error(val code: Int, val message: String) : FakeResponse()
}

private class FakeRpcServer(private val handler: (method: String, params: JsonObject) -> FakeResponse) {
    val received = CopyOnWriteArrayList<JsonObject>()
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile private var running = true

    init {
        thread(isDaemon = true) {
            while (running) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    sock.use { s ->
                        try {
                            val reader = s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                            val line = reader.readLine() ?: return@thread
                            val parsed = Json.parseToJsonElement(line).jsonObject
                            received.add(parsed)
                            val method = parsed["method"]!!.jsonPrimitive.content
                            val params = (parsed["params"] as? JsonObject) ?: buildJsonObject {}
                            val id = parsed["id"]!!.jsonPrimitive.content
                            val out = when (val resp = handler(method, params)) {
                                is FakeResponse.Ok -> """{"jsonrpc":"2.0","id":"$id","result":${resp.json}}""" + "\n"
                                is FakeResponse.Error ->
                                    """{"jsonrpc":"2.0","id":"$id","error":{"code":${resp.code},"message":"${resp.message}"}}""" + "\n"
                            }
                            s.getOutputStream().write(out.toByteArray(StandardCharsets.UTF_8))
                            s.getOutputStream().flush()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }
}

class SignalCliClientTest {
    private var server: FakeRpcServer? = null

    @AfterEach
    fun tearDown() { server?.stop() }

    @Test
    fun `listPendingMembers over TCP`() {
        server = FakeRpcServer { method, _ ->
            when (method) {
                "getGroup" -> FakeResponse.Ok(buildJsonObject {
                    put("id", "gid")
                    put("requestingMembers", buildJsonArray {
                        add(buildJsonObject { put("uuid", "u1"); put("number", "+1") })
                        add(buildJsonObject { put("uuid", "u2"); put("number", "+2") })
                    })
                }.toString())
                else -> FakeResponse.Ok("null")
            }
        }
        val client = SignalCliClient(socketPath = "localhost:${server!!.port}", maxRetries = 0)
        val members = client.listPendingMembers("+1234567", "gid")
        assertEquals(2, members.size)
        assertEquals("u1", members[0].uuid)
        assertEquals("+2", members[1].number)
    }

    @Test
    fun `deny falls back through refuseMembership then updateGroup`() {
        val methodsSeen = CopyOnWriteArrayList<String>()
        server = FakeRpcServer { method, _ ->
            methodsSeen.add(method)
            when (method) {
                "refuseMembership", "refuse_membership" ->
                    FakeResponse.Error(-32601, "Method not found")
                "updateGroup" -> FakeResponse.Ok("{}")
                else -> FakeResponse.Ok("null")
            }
        }
        val client = SignalCliClient(socketPath = "localhost:${server!!.port}", maxRetries = 0)
        client.denyMembership("+1234567", "gid", listOf(Member(uuid = "u1", number = "+1")))
        assertTrue(methodsSeen.contains("refuseMembership"))
        assertTrue(methodsSeen.contains("refuse_membership"))
        assertTrue(methodsSeen.contains("updateGroup"))
    }

    @Test
    fun `listGroups returns empty on null result`() {
        server = FakeRpcServer { _, _ -> FakeResponse.Ok("null") }
        val client = SignalCliClient(socketPath = "localhost:${server!!.port}", maxRetries = 0)
        assertTrue(client.listGroups().isEmpty())
    }

    @Test
    fun `addMembersToGroup uses updateGroup over daemon`() {
        val methodsSeen = CopyOnWriteArrayList<String>()
        server = FakeRpcServer { method, _ ->
            methodsSeen.add(method)
            when (method) {
                "updateGroup" -> FakeResponse.Ok("{}")
                else -> FakeResponse.Ok("null")
            }
        }
        val client = SignalCliClient(socketPath = "localhost:${server!!.port}", maxRetries = 0)
        client.addMembersToGroup(
            "+1",
            "otherGid",
            listOf(Member(uuid = "u1", number = "+99")),
        )
        assertTrue(methodsSeen.contains("updateGroup"))
    }

    @Test
    fun `listGroups unwraps array-in-object response`() {
        server = FakeRpcServer { _, _ ->
            FakeResponse.Ok("""{"groups":[{"id":"g1","name":"G1"}]}""")
        }
        val client = SignalCliClient(socketPath = "localhost:${server!!.port}", maxRetries = 0)
        val groups = client.listGroups()
        assertEquals(1, groups.size)
    }
}
