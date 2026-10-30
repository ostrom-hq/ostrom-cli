// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.cloud

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.api.TerminalChannel
import dev.ostrom.cli.api.TerminalMessage
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.cloud.CloudStart
import dev.ostrom.cli.launcher.cloud.TerminalOutput
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.RawTerminal
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.platform.TerminalSize
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cloud commands' wiring: the server through the API client, the terminal socket, `origin` read with git. */
class CloudRunnerTest {
    private val errors = StringBuilder()
    private val console = object : Console {
        override fun out(text: String) = Unit

        override fun err(text: String) {
            errors.append(text)
        }

        override val isInteractive = false

        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    private val requests = mutableListOf<Pair<String, ServerRequest>>()
    private var closed = 0

    private val api: (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))

            override fun close() {
                closed++
            }

            override suspend fun send(request: ServerRequest): ServerResponse {
                requests += url to request
                return when (request.method) {
                    "POST" -> ServerResponse(201, """{"id":"cs1"}""", 1)
                    else -> ServerResponse(200, """{"status":"running"}""", 1)
                }
            }
        }
    }

    private class Channel(private val messages: ArrayDeque<TerminalMessage>) : TerminalChannel {
        val sent = mutableListOf<String>()
        override val closeCode: Int? = 1000

        override suspend fun receive(): TerminalMessage? = messages.removeFirstOrNull()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes.decodeToString()
        }

        override suspend fun sendText(text: String) {
            sent += text
        }

        override suspend fun close() {
            sent += "close"
        }
    }

    private val terminal = object : RawTerminal {
        override fun enterRaw() = false

        override fun restore() = Unit

        override fun size(): TerminalSize? = null

        override fun read(buffer: ByteArray, timeoutMillis: Int) = -1

        override fun write(bytes: ByteArray) = Unit
    }

    private val gitCalls = mutableListOf<List<String>>()
    private var origin: CaptureResult = CaptureResult.Exited(0, "git@github.com:acme/api.git\n")
    private val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult = origin.also {
            gitCalls += command
        }

        override fun detach(command: List<String>) = error("not used")
    }

    private val opened = mutableListOf<Triple<String, String, String>>()
    private var unreachable = false

    private fun runner() = CloudRunnerImpl(
        console,
        api,
        { url, path, token ->
            opened += Triple(url, path, token)
            if (unreachable) throw ServerUnreachableException(url, IOException("connection reset"))
            Channel(ArrayDeque())
        },
        terminal,
        capture,
    )

    private val site = CloudSite("https://ostrom.example", "ost_1", "/work/acme")

    @Test
    fun aSessionForThisFolderSendsItsOriginAsTheRepository() = runTest {
        assertEquals(0, runner().start(CloudStart(Agent.CLAUDE, null, null), site))
        assertEquals(1, closed, "the client is closed with the command")
        assertEquals(listOf(listOf("git", "-C", "/work/acme", "remote", "get-url", "origin")), gitCalls)
        val created = requests.first()
        assertEquals("https://ostrom.example", created.first)
        assertEquals("""{"agent":"claude","repo":"https://github.com/acme/api"}""", created.second.body)
        assertEquals("ost_1", created.second.token)
        assertEquals(
            listOf(Triple("https://ostrom.example", "/api/v1/cloud/sessions/cs1/terminal", "ost_1")),
            opened,
        )
        assertTrue(errors.contains("Starting Claude Code in Ostrom's cloud (session cs1)…\n"), errors.toString())
    }

    @Test
    fun withoutAnOriginNoRepositoryIsSent() = runTest {
        for (result in listOf(CaptureResult.NotFound, CaptureResult.Exited(2, "x"), CaptureResult.Exited(0, " \n"))) {
            origin = result
            requests.clear()
            runner().start(CloudStart(Agent.CODEX, null, null), site)
            assertEquals("""{"agent":"codex"}""", requests.first().second.body, "$result")
        }
    }

    @Test
    fun anOriginElsewhereThanGitHubStartsWithoutARepositoryAndSaysSo() = runTest {
        // an ssh host alias, GitLab: not a usage error, since the developer named no repository
        for (url in listOf("git@github.com-work:acme/api.git", "https://gitlab.com/acme/api.git")) {
            origin = CaptureResult.Exited(0, "$url\n")
            requests.clear()
            assertEquals(0, runner().start(CloudStart(Agent.CODEX, null, null), site), url)
            assertEquals("""{"agent":"codex"}""", requests.first().second.body, url)
            val said = "ostrom: origin is not on GitHub ($url): the session starts without a repository " +
                "(--repo names one)\n"
            assertTrue(errors.contains(said), errors.toString())
        }
    }

    @Test
    fun aRepositoryNamedIsSentWithoutAskingGit() = runTest {
        runner().start(CloudStart(Agent.GEMINI, "pro", "acme/web"), site)
        assertTrue(gitCalls.isEmpty())
        val body = """{"agent":"gemini","model":"pro","repo":"https://github.com/acme/web"}"""
        assertEquals(body, requests.first().second.body)
    }

    @Test
    fun aTerminalThatCannotOpenIsUnreachable() = runTest {
        unreachable = true
        assertEquals(69, runner().attach("cs1", site))
        assertEquals(1, closed, "the client is closed with the command")
        assertTrue(errors.endsWith("ostrom: could not attach to session cs1 (connection reset)\n"), errors.toString())
    }

    @Test
    fun theSocketCarriesTheChannelsMessages() = runTest {
        val channel = Channel(
            ArrayDeque(listOf(TerminalMessage.Output(byteArrayOf(65)), TerminalMessage.Control("""{"type":"x"}"""))),
        )
        val socket = ChannelSocket(channel)
        assertContentEquals(byteArrayOf(65), (socket.receive() as TerminalOutput.Bytes).bytes)
        assertEquals(TerminalOutput.Control("""{"type":"x"}"""), socket.receive())
        assertNull(socket.receive())
        assertEquals(1000, socket.closeCode)
        socket.send("k".encodeToByteArray())
        socket.sendText("t")
        socket.close()
        assertEquals(listOf("k", "t", "close"), channel.sent)
    }
}
