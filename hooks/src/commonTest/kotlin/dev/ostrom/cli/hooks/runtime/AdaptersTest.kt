package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookKind
import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.LoopbackException
import dev.ostrom.cli.platform.LoopbackFailure
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.ProcessCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptersTest {
    private class Http(private val answer: Result<LoopbackResponse>) : LoopbackHttp {
        val sent = mutableListOf<Pair<Int, LoopbackRequest>>()

        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += port to request
            return answer
        }
    }

    private class Capture(private val result: CaptureResult = CaptureResult.Exited(0, "")) : ProcessCapture {
        val captured = mutableListOf<List<String>>()
        val detached = mutableListOf<List<String>>()

        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            captured += command
            return result
        }

        override fun detach(command: List<String>): Boolean {
            detached += command
            return true
        }
    }

    private class Variables(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private fun answer(status: Int, body: String) = Http(Result.success(LoopbackResponse(status, body)))

    @Test
    fun theRelayIsCalledOnItsPortWithTheSessionToken() {
        val http = answer(200, """{"ok":true}""")
        val calls = RelayCalls("http://127.0.0.1:4100/", "t0k", http)
        assertEquals(ok("""{"ok":true}"""), calls.call("/a", JsonObject(mapOf("x" to JsonPrimitive(1))), 900))
        val post = LoopbackRequest("POST", "/a", mapOf("Authorization" to "Bearer t0k"), """{"x":1}""")
        assertEquals(4100 to post, http.sent[0])
        RelayCalls("http://127.0.0.1:4100", null, http).call("/b", null, 900)
        assertEquals(4100 to LoopbackRequest("GET", "/b"), http.sent[1])
    }

    @Test
    fun theRelaysAnswerIsReadAsAResult() {
        fun call(status: Int, body: String) =
            RelayCalls("http://127.0.0.1:1", null, answer(status, body)).call("/", null, 1)
        assertEquals(ok(), call(204, ""))
        assertEquals(ok(), call(200, "[1]"))
        assertEquals(CallResult.Failed("session closed: s1"), call(409, """{"error":"session closed: s1"}"""))
        assertEquals(CallResult.Failed("HTTP 502"), call(502, "bad gateway"))
        assertEquals(CallResult.Failed("HTTP 403"), call(403, """{"error":3}"""))
        val down = Http(Result.failure(LoopbackException(LoopbackFailure.TIMED_OUT)))
        val late = RelayCalls("http://127.0.0.1:1", null, down).call("/", null, 1)
        assertEquals(CallResult.Failed("the local relay did not answer in time"), late)
    }

    @Test
    fun withoutARelayNothingIsCalled() {
        val http = answer(200, "{}")
        for (url in listOf(null, "https://api.ostrom.run", "http://127.0.0.1:x", "http://10.0.0.1:80")) {
            val result = RelayCalls(url, "t", http).call("/", null, 1)
            val expected = "no Ostrom relay in this session (run the agent with `ostrom <agent>`)"
            assertEquals(CallResult.Failed(expected), result)
        }
        assertTrue(http.sent.isEmpty())
    }

    @Test
    fun gitIsAskedInTheFolderAndItsOutputTrimmed() {
        val capture = Capture(CaptureResult.Exited(0, " main\n"))
        assertEquals("main", CapturedGit(capture).run("/w", listOf("symbolic-ref", "HEAD")))
        CapturedGit(capture).run("", listOf("status"))
        val expected = listOf(listOf("git", "-C", "/w", "symbolic-ref", "HEAD"), listOf("git", "-C", ".", "status"))
        assertEquals(expected, capture.captured)
        assertEquals("", CapturedGit(Capture(CaptureResult.Exited(128, "fatal"))).run("/w", listOf("x")))
        assertEquals("", CapturedGit(Capture(CaptureResult.TimedOut)).run("/w", listOf("x")))
    }

    @Test
    fun theSessionFilesLiveOnTheFileSystem() {
        val fileSystem = FakeFileSystem()
        var now = 0L
        val files = FileSystemHookFiles(fileSystem) { now }
        assertNull(files.read("/s/gates.json"))
        assertNull(files.ageMillis("/s/gates.json"))
        assertTrue(files.write("/s/gates.json", "{}"))
        assertEquals("{}", files.read("/s/gates.json"))
        now = checkNotNull(fileSystem.metadata("/s/gates.json".toPath()).lastModifiedAtMillis) + 42
        assertEquals(42, files.ageMillis("/s/gates.json"))
        assertEquals(listOf("/s/gates.json".toPath()), fileSystem.list("/s".toPath()), "no temporary file left")
        files.delete("/s/gates.json")
        files.delete("/s/gates.json")
        assertNull(files.read("/s/gates.json"))
        assertEquals("/s", files.canonical("/s/../s"))
        assertEquals("/nowhere/x", files.canonical("/nowhere/x"))
    }

    @Test
    fun aFileThatCannotBeReadOrWrittenIsSaid() {
        val fileSystem = FakeFileSystem()
        val files = FileSystemHookFiles(fileSystem) { 0 }
        files.write("/s/file", "x")
        assertFalse(files.write("/s/file/child", "y"), "a file is in the way")
        assertNull(files.read("/s"), "a folder is not a file")
        files.write("/d/inner", "z")
        files.delete("/d")
        assertEquals("z", files.read("/d/inner"), "a folder with files is not deleted")
    }

    @Test
    fun theRealWiringReachesTheRelayGitAndTheBackgroundRefresh() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/s".toPath())
        fileSystem.write("/s/gates.json".toPath()) { writeUtf8("""{"actions":[]}""") }
        val written = checkNotNull(fileSystem.metadata("/s/gates.json".toPath()).lastModifiedAtMillis)
        val capture = Capture()
        val http = answer(200, """{"actions":[{"class":"x"}]}""")
        val environment = Variables(
            mapOf(
                "OSTROM_GATES" to "/s/gates.json",
                "OSTROM_RELAY_URL" to "http://127.0.0.1:4100",
                "OSTROM_BIN" to "/opt/ostrom",
            ),
        )
        val processor = SessionHooks.processor(environment, fileSystem, capture, http, { written + 6_000 })
        val gate = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"ls"}}"""
        assertEquals(HookReply(), processor.process(HookKind.GATE, gate.encodeToByteArray(), "claude"))
        assertEquals(listOf(listOf("/opt/ostrom", "hook", "gates-refresh")), capture.detached)
        assertEquals(HookReply(), processor.process(HookKind.GATES_REFRESH, null, "claude"))
        assertEquals("/api/bridge/gates/catalog?repo=", http.sent.single().second.path)
        val plain = SessionHooks.processor(Variables(emptyMap()), fileSystem, capture, http, SessionHooks::epochMillis)
        assertEquals(HookReply(), plain.process(HookKind.SESSION, null, "claude"))
        assertTrue(SessionHooks.epochMillis() > 1_700_000_000_000)
    }

    @Test
    fun theProcessorSpeaksTheDialectItIsGiven() {
        val catalogue = """{"actions":[{"class":"x","tools":["Bash"],"patterns":["rm"],"gate":{"enforce":true}}]}"""
        val files = FakeFiles(mutableMapOf("/s/gates.json" to catalogue))
        val ports = HookPorts(FakeCalls(), files, { _, _ -> "" }, { true }, { it }, "/w")
        val processor = SessionHookProcessor(contextOf(), ports)
        val event = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"rm x"}}"""
        val reply = processor.process(HookKind.GATE, event.encodeToByteArray(), "grok")
        assertEquals(2, reply.exitCode)
        assertTrue(reply.stdout.startsWith("""{"decision":"deny""""))
        val start = """{"hook_event_name":"SessionStart","session_id":"a1"}"""
        val unrecorded = processor.process(HookKind.SESSION, start.encodeToByteArray(), "claude").stderr
        assertEquals("Ostrom unreachable (HTTP 404): session not recorded.\n", unrecorded)
    }
}
