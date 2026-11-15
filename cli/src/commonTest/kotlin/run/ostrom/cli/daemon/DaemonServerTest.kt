// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.daemon

import kotlinx.coroutines.test.runTest
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The daemon's calls as they read the server's answers, and how a failed one is told. */
class DaemonServerTest {
    private fun failed(status: Int?, type: String?) = DaemonCallException(ServerAnswer.Failed(status, type, "Nope."))

    /** A server that answers every call with [status] and [body]. */
    private fun answering(body: String, status: Int = 200, token: String? = "ost_1") =
        DaemonServer(ServerJson { ServerReply(status, body) }) { token }

    @Test
    fun aFailedCallIsToldByItsTypeElseItsStatus() {
        assertEquals("session_closed: Nope.", failed(409, "session_closed").describe())
        assertEquals("HTTP 500: Nope.", failed(500, null).describe())
        assertEquals("Nope.", failed(null, null).describe(), "Neither: the message alone")
    }

    @Test
    fun onlyAnAnswerOfTheServerIsARefusal() {
        val refused = failed(404, "not_found")
        assertSame(refused, refusalOf(refused))
        assertNull(refusalOf(failed(null, "unreachable")), "The server was not reached")
        assertNull(refusalOf(IllegalStateException("not a call")))
        assertTrue(refusedWith(refused, 404))
        assertFalse(refusedWith(refused, 401))
        assertFalse(refusedWith(failed(null, "unreachable"), 404))
        assertFalse(refusedWith(IllegalStateException("not a call"), 404))
    }

    @Test
    fun theCapabilitiesAreTheFeaturesThatAreNames() = runTest {
        assertEquals(listOf("a.v1", "2"), answering("""{"features":["a.v1",{"b":1},2]}""").capabilities())
    }

    @Test
    fun anOpenedHostWithoutItsOrganisationOrEpochIsAFailure() = runTest {
        for (body in listOf("""{"epoch":1}""", """{"orgId":"o1"}""")) {
            val error = assertFailsWith<DaemonCallException> { answering(body).host("s1") }
            assertNull(error.status, body)
        }
        assertEquals(OpenedHost("o1", 2), answering("""{"orgId":"o1","epoch":2}""").host("s1"))
    }

    @Test
    fun aDeviceNotSignedInCallsNothing() = runTest {
        val error = assertFailsWith<DaemonCallException> { answering("{}", token = null).capabilities() }
        assertEquals(401, error.status)
        assertEquals("unauthorized: Not signed in: run ostrom login.", error.describe())
    }
}
