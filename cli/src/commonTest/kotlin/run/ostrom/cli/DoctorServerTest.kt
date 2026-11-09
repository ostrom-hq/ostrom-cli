// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
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

    /** A version of the contract this build speaks, or any when the build has none. */
    private val spoken = BuildInfo.CONTRACT_VERSION.ifBlank { "0.1.0" }

    private fun doctor(api: (String) -> OstromApi, installed: Set<String> = setOf("git", "claude")) =
        cli(environment = withServer, installed = installed, api = api).run(arrayOf("doctor"))

    @Test
    fun anOstromServerOfTheContractsVersionIsReachable() {
        assertEquals(0, doctor(answering(200, health("$spoken-dev"), 84)))
        assertOut("  ✓ ostrom.example reachable (84 ms) · Ostrom $spoken-dev\n")
        assertEquals(listOf("/api/v1/health"), asked)
    }

    @Test
    fun aClientErrorARedirectOrAnInformationalAnswerIsNotReachable() {
        val error = """{"error":{"type":"not_found","message":"no"}}"""
        val answers = listOf(404 to error, 401 to error, 301 to "", 302 to "<html>moved</html>", 101 to "")
        for ((status, body) in answers) {
            console.out.clear()
            assertEquals(1, doctor(answering(status, body)), "$status")
            assertOut("  ✗ ostrom.example answers $status on /api/v1/health: not an Ostrom server of this version")
        }
    }

    @Test
    fun anAnswerThatIsNotOstromsHealthIsNotReachable() {
        val bodies = listOf("", "<html>captive portal</html>", """{"status":"ok"}""", """{"version":"0.1.0"}""",
            """{"status":1,"version":"0.1.0"}""", """{"status":"weird","version":"0.1.0"}""", health(""),
            health("\\u001b]0;pwned\\u0007"), health("0.1.0\\n  ✓ all good"))
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
    fun aServerInTroubleSaysWhichOfItsChecksFailed() {
        val body = """{"status":"degraded","version":"0.1.0","checks":""" +
            """{"database":"down","cache":"ok","queue":{"x":1},"bad name!":"down","lag":"slow now"}}"""
        assertEquals(1, doctor(answering(503, body)))
        assertOut("  ✗ ostrom.example answers 503: the server is in trouble (database: down, queue: ?, lag: ?), try")
    }

    @Test
    fun anotherVersionOfTheApiIsAWarning() {
        if (BuildInfo.CONTRACT_VERSION.isBlank()) return // a build without ostrom-core's checkout compares nothing
        assertEquals(0, doctor(answering(200, health("9.4.2"))))
        assertOut("  ! ostrom.example reachable (1 ms) · Ostrom 9.4.2, but this ostrom speaks the API ")
        assertOut(": this ostrom is behind: ostrom update.\n")
    }

    @Test
    fun oneProblemAndOneWarningAreCountedInTheSingular() {
        assertEquals(1, doctor(answering(200, health(spoken)), installed = setOf("claude")))
        val expected = "  ✗ git not found: install git, agents need it.\n" +
            "  ✓ Agents found: claude\n1 problem, 1 warning.\n"
        assertTrue(console.out.endsWith(expected), console.out.toString())
    }
}
