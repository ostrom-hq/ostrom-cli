// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.remote.hosted
import run.ostrom.cli.remote.refuseDirectory
import run.ostrom.cli.remote.remoteDirectory
import run.ostrom.cli.remote.remoteLine
import run.ostrom.cli.runner.conversation.AgentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A remote session's records, its directory and its rows, as TeamCode's `RemoteSessionTest`. */
class RemoteSessionTest {
    private val id = "8f3c0a2e-1111-4222-8333-944455556666"
    private val fileSystem = FakeFileSystem()
    private val root = "/home/me/.config/ostrom/remote"
    private val restricted = mutableListOf<String>()
    private val sessions = RemoteSessions(root, FileStoreImpl(fileSystem), fileSystem) { restricted += it }

    @Test
    fun refusesRootsHomesAndSystemDirectories() {
        val home = "/Users/me".toPath()
        refuseDirectory("/Users/me/project".toPath(), home, windows = false)
        refuseDirectory("/Users/me/usr".toPath(), home, windows = false)
        refuseDirectory("/usrlocal".toPath(), home, windows = false)
        refuseDirectory("/Users/me/project".toPath(), null, windows = false)
        val refusedPaths = listOf(
            "/", "/Users/me", "/usr", "/usr/local/bin", "/etc", "/System/Library", "/private/etc/ssh",
        )
        for (path in refusedPaths) {
            val refused = assertFailsWith<PrintMessage>(path) { refuseDirectory(path.toPath(), home, windows = false) }
            assertEquals(ExitCode.USAGE.code, refused.statusCode)
            assertTrue(refused.message!!.startsWith("ostrom: choose a project directory: "), refused.message)
        }
        val windowsHome = "C:\\Users\\me".toPath()
        refuseDirectory("C:\\Users\\me\\project".toPath(), windowsHome, windows = true)
        val windowsPaths = listOf(
            "C:\\", "c:\\users\\ME", "C:\\Windows", "c:\\windows\\System32", "C:\\Program Files\\Git",
        )
        for (path in windowsPaths) {
            assertFailsWith<PrintMessage>(path) { refuseDirectory(path.toPath(), windowsHome, windows = true) }
        }
    }

    @Test
    fun theDirectoryIsResolvedAgainstTheWorkingDirectoryAndMustExist() {
        fileSystem.createDirectories("/home/me/code/api".toPath())
        fileSystem.write("/home/me/code/notes.txt".toPath()) { writeUtf8("x") }
        val api = "/home/me/code/api".toPath()
        assertEquals(api, remoteDirectory(fileSystem, "api", "/home/me/code", "/home/me", false))
        assertEquals(api, remoteDirectory(fileSystem, "/home/me/code/api", "/", null, false))
        val missing = assertFailsWith<PrintMessage> { remoteDirectory(fileSystem, "gone", "/home/me", null, false) }
        assertEquals(ExitCode.NOT_FOUND.code, missing.statusCode)
        assertEquals("ostrom: gone does not exist.", missing.message)
        val file = assertFailsWith<PrintMessage> {
            remoteDirectory(fileSystem, "code/notes.txt", "/home/me", null, false)
        }
        assertEquals(ExitCode.USAGE.code, file.statusCode)
        val home = assertFailsWith<PrintMessage> { remoteDirectory(fileSystem, ".", "/home/me", "/home/me/", false) }
        assertEquals("ostrom: choose a project directory: a session may not run in /home/me itself.", home.message)
        // A home that cannot be resolved is compared as it is written.
        assertEquals(
            "/home/me/code/api".toPath(),
            remoteDirectory(fileSystem, "code/api", "/home/me", "/home/ghost", false),
        )
    }

