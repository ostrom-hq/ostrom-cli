// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.login

import dev.ostrom.cli.launcher.Credentials
import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.launcher.ServerTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The device flow of `ostrom login` against a scripted server: each poll takes the next answer. */
private const val CONSOLE = "https://app.ostrom.run"

class DeviceLoginTest {
    private val said = mutableListOf<String>()
    private val waits = mutableListOf<Long>()
    private val polls = ArrayDeque<ServerReply>()
    private val calls = mutableListOf<ServerCall>()
    private var start = ServerReply(
        200,
        """{"deviceCode":"dc","userCode":"4F2C-9B1D","verificationUri":"https://github.com/login/device",""" +
            """"interval":5,"expiresIn":20}""",
    )

    private val transport = ServerTransport { call ->
        calls += call
        if (call.path == "/api/v1/auth/device") start else polls.removeFirst()
    }

    private suspend fun signIn(serverUrl: String = "https://api.ostrom.run") =
        DeviceLogin(ServerJson(transport), "https://github.com", CONSOLE, { waits += it }) { said += it }
            .signIn(serverUrl)

    /** Whether a flow whose page is [page] signs in on [serverUrl] (else the page was refused). */
    private suspend fun shown(page: String, serverUrl: String = "https://api.ostrom.run"): Boolean {
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"X","verificationUri":"$page"}""")
        polls.clear()
        polls += ServerReply(200, """{"token":"ost_new","login":"maya-k"}""")
        return signIn(serverUrl) is LoginOutcome.SignedIn
    }

    private fun pending() = ServerReply(428, """{"error":{"type":"authorization_pending","message":"waiting"}}""")

    @Test
    fun theCodeIsShownThenTheServerIsAskedUntilGitHubSaysYes() = runTest {
        polls += pending()
        polls += ServerReply(200, """{"token":"ost_new","login":"maya-k","expiresAt":"2026-11-29T00:00:00Z"}""")
        val signedIn = LoginOutcome.SignedIn(Credentials("ost_new", "maya-k"), emptyList(), "2026-11-29T00:00:00Z")
        assertEquals(signedIn, signIn())
        assertEquals(
            listOf("! Copy your one-time code: 4F2C-9B1D", "  Open https://github.com/login/device and enter it."),
            said,
        )
        assertEquals(listOf(5_000L, 5_000L), waits)
        assertEquals("""{"deviceCode":"dc"}""", calls.last().body)
        assertEquals(null, calls.last().token, "no token before signing in")
    }

    @Test
    fun slowDownWaitsLongerAndAnExpiredCodeStops() = runTest {
        polls += ServerReply(400, """{"error":{"type":"slow_down","message":"slow"}}""")
        polls += pending()
        polls += pending()
        assertEquals(LoginOutcome.Stopped(1, "The code expired. Run ostrom login again."), signIn())
        assertEquals(listOf(5_000L, 10_000L, 10_000L), waits)
    }

    @Test
    fun githubsNoAndAnExpiredCodeAreSaidAsTheSpecSays() = runTest {
        polls += ServerReply(400, """{"error":{"type":"access_denied","message":"no"}}""")
        assertEquals(LoginOutcome.Stopped(2, "GitHub sign-in was cancelled."), signIn())
        polls += ServerReply(400, """{"error":{"type":"expired_token","message":"gone"}}""")
        assertEquals(LoginOutcome.Stopped(1, "The code expired. Run ostrom login again."), signIn())
    }

    @Test
    fun noInvitationAnUnreachableServerOrABrokenAnswerStop() = runTest {
        polls += ServerReply(403, """{"error":{"type":"forbidden","message":"@maya-k has no access yet"}}""")
        assertEquals(LoginOutcome.Stopped(77, "@maya-k has no access yet"), signIn())
        polls += ServerReply(400, """{"error":{"type":"bad_request","message":"boom"}}""")
        assertEquals(LoginOutcome.Stopped(69, "Ostrom could not sign you in: boom"), signIn())
        polls += ServerReply(200, """{"token":"ost_new"}""")
        assertEquals(LoginOutcome.Stopped(69, "Ostrom answered without a token. Try again later."), signIn())
        polls += ServerReply(200, """{"login":"maya"}""")
        assertEquals(LoginOutcome.Stopped(69, "Ostrom answered without a token. Try again later."), signIn())
        start = ServerReply(null, "connection refused")
        val unreachable = "Ostrom is unreachable at https://api.ostrom.run. Check your network, or the --server URL."
        assertEquals(LoginOutcome.Stopped(69, unreachable), signIn())
    }

    @Test
    fun aSignInPageOfTheServersOwnOriginOrItsConsoleIsShown() = runTest {
        // decision of 30/10/2026: the server sends to its console's /device page, of its own origin
        for (page in listOf(
            "https://github.com/login/device",
            "https://api.ostrom.run/device",
            "https://API.Ostrom.run:443/device?from=cli",
            "https://app.ostrom.run/device",
            "https://app.ostrom.run#device",
        )) {
            assertTrue(shown(page), page)
        }
        // another host, a neighbour subdomain, a look-alike, another port, a user in front, a page in clear
        for (page in listOf(
            "https://evil.example/device",
            "https://login.ostrom.run/device",
            "https://api.ostrom.run.evil.example/device",
            "https://api.ostrom.run:8443/device",
            "https://api.ostrom.run@evil.example/device",
            "http://api.ostrom.run/device",
            "http://app.ostrom.run/device",
            "ftp://api.ostrom.run/device",
            "/device",
        )) {
            assertFalse(shown(page), page)
        }
        // in clear, only to this machine, on the very port of the server
        assertTrue(shown("http://localhost:8080/device", "http://localhost:8080"))
        assertTrue(shown("http://[::1]:8080/device", "http://[::1]:8080/"))
        assertFalse(shown("http://localhost:9090/device", "http://localhost:8080"))
        assertFalse(shown("http://ostrom.lan/device", "http://ostrom.lan"))
    }

    @Test
    fun aSignInPageThatIsNotGitHubsIsNeverShown() = runTest {
        // S2-35: a server that is not Ostrom could send the developer to a look-alike page for their code
        for (page in listOf("https://github.com.evil.example/login/device", "http://github.com/login/device")) {
            start = ServerReply(200, """{"deviceCode":"dc","userCode":"X","verificationUri":"$page"}""")
            val stopped = LoginOutcome.Stopped(
                69,
                "https://api.ostrom.run sent a sign-in page that is neither GitHub's nor its own ($page). " +
                    "Check the --server URL.",
            )
            assertEquals(stopped, signIn())
        }
        // an escape sequence after GitHub's address could rewrite the line the developer reads
        val escaped = "https://github.com/login/device\\u001b[2K\\u007f"
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"X","verificationUri":"$escaped"}""")
        val cleaned = "https://api.ostrom.run sent a sign-in page that is neither GitHub's nor its own " +
            "(https://github.com/login/device[2K). Check the --server URL."
        assertEquals(LoginOutcome.Stopped(69, cleaned), signIn())
        assertEquals(emptyList(), said, "the code is never shown")
        assertEquals(List(3) { "/api/v1/auth/device" }, calls.map { it.path })
    }

    @Test
    fun aStartWithoutAUsableCodeStopsAtOnce() = runTest {
        val noCode = "https://api.ostrom.run answered without a usable sign-in code. Check the --server URL."
        val escaped = """{"deviceCode":"dc","userCode":"\u001b[2J"}"""
        for (body in listOf("{}", """{"deviceCode":"dc"}""", """{"userCode":"4F2C"}""", escaped)) {
            start = ServerReply(200, body)
            assertEquals(LoginOutcome.Stopped(69, noCode), signIn(), body)
        }
        assertEquals(emptyList(), said, "nothing is shown")
        assertEquals(emptyList(), waits, "nothing is asked")
    }

    @Test
    fun oddWaitsAreDefaultsOrKeptWithinBounds() = runTest {
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"AB","interval":{},"expiresIn":[1]}""")
        polls += ServerReply(200, """{"token":"t","login":"l"}""")
        assertEquals(LoginOutcome.SignedIn(Credentials("t", "l")), signIn(), "fields of the wrong kind are defaults")
        assertEquals("  Open https://github.com/login/device and enter it.", said.last(), "GitHub's page by default")
        assertEquals(listOf(5_000L), waits)
        // numbers that are not numbers are defaults too
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"AB","interval":"soon","expiresIn":"later"}""")
        polls += ServerReply(200, """{"token":"t","login":"l"}""")
        assertEquals(LoginOutcome.SignedIn(Credentials("t", "l")), signIn())
        assertEquals(listOf(5_000L), waits.drop(1))
        waits.removeAt(1)
        // an interval of 0 would ask in a tight loop; one past a minute, or an expiry past 30 minutes, is cut
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"AB","interval":0,"expiresIn":2}""")
        polls += pending()
        polls += pending()
        assertEquals(LoginOutcome.Stopped(1, "The code expired. Run ostrom login again."), signIn())
        assertEquals(listOf(1_000L, 1_000L), waits.drop(1))
        start = ServerReply(200, """{"deviceCode":"dc","userCode":"AB","interval":3600,"expiresIn":999999}""")
        repeat(30) { polls += pending() }
        assertEquals(LoginOutcome.Stopped(1, "The code expired. Run ostrom login again."), signIn())
        assertEquals(List(30) { 60_000L }, waits.drop(3))
    }

    @Test
    fun aServerLostWhileWaitingIsAskedAgainUntilTheCodeExpires() = runTest {
        // the developer may already have entered the code: a blip, a 5xx or a 429 does not waste it
        polls += ServerReply(null, "connection reset")
        polls += ServerReply(502, """{"error":{"type":"bad_gateway","message":"deploying"}}""")
        polls += ServerReply(429, """{"error":{"type":"rate_limited","message":"later"}}""")
        polls += ServerReply(200, """{"token":"ost_new","login":"maya-k"}""")
        assertEquals(LoginOutcome.SignedIn(Credentials("ost_new", "maya-k")), signIn())
        repeat(4) { polls += ServerReply(null, "connection reset") }
        val unreachable = "Ostrom is unreachable at https://api.ostrom.run. Check your network, or the --server URL."
        assertEquals(LoginOutcome.Stopped(69, unreachable), signIn(), "still lost when the code expires")
        repeat(3) { polls += ServerReply(null, "connection reset") }
        polls += pending()
        val expired = LoginOutcome.Stopped(1, "The code expired. Run ostrom login again.")
        assertEquals(expired, signIn(), "back before the expiry: the code expired")
    }
}
