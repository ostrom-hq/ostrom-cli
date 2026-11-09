// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.capabilities

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.core.api.models.Capabilities
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a server speaks and serves, read once per server and token for the process. */
class ServerCapabilitiesTest {
    private val asked = mutableListOf<Pair<String, ServerRequest>>()
    private var answer: () -> ServerResponse = { ServerResponse(200, SPOKEN, 1) }

    private val api: (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = error("never a bare get: the read carries a token")
            override suspend fun send(request: ServerRequest) = answer().also { asked += url to request }
        }
    }

    @Test
    fun theCapabilitiesAreReadWithTheTokenOncePerServerAndToken() = runTest {
        val capabilities = ServerCapabilitiesImpl(api)
        val expected = CapabilitiesRead.Read(Capabilities(1, "1.4.0", listOf("runners.cloud.v1")))
        assertEquals(expected, capabilities.read("https://ostrom.example/", "ost_1"))
        assertEquals(expected, capabilities.read("https://ostrom.example", "ost_1"), "the same server")
        val read = ServerRequest("GET", "/api/v1/capabilities", token = "ost_1")
        assertEquals(listOf("https://ostrom.example" to read), asked)
        capabilities.read("https://ostrom.example", "ost_adm")
        capabilities.read("https://other.example", "ost_1")
        assertEquals(listOf("ost_1", "ost_adm", "ost_1"), asked.map { it.second.token })
    }

    @Test
    fun aServerFromBeforeTheRouteSaysNothingAndIsNotAskedAgain() = runTest {
        answer = { ServerResponse(404, """{"error":{"type":"not_found","message":"no route"}}""", 1) }
        val capabilities = ServerCapabilitiesImpl(api)
        assertEquals(CapabilitiesRead.Unknown, capabilities.read("https://ostrom.example", "ost_1"))
        assertEquals(CapabilitiesRead.Unknown, capabilities.read("https://ostrom.example", "ost_1"))
        assertEquals(1, asked.size)
    }

    @Test
    fun aFailureSaysWhyAndIsAskedAgain() = runTest {
        val capabilities = ServerCapabilitiesImpl(api)
        answer = { ServerResponse(401, """{"error":{"type":"unauthorized","message":"token revoked"}}""", 1) }
        assertEquals(CapabilitiesRead.Failed(401, "token revoked"), capabilities.read("https://o.example", "t"))
        answer = { ServerResponse(502, "<html>bad gateway</html>", 1) }
        assertEquals(CapabilitiesRead.Failed(502, "HTTP 502"), capabilities.read("https://o.example", "t"))
        answer = { ServerResponse(101, "", 1) }
        assertEquals(CapabilitiesRead.Failed(101, "HTTP 101"), capabilities.read("https://o.example", "t"))
        answer = { ServerResponse(200, """{"apiVersion":"one"}""", 1) }
        val unreadable = CapabilitiesRead.Failed(200, "its capabilities cannot be read")
        assertEquals(unreadable, capabilities.read("https://o.example", "t"))
        answer = { throw ServerUnreachableException("https://o.example", IOException("connection refused")) }
        assertEquals(CapabilitiesRead.Failed(null, "connection refused"), capabilities.read("https://o.example", "t"))
        assertEquals(4, asked.size, "each failure asked again (the unreachable one never answered)")
    }

    @Test
    fun aServerOfAnotherApiNamesBothVersionsAndTheSideToUpdate() {
        assertNull(incompatibility("https://o.example", Capabilities(1, "1.4.0", emptyList()), "1.2.0"))
        assertEquals(
            "https://o.example speaks the Ostrom API v2 (Ostrom 2.0.0), and ostrom 1.2.0 speaks v1: update this " +
                "ostrom: ostrom update.",
            incompatibility("https://o.example", Capabilities(2, "2.0.0", emptyList()), "1.2.0"),
        )
        assertEquals(
            "https://o.example speaks the Ostrom API v0 (Ostrom ?), and ostrom 1.2.0 speaks v1: ask your admin to " +
                "update the server.",
            incompatibility("https://o.example", Capabilities(0, "0.9\u001b]0;x", emptyList()), "1.2.0"),
        )
    }

    private companion object {
        const val SPOKEN = """{"apiVersion":1,"serverVersion":"1.4.0","features":["runners.cloud.v1"],"new":true}"""
    }
}
