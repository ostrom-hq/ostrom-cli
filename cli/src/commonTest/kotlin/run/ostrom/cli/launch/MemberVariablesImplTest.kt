// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.Credentials
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchFilesImpl
import run.ostrom.cli.launcher.LaunchSite
import run.ostrom.cli.launcher.ServerCall
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.launcher.ServerTransport
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom run <agent>` and the member's environment variables, as TeamCode's `teamcode run` (Run.kt). */
class MemberVariablesImplTest {
    private val calls = mutableListOf<ServerCall>()
    private var reply = ServerReply(200, """{"variables":{"API_URL":"https://api","PATH":"/evil"},"unavailable":[]}""")
    private val server = ServerJson(
        ServerTransport { call ->
            calls += call
            reply
        },
    )
    private val fileSystem = FakeFileSystem()
    private val files = LaunchFilesImpl(fileSystem, restrict = { _, _ -> })
    private val said = mutableListOf<String>()
    private var inherited = mapOf<String, String>()
    private val site get() = LaunchSite(
        "https://o.example", Credentials("device-token", "alice"), "/w", "ostrom", "/c", "/tmp", "/h", ':',
        { inherited[it] },
    )
    private val features = setOf(ServerFeatures.ENVIRONMENT)

    @Test
    fun theVariablesAreReadWithTheLoginAndOnlyTheirNamesAreSaid() = runTest {
        reply = ServerReply(
            200,
            """{"variables":{"B_TOKEN":"b","A_URL":"a","PATH":"/evil","OSTROM_X":"x","N":1,"O":{}},""" +
                """"unavailable":["Z","Y",{}]}""",
        )
        val read = MemberVariablesImpl(server, files).read(site, features)
        assertEquals(mapOf("A_URL" to "a", "B_TOKEN" to "b"), read.variables)
        val call = calls.single()
        assertEquals("GET /api/v1/environment device-token", "${call.method} ${call.path} ${call.token}")
        assertEquals(
            listOf(
                "ostrom: environment variables unavailable right now: Y, Z",
                "ostrom: environment variables not given to the agent (reserved names): OSTROM_X, PATH",
                "ostrom: environment variables: A_URL, B_TOKEN",
            ),
            read.notes,
        )
        assertFalse(read.notes.any { "/evil" in it || "x" == it }, "never a value")
    }

    @Test
    fun nothingIsReadFromAServerWithoutTheFeature() = runTest {
        val read = MemberVariablesImpl(server, files).read(site, emptySet())
        assertNull(read.variables)
        assertTrue(read.notes.isEmpty() && calls.isEmpty())
    }

    @Test
    fun aServiceKeyStartsWithoutThemAndAnyOtherFailureWithAnEmptySet() = runTest {
        reply = ServerReply(403, """{"error":{"type":"forbidden","message":"This credential does not allow it."}}""")
        val refused = MemberVariablesImpl(server, files).read(site, features)
        assertNull(refused.variables)
        assertEquals(
            listOf("ostrom: environment variables are given to interactive logins only; this credential starts the " +
                "agent without them"),
            refused.notes,
        )
        reply = ServerReply(null, "connection refused")
        val failed = MemberVariablesImpl(server, files).read(site, features)
        assertEquals(emptyMap(), failed.variables, "Claude Code still gets them at the next refresh")
        assertEquals(listOf(UNREADABLE), failed.notes)
        val broken = ServerJson { throw IllegalStateException("the transport broke") }
        assertEquals(listOf(UNREADABLE), MemberVariablesImpl(broken, files).read(site, features).notes)
        val cancelled = ServerJson { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { MemberVariablesImpl(cancelled, files).read(site, features) }
        reply = ServerReply(200, """{"unavailable":[]}""")
        val none = MemberVariablesImpl(server, files).read(site, features)
        assertEquals(emptyMap(), none.variables)
        assertTrue(none.notes.isEmpty())
    }

    @Test
    fun claudeCodeGetsAPrivateEnvFileThatChainsTheDevelopersAndIsRemovedAtTheEnd() = runTest {
        fileSystem.createDirectories("/tmp/run".toPath())
        inherited = mapOf("CLAUDE_ENV_FILE" to "/home/me/env.sh")
        val delivery = MemberVariablesImpl(server, files).deliver(Agent.CLAUDE, mapOf("A" to "1"), site, "/tmp/run") {
            said += it
        }!!
        assertEquals(mapOf<String, String?>("CLAUDE_ENV_FILE" to "/tmp/run/environment.sh"), delivery.environment)
        val script = LocalEnvironment.script("/tmp/run/environment-values.sh", "/home/me/env.sh")
        assertEquals(script, read("environment.sh"))
        assertTrue("export A='1'" in read("environment-values.sh"))
        delivery.close()
        assertFalse(fileSystem.exists("/tmp/run/environment.sh".toPath()))
        assertFalse(fileSystem.exists("/tmp/run/environment-values.sh".toPath()))
        delivery.close()
        assertTrue(said.isEmpty())
        val stuck = object : LaunchFiles by files {
            override fun deleteRecursively(path: String) = throw IOException("busy")
        }
        MemberVariablesImpl(server, stuck).deliver(Agent.CLAUDE, emptyMap(), site, "/tmp/run") {}!!.close()
    }

    @Test
    fun theRefreshWaitsAMinuteBetweenReads() = runTest {
        fileSystem.createDirectories("/tmp/run".toPath())
        val delivery = MemberVariablesImpl(server, files).deliver(Agent.CLAUDE, mapOf("A" to "1"), site, "/tmp/run") {
            said += it
        }!!
        val refresh = launch { delivery.keepCurrent() }
        advanceTimeBy(59_000)
        assertTrue(calls.isEmpty())
        advanceTimeBy(2_000)
        assertEquals(1, calls.size)
        refresh.cancel()
    }

    @Test
    fun theFileIsRefreshedEveryMinuteFromTheServer() = runTest {
        fileSystem.createDirectories("/tmp/run".toPath())
        inherited = mapOf("CLAUDE_ENV_FILE" to " ")
        val sleeps = mutableListOf<Long>()
        val variables = MemberVariablesImpl(server, files) {
            sleeps += it
            if (sleeps.size == 3) throw CancellationException("the agent exited")
        }
        val delivery = variables.deliver(Agent.CLAUDE, mapOf("A" to "1"), site, "/tmp/run") { said += it }!!
        assertFalse("env.sh" in read("environment.sh"), "a blank CLAUDE_ENV_FILE chains nothing")
        assertFailsWith<CancellationException> { delivery.keepCurrent() }
        assertEquals(listOf(60_000L, 60_000L, 60_000L), sleeps)
        assertTrue("export API_URL='https://api'" in read("environment-values.sh"))
        assertTrue("unset A" in read("environment-values.sh"))
        assertEquals(listOf("ostrom: environment variables updated for the agent's next commands: API_URL"), said)
        assertEquals("device-token", calls.last().token)
        reply = ServerReply(500, "{}")
        val failing = variables.deliver(Agent.CLAUDE, mapOf("A" to "1"), site, "/tmp/run") { said += it }!!
        sleeps.clear()
        assertFailsWith<CancellationException> { failing.keepCurrent() }
        assertEquals(NOT_REFRESHED, said.last())
    }

    @Test
    fun theOtherAgentsKeepWhatTheyStartWith() {
        assertNull(MemberVariablesImpl(server, files).deliver(Agent.CODEX, mapOf("A" to "1"), site, "/tmp/run") {})
    }

    @Test
    fun aFileThatCannotBeWrittenLeavesTheAgentWithWhatItStartsWith() {
        val readOnly = LaunchFilesImpl(fileSystem, restrict = { _, _ -> throw IOException("read-only") })
        val variables = MemberVariablesImpl(server, readOnly)
        val delivery = variables.deliver(Agent.CLAUDE, mapOf("A" to "1"), site, "/tmp/run") { said += it }
        assertNull(delivery)
        assertEquals(
            listOf("ostrom: the environment file could not be written; the agent keeps the variables it starts with"),
            said,
        )
    }

    private fun read(name: String) = fileSystem.read("/tmp/run/$name".toPath()) { readUtf8() }

    private companion object {
        const val UNREADABLE = "ostrom: environment variables could not be read; starting the agent without them"
        const val NOT_REFRESHED =
            "ostrom: environment variables could not be refreshed; the agent keeps the last values"
    }
}
