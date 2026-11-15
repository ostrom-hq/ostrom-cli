// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli

import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.daemon.ClaudeTranscripts
import run.ostrom.cli.daemon.DaemonPorts
import run.ostrom.cli.daemon.DaemonTiming
import run.ostrom.cli.daemon.FakeDaemonServer
import run.ostrom.cli.daemon.UnreadableFiles
import run.ostrom.cli.daemon.folderStore
import run.ostrom.cli.daemon.runDaemon
import run.ostrom.cli.launcher.ServerCall
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.remote.RemoteHostPorts
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.remote.remoteCommand
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.service.CLOUD_RUNNER_REFUSAL
import run.ostrom.cli.service.ServiceSettings
import run.ostrom.cli.service.serviceCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `ostrom folders`, `ostrom remote resumable|folders`, and `remote start|resume` through this device's daemon (TeamCode's
 * `FolderCommandsTest` and `RemoteDaemonTest`), against the daemon's fake server.
 */
class DaemonCommandsTest : SetupKit() {
    private var fake = FakeDaemonServer()

    /** The capabilities route fails (a server restarting): the command's feature check lets it through. */
    private var capabilitiesDown = false
    private val conversation = "0c1d2e3f-aaaa-4bbb-8ccc-000000000001"
    private val records = "/home/maya/.config/ostrom/remote"

    init {
        fileSystem.allowSymlinks = true
        fileSystem.createDirectories("/work/acme/project".toPath())
        fileSystem.createDirectories("/home/maya".toPath())
    }

