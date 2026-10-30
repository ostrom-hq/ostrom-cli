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

/** The device flow of `ostrom login` against a scripted server: each poll takes the next answer. */
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

    private suspend fun signIn() =
        DeviceLogin(ServerJson(transport), "https://github.com", { waits += it }) { said += it }
            .signIn("https://api.ostrom.run")

    private fun pending() = ServerReply(428, """{"error":{"type":"authorization_pending","message":"waiting"}}""")

    @Test
    fun theCodeIsShownThenTheServerIsAskedUntilGitHubSaysYes() = runTest {
        polls += pending()
        polls += ServerReply(200, """{"token":"ost_new","login":"maya-k","expiresAt":"2026-11-29T00:00:00Z"}""")
        assertEquals(LoginOutcome.SignedIn(Credentials("ost_new", "maya-k")), signIn())
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
        polls += ServerReply(500, """{"error":{"type":"internal","message":"boom"}}""")
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
    fun aSignInPageThatIsNotGitHubsIsNeverShown() = runTest {
        // S2-35: a server that is not Ostrom could send the developer to a look-alike page for their code
        for (page in listOf("https://github.com.evil.example/login/device", "http://github.com/login/device")) {
            start = ServerReply(200, """{"deviceCode":"dc","userCode":"X","verificationUri":"$page"}""")
            val stopped = LoginOutcome.Stopped(
                69,
                "https://api.ostrom.run sent a sign-in page that is not GitHub's ($page). Check the --server URL.",
            )
            assertEquals(stopped, signIn())
        }
        assertEquals(emptyList(), said, "the code is never shown")
        assertEquals(listOf("/api/v1/auth/device", "/api/v1/auth/device"), calls.map { it.path })
    }

    @Test
    fun aStartWithoutItsFieldsFallsBackOnGitHubsDefaults() = runTest {
        start = ServerReply(200, "{}")
        polls += ServerReply(200, """{"token":"t","login":"l"}""")
        assertEquals(LoginOutcome.SignedIn(Credentials("t", "l")), signIn())
        assertEquals("! Copy your one-time code: ?", said.first())
        assertEquals("  Open https://github.com/login/device and enter it.", said.last())
        assertEquals(listOf(5_000L), waits)
        assertEquals("""{"deviceCode":""}""", calls.last().body)
    }
}
