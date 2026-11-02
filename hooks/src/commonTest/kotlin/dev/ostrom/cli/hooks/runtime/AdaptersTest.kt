// SPDX-License-Identifier: Apache-2.0
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
import okio.ByteString.Companion.encodeUtf8
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

        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            captured += command
            return result
        }

        override fun detach(command: List<String>): Boolean = error("the hooks start nothing in the background")
    }

    private class Variables(
        private val values: Map<String, String>,
        override val operatingSystem: OperatingSystem = OperatingSystem.LINUX,
    ) : Environment {
        override fun get(name: String) = values[name]
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
        assertEquals(CallResult.Failed("HTTP 101"), call(101, ""), "below 200 is no answer either")
        assertEquals(CallResult.Failed("HTTP 403"), call(403, """{"error":3}"""))
        val contract = call(409, """{"error":{"type":"session_closed","message":"session s1 is closed"}}""")
        assertEquals(CallResult.Failed("session s1 is closed", "session_closed"), contract)
        assertEquals(CallResult.Failed("HTTP 404"), call(404, """{"error":{"message":3}}"""))
        val down = Http(Result.failure(LoopbackException(LoopbackFailure.TIMED_OUT)))
        val late = RelayCalls("http://127.0.0.1:1", null, down).call("/", null, 1)
        assertEquals(CallResult.Failed("the local relay did not answer in time", CallResult.UNREACHABLE), late)
        assertTrue((late as CallResult.Failed).unreachable, "a relay that does not answer is an outage")
        assertFalse(CallResult.Failed("HTTP 403").unreachable, "a refusal is not")
        assertFalse(late.relayDown, "a slow relay is still there")
        val gone = Http(Result.failure(LoopbackException(LoopbackFailure.UNREACHABLE)))
        val refused = RelayCalls("http://127.0.0.1:1", null, gone).call("/", null, 1) as CallResult.Failed
        assertTrue(refused.relayDown && refused.unreachable, "nothing listens: the launch has ended")
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
        assertEquals("main", CapturedGit(capture, "/usr/bin/git").run("/w", listOf("symbolic-ref", "HEAD")))
        CapturedGit(capture, "git").run("", listOf("status"))
        val expected = listOf(
            listOf("/usr/bin/git", "-C", "/w", "symbolic-ref", "HEAD"),
            listOf("git", "-C", ".", "status"),
        )
        assertEquals(expected, capture.captured)
        assertEquals("", CapturedGit(Capture(CaptureResult.Exited(128, "fatal")), "git").run("/w", listOf("x")))
        assertEquals("", CapturedGit(Capture(CaptureResult.TimedOut), "git").run("/w", listOf("x")))
    }

    @Test
    fun theSessionFilesLiveOnTheFileSystem() {
        val fileSystem = FakeFileSystem()
        val files = FileSystemHookFiles(fileSystem) { 0 }
        assertNull(files.read("/s/session"))
        assertTrue(files.write("/s/session", "s1"))
        assertEquals("s1", files.read("/s/session"))
        assertEquals(listOf("/s/session".toPath()), fileSystem.list("/s".toPath()), "no temporary file left")
        assertEquals(fileSystem.canonicalize("/s".toPath()).toString(), files.canonical("/s"))
        assertEquals("/nowhere/x", files.canonical("/nowhere/x"))
    }

    @Test
    fun aFileThatCannotBeReadOrWrittenIsSaid() {
        val fileSystem = FakeFileSystem()
        val files = FileSystemHookFiles(fileSystem) { 0 }
        files.write("/s/file", "x")
        assertFalse(files.write("/s/file/child", "y"), "a file is in the way")
        assertFalse(files.write("/", "z"), "the root has no folder to make and is no file")
        assertNull(files.read("/s"), "a folder is not a file")
        assertNull(files.readStart("/s", 4))
    }

    @Test
    fun aScriptIsReadUpToItsLimitOnly() {
        val fileSystem = FakeFileSystem()
        val files = FileSystemHookFiles(fileSystem) { 0 }
        files.write("/s/big", "x".repeat(100))
        assertEquals("xxxx", files.readStart("/s/big", 4))
        assertEquals("x".repeat(100), files.readStart("/s/big", 1_000), "a shorter file whole")
    }

    @Test
    fun theRealWiringReachesTheRelayWithTheTokenOfItsFileAndTheResolvedGit() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/s".toPath())
        fileSystem.write("/s/relay-token".toPath()) { writeUtf8("t0k\n") }
        val capture = Capture()
        val signed = PolicyVectors.envelope(payload = PolicyVectors.PAYLOAD_R, signature = PolicyVectors.SIGNATURE_R)
        val http = answer(200, signed.toString())
        val environment = Variables(
            mapOf(
                "OSTROM_RELAY_URL" to "http://127.0.0.1:4100",
                "OSTROM_RELAY_TOKEN_FILE" to "/s/relay-token",
                // S2-25: a token left in the environment is never the one sent
                "OSTROM_RELAY_TOKEN" to "from-the-environment",
                "OSTROM_INSTANCE_KEYS" to PolicyVectors.KEYS,
                "OSTROM_GIT" to "/usr/bin/git",
                "OSTROM_REPO" to "r",
            ),
        )
        val processor = SessionHooks.processor(environment, fileSystem, capture, http) { PolicyVectors.NOW }
        val push = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"git push"}}"""
        assertEquals(HookReply(), processor.process(HookKind.GATE, push.encodeToByteArray(), "claude"))
        val asked = http.sent.first().second
        assertEquals("/api/v1/gates/catalog/signed?repo=r", asked.path)
        assertEquals(mapOf("Authorization" to "Bearer t0k"), asked.headers)
        assertEquals("/usr/bin/git", capture.captured.first().first(), "never the git of the agent's PATH (S2-30)")
        val missing = Variables(mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_RELAY_TOKEN_FILE" to "/x"))
        SessionHooks.processor(missing, fileSystem, capture, http, SessionHooks::epochMillis)
            .process(HookKind.GATE, push.encodeToByteArray(), "claude")
        assertEquals(emptyMap(), http.sent.last().second.headers, "no token file, no token")
        // Grok's hooks get a budget: a call that would end after it is not made, and the gate refuses
        val grok = Variables(mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_HOOK_BUDGET_MS" to "100"))
        val sent = http.sent.size
        SessionHooks.processor(grok, fileSystem, capture, http, SessionHooks::epochMillis)
            .process(HookKind.GATE, push.encodeToByteArray(), "claude")
        assertEquals(sent, http.sent.size, "no time left for any call")
        val plain = SessionHooks.processor(Variables(emptyMap()), fileSystem, capture, http, SessionHooks::epochMillis)
        assertEquals(HookReply(), plain.process(HookKind.SESSION, null, "claude"))
        assertTrue(SessionHooks.epochMillis() > 1_700_000_000_000)
    }

    @Test
    fun aLaidPackageRunsItsChecksThroughTheRealWiring() {
        val script = "npm test\n"
        val view = """{"digest":"sha256:p","tmp":"/t","hooks":[{"id":"h","nodeId":"n","kind":"check",
            "event":"PreToolUse","tools":["Bash"],"patterns":["npm test"],"mode":"enforce","scriptId":"s"}],
            "scripts":[{"id":"s","label":"Tests","path":"scripts/s.sh","command":"npm test","timeoutSec":5,
            "sha256":"${script.encodeUtf8().sha256().hex()}"}]}"""
        val signed = PolicyVectors.envelope(payload = PolicyVectors.PAYLOAD_R, signature = PolicyVectors.SIGNATURE_R)
        for ((system, sandboxed) in listOf(
            OperatingSystem.LINUX to false,
            OperatingSystem.MACOS to true,
            OperatingSystem.MACOS to false,
        )) {
            val fileSystem = FakeFileSystem()
            fileSystem.createDirectories("/t/scripts".toPath())
            fileSystem.write("/t/package.json".toPath()) { writeUtf8(view) }
            fileSystem.write("/t/scripts/s.sh".toPath()) { writeUtf8(script) }
            if (sandboxed) {
                fileSystem.createDirectories("/usr/bin".toPath())
                fileSystem.write("/usr/bin/sandbox-exec".toPath()) { writeUtf8("binary") }
            }
            val capture = Capture()
            val http = answer(200, signed.toString())
            val environment = Variables(
                mapOf(
                    "OSTROM_RELAY_URL" to "http://127.0.0.1:4100",
                    "OSTROM_INSTANCE_KEYS" to PolicyVectors.KEYS,
                    "OSTROM_REPO" to "r",
                    "OSTROM_PACKAGE" to "/t/package.json",
                ),
                system,
            )
            val processor = SessionHooks.processor(environment, fileSystem, capture, http) { PolicyVectors.NOW }
            val test = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"npm test"}}"""
            assertEquals(HookReply(), processor.process(HookKind.GATE, test.encodeToByteArray(), "claude"))
            val run = capture.captured.single { it.first() == "/usr/bin/env" }
            assertEquals(sandboxed, "/usr/bin/sandbox-exec" in run, "$system")
            assertEquals(Routes.CHECKS, http.sent.last().second.path)
        }
        // a cloud session asks its runner over the socket, and never runs the check here when it does not answer
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/t/scripts".toPath())
        fileSystem.write("/t/package.json".toPath()) { writeUtf8(view) }
        fileSystem.write("/t/scripts/s.sh".toPath()) { writeUtf8(script) }
        val capture = Capture()
        val http = answer(200, signed.toString())
        val cloud = Variables(
            mapOf(
                "OSTROM_RELAY_URL" to "http://127.0.0.1:4100",
                "OSTROM_INSTANCE_KEYS" to PolicyVectors.KEYS,
                "OSTROM_REPO" to "r",
                "OSTROM_PACKAGE" to "/t/package.json",
                "OSTROM_RUNNER_CHECKS" to "/nonexistent/ostrom/checks.sock",
            ),
            OperatingSystem.LINUX,
        )
        val processor = SessionHooks.processor(cloud, fileSystem, capture, http) { PolicyVectors.NOW }
        val test = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"npm test"}}"""
        val refusal = processor.process(HookKind.GATE, test.encodeToByteArray(), "claude").stderr
        assertTrue(refusal.contains("could not run (the session's runner did not answer"), refusal)
        assertTrue(capture.captured.none { it.first() == "/usr/bin/env" }, "never run on the agent's host")
        assertTrue(http.sent.none { it.second.path == Routes.CHECKS }, "the runner reports, the hook does not")
    }

    @Test
    fun theProcessorSpeaksTheDialectItIsGiven() {
        val catalogue = """{"actions":[{"class":"x","tools":["Bash"],"patterns":["rm"],"gate":{"enforce":true}}]}"""
        val files = FakeFiles(mutableMapOf("/s/gates.json" to catalogue))
        val ports = HookPorts(FakeCalls(), files, { _, _ -> "" }, { it }, "/w")
        val processor = SessionHookProcessor(contextOf(), ports)
        val event = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"rm x"}}"""
        val reply = processor.process(HookKind.GATE, event.encodeToByteArray(), "grok")
        assertEquals(2, reply.exitCode)
        assertTrue(reply.stdout.startsWith("""{"decision":"deny""""))
        val start = """{"hook_event_name":"SessionStart","session_id":"a1"}"""
        val unrecorded = processor.process(HookKind.SESSION, start.encodeToByteArray(), "claude").stderr
        assertEquals("Ostrom unreachable (HTTP 404): session not recorded.\n", unrecorded)
    }

    @Test
    fun aGateEventThatCouldNotBeReadIsRefused() {
        // S2-30: too large, late or broken used to read as an empty event, and the action ran
        val ports = HookPorts(FakeCalls(), FakeFiles(), { _, _ -> "" }, { it }, "/w")
        val processor = SessionHookProcessor(contextOf(), ports)
        for (input in listOf(null, "not json".encodeToByteArray(), """{"tool_name":"Bash"}""".encodeToByteArray())) {
            val reply = processor.process(HookKind.GATE, input, "hermes")
            assertEquals(2, reply.exitCode)
            assertTrue(reply.stdout.startsWith("""{"decision":"block""""))
            assertEquals(GateTexts.UNREAD_EVENT + "\n", reply.stderr)
        }
        assertEquals(HookReply(), processor.process(HookKind.SESSION, null, "claude"), "a session event is not a gate")
    }
}