    /** The fake daemon server behind the CLI's HTTP client: the organisations are Acme only. */
    private val api: (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                requests += url to request
                return when {
                    request.path == "/api/v1/orgs" -> ServerResponse(200, """{"items":[$acme]}""", 1)
                    request.path == "/api/v1/capabilities" && capabilitiesDown -> ServerResponse(503, "{}", 1)
                    else -> daemonServer(url, request)
                }
            }
        }
    }

    /** What [fake] answers [request]; one it does not answer is a network failure. */
    private suspend fun daemonServer(url: String, request: ServerRequest): ServerResponse {
        val call = ServerCall(request.method, request.path, request.body, request.token, request.timeoutMillis)
        val reply = fake.send(call)
        val status = reply.status ?: throw ServerUnreachableException(url, Exception(reply.body))
        return ServerResponse(status, reply.body, 1)
    }

    private fun ports() = RemoteHostPorts(
        processes = ProcessStarter { _, _, _, _ -> error("no agent in this test") },
        environment = { emptyMap() },
        http = { error("no host in this test") },
        relays = { error("no relay in this test") },
        detach = { _, _ -> throw IOException("no background host in this test") },
        installSignals = {},
        stopRequested = { false },
        heartbeat = 1.seconds,
        signalPollMillis = 5,
        startPollMillis = 1_000,
        startMillis = 3_000,
        uploads = { error("no uploads in this test") },
    )

    private fun ostrom(vararg args: String, signedIn: Boolean = true): Int {
        if (signedIn) signIn("/home/maya/.config/ostrom")
        val command = cli(environment = withServer + ("PATH" to "/usr/bin"), api = api, remote = {
            remoteCommand(it) { ports() }
        })
        return command.run(arrayOf(*args))
    }

    private fun out(): String = console.out.toString().also { console.out.clear() }

    /** The device's daemon runs and announced continuing conversations. */
    private fun daemonOnline(features: List<String> = listOf("resume.v1")) {
        fake.daemonOnline = true
        fake.deviceFeatures = features
    }

    /** A Claude Code conversation of [directory] on this device. */
    private fun claudeConversation(directory: String, id: String = conversation, prompt: String = "Fix the build") {
        val project = "/home/maya/.claude/projects".toPath() / ClaudeTranscripts.projectName(directory)
        fileSystem.createDirectories(project)
        fileSystem.write(project / "$id.jsonl") {
            writeUtf8("""{"type":"user","cwd":"$directory","message":{"role":"user","content":"$prompt"}}""" + "\n")
        }
    }

    /** What the daemon would do once it hosts the session. */
    private fun hostOnSleep(id: String) {
        onSleep = {
            fake.sessions[id]?.let {
                it.epoch++
                it.online = true
                it.status = "running"
            }
            onSleep = {}
        }
    }

    @Test
    fun foldersAreAddedListedAndRemovedAndPublishedAtOnce() {
        assertEquals(0, ostrom("folders", "list"))
        assertErr("No folder yet; add one with ostrom folders add PATH.")
        assertEquals(0, ostrom("folders", "add", "project"))
        val added = out()
        val id = added.substringAfter("(").substringBefore(")")
        assertEquals("Added /work/acme/project ($id).\n", added)
        assertEquals(listOf(id to "project"), fake.folders.map { it.id to it.name })
        assertEquals("/work/acme/project", fake.folders.single().label, "Outside the home: the path")
        assertEquals(0, ostrom("folders", "add", "/work/acme/project"))
        assertEquals("/work/acme/project is already one of this device's folders ($id).\n", out())
        assertEquals(0, ostrom("folders", "list"))
        assertEquals("$id\t/work/acme/project\n", out())
        assertEquals(0, ostrom("folders", "list", "--json"))
        assertEquals("""[{"id":"$id","path":"/work/acme/project"}]""" + "\n", out())
        fileSystem.delete("/work/acme/project".toPath())
        assertEquals(0, ostrom("folders", "list"))
        assertEquals("$id\t/work/acme/project\tmissing\n", out())
        assertEquals(66, ostrom("folders", "remove", "unknown"))
        assertErr("ostrom: unknown is not one of this device's folders (ostrom folders list).")
        assertEquals(0, ostrom("folders", "remove", id))
        assertEquals("Removed /work/acme/project ($id).\n", out())
        assertTrue(fake.folders.isEmpty(), "Published at once")
        assertEquals(64, ostrom("folders", "add", "/home/maya"))
        assertErr("may not run in /home/maya itself")
    }

    @Test
    fun aFolderIsKeptWhenItCannotBePublishedNow() {
        assertEquals(0, ostrom("folders", "add", "project", signedIn = false))
        assertErr("Not signed in: this device's daemon publishes its folders once you sign in with ostrom login.")
        fake = FakeDaemonServer(daemonCapability = false)
        console.err.clear()
        assertEquals(0, ostrom("folders", "add", "project"))
        assertErr("This server does not start sessions on devices yet (runners.cloud.remote.daemon.v1).")
        fake = FakeDaemonServer()
        fake.failing["PUT /api/v1/devices/current/folders"] = 500
        assertEquals(0, ostrom("folders", "add", "project"))
        assertErr("The folders could not be published now (refused: refused); this device's daemon publishes them")
        assertEquals(0, ostrom("folders", "list", "--json"))
        assertTrue("/work/acme/project" in out())
    }

    @Test
    fun remoteFoldersListsTheRecentDirectoriesThenTheOfferedFolders() {
        assertEquals(0, ostrom("remote", "folders"))
        assertErr("No known folder yet: start a session with ostrom remote start DIRECTORY.")
        fileSystem.createDirectories("/work/acme/api".toPath())
        fileSystem.createDirectories("/work/acme/web".toPath())
        val sessions = RemoteSessions(records, FileStoreImpl(fileSystem), fileSystem) {}
        sessions.save(RemoteSessionRecord("s1", "/work/acme/api", "claude"))
        sessions.save(RemoteSessionRecord("s2", "/work/acme/web", "claude"))
        sessions.save(RemoteSessionRecord("s3", "/work/acme/api", "codex"))
        sessions.save(RemoteSessionRecord("s4", "/work/gone", "claude"))
        fileSystem.write("$records/s5.json".toPath()) { writeUtf8("{damaged") }
        fileSystem.write("$records/s1.conversation.json".toPath()) { writeUtf8("{}") }
        fileSystem.createSymlink("$records/s6.json".toPath(), "$records/s1.json".toPath())
        fileSystem.write("$records/s1.log".toPath()) { writeUtf8("log") }
        fileSystem.createDirectories("$records/s7.json".toPath())
        assertEquals(0, ostrom("folders", "add", "project"))
        out()
        assertEquals(0, ostrom("remote", "folders"))
        assertEquals(setOf("/work/acme/api", "/work/acme/web", "/work/acme/project"), out().lines().filter { it.isNotEmpty() }.toSet())
        assertEquals(0, ostrom("remote", "folders", "--json", "--limit", "1"))
        val json = out()
        assertTrue(json.startsWith("""{"folders":[{"path":"/work/acme/"""), json)
        assertTrue("lastUsedAt" in json, json)
        assertEquals(64, ostrom("remote", "folders", "--limit", "0"))
        assertErr("ostrom: --limit is between 1 and")
        console.err.clear()
        assertEquals(64, ostrom("remote", "folders", "--limit", "51"))
        assertErr("ostrom: --limit is between 1 and 50.")
        val unreadable = RemoteSessions(records, UnreadableFiles(FileStoreImpl(fileSystem)), fileSystem) {}
        assertEquals(emptyList(), unreadable.recentDirectories(), "Records that cannot be read are skipped")
    }

    @Test
    fun knownFoldersAreBoundedCheckedAndEachOnce() {
        val view = run.ostrom.cli.remote.knownFolders(
            recent = listOf("/w/a" to 2_000L, "/w/a/" to 1_000L, "/usr/bin" to 500L, "" to 1L, "/w/\u0007" to 1L, "/w/gone" to 1L),
            offered = listOf("/w/c", "/w/b", "/w/a"),
            limit = 3,
            home = "/home/maya".toPath(),
            windows = false,
        ) { it != "/w/gone" }
        assertEquals(listOf("/w/a", "/w/b", "/w/c"), view.folders.map { it.path })
        assertEquals("1970-01-01T00:00:02Z", view.folders.first().lastUsedAt)
        assertNull(view.folders.last().lastUsedAt, "An offered folder has no date")
        val windows = run.ostrom.cli.remote.knownFolders(listOf("C:\\w\\a" to 1L, "c:/W/A/" to 1L), emptyList(), 5, null, true) { true }
        assertEquals(listOf("C:\\w\\a"), windows.folders.map { it.path })
    }

    @Test
    fun resumableListsTheFoldersConversationsWhenTheDaemonCanContinueThem() {
        claudeConversation("/work/acme/project")
        claudeConversation("/work/acme/project", "0c1d2e3f-aaaa-4bbb-8ccc-000000000002", "Older")
        assertEquals(1, ostrom("remote", "resumable", "project"))
        assertErr("which is not running: start it with ostrom service install.")
        assertEquals(0, ostrom("remote", "resumable", "project", "--json"))
        assertEquals("""{"unavailable":"daemon_offline"}""" + "\n", out())
        daemonOnline(features = emptyList())
        assertEquals(1, ostrom("remote", "resumable", "project"))
        assertErr("This device's daemon is too old to continue a conversation")
        daemonOnline()
        assertEquals(0, ostrom("remote", "resumable", "project", "--limit", "1"))
        val line = out()
        assertTrue(line.endsWith("\t1 msg\tFix the build\n") || line.endsWith("\t1 msg\tOlder\n"), line)
        assertErr("Older ones: --before ")
        assertEquals(0, ostrom("remote", "resumable", "project", "--json"))
        assertTrue(out().startsWith("""{"items":[{"id":"0c1d2e3f-"""))
        assertEquals(0, ostrom("remote", "resumable", "project", "--before", "1970-01-01T00:00:00Z"))
        assertErr("No Claude Code conversation of /work/acme/project on this device.")
        assertEquals(64, ostrom("remote", "resumable", "project", "--before", "yesterday"))
        assertErr("ostrom: --before is an ISO 8601 time")
        assertEquals(64, ostrom("remote", "resumable", "--limit", "51"))
        assertErr("ostrom: --limit is between 1 and 50.")
        console.err.clear()
        assertEquals(64, ostrom("remote", "resumable", "--limit", "0"))
        assertErr("ostrom: --limit is between 1 and 50.")
        assertEquals(64, ostrom("remote", "resumable", "/home/maya"))
        fake = FakeDaemonServer(daemonCapability = false)
        assertEquals(1, ostrom("remote", "resumable", "project"))
        assertErr("This server does not continue a conversation of a device")
        fake = FakeDaemonServer()
        capabilitiesDown = true
        console.err.clear()
        assertEquals(1, ostrom("remote", "resumable", "project"))
        assertErr("This server does not continue a conversation of a device")
        capabilitiesDown = false
        fake.failing["GET /api/v1/devices"] = 500
        assertTrue(ostrom("remote", "resumable", "project") != 0)
    }

    @Test
    fun startHandsTheSessionToTheOnlineDaemon() {
        daemonOnline()
        hostOnSleep("5e55i0n-1")
        assertEquals(0, ostrom("remote", "start", "project", "--message", "Fix it", "--json"))
        assertTrue(out().startsWith("""{"id":"5e55i0n-1""""))
        val body = fake.created.single().toString()
        assertTrue(""""deviceId":"d1"""" in body && """"folderId":"${fake.folders.single().id}"""" in body, body)
        assertFalse("directoryName" in body, "The folder names the directory")
        assertErr("Added /work/acme/project to this device's folders")
        assertErr("Remote session 5e55i0n-1 on this device, in /work/acme/project; this device's daemon hosts it.")
        assertErr("This device's daemon hosts it.")
        assertErr("Follow it from Ostrom's console or app. ostrom remote stop 5e55i0n-1 pauses it.")
        val records = RemoteSessions(records, FileStoreImpl(fileSystem), fileSystem) {}
        assertEquals(RemoteSessionRecord("5e55i0n-1", "/work/acme/project", "claude"), records.load("5e55i0n-1"))
        // A daemon that does not take it in time, then one that refuses it.
        console.err.clear()
        waits.clear()
        assertEquals(0, ostrom("remote", "start", "project"))
        assertFalse("Added /work/acme/project" in console.err, "Already a folder")
        assertErr("This device's daemon has not taken the session yet")
        assertEquals(30, waits.size)
        onSleep = {
            fake.sessions["5e55i0n-3"]?.let {
                it.status = "terminated"
                it.failure = "host_refused" to "The device refused to start this session (folder_missing)."
            }
        }
        assertEquals(1, ostrom("remote", "start", "project"))
        assertErr("ostrom: this device's daemon did not start session 5e55i0n-3: The device refused to start this session")
        onSleep = { fake.sessions["5e55i0n-4"]?.status = "failed" }
        assertEquals(1, ostrom("remote", "start", "project"))
        assertErr("ostrom: this device's daemon did not start session 5e55i0n-4.")
        onSleep = { fake.unreachable += "GET /api/v1/cloud/sessions/5e55i0n-5" }
        waits.clear()
        assertEquals(0, ostrom("remote", "start", "project"))
        assertEquals(30, waits.size, "A failed read is read again")
    }

    @Test
    fun startResumesAConversationOfThisDeviceThroughTheDaemonOnly() {
        claudeConversation("/work/acme/project")
        assertEquals(64, ostrom("remote", "start", "project", "--resume", conversation, "--agent", "codex"))
        assertErr("ostrom: --resume continues a Claude Code conversation: use it with --agent claude.")
        console.err.clear()
        assertEquals(64, ostrom("remote", "start", "project", "--resume", "NOT-AN-ID"), "$console.out $console.err")
        assertErr("ostrom: --resume takes a Claude Code session id (a lowercase UUID).")
        assertEquals(1, ostrom("remote", "start", "project", "--resume", conversation))
        assertErr("which is not running: start it with ostrom service install.")
        assertTrue(fake.created.isEmpty())
        daemonOnline()
        val other = "0c1d2e3f-aaaa-4bbb-8ccc-000000000009"
        assertEquals(66, ostrom("remote", "start", "project", "--resume", other))
        assertErr("ostrom: $other is not one of the Claude Code conversations of /work/acme/project on this device")
        assertTrue(fake.folders.isEmpty(), "A wrong id adds no folder")
        hostOnSleep("5e55i0n-1")
        assertEquals(0, ostrom("remote", "start", "project", "--resume", conversation))
        assertTrue(""""resumeSessionId":"$conversation"""" in fake.created.single().toString())
        assertEquals(conversation, fake.sessions.getValue("5e55i0n-1").resume)
        assertEquals(64, ostrom("remote", "start", "/home/maya"))
    }

    @Test
    fun resumePutsADaemonsSessionBackInItsQueue() {
        daemonOnline()
        hostOnSleep("5e55i0n-1")
        assertEquals(0, ostrom("remote", "start", "project"))
        val session = fake.sessions.getValue("5e55i0n-1")
        session.desiredStatus = "paused"
        fake.checkpoint("5e55i0n-1")
        hostOnSleep("5e55i0n-1")
        console.err.clear()
        assertEquals(0, ostrom("remote", "resume", "5e55i0n-1"))
        assertTrue("resume 5e55i0n-1" in fake.events)
        assertErr("This device's daemon hosts it.")
        // Wanted running but not hosted (a daemon about to take it): waited for, not resumed again.
        session.online = false
        hostOnSleep("5e55i0n-1")
        assertEquals(0, ostrom("remote", "resume", "5e55i0n-1"))
        assertEquals(1, fake.events.count { it == "resume 5e55i0n-1" })
        assertEquals(0, ostrom("remote", "resume", "5e55i0n-1"))
        assertErr("Session 5e55i0n-1 is already hosted on this device.")
        // Its daemon gone offline, this terminal hosts it: here, a background host that cannot start.
        fake.daemonOnline = false
        session.online = false
        session.desiredStatus = "paused"
        assertTrue(ostrom("remote", "resume", "5e55i0n-1", "--detach") != 0)
        assertErr("the host of 5e55i0n-1 could not start in the background.")
        assertEquals(1, fake.events.count { it == "resume 5e55i0n-1" }, "Not put back in the daemon's queue")
    }

    @Test
    fun withoutAHomeDirectoryAFolderIsLabelledByItsPath() {
        signIn("/home/maya/.config/ostrom")
        val environment = mapOf("OSTROM_HOME" to "/home/maya/.config/ostrom", "OSTROM_URL" to "https://ostrom.example")
        assertEquals(0, cli(environment = environment, api = api).run(arrayOf("folders", "add", "/work/acme/project")))
        assertEquals("/work/acme/project", fake.folders.single().label)
    }

    /** The CLI's dependencies, as `ostrom service serve` would hand them to the daemon. */
    private fun dependencies(environment: Map<String, String> = withServer + ("PATH" to "/usr/bin")): CliDependencies {
        var captured: CliDependencies? = null
        cli(environment = environment, api = api, service = { captured = it; serviceCommand(it) }).run(arrayOf("folders"))
        console.out.clear()
        console.err.clear()
        return checkNotNull(captured)
    }

    private fun settings(config: String = "/home/maya/.config/ostrom/config.toml") =
        ServiceSettings("a".repeat(32), "/opt/ostrom/ostrom", config, "/tmp/reg", "linux", "501")

    private val detached = mutableListOf<List<String>>()
    private val paths = mutableListOf<String>()

    private fun daemonPorts() = DaemonPorts(
        detach = { command, _ ->
            detached += command
            val id = command[3]
            DetachedProcess { if (fake.sessions[id]?.desiredStatus == "running") null else 0 }
        },
        setPath = { paths += it },
        timing = DaemonTiming(pollWaitMillis = 50, tickMillis = 10, stopWaitMillis = 5_000, retryMillis = 100),
    )

    /** What the daemon said, run until [stop] (told how many times it asked). */
    private var reports = emptyList<String>()

    private fun daemonReports(
        dependencies: CliDependencies = dependencies(),
        settings: ServiceSettings = settings(),
        stop: (Int) -> Boolean,
    ) = runTest {
        val said = mutableListOf<String>()
        var asked = 0
        runDaemon(dependencies, settings, said::add, { stop(++asked) }, daemonPorts())
        reports = said
    }

    @Test
    fun theDaemonStaysOfflineWhereItCannotHost() {
        daemonReports(dependencies(withServer + ("OSTROM_AGENT_SOCKET" to "/s")), stop = { it > 2 })
        assertEquals(listOf(CLOUD_RUNNER_REFUSAL), reports)
        daemonReports(settings = settings("/elsewhere/config.toml"), stop = { it > 2 })
        assertEquals(listOf("This service uses another configuration than the CLI's (/elsewhere/config.toml); the daemon stays offline."), reports)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun theDaemonWaitsForASignInAndAServerThatStartsSessionsOnDevices() {
        daemonReports(stop = { it > 300 })
        assertEquals(listOf("not signed in to https://ostrom.example: run `ostrom login`"), reports, "Said once")
        signIn("/home/maya/.config/ostrom")
        fake = FakeDaemonServer(daemonCapability = false)
        daemonReports(stop = { it > 3 })
        assertEquals(1, reports.size)
        assertTrue("does not start sessions on devices" in reports.single(), reports.toString())
        fake = FakeDaemonServer()
        fake.unreachable += "GET /api/v1/capabilities"
        daemonReports(stop = { it > 3 })
        assertTrue(reports.single().startsWith("The server's capabilities could not be read (unreachable: "), reports.toString())
    }

    @Test
    fun theDaemonHostsOrdersWithTheServicesExecutableAndTheTerminalsPath() {
        signIn("/home/maya/.config/ostrom")
        fileSystem.createDirectories("/home/maya/.local/bin".toPath())
        val dependencies = dependencies()
        dependencies.folderStore().add("/work/acme/project".toPath(), "/home/maya/.nvm/bin:/usr/bin")
        val folder = dependencies.folderStore().read().folders.single()
        fake.queue("s1", folder.id, "project")
        daemonReports(dependencies, stop = { detached.isNotEmpty() && it > 50 })
        assertEquals(listOf("/home/maya/.nvm/bin:/usr/bin:/home/maya/.local/bin"), paths)
        assertEquals(
            listOf(
                "/opt/ostrom/ostrom", "remote", "host", "s1", "--accepted-epoch", "1", "--org", "o1",
                "--server", "https://ostrom.example",
            ),
            detached.single(),
        )
        assertTrue("Daemon started" in reports.first(), reports.toString())
        assertTrue("pause s1" in fake.events && "offline" in fake.events, fake.events.toString())
        // A damaged folders file keeps the service's own PATH.
        fileSystem.write("/home/maya/.config/ostrom/folders.json".toPath()) { writeUtf8("{damaged") }
        paths.clear()
        daemonReports(dependencies, stop = { it > 20 })
        assertEquals(listOf("/usr/bin:/home/maya/.local/bin"), paths)
    }

    @Test
    fun theDaemonKeepsAPathThatAlreadyHoldsEverything() {
        signIn("/home/maya/.config/ostrom")
        daemonReports(stop = { it > 20 })
        assertTrue("Daemon started" in reports.first(), reports.toString())
        assertTrue(paths.isEmpty(), paths.toString())
    }
}
