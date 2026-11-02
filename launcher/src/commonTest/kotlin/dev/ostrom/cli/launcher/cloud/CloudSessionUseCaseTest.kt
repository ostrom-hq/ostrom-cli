// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.cloud

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.launcher.ServerTransport
import dev.ostrom.cli.platform.RawTerminal
import dev.ostrom.cli.platform.TerminalSize
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom <agent> --cloud` and `ostrom attach` against a scripted server, socket and terminal. */
class CloudSessionUseCaseTest {
    /** Each route answers its replies in order, the last one again and again. */
    private val replies = mutableMapOf<String, ArrayDeque<ServerReply>>()
    private val calls = mutableListOf<ServerCall>()
    private val transport = ServerTransport { call ->
        calls += call
        val queue = replies["${call.method} ${call.path}"]
        when {
            queue == null -> ServerReply(404, """{"error":{"type":"not_found","message":"no ${call.path}"}}""")
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
    }

    private fun on(route: String, vararg answers: Pair<Int?, String>) {
        replies[route] = ArrayDeque(answers.map { ServerReply(it.first, it.second) })
    }

    private class Socket(outputs: List<TerminalOutput>, private val endsOnItsOwn: Boolean) : TerminalSocket {
        private val channel = Channel<TerminalOutput>(Channel.UNLIMITED).also { c ->
            outputs.forEach { c.trySend(it) }
            if (endsOnItsOwn) c.close()
        }
        val sent = mutableListOf<String>()
        val texts = mutableListOf<String>()
        var closed = false
        // a session's end closes with 1000; null is a connection lost without a close frame
        override var closeCode: Int? = 1000

        override suspend fun receive(): TerminalOutput? = channel.receiveCatching().getOrNull()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes.decodeToString()
        }

        override suspend fun sendText(text: String) {
            texts += text
        }

        override suspend fun close() {
            closed = true
            channel.close()
        }

        /** The session ends on its own, now. */
        fun end() {
            channel.close()
        }
    }

    private class Terminal(private val keys: ArrayDeque<String>, private val sizes: ArrayDeque<TerminalSize?>) :
        RawTerminal {
        var raw = true
        var idle = false
        var restored = 0
        var atTheEnd: () -> Unit = {}
        val shown = StringBuilder()

        override fun enterRaw() = raw

        override fun restore() {
            restored++
        }

        override fun size(): TerminalSize? = if (sizes.size > 1) sizes.removeFirst() else sizes.firstOrNull()

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            val next = keys.removeFirstOrNull() ?: return if (idle) 0 else -1.also { atTheEnd() }
            val bytes = next.encodeToByteArray()
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun write(bytes: ByteArray) {
            shown.append(bytes.decodeToString())
        }
    }

    private val said = mutableListOf<String>()
    private val slept = mutableListOf<Long>()
    private var socket = Socket(listOf(TerminalOutput.Bytes("hello\r\n".encodeToByteArray())), endsOnItsOwn = true)
    private var terminal = Terminal(ArrayDeque(), ArrayDeque(listOf(TerminalSize(80, 24))))
    private var refuseSocket = false
    private val opened = mutableListOf<Pair<String, String>>()

    private fun useCase() = CloudSessionUseCase(
        ServerJson(transport),
        { path, token ->
            opened += path to token
            if (refuseSocket) throw IOException("TLS handshake failed")
            socket
        },
        terminal,
        { said += it },
        { slept += it },
        EmptyCoroutineContext,
    )

    private val claude = CloudStart(Agent.CLAUDE, "opus", "acme/api")

    @Test
    fun aSessionIsStartedWaitedForThenShownInThisTerminal() = runTest {
        on("POST /api/v1/cloud/sessions", 201 to """{"id":"cs1","status":"creating"}""")
        on(
            "GET /api/v1/cloud/sessions/cs1",
            200 to """{"id":"cs1","status":"creating","phase":"cloning"}""",
            200 to """{"id":"cs1","status":"creating","phase":"cloning"}""",
            200 to """{"id":"cs1","status":"creating"}""",
            200 to """{"id":"cs1","status":"running","phase":"ready"}""",
        )
        socket.closeCode = 1000
        assertEquals(0, useCase().start(claude, "device"))
        val created = calls.first()
        assertEquals("""{"agent":"claude","model":"opus","repo":"https://github.com/acme/api"}""", created.body)
        assertEquals("device", created.token)
        val starting = "Starting Claude Code in Ostrom's cloud (session cs1)…"
        assertEquals(listOf(starting, "  cloning…", "  ready…", "Session cs1 ended."), said)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), slept)
        assertEquals(listOf("/api/v1/cloud/sessions/cs1/terminal" to "device"), opened)
        assertEquals("hello\r\n", terminal.shown.toString())
        assertEquals(listOf("""{"type":"resize","columns":80,"rows":24}"""), socket.texts)
        assertEquals(1, terminal.restored, "the terminal is given back")
    }

    @Test
    fun keysGoToTheSessionAndCtrlBracketDetachesLeavingItRunning() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        socket = Socket(emptyList(), endsOnItsOwn = false)
        terminal = Terminal(
            ArrayDeque(listOf("ls\r", "ab\u001Dignored")),
            ArrayDeque(listOf(null, TerminalSize(80, 24), TerminalSize(100, 30), null)),
        )
        terminal.raw = false
        assertEquals(0, useCase().attach("cs1", "device"))
        assertEquals(listOf("ls\r", "ab"), socket.sent)
        assertTrue(socket.closed)
        assertEquals(listOf("""{"type":"resize","columns":100,"rows":30}"""), socket.texts, "a resize when it changes")
        assertEquals("Detached: session cs1 keeps running. Attach again with `ostrom attach cs1`.", said.last())
        assertEquals(0, terminal.restored, "nothing to give back when raw mode was not set")
    }

    @Test
    fun detachingAsTheFirstKeySendsNothing() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        socket = Socket(emptyList(), endsOnItsOwn = false)
        terminal = Terminal(ArrayDeque(listOf("\u001D")), ArrayDeque(listOf(null)))
        assertEquals(0, useCase().attach("cs1", "device"))
        assertEquals(emptyList(), socket.sent)
    }

    @Test
    fun theRunnerLosingItsMachineIsSaidInTheTerminal() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        socket = Socket(
            listOf(
                TerminalOutput.Control("""{"type":"runner","connected":true}"""),
                TerminalOutput.Control("""{"type":"runner","connected":false}"""),
                TerminalOutput.Control("""{"type":"runner","connected":false}"""),
                TerminalOutput.Control("not json"),
                TerminalOutput.Control("""{"type":"note","text":"\"connected\":false"}"""),
                TerminalOutput.Control("""{"type":"runner","connected":true}"""),
            ),
            endsOnItsOwn = true,
        )
        assertEquals(0, useCase().attach("cs1", "device"))
        val shown = "\r\n[ostrom] the session's machine is reconnecting…\r\n" +
            "\r\n[ostrom] the session's machine is back.\r\n"
        assertEquals(shown, terminal.shown.toString(), "said once each way, and only for connected itself")
        assertEquals("Session cs1 ended.", said.last())
    }

    @Test
    fun theSessionsEndStopsAnIdleKeyboard() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        terminal.idle = true
        assertEquals(0, useCase().attach("cs1", "device"), "the pump ends without a key")
        assertEquals("Session cs1 ended.", said.last())
        assertEquals(1, terminal.restored)
    }

    @Test
    fun aLookThatFailsForAMomentIsLookedAgain() = runTest {
        on(
            "GET /api/v1/cloud/sessions/cs1",
            503 to """{"error":{"type":"unavailable","message":"busy"}}""",
            null to "refused",
            429 to """{"error":{"type":"rate_limited","message":"slow down"}}""",
            200 to """{"status":"running"}""",
        )
        assertEquals(0, useCase().attach("cs1", "device"))
        assertEquals(3, slept.size)
        assertEquals("Session cs1 ended.", said.last())
    }

    @Test
    fun theCloseCodeSaysWhyTheTerminalClosed() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        val lost = "ostrom: The connection to session cs1 was lost; it keeps running. Attach again with " +
            "`ostrom attach cs1`."
        val cases = mapOf(
            null to (69 to lost),
            4401 to (65 to "ostrom: Ostrom refused the terminal: run `ostrom login`."),
            4403 to (77 to "ostrom: Session cs1 is not yours to attach to."),
            4404 to (66 to "ostrom: No session cs1."),
            4409 to (69 to "ostrom: The terminal of session cs1 closed (4409)."),
        )
        for ((code, expected) in cases) {
            socket = Socket(emptyList(), endsOnItsOwn = true).also { it.closeCode = code }
            assertEquals(expected.first, useCase().attach("cs1", "device"), "$code")
            assertEquals(expected.second, said.last())
        }
    }

    @Test
    fun aSessionThatCannotRunIsSaidWithoutAttaching() = runTest {
        val failed = """{"status":"failed","failure":{"code":"start_failed","message":"no image"}}"""
        on("GET /api/v1/cloud/sessions/f", 200 to failed)
        on("GET /api/v1/cloud/sessions/t", 200 to """{"status":"terminated"}""")
        on("GET /api/v1/cloud/sessions/p", 200 to """{"status":"paused"}""")
        assertEquals(1, useCase().attach("f", "device"))
        assertEquals("ostrom: session f is failed: no image", said.last())
        assertEquals(1, useCase().attach("t", "device"))
        assertEquals("ostrom: session t is terminated", said.last())
        assertEquals(1, useCase().attach("p", "device"))
        assertEquals("ostrom: session p is paused: resume it from Ostrom's console, then attach again", said.last())
        on("GET /api/v1/cloud/sessions/q", 200 to """{"status":"pausing"}""")
        assertEquals(1, useCase().attach("q", "device"))
        assertEquals("ostrom: session q is paused: resume it from Ostrom's console, then attach again", said.last())
        assertEquals(emptyList(), opened)
    }

    @Test
    fun aSessionThatNeverStartsIsGivenUpOnAfterTenMinutes() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"creating"}""")
        assertEquals(69, useCase().attach("cs1", "device"))
        assertEquals("ostrom: session cs1 did not start within 10 minutes", said.last())
        assertEquals(300, slept.size)
    }

    @Test
    fun refusalsAndAnUnreachableServerHaveTheirCodes() = runTest {
        val cases = mapOf<Int?, Int>(401 to 65, 403 to 77, 404 to 66, null to 69, 500 to 1)
        for ((status, code) in cases) {
            on("GET /api/v1/cloud/sessions/x", status to """{"error":{"type":"e","message":"refused $status"}}""")
            assertEquals(code, useCase().attach("x", "device"), "$status")
        }
        assertEquals("ostrom: refused 500", said.last())
        on("POST /api/v1/cloud/sessions", 402 to """{"error":{"type":"plan","message":"no cloud hours left"}}""")
        assertEquals(1, useCase().start(claude, "device"))
        assertEquals("ostrom: no cloud hours left", said.last())
    }

    @Test
    fun aSocketThatCannotOpenIsUnreachable() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        refuseSocket = true
        assertEquals(69, useCase().attach("cs1", "device"))
        assertEquals("ostrom: could not attach to session cs1 (TLS handshake failed)", said.last())
    }

    @Test
    fun anAgentWithoutACloudImageOrAnAnswerWithoutIdStops() = runTest {
        assertEquals(64, useCase().start(CloudStart(Agent.PI, null, null), "device"))
        val cloud = CloudSessionUseCase.CLOUD_AGENTS.joinToString()
        assertEquals("ostrom: Pi does not run in Ostrom's cloud yet ($cloud do)", said.last())
        assertEquals(listOf("claude", "codex", "opencode", "gemini"), CloudSessionUseCase.CLOUD_AGENTS)
        assertTrue(calls.isEmpty())
        on("POST /api/v1/cloud/sessions", 201 to "{}")
        assertEquals(69, useCase().start(CloudStart(Agent.CODEX, null, null), "device"))
        assertEquals("""{"agent":"codex"}""", calls.last().body)
        assertEquals("ostrom: Ostrom answered without a session", said.last())
    }

    @Test
    fun aRepositoryIsSentAsItsGitHubUrl() {
        assertEquals("https://github.com/acme/api", CloudSessionUseCase.gitHubUrl("acme/api"))
        assertEquals("https://github.com/acme/api", CloudSessionUseCase.gitHubUrl("git@github.com:acme/api.git"))
        assertEquals("https://github.com/acme/api", CloudSessionUseCase.gitHubUrl("https://github.com/acme/api.git"))
        val same = listOf(
            "ssh://git@github.com/acme/api.git",
            "ssh://git@github.com:22/acme/api",
            "https://maya:ghp_secret@github.com/acme/api.git",
            "https://GitHub.com/acme/api/",
        )
        for (remote in same) assertEquals("https://github.com/acme/api", CloudSessionUseCase.gitHubUrl(remote), remote)
        val others = listOf(
            "git@gitlab.com:acme/api.git",
            "https://gitlab.com/acme/api",
            "https://github.com",
            "https://github.com/acme/api/tree/main",
            "acme",
        )
        for (remote in others) assertEquals(null, CloudSessionUseCase.gitHubUrl(remote), remote)
    }

    @Test
    fun aRepositoryOffGitHubIsRefusedBeforeAnythingIsAsked() = runTest {
        assertEquals(64, useCase().start(CloudStart(Agent.CLAUDE, null, "https://gitlab.com/acme/api"), "device"))
        val refusal = "ostrom: the repository to clone must be on GitHub: --repo owner/name or " +
            "https://github.com/owner/name"
        assertEquals(refusal, said.last())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theEndOfTheKeyboardLeavesTheSessionToEndOnItsOwn() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        socket = Socket(emptyList(), endsOnItsOwn = false)
        // "" is a read that timed out; the same size again sends no resize
        terminal = Terminal(ArrayDeque(listOf("x", "")), ArrayDeque(listOf(null, TerminalSize(80, 24))))
        terminal.atTheEnd = { socket.end() }
        assertEquals(0, useCase().attach("cs1", "device"))
        assertEquals(listOf("x"), socket.sent)
        assertEquals(emptyList(), socket.texts)
        assertFalse(socket.closed, "not detached: the session closed the socket")
        assertEquals("Session cs1 ended.", said.last())
    }

    @Test
    fun aCancelledAttachGivesTheTerminalBack() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        for (raw in listOf(true, false)) {
            socket = Socket(emptyList(), endsOnItsOwn = false)
            terminal = Terminal(ArrayDeque(), ArrayDeque(listOf(null)))
            terminal.raw = raw
            val attach = launch { useCase().attach("cs1", "device") }
            advanceUntilIdle()
            attach.cancelAndJoin()
            assertEquals(if (raw) 1 else 0, terminal.restored, "raw $raw")
        }
    }

    @Test
    fun theRealClockAndKeyboardThreadAreTheDefaults() = runTest {
        on(
            "GET /api/v1/cloud/sessions/cs1",
            200 to """{"status":"creating"}""",
            200 to """{"status":"running"}""",
        )
        val plain = CloudSessionUseCase(ServerJson(transport), { _, _ -> socket }, terminal, { said += it })
        assertEquals(0, plain.attach("cs1", "device"))
        assertEquals("Session cs1 ended.", said.last())
    }
}
