// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom doctor` and the server: reachable means an Ostrom server answering its health route, of a known version. */
class DoctorServerTest : CliKit() {
    private val asked = mutableListOf<String>()

    /** A server answering every GET with [status] and [body] after [millis], recording the paths asked. */
    private fun answering(status: Int, body: String, millis: Long = 1): (String) -> OstromApi = {
        object : OstromApi {
            override suspend fun get(path: String) = ServerResponse(status, body, millis).also { asked += path }
            override suspend fun send(request: ServerRequest) = get(request.path)
        }
    }

    private fun health(version: String, status: String = "ok") = """{"status":"$status","version":"$version"}"""

    private fun doctor(api: (String) -> OstromApi, installed: Set<String> = setOf("git", "claude")) =
        cli(environment = withServer, installed = installed, api = api).run(arrayOf("doctor"))

    @Test
    fun anOstromServerOfTheContractsVersionIsReachable() {
        assertEquals(0, doctor(answering(200, health("${BuildInfo.CONTRACT_VERSION}-dev"), 84)))
        assertOut("  ✓ ostrom.example reachable (84 ms) · Ostrom ${BuildInfo.CONTRACT_VERSION}-dev\n")
        assertEquals(listOf("/api/v1/health"), asked)
    }

    @Test
    fun aClientErrorOrARedirectIsNotReachable() {
        for (status in listOf(404, 401, 301)) {
            console.out.clear()
            assertEquals(1, doctor(answering(status, """{"error":{"type":"not_found","message":"no"}}""")), "$status")
            assertOut("  ✗ ostrom.example answers $status on /api/v1/health: not an Ostrom server of this version")
        }
    }

    @Test
    fun anAnswerThatIsNotOstromsHealthIsNotReachable() {
        val bodies = listOf("", "<html>captive portal</html>", """{"status":"ok"}""", """{"version":"0.1.0"}""",
            """{"status":1,"version":"0.1.0"}""")
        for (body in bodies) {
            console.out.clear()
            assertEquals(1, doctor(answering(200, body)), body)
            assertOut("  ✗ ostrom.example answers, but not as an Ostrom server: check the server URL")
        }
    }

    @Test
    fun aServerInTroubleIsAProblemToo() {
        assertEquals(1, doctor(answering(503, health("0.1.0", status = "degraded"))))
        assertOut("  ✗ ostrom.example answers 503: the server is in trouble, try again later.\n")
    }

    @Test
    fun anotherVersionOfTheApiIsAWarning() {
        assertEquals(0, doctor(answering(200, health("9.4.2"))))
        assertOut("  ! ostrom.example reachable (1 ms) · Ostrom 9.4.2, but this ostrom speaks the API ")
    }

    @Test
    fun oneProblemAndOneWarningAreCountedInTheSingular() {
        assertEquals(1, doctor(answering(204, health(BuildInfo.CONTRACT_VERSION)), installed = setOf("claude")))
        val expected = "  ✗ git not found: install git, agents need it.\n" +
            "  ✓ Agents found: claude\n1 problem, 1 warning.\n"
        assertTrue(console.out.endsWith(expected), console.out.toString())
    }
}
