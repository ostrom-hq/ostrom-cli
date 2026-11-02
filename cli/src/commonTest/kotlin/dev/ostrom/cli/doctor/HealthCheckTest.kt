// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.doctor

import dev.ostrom.cli.api.ServerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The verdict on one answer of the health route, whatever the version of the contract this build speaks. */
class HealthCheckTest {
    private val healthy = ServerResponse(200, """{"status":"ok","version":"0.3.1"}""", 5)

    private fun check(response: ServerResponse, contract: String) =
        healthCheck("ostrom.example", "https://ostrom.example", response, contract)

    @Test
    fun theSameMajorAndMinorAsTheContractIsOk() {
        assertEquals(CheckStatus.OK, check(healthy, "0.3.0").status)
        assertEquals(CheckStatus.OK, check(healthy, "v0.3.9").status)
    }

    @Test
    fun anotherMinorOrMajorIsAWarningThatSaysWhichSideIsBehind() {
        val server = check(healthy, "0.4.0")
        assertEquals(CheckStatus.WARNING, server.status)
        assertEquals(
            "ostrom.example reachable (5 ms) · Ostrom 0.3.1, but this ostrom speaks the API 0.4.0: " +
                "the server is behind: ask your admin to update it.",
            server.message,
        )
        assertTrue(check(healthy, "1.3.0").message.endsWith(": the server is behind: ask your admin to update it."))
        assertTrue(check(healthy, "0.2.7").message.endsWith(": this ostrom is behind: ostrom update."))
    }

    @Test
    fun aServerVersionWithoutNumbersIsAWarningToo() {
        val unnumbered = ServerResponse(200, """{"status":"ok","version":"dev"}""", 5)
        val verdict = check(unnumbered, "0.3.0")
        assertEquals(CheckStatus.WARNING, verdict.status)
        assertTrue(verdict.message.endsWith(": compare them with your admin."), verdict.message)
    }

    @Test
    fun aBuildWithoutTheContractsVersionComparesNothing() {
        val verdict = check(healthy, "")
        assertEquals(CheckStatus.OK, verdict.status)
        assertEquals("ostrom.example reachable (5 ms) · Ostrom 0.3.1", verdict.message)
    }

    @Test
    fun aServerInTroubleNamesOnlyTheChecksThatFailed() {
        val trouble = "ostrom.example answers 503: the server is in trouble, try again later."
        for (body in listOf("", "<html>bad gateway</html>", """{"status":"degraded","version":"0.3.1"}""",
            """{"checks":"down"}""", """{"checks":{"database":"ok"}}""")) {
            assertEquals(trouble, check(ServerResponse(503, body, 5), "0.3.0").message, body)
        }
        val failed = ServerResponse(503, """{"checks":{"database":"down","cache":"ok"}}""", 5)
        assertEquals(
            "ostrom.example answers 503: the server is in trouble (database: down), try again later.",
            check(failed, "0.3.0").message,
        )
        val odd = ServerResponse(503, """{"checks":{"database":{"state":"down"},"cache":"d\u001b[31mown"}}""", 5)
        assertEquals(
            "ostrom.example answers 503: the server is in trouble (database: ?, cache: ?), try again later.",
            check(odd, "0.3.0").message,
        )
    }
}
