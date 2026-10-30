// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.cloud

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.launcher.ServerTransport
import dev.ostrom.cli.platform.RawTerminal
import dev.ostrom.cli.platform.TerminalSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override var closeCode: Int? = null

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
    }

    private class Terminal(private val keys: ArrayDeque<String>, private val sizes: ArrayDeque<TerminalSize?>) :
        RawTerminal {
        var raw = true
        var restored = 0
        val shown = StringBuilder()

        override fun enterRaw() = raw

        override fun restore() {
            restored++
        }

        override fun size(): TerminalSize? = if (sizes.size > 1) sizes.removeFirst() else sizes.firstOrNull()

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            val next = keys.removeFirstOrNull() ?: return -1
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
            ),
            endsOnItsOwn = true,
        )
        assertEquals(0, useCase().attach("cs1", "device"))
        assertEquals("\r\n[ostrom] the session's machine is reconnecting…\r\n", terminal.shown.toString())
        assertEquals("Session cs1 ended.", said.last(), "no close code: an end")
    }

    @Test
    fun theCloseCodeSaysWhyTheTerminalClosed() = runTest {
        on("GET /api/v1/cloud/sessions/cs1", 200 to """{"status":"running"}""")
        val cases = mapOf(
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
        assertEquals("ostrom: Pi does not run in Ostrom's cloud yet (claude, codex, opencode, gemini do)", said.last())
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
    }
}