    @Test
    fun recordsStayOnThisDeviceAndRejectOddIds() {
        val record = RemoteSessionRecord(id, "/Users/me/project", "claude")
        sessions.save(record)
        assertEquals(record, sessions.load(id))
        assertEquals(listOf(root), restricted, "The records' folder is made the owner's alone")
        assertNull(sessions.load("0a1b2c3d-1111-4222-8333-944455556666"), "No record of another session")
        assertNull(sessions.load("../config"), "Never a path outside the records")
        assertFailsWith<IllegalArgumentException> { sessions.save(record.copy(sessionId = "../x")) }
        fileSystem.write("$root/damaged.json".toPath()) { writeUtf8("{not json") }
        assertNull(sessions.load("damaged"), "A damaged record is no record")
        fileSystem.write("$root/other.json".toPath()) { writeUtf8("""{"sessionId":"x","directory":"/","agent":"c"}""") }
        assertNull(sessions.load("other"), "A record of another id is not this session's")
    }

    @Test
    fun theConversationIsKeptBesideTheRecord() = runTest {
        val state = AgentState("claude", "2.1", "0000000a-aaaa-4bbb-8ccc-00000000000a")
        sessions.conversation(id).write(state)
        assertEquals(state, sessions.conversation(id).read())
        assertTrue(fileSystem.exists("$root/$id.conversation.json".toPath()))
    }

    @Test
    fun recordsArePrunedOnlyForEndedSessionsAndLogsStayBounded() {
        val kept = RemoteSessionRecord(id, "/Users/me/project", "claude")
        val ended = RemoteSessionRecord("0a1b2c3d-1111-4222-8333-944455556666", "/Users/me/old", "codex")
        sessions.save(kept)
        sessions.save(ended)
        val log = sessions.log(ended.sessionId).toPath()
        assertEquals(0, fileSystem.metadata(log).size, "Created empty")
        fileSystem.write(log) { writeUtf8("x".repeat(10)) }
        assertEquals(log.toString(), sessions.log(ended.sessionId), "A small log is kept")
        assertEquals(10, fileSystem.metadata(log).size)
        fileSystem.write(log) { writeUtf8("x".repeat((RemoteSessions.MAX_LOG_BYTES + 1).toInt())) }
        sessions.log(ended.sessionId)
        assertEquals(0, fileSystem.metadata(log).size, "A log past 1 MiB starts again")
        assertFailsWith<IllegalArgumentException> { sessions.log("../x") }
        sessions.prune(setOf(ended.sessionId, "../config", "0b1b2c3d-1111-4222-8333-944455556666"))
        assertNull(sessions.load(ended.sessionId))
        assertFalse(fileSystem.exists(log))
        assertEquals(kept, sessions.load(kept.sessionId))
        sessions.prune(emptySet())
        assertEquals(kept, sessions.load(kept.sessionId))
    }

    private fun session(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun rowsSayWhereASessionRuns() {
        val remote = session(
            """{"id":"$id","status":"paused","phase":"draining","name":"paywall","agent":"claude","epoch":2,""" +
                """"host":{"online":true,"deviceName":"Work laptop","directoryName":"mon-projet"}}""",
        )
        assertEquals(
            listOf(id, "paused (draining)", "paywall", "claude", "online", "/Users/me/project"),
            remoteLine(remote, RemoteSessionRecord(id, "/Users/me/project", "claude"), showDevice = false).split('\t'),
        )
        assertEquals(
            listOf(id, "paused (draining)", "paywall", "claude", "online", "Work laptop", "mon-projet"),
            remoteLine(remote, null, showDevice = true).split('\t'),
            "Without a record of this device, only the directory's name",
        )
        val bare = listOf("?", "", "-", "-", "offline", "removed device", "-")
        assertEquals(bare, remoteLine(session("{}"), null, true).split('\t'))
        assertEquals(bare, remoteLine(session("""{"host":{}}"""), null, true).split('\t'))
        assertTrue(hosted(remote, previousEpoch = 1))
        assertFalse(hosted(remote, previousEpoch = 2), "Still the previous host")
        assertFalse(hosted(session("""{"epoch":2,"host":{"online":false}}"""), previousEpoch = 1))
        assertFalse(hosted(session("""{"epoch":2}"""), previousEpoch = 1))
        assertFalse(hosted(session("{}"), previousEpoch = -1))
    }
}
