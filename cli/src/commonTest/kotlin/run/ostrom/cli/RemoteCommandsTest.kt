// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.relay.LocalRelay
import run.ostrom.cli.relay.LocalRelayStarter
import run.ostrom.cli.relay.RelayConfig
import run.ostrom.cli.remote.RemoteHostPorts
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.remote.remoteCommand
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.runner.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `ostrom remote start|list|resume|stop|host`, as TeamCode's `RemoteCommandsTest`: the commands against a fake server,
 * the host against a fake runner channel, a fake agent and a fake relay.
 */
class RemoteCommandsTest : SetupKit() {
    private val id = "8f3c0a2e-1111-4222-8333-944455556666"
    private val records = "/home/maya/.config/ostrom/remote"
    private val session = "/api/v1/cloud/sessions/$id"
    private val host = "POST $session/host"
    private val pause = "POST $session/pause"
    private val create = "POST /api/v1/cloud/sessions"

    /** What the server answers, by `METHOD path`: the last answer repeats. */
    private val answers = mutableMapOf<String, MutableList<Pair<Int, String>>>()

    private fun answer(route: String, vararg replies: Pair<Int, String>) {
        answers[route] = replies.toMutableList()
    }

    private val server: (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                requests += url to request
                val queue = answers["${request.method} ${request.path}"]
                val (status, body) = when {
                    queue == null -> 404 to "{}"
                    queue.size > 1 -> queue.removeAt(0)
                    else -> queue.single()
                }
                return ServerResponse(status, body, 1)
            }
        }
    }

    // The runner channel, over the HTTP client of the host.
    private val runnerCalls = mutableListOf<String>()
    private var launch: () -> Pair<Int, String> = { 200 to """{"launchId":"L1","agentToken":"agent-token"}""" }
    private var agentView = 200 to """{"kind":"claude","conversation":"none"}"""
    private var instruction: () -> String = { "stop" }

    private fun http() = HttpClient(
        MockEngine { request ->
            val route = request.url.encodedPath.removePrefix("/api/v1/cloud/runner/")
            runnerCalls += route
            val (status, body) = when (route) {
                "launch" -> launch()
                "agent" -> agentView
                "heartbeat" -> instruction().let {
                    if (it == "end") 409 to "{}" else 200 to """{"instruction":"$it","state":"running"}"""
                }
                "inputs" -> 200 to """{"inputs":[]}""".also { delay(5) }
                else -> 200 to """{"acceptedThrough":0,"lastSeq":0}"""
            }
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { expectSuccess = false }

    // The machine: the agent's processes, the relay, the background host, the signals.
    private val started = mutableListOf<Pair<List<String>, Map<String, String>>>()
    private val relays = mutableListOf<RelayConfig>()
    private var relaysClosed = 0
    private val detached = mutableListOf<Pair<List<String>, String>>()
    private var detachedExit: Int? = null
    private var detachFails = false
    private var signals = 0

    private class VersionTerminal : Terminal {
        private var said = false
        override suspend fun read(): ByteArray? = if (said) null else "2.1.0\n".encodeToByteArray().also { said = true }
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun terminate() = Unit
        override fun close() = Unit
        override val exitCode: Int = 0
    }

    private val processes = ProcessStarter { _, arguments, environment, _ ->
        started += arguments to environment
        VersionTerminal()
    }

    private fun ports(processes: ProcessStarter? = this.processes, stop: () -> Boolean = { false }) = RemoteHostPorts(
        processes = processes,
        environment = { mapOf("PATH" to "/usr/bin", "OSTROM_TOKEN" to "secret", "ANTHROPIC_API_KEY" to "own-key") },
        http = ::http,
        relays = { _ ->
            LocalRelayStarter { config ->
                relays += config
                object : LocalRelay {
                    override val url = "http://127.0.0.1:4000"
                    override val token = "hook-token"
                    override val clientToken = "client-token"
                    override fun close() {
                        relaysClosed++
                    }
                }
            }
        },
        detach = { command, log ->
            if (detachFails) throw IOException("no such program")
            detached += command to log
            DetachedProcess { detachedExit }
        },
        installSignals = { signals++ },
        stopRequested = stop,
        heartbeat = 20.milliseconds,
        signalPollMillis = 5,
        startPollMillis = 1_000,
        startMillis = 3_000,
    )

    private fun remote(
        vararg args: String,
        environment: Map<String, String> = withServer,
        executable: String? = "/opt/ostrom",
        ports: RemoteHostPorts = ports(),
        features: Array<String> = arrayOf("runners.cloud.v1", "runners.cloud.remote.v1"),
    ): Int {
        signIn("/home/maya/.config/ostrom")
        answers.getOrPut("GET /api/v1/orgs") { mutableListOf(orgs(acme).second) }
        answers["GET /api/v1/capabilities"] = mutableListOf(capabilities(*features).second)
        val command = cli(environment = environment, api = server, executable = executable, remote = {
            remoteCommand(it) { ports }
        })
        return command.run(arrayOf("remote", *args))
    }

    private fun sessions() = RemoteSessions(records, FileStoreImpl(fileSystem), fileSystem) {}

    private fun record(sessionId: String = id, agent: String = "claude") {
        fileSystem.createDirectories("/work/acme".toPath())
        sessions().save(RemoteSessionRecord(sessionId, "/work/acme", agent))
    }

    private fun hosting(epoch: Int = 4) {
        val opened = """{"orgId":"o1","epoch":$epoch,"session":{"name":"paywall","host":{"permissionMode":"ask"}}}"""
        answer(host, 200 to opened)
    }

    private val models = "GET /api/v1/inference/v1/models"

    @Test
    fun startCreatesARemoteSessionInTheDirectoryAndHostsItUntilItPauses() {
        fileSystem.createDirectories("/work/acme/api".toPath())
        answer(create, 201 to """{"id":"$id","kind":"remote","epoch":0}""")
        answer("POST $session/messages", 201 to """{"id":"m1"}""")
        hosting()
        answer(models, 200 to """{"data":[{"id":"opus"}]}""")
        val options = arrayOf("--name", "Paywall", "--model", "opus", "--message", "Fix the build", "--json")
        val code = remote("start", "api", *options)
        assertEquals(0, code, console.err.toString())
        assertEquals(
            """{"agent":"claude","name":"Paywall","model":"opus","kind":"remote","directoryName":"api",""" +
                """"permissionMode":"ask"}""",
            bodyOf(create),
        )
        assertEquals(36, requests.last { it.second.path == "/api/v1/cloud/sessions" }.second.idempotencyKey?.length)
        assertEquals("""{"text":"Fix the build"}""", bodyOf("POST $session/messages"))
        assertEquals("""{"id":"$id","kind":"remote","epoch":0}""" + "\n", console.out.toString())
        assertEquals(RemoteSessionRecord(id, "/work/acme/api", "claude"), sessions().load(id))
        assertErr("Remote session $id on this device, in /work/acme/api.")
        assertErr("Hosting paywall (epoch 4) in /work/acme/api.")
        assertErr("Paused: the host checkpointed and exits. ostrom remote resume $id resumes it.")
        assertEquals(1, signals)
        // The launch on the runner channel, its agent token for the relay, revoked at the end.
        assertEquals(listOf("launch", "agent"), runnerCalls.take(2))
        assertTrue("heartbeat" in runnerCalls)
        assertEquals("agent-token", relays.single().agentToken)
        assertEquals("L1", relays.single().launchId)
        assertTrue(relays.single().inference)
        assertEquals(1, relaysClosed)
        assertTrue("DELETE /api/v1/launches/L1" in sent())
        // The agent's environment: no Ostrom variable, no key of its own, the relay instead.
        val (arguments, environment) = started.single()
        assertEquals(listOf("claude", "--version"), arguments)
        assertEquals("/usr/bin", environment["PATH"])
        assertEquals("http://127.0.0.1:4000", environment["ANTHROPIC_BASE_URL"])
        assertEquals("client-token", environment["ANTHROPIC_AUTH_TOKEN"])
        assertNull(environment["OSTROM_TOKEN"])
        assertNull(environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun bypassingPermissionsIsSaidAndSentAndTheDirectoryNamesTheSession() {
        fileSystem.createDirectories("/work/acme".toPath())
        answer(create, 201 to """{"id":"$id"}""")
        hosting()
        assertEquals(0, remote("start", "--agent", "codex", "--bypass-permissions"))
        assertEquals(
            """{"agent":"codex","name":"acme","kind":"remote","directoryName":"acme","permissionMode":"bypass"}""",
            bodyOf(create),
        )
        assertErr("Warning: the agent acts on your files without asking for permission.")
        assertTrue(relays.isEmpty(), "Codex keeps its own provider")
        assertTrue(sent().none { it == models })
        assertEquals("", console.out.toString())
    }

    @Test
    fun startRefusesWhatItCannotHost() {
        fileSystem.createDirectories("/work/acme".toPath())
        assertEquals(66, remote("start", "gone"))
        assertErr("ostrom: gone does not exist.")
        operatingSystem = OperatingSystem.WINDOWS
        assertEquals(70, remote("start", ports = ports(processes = null)))
        assertErr("this version hosts remote sessions on macOS and Linux only")
        operatingSystem = OperatingSystem.LINUX
        assertEquals(69, remote("start", features = arrayOf("runners.cloud.v1")))
        assertErr("runs no remote session (runners.cloud.remote.v1)")
        answer(create, 201 to """{"status":"creating"}""")
        assertEquals(ExitCode.UNREACHABLE.code, remote("start"))
        assertErr("ostrom: the server created no session.")
        assertTrue(fileSystem.listOrNull(records.toPath()).isNullOrEmpty(), "No record without a session")
    }

    @Test
    fun aDetachedStartRunsTheHostCommandAndWaitsUntilItIsOnline() {
        fileSystem.createDirectories("/work/acme".toPath())
        answer(create, 201 to """{"id":"$id","epoch":0}""")
        answer(
            "GET $session",
            200 to """{"id":"$id","epoch":0,"host":{"online":false}}""",
            200 to """{"id":"$id","epoch":1,"host":{"online":true}}""",
        )
        assertEquals(0, remote("start", "--detach"))
        val log = "$records/$id.log"
        assertEquals(
            listOf("/opt/ostrom", "remote", "host", id, "--server", "https://ostrom.example", "--org", "o1") to log,
            detached.single(),
        )
        assertTrue(fileSystem.exists(log.toPath()), "The log exists before the child opens it")
        assertEquals(listOf(1_000L), waits, "The epoch it had before is not the background host's")
        assertErr("The host runs in the background (log: $log).")
        assertErr("Hosted in the background. ostrom remote stop $id pauses it.")
        assertTrue(runnerCalls.isEmpty(), "Nothing hosted in this process")
    }

    @Test
    fun aBackgroundHostThatExitsFailsOrIsSlowIsSaid() {
        record()
        answer("GET $session", 200 to """{"id":"$id","epoch":3,"desiredStatus":"paused","host":{"online":false}}""")
        detachedExit = 2
        assertEquals(1, remote("resume", id, "--detach"))
        assertErr("ostrom: the host of $id stopped (exit code 2); see its log: $records/$id.log")
        detachedExit = null
        assertEquals(0, remote("resume", id, "--detach"))
        assertErr("The host is still starting in the background (log: $records/$id.log).")
        detachFails = true
        assertEquals(1, remote("resume", id, "--detach"))
        assertErr("ostrom: the host of $id could not start in the background.")
        assertEquals(1, remote("resume", id, "--detach", executable = null))
        assertErr("ostrom: this binary's path is unknown: run ostrom remote host.")
        assertEquals(70, remote("resume", id, "--detach", ports = ports(processes = null)))
    }

    @Test
    fun resumeHostsOnlyThisDevicesSessionsAndNotTwice() {
        assertEquals(66, remote("resume", id))
        assertErr("ostrom: this device has no record of session $id")
        record()
        answer("GET $session", 200 to """{"id":"$id","epoch":3,"desiredStatus":"running","host":{"online":true}}""")
        assertEquals(0, remote("resume", id))
        assertErr("Session $id is already hosted on this device.")
        assertTrue(sent().none { it == host })
        answer("GET $session", 200 to """{"id":"$id","desiredStatus":"running","host":{"online":false}}""")
        hosting()
        assertEquals(0, remote("resume", id))
        assertErr("Follow it from Ostrom's console or app. Ctrl-C pauses it; ostrom remote resume $id resumes it.")
        assertErr("Paused: the host checkpointed")
    }

    @Test
    fun aHostWaitsForAPausingSessionThenTakesIt() {
        record()
        val pausing = 409 to """{"error":{"type":"session_pausing","message":"pausing"}}"""
        answer(host, pausing, pausing, 200 to """{"orgId":"o1","epoch":5}""")
        assertEquals(0, remote("host", id))
        assertEquals(3, sent().count { it == host })
        assertEquals(listOf(2_000L, 2_000L), waits)
        assertEquals(1, console.err.split("The session is still pausing").size - 1, "Said once that it waits")
        assertErr("Hosting the session (epoch 5) in /work/acme.")
        // Any other refusal is final; a session that pauses too long is refused as the server says.
        answer(host, 409 to """{"error":{"type":"remote_host_mismatch","message":"another device hosts it"}}""")
        val before = sent().count { it == host }
        assertTrue(remote("host", id) != 0)
        assertEquals(before + 1, sent().count { it == host })
        assertErr("another device hosts it")
        answer(host, pausing)
        waits.clear()
        assertTrue(remote("host", id) != 0)
        assertEquals(44, waits.size)
        answer(host, 200 to """{"orgId":"o1"}""")
        assertEquals(ExitCode.UNREACHABLE.code, remote("host", id))
        assertErr("ostrom: the server opened no execution for this host.")
    }

    @Test
    fun aHostRefusesARecordThatNamesNoSession() {
        record(sessionId = "not-a-uuid")
        assertEquals(64, remote("host", "not-a-uuid"))
        assertErr("ostrom: not-a-uuid is not a session id.")
        assertEquals(70, remote("host", "not-a-uuid", ports = ports(processes = null)))
    }

    @Test
    fun aSignalAsksTheServerForAPause() {
        record()
        hosting()
        answer(pause, 200 to """{"status":"running","phase":"pausing"}""")
        instruction = { if (pause in sent()) "stop" else "run" }
        assertEquals(0, remote("host", id, ports = ports(stop = { true })))
        assertErr("Pausing the session: the agent finishes its turn, then the host checkpoints and exits.")
        assertErr("Paused: the host checkpointed")
    }

    @Test
    fun aPauseThatCannotBeRequestedStopsTheHostAtOnce() {
        record()
        hosting()
        instruction = { "run" }
        assertEquals(1, remote("host", id, ports = ports(stop = { true })))
        assertErr("The pause could not be requested; stopping now.")
        assertTrue("DELETE /api/v1/launches/L1" in sent(), "The launch still ends")
    }

    @Test
    fun aFencedOrTerminalExecutionEndsTheHostWithAMessage() {
        record()
        hosting()
        agentView = 409 to """{"error":{"type":"execution_fenced","message":"fenced"}}"""
        assertEquals(0, remote("host", id))
        assertErr("This host no longer runs the session: another host took it")
        assertFalse("heartbeat" in runnerCalls)
        console.err.clear()
        agentView = 409 to """{"error":{"type":"terminal_session","message":"a terminal"}}"""
        assertEquals(0, remote("host", id))
        assertErr("This host no longer runs the session")
        // The server ends the execution at a heartbeat: the host says so and ends.
        console.err.clear()
        agentView = 200 to """{"kind":"claude"}"""
        instruction = { "end" }
        assertEquals(0, remote("host", id))
        assertErr("This host no longer runs the session")
    }

    @Test
    fun aLaunchRefusedOrUnreachableEndsTheHost() {
        record()
        hosting()
        answer(pause, 200 to """{"status":"paused"}""")
        launch = { 403 to """{"error":{"type":"policy_denied","message":"the policy refuses remote sessions"}}""" }
        assertEquals(77, remote("host", id))
        assertErr("Your organisation refused this session's launch: ")
        assertTrue(pause in sent(), "A refused launch pauses the session")
        launch = { 409 to """{"error":{"type":"execution_fenced","message":"fenced"}}""" }
        assertEquals(0, remote("host", id))
        assertErr("This host no longer runs the session")
        launch = { 503 to "{}" }
        assertEquals(ExitCode.UNREACHABLE.code, remote("host", id))
        assertErr("The session's launch could not be opened (IllegalStateException): run ostrom remote resume $id")
        launch = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { remote("host", id) }
    }

    @Test
    fun theAgentsInferenceFollowsOstromInferenceThenTheOrganisationsModels() {
        record()
        hosting()
        assertEquals(0, remote("host", id, environment = withServer + ("OSTROM_INFERENCE" to "off")))
        assertErr("The agent runs with its own provider configuration.")
        assertTrue(relays.isEmpty())
        assertEquals("own-key", started.last().second["ANTHROPIC_API_KEY"])
        assertEquals(0, remote("host", id, environment = withServer + ("OSTROM_INFERENCE" to "on")))
        assertEquals(1, relays.size)
        assertTrue(sent().none { it == models }, "Forced on: no question to the server")
        answer(models, 200 to """{"data":[]}""")
        assertEquals(0, remote("host", id))
        assertEquals(1, relays.size, "No model served: the agent keeps its provider")
        answers.remove(models)
        assertEquals(0, remote("host", id))
        assertEquals(1, relays.size)
    }

    @Test
    fun stopPausesARemoteSessionFromAnyDevice() {
        answer("GET $session", 200 to """{"id":"$id","kind":"remote"}""")
        answer(pause, 200 to """{"status":"running","phase":"pausing","host":{"online":true}}""")
        assertEquals(0, remote("stop", id))
        assertOut("Session $id: running (pausing), pausing; the agent finishes its turn, then its host checkpoints")
        console.out.clear()
        answer(pause, 200 to """{"status":"paused"}""")
        assertEquals(0, remote("stop", id))
        assertEquals("Session $id: paused, pausing.\n", console.out.toString())
        answer("GET $session", 200 to """{"id":"$id","kind":"cloud"}""")
        assertEquals(64, remote("stop", id))
        assertErr("is a cloud session; use ostrom cloud pause.")
        answers.remove("GET $session")
        assertEquals(66, remote("stop", id))
        assertErr("No remote session $id.")
    }

    private fun item(sessionId: String, status: String = "running", device: String = "d1", online: Boolean = true) =
        """{"id":"$sessionId","status":"$status","name":"n","agent":"claude",""" +
            """"host":{"online":$online,"deviceId":"$device","deviceName":"Laptop","directoryName":"api"}}"""

    @Test
    fun listShowsThisDevicesSessionsAndForgetsTerminatedOnes() {
        val ended = "0a1b2c3d-1111-4222-8333-944455556666"
        record()
        record(sessionId = ended)
        answer("GET /api/v1/devices", 200 to """{"items":[{"id":"d0"},{"id":"d1","current":true}]}""")
        val list = "GET /api/v1/cloud/sessions?kind=remote"
        answer(list, 200 to """{"items":[${item(id)},${item("other", device = "d2")}],"next":"other"}""")
        answer("$list&before=other", 200 to """{"items":[${item(ended, status = "terminated")}]}""")
        assertEquals(0, remote("list"))
        assertEquals("$id\trunning\tn\tclaude\tonline\t/work/acme\n", console.out.toString())
        assertNull(sessions().load(ended), "A terminated session's record is dropped")
        assertTrue(sessions().load(id) != null)
        console.out.clear()
        assertEquals(0, remote("list", "--all-devices"))
        assertEquals(
            "$id\trunning\tn\tclaude\tonline\tLaptop\t/work/acme\nother\trunning\tn\tclaude\tonline\tLaptop\tapi\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, remote("list", "--json"))
        assertTrue(console.out.startsWith("""[{"id":"$id","""), console.out.toString())
    }

    @Test
    fun listSaysWhenNothingRunsAndStopsAtFiveHundred() {
        val list = "GET /api/v1/cloud/sessions?kind=remote"
        answer(list, 200 to """{"items":[]}""")
        assertEquals(0, remote("list"))
        assertErr("No remote session on this device; start one with ostrom remote start.")
        assertEquals(0, remote("list", "--all-devices"))
        assertErr("No remote session.")
        val page = (1..250).joinToString(",") { item("s$it", device = "d9") }
        answer(list, 200 to """{"items":[$page],"next":"a"}""")
        answer("$list&before=a", 200 to """{"items":[$page],"next":"b"}""")
        console.out.clear()
        assertEquals(0, remote("list", "--json"))
        assertEquals("[]\n", console.out.toString(), "No device of this server is current: nothing is here")
        assertErr("Showing the newest 500 remote sessions.")
        assertFalse(sent().any { it.endsWith("before=b") })
    }
}
