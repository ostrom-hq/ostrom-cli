// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerJsonTest {
    private val fake = FakeServer()
    private val server = ServerJson(fake)

    @Test
    fun aSuccessIsItsJsonBodyAndAnEmptyBodyIsAnEmptyObject() = runTest {
        fake["POST /a"] = 201 to """{"x":"y"}"""
        fake["GET /b"] = 204 to ""
        assertEquals(ServerAnswer.Ok(objectOf("x" to "y")), server.post("/a", objectOf("k" to 1), "t"))
        assertEquals(ServerAnswer.Ok(JsonObject(emptyMap())), server.get("/b", null))
        val sent = fake.sent("POST /a").single()
        assertEquals("""{"k":1}""", sent.body)
        assertEquals("t", sent.token)
        assertEquals(10_000L, sent.timeoutMillis)
    }

    @Test
    fun aCallCanCarryAnIdempotencyKey() = runTest {
        fake["POST /a"] = 201 to "{}"
        server.call("POST", "/a", null, "t", 5, "key-1")
        server.post("/a", objectOf("k" to 1), "t")
        assertEquals(listOf("key-1", null), fake.sent("POST /a").map { it.idempotencyKey })
    }

    @Test
    fun anErrorIsReadAsTheContractsApiErrorOrThePrototypes() = runTest {
        fake["GET /closed"] = 409 to """{"error":{"type":"session_closed","message":"closed"}}"""
        fake["GET /old"] = 400 to """{"error":"bad thing"}"""
        fake["GET /html"] = 502 to "<html>"
        fake["GET /bare"] = 422 to """{"error":{"type":"invalid"}}"""
        fake["GET /early"] = 101 to "{}"
        assertEquals(ServerAnswer.Failed(409, "session_closed", "closed"), server.get("/closed", null))
        assertEquals(ServerAnswer.Failed(400, null, "bad thing"), server.get("/old", null))
        assertEquals(ServerAnswer.Failed(502, null, "HTTP 502"), server.get("/html", null))
        assertEquals(ServerAnswer.Failed(422, "invalid", "HTTP 422"), server.get("/bare", null), "no message")
        assertEquals(ServerAnswer.Failed(101, null, "HTTP 101"), server.get("/early", null), "below 200")
    }

    @Test
    fun noAnswerIsUnreachable() = runTest {
        fake.unreachable = setOf("GET /x")
        assertEquals(ServerAnswer.Failed(null, "unreachable", "connection refused"), server.get("/x", null, 5))
    }
}
