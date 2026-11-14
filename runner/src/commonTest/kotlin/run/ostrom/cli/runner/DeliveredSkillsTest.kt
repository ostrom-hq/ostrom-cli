// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class DeliveredSkillsTest {
    private val config = RunnerConfig(
        "http://s",
        Uuid.parse("00000000-0000-0000-0000-000000000001"),
        3,
        "claude",
        null,
        null,
        "t",
        "/workspace",
    )
    private val fileSystem = FakeFileSystem()
    private val home = "/workspace/.home"
    private val agentEnvironment = mapOf(
        "HOME" to home,
        "OSTROM_AGENT_COMMAND" to "claude",
        "OSTROM_AGENT_TOKEN" to "ostrom_agent_secret",
        "OSTROM_AGENT_PROMPT" to "the first message",
    )

    /** The installer's terminal: runs [CloudInstall.serve]'s side in memory, as the agent host would. */
    private inner class InstallerTerminal(
        environment: Map<String, String>,
        private val quiet: Boolean = false,
    ) : Terminal {
        private val output = Channel<ByteArray?>(Channel.UNLIMITED)
        private val installer = CloudInstall.installer(
            environment.getValue(PodDeliveries.KIND_VARIABLE),
            environment["OSTROM_AGENT_COMMAND"],
            environment,
            fileSystem,
        ) {}
        private val pending = StringBuilder()
        var terminated = false
        var closed = false
        override var exitCode: Int? = null

        init {
            if (!quiet) {
                // Noise before the hello (a profile's echo) is skipped by the runner.
                output.trySend("motd\r\n".encodeToByteArray())
                output.trySend((InstallMessage(InstallMessage.HELLO).encode() + "\n").encodeToByteArray())
            }
        }

        override suspend fun read(): ByteArray? = output.receive()

        override suspend fun write(bytes: ByteArray) {
            pending.append(bytes.decodeToString())
            while ('\n' in pending) {
                val line = pending.substring(0, pending.indexOf('\n'))
                pending.deleteRange(0, line.length + 1)
                val message = InstallMessage.decode(line) ?: continue
                for (reply in installer!!.handle(message)) output.send((reply.encode() + "\n").encodeToByteArray())
                if (message.t == InstallMessage.END) {
                    exitCode = 0
                    output.send(null)
                }
            }
        }

        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun terminate() {
            terminated = true
        }
        override fun close() {
            closed = true
        }
    }

    private val started = mutableListOf<Pair<String, Map<String, String>>>()
    private val terminals = mutableListOf<InstallerTerminal>()

    private fun starter(quiet: Boolean = false) = object : TerminalStarter {
        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
            assertEquals("/workspace", directory)
            started += script to environment
            return InstallerTerminal(environment, quiet).also { terminals += it }
        }
    }

    private class FakeApi : DeliveryApi {
        val manifests = mutableMapOf<String, JsonObject?>()
        val artifacts = mutableMapOf<String, JsonObject>()
        val asked = mutableListOf<String>()
        override suspend fun manifest(kind: String): JsonObject? = manifests[kind] ?: error("$kind down")
        override suspend fun artifact(kind: String, name: String, version: String): JsonObject {
            asked += "$kind/$name@$version"
            return artifacts["$kind/$name"] ?: error("not found")
        }
    }

    private val skillFiles = listOf(
        Artifacts.file("SKILL.md", "# Review"),
        Artifacts.file("bin/x", "echo", "executable"),
    )
    private val skillDigest = Artifacts.digest(skillFiles)
    private val definition = Artifacts.definition(listOf("PreToolUse", "Stop"), "Bash", "guard.sh", 30)
    private val hookFiles = listOf(
        Artifacts.file("README.md", "Guards Bash."),
        Artifacts.file("guard.sh", "#!/bin/sh\nexit 0\n", "executable"),
    )
    private val hookDigest = Artifacts.digest(hookFiles, assertNotNullDefinition().digestLine)

    private fun assertNotNullDefinition() = DeliveredItems.definition(definition)!!

    private fun skill(name: String, digest: String = skillDigest, size: Long = 12, count: Long = 2) = buildJsonObject {
        put("name", name)
        put("version", "1")
        put("digest", digest)
        put("sizeBytes", size)
        put("fileCount", count)
    }

    private fun hook(name: String, definition: JsonObject = this.definition, digest: String = hookDigest) =
        JsonObject(
            buildJsonObject {
                put("name", name)
                put("version", "2")
                put("digest", digest)
            } + ("definition" to definition),
        )

    private fun api(
        skills: List<JsonObject> = listOf(skill("review")),
        hooks: List<JsonObject> = listOf(hook("guard")),
    ) =
        FakeApi().apply {
            manifests[DeliveryApi.SKILLS] = JsonObject(mapOf("skills" to JsonArray(skills)))
            manifests[DeliveryApi.HOOKS] = JsonObject(mapOf("hooks" to JsonArray(hooks)))
            artifacts["skills/review"] = Artifacts.artifact("review", "1", skillDigest, skillFiles)
            artifacts["hooks/guard"] = Artifacts.artifact("guard", "2", hookDigest, hookFiles, definition)
        }

    @Test
    fun claudeGetsTheOwnersSkillsAndTeamHooksInItsSettings() = runTest {
        val logs = mutableListOf<String>()
        val api = api()
        val variables = PodDeliveries(api, starter(), config, logs::add).install(agentEnvironment)

        assertEquals("# Review", fileSystem.read("$home/.claude/skills/review/SKILL.md".toPath()) { readUtf8() })
        assertEquals(
            "#!/bin/sh\nexit 0\n",
            fileSystem.read("$home/.ostrom/hooks/guard/guard.sh".toPath()) { readUtf8() },
        )
        assertEquals(
            listOf(CloudProcess.INSTALL_SKILLS, CloudProcess.INSTALL_HOOKS),
            started.map { it.second.getValue(PodDeliveries.KIND_VARIABLE) },
        )
        assertTrue(
            started.all {
                it.first == PodDeliveries.SCRIPT && it.second[PodDeliveries.BINARY_VARIABLE] == RunnerConfig.BINARY
            },
        )
        assertTrue(terminals.all { it.closed && !it.terminated })
        val hooks = Json.parseToJsonElement(variables.getValue(RunnerConfig.SETTINGS_VARIABLE))
            .jsonObject.getValue("hooks").jsonObject
        assertEquals(listOf("PreToolUse", "SessionEnd", "Stop"), hooks.keys.sorted())
        val stop = hooks.getValue("Stop").jsonArray
        // The team's hook first, then the runner's own agent-state hook, kept in every case.
        assertEquals("\"\$HOME\"/'.ostrom/hooks/guard/guard.sh'", stop[0].command())
        assertEquals("Bash", stop[0].jsonObject.getValue("matcher").jsonPrimitive.content)
        assertTrue(stop[1].command().endsWith("cloud agent-state"))
        assertTrue(hooks.getValue("SessionEnd").jsonArray.single().command().endsWith("cloud agent-state"))
        assertEquals(listOf("skills: 1 installed", "hooks: 1 installed"), logs)
        assertTrue(logs.none { "review" in it || "guard" in it })

        // At the next start the skill is unchanged and not downloaded again; the hook is put back.
        api.asked.clear()
        PodDeliveries(api, starter(), config, logs::add).install(agentEnvironment)
        assertEquals(listOf("hooks/guard@2"), api.asked)
        assertTrue("skills: 1 unchanged" in logs)
    }

    private fun kotlinx.serialization.json.JsonElement.command() =
        jsonObject.getValue("hooks").jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content

    @Test
    fun codexGetsSkillsAndNoTeamHook() = runTest {
        val logs = mutableListOf<String>()
        val variables = PodDeliveries(api(), starter(), config.copy(agentCommand = "codex"), logs::add)
            .install(agentEnvironment + ("OSTROM_AGENT_COMMAND" to "codex"))
        assertEquals(emptyMap(), variables)
        assertTrue(fileSystem.exists("$home/.codex/skills/review/SKILL.md".toPath()))
        assertEquals(
            listOf(CloudProcess.INSTALL_SKILLS),
            started.map { it.second.getValue(PodDeliveries.KIND_VARIABLE) },
        )
    }

    @Test
    fun otherAgentsGetNothing() = runTest {
        for (agent in listOf("gemini", "bash")) {
            assertEquals(
                emptyMap(),
                PodDeliveries(api(), starter(), config.copy(agentCommand = agent), {}).install(agentEnvironment),
            )
        }
        assertTrue(started.isEmpty())
    }

    @Test
    fun aServerWithoutTheRoutesOrDownIsANotice() = runTest {
        val logs = mutableListOf<String>()
        val notServed = FakeApi().apply {
            manifests[DeliveryApi.SKILLS] = null
            manifests[DeliveryApi.HOOKS] = null
        }
        // The fake answers null as not served only when the key holds null; a missing key fails.
        val api = object : DeliveryApi by notServed {
            override suspend fun manifest(kind: String): JsonObject? = null
        }
        assertEquals(emptyMap(), PodDeliveries(api, starter(), config, logs::add).install(agentEnvironment))
        assertEquals(
            listOf(
                "skills: this server delivers none to cloud sessions",
                "hooks: this server delivers none to cloud sessions",
            ),
            logs,
        )
        logs.clear()
        assertEquals(emptyMap(), PodDeliveries(FakeApi(), starter(), config, logs::add).install(agentEnvironment))
        assertTrue(
            logs.all { it.endsWith("not installed (IllegalStateException); the agent starts with what is installed") },
        )
        assertEquals(2, logs.size)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aRefusedOrFailedDownloadIsCountedAndTheRestInstalled() = runTest {
        val logs = mutableListOf<String>()
        val broken = Artifacts.file("SKILL.md", "other")
        val api = api(
            skills = listOf(
                skill("review"),
                skill("tampered"),
                skill("missing"),
                skill("Bad"),
                skill("huge", size = 1L shl 40),
                skill("many", count = 201),
                JsonObject(skill("unsized") - "sizeBytes"),
                JsonObject(skill("uncounted") + ("fileCount" to JsonArray(emptyList()))),
                JsonObject(skill("textsize") + ("sizeBytes" to JsonPrimitive("x"))),
                JsonObject(skill("nocount") - "fileCount"),
                JsonObject(skill("textcount") + ("fileCount" to JsonPrimitive("x"))),
            ),
            hooks = listOf(
                hook("guard"),
                hook("Unnamed"),
                hook("bad", definition = Artifacts.definition(events = listOf("Unknown"))),
                hook("missing"),
                hook("tampered"),
                hook("redefined"),
                hook("noexec"),
                hook("undefined"),
                JsonObject(hook("nodefinition") - "definition"),
            ),
        ).apply {
            artifacts["skills/tampered"] = Artifacts.artifact("tampered", "1", skillDigest, listOf(broken))
            artifacts["hooks/tampered"] = Artifacts.artifact("tampered", "2", hookDigest, listOf(broken), definition)
            artifacts["hooks/undefined"] =
                JsonObject(Artifacts.artifact("undefined", "2", hookDigest, hookFiles, definition) - "definition")
            artifacts["hooks/redefined"] =
                Artifacts.artifact("redefined", "2", hookDigest, hookFiles, Artifacts.definition(timeout = 5))
            val plain = listOf(Artifacts.file("guard.sh", "#!/bin/sh\nexit 0\n"))
            artifacts["hooks/noexec"] = Artifacts.artifact(
                "noexec",
                "2",
                Artifacts.digest(plain, assertNotNullDefinition().digestLine),
                plain,
                definition,
            )
        }
        val variables = PodDeliveries(api, starter(), config, logs::add).install(agentEnvironment)
        assertTrue(fileSystem.exists("$home/.claude/skills/review".toPath()))
        assertTrue(fileSystem.list("$home/.claude/skills".toPath()).map { it.name } == listOf("review"))
        assertEquals(
            listOf(
                "skills: 8 not installed (limits)",
                "skills: 2 refused or not downloaded",
                "skills: 1 installed",
                "hooks: 8 refused or not downloaded",
                "hooks: 1 installed",
            ),
            logs,
        )
        assertTrue("guard" in variables.getValue(RunnerConfig.SETTINGS_VARIABLE))
    }

    @Test
    fun aManifestWithoutItsListOrWithOtherEntriesDeliversWhatItHolds() = runTest {
        val logs = mutableListOf<String>()
        val api = api().apply {
            val entries = JsonArray(listOf(JsonPrimitive(1), skill("review")))
            manifests[DeliveryApi.SKILLS] = JsonObject(mapOf("skills" to entries))
            manifests[DeliveryApi.HOOKS] = JsonObject(emptyMap())
        }
        PodDeliveries(api, starter(), config, logs::add).install(agentEnvironment)
        assertEquals(listOf("skills: 1 installed", "hooks: none delivered"), logs)
    }

    @Test
    fun hooksNoLongerDeliveredAreRemovedAndTheSettingsKeepTheRunnersOwn() = runTest {
        PodDeliveries(api(), starter(), config, {}).install(agentEnvironment)
        assertTrue(fileSystem.exists("$home/.ostrom/hooks/guard".toPath()))
        val logs = mutableListOf<String>()
        val variables = PodDeliveries(api(hooks = emptyList()), starter(), config, logs::add).install(agentEnvironment)
        assertEquals(emptyMap(), variables)
        assertTrue("hooks: 1 removed" in logs)
        assertTrue(!fileSystem.exists("$home/.ostrom/hooks/guard".toPath()))
    }

    @Test
    fun aMembersOwnHookFolderIsAConflictAndLeftOutOfTheSettings() = runTest {
        fileSystem.createDirectories("$home/.ostrom/hooks/guard".toPath())
        val logs = mutableListOf<String>()
        assertEquals(
            emptyMap(),
            PodDeliveries(api(skills = emptyList()), starter(), config, logs::add).install(agentEnvironment),
        )
        assertEquals(listOf("skills: none delivered", "hooks: 1 conflict"), logs)
    }

    @Test
    fun anInstallerThatNeverSaysHelloIsANotice() = runTest {
        val logs = mutableListOf<String>()
        val deliveries = PodDeliveries(api(), starter(quiet = true), config, logs::add, helloTimeout = 1.seconds)
        assertEquals(emptyMap(), deliveries.install(agentEnvironment))
        assertEquals(listOf("skills: the installer did not start", "hooks: the installer did not start"), logs)
        assertTrue(terminals.all { it.terminated && it.closed })
    }

    @Test
    fun anInstallerThatEndsEarlyOrHangsLeavesWhatIsInstalled() = runTest {
        val logs = mutableListOf<String>()
        val ends = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                ScriptedTerminal(listOf(InstallMessage(InstallMessage.HELLO).encode() + "\n"))
        }
        assertEquals(emptyMap(), PodDeliveries(api(), ends, config, logs::add).install(agentEnvironment))
        assertTrue(logs.isEmpty())
        val ready = InstallMessage(InstallMessage.HELLO).encode() + "\n" +
            InstallMessage(InstallMessage.READY).encode() + "\n"
        val stops = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                ScriptedTerminal(listOf(ready))
        }
        assertEquals(emptyMap(), PodDeliveries(api(), stops, config, logs::add).install(agentEnvironment))
        assertEquals(listOf("skills: none delivered", "hooks: none delivered"), logs)
        logs.clear()
        // A need without a name, and any other line before the ready, are ignored.
        val hello = listOf(InstallMessage.HELLO, InstallMessage.NEED, InstallMessage.DONE, InstallMessage.READY)
            .joinToString("") { InstallMessage(it).encode() + "\n" }
        val odd = listOf(
            InstallMessage(InstallMessage.NEED),
            InstallMessage(InstallMessage.RESULT),
            InstallMessage(InstallMessage.RESULT, name = "x"),
            InstallMessage(InstallMessage.RESULT, status = "installed"),
            InstallMessage(InstallMessage.RESULT, name = "x", status = "installed"),
            InstallMessage(InstallMessage.DONE),
        ).joinToString("") { it.encode() + "\n" } + "after\n"
        val answers = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                ScriptedTerminal(listOf(hello, odd))
        }
        PodDeliveries(api(), answers, config, logs::add).install(agentEnvironment)
        assertEquals("skills: 1 installed", logs.first())
        logs.clear()
        val hangs = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                ScriptedTerminal(listOf(ready), hang = true)
        }
        assertEquals(
            emptyMap(),
            PodDeliveries(api(), hangs, config, logs::add, timeout = 5.seconds).install(agentEnvironment),
        )
        assertEquals(
            listOf(
                "skills: took too long; the agent starts with what is installed",
                "hooks: took too long; the agent starts with what is installed",
            ),
            logs,
        )
    }

    @Test
    fun aCancelledDownloadStaysCancelled() = runTest {
        val api = object : DeliveryApi by api() {
            override suspend fun artifact(kind: String, name: String, version: String): JsonObject =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { PodDeliveries(api, starter(), config, {}).install(agentEnvironment) }
        val manifests = object : DeliveryApi by api() {
            override suspend fun manifest(kind: String): JsonObject? = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> {
            PodDeliveries(manifests, starter(), config, {}).install(agentEnvironment)
        }
        val hooks = object : DeliveryApi by api(skills = emptyList()) {
            override suspend fun artifact(kind: String, name: String, version: String): JsonObject =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { PodDeliveries(hooks, starter(), config, {}).install(agentEnvironment) }
    }

    @Test
    fun aBigFileIsStreamedInChunks() = runTest {
        val big = "x".repeat(InstallMessage.CHUNK_BYTES * 2 + 5)
        val files = listOf(Artifacts.file("SKILL.md", big))
        val digest = Artifacts.digest(files)
        val api = api(
            skills = listOf(skill("big", digest = digest, size = big.length.toLong(), count = 1)),
            hooks = emptyList(),
        ).apply {
            artifacts["skills/big"] = Artifacts.artifact("big", "1", digest, files)
        }
        PodDeliveries(api, starter(), config, {}).install(agentEnvironment)
        assertEquals(big, fileSystem.read("$home/.claude/skills/big/SKILL.md".toPath()) { readUtf8() })
    }

    /** A terminal that prints [lines], then ends, or hangs forever on its next read. */
    private class ScriptedTerminal(lines: List<String>, private val hang: Boolean = false) : Terminal {
        private val output = ArrayDeque(lines.map { it.encodeToByteArray() })
        override val exitCode: Int? = null
        override suspend fun read(): ByteArray? = output.removeFirstOrNull() ?: if (hang) awaitCancellation() else null
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun terminate() = Unit
        override fun close() = Unit
    }

    @Test
    fun theLinkSplitsLinesAndSkipsTheLongOnes() = runTest {
        val terminal = ScriptedTerminal(listOf("a\r\nbb", "b\n", "x".repeat(10) + "\nc\n"))
        val link = InstallerLink(terminal, maxLine = 5)
        assertEquals(listOf("a", "bbb", "c"), listOf(link.read(), link.read(), link.read()))
        assertNull(link.read())
        assertNull(InstallerLink(ScriptedTerminal(listOf("not json\n"))).message())
        // A character split across two reads stays whole.
        val bytes = "é\n".encodeToByteArray()
        val split = object : Terminal by terminal {
            private val parts = ArrayDeque(listOf(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, bytes.size)))
            override suspend fun read(): ByteArray? = parts.removeFirstOrNull()
        }
        assertEquals("é", InstallerLink(split).read())
    }

    @Test
    fun theInstallerGetsNeitherTheAgentsTokenNorItsFirstMessage() = runTest {
        PodDeliveries(api(), starter(), config, {}).install(agentEnvironment)
        val given = started.first().second
        val expected = setOf("HOME", "OSTROM_AGENT_COMMAND", PodDeliveries.KIND_VARIABLE, PodDeliveries.BINARY_VARIABLE)
        assertEquals(expected, given.keys)
    }

    @Test
    fun theRoutesAreCalledWithTheRunnerToken() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                calls += "${request.method.value} ${request.url.encodedPath} " +
                    "${request.headers[HttpHeaders.Authorization]} $body"
                val (status, answer) = when (request.url.encodedPath) {
                    "/api/v1/cloud/runner/skills" -> HttpStatusCode.OK to """{"memberId":"m","skills":[]}"""
                    "/api/v1/cloud/runner/hooks" -> HttpStatusCode.NotFound to """{"error":{}}"""
                    "/api/v1/cloud/runner/skills/artifact" -> HttpStatusCode.OK to """{"name":"review"}"""
                    else -> HttpStatusCode.Conflict to """{"error":{"type":"execution_fenced"}}"""
                }
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { expectSuccess = false }
        val api = HttpDeliveryApi(client, config)
        assertEquals(JsonArray(emptyList()), api.manifest(DeliveryApi.SKILLS)?.get("skills"))
        assertNull(api.manifest(DeliveryApi.HOOKS))
        assertEquals(JsonPrimitive("review"), api.artifact(DeliveryApi.SKILLS, "review", "1")["name"])
        assertFailsWith<IllegalStateException> { api.artifact(DeliveryApi.HOOKS, "guard", "2") }
        val fenced = HttpDeliveryApi(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.Conflict) }) { expectSuccess = false },
            config,
        )
        assertFailsWith<IllegalStateException> { fenced.manifest(DeliveryApi.SKILLS) }
        val execution = """{"sessionId":"00000000-0000-0000-0000-000000000001","epoch":3"""
        assertEquals(
            listOf(
                "POST /api/v1/cloud/runner/skills Bearer t $execution}",
                "POST /api/v1/cloud/runner/hooks Bearer t $execution}",
                "POST /api/v1/cloud/runner/skills/artifact Bearer t $execution,\"name\":\"review\",\"version\":\"1\"}",
                "POST /api/v1/cloud/runner/hooks/artifact Bearer t $execution,\"name\":\"guard\",\"version\":\"2\"}",
            ),
            calls,
        )
    }

    @Test
    fun withoutTeamHooksTheSettingsAreTheRunnersOwn() {
        val settings = Json.parseToJsonElement(TeamHookSettings.claude("/opt/ostrom/ostrom", emptyList()))
        assertEquals(Json.parseToJsonElement(AgentState.claudeSettings("/opt/ostrom/ostrom")), settings)
        val unmatched = InstalledHook("it's", TeamHookDefinition(listOf("Notification"), null, "a.sh", 5))
        val hooks = Json.parseToJsonElement(TeamHookSettings.claude("/opt/ostrom/ostrom", listOf(unmatched)))
            .jsonObject.getValue("hooks").jsonObject
        val entry = hooks.getValue("Notification").jsonArray.single().jsonObject
        assertNull(entry["matcher"])
        assertEquals(
            "\"\$HOME\"/'.ostrom/hooks/it'\\''s/a.sh'",
            entry.getValue("hooks").jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content,
        )
    }
}
