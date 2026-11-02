// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.BuildInfo
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.FileStore
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.FileWriteException
import dev.ostrom.cli.platform.ProcessCapture
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateNoticeTest {
    private val fileSystem = FakeFileSystem()
    private val path = "/home/maya/.config/ostrom/update-check.json"
    private var time = 10 * UpdateNotice.DAY_MILLIS
    private var refreshes = 0
    private var started = true
    private val said = mutableListOf<String>()

    private var advice: String? = "Run ostrom update."

    private fun notice(current: String = "1.0.0", at: String = path) =
        UpdateNotice(FileStoreImpl(fileSystem), at, current, { time }, { advice }) { started.also { refreshes++ } }

    private fun state() = parseObject(fileSystem.read(path.toPath()) { readUtf8() })!!

    private fun millis(key: String) = (state()[key] as JsonPrimitive).longOrNull

    @Test
    fun theFirstCommandSaysNothingAndStartsOneRefreshADay() {
        notice().after { said += it }
        assertEquals(1, refreshes)
        assertEquals(time, millis(UpdateNotice.CHECKED), "written before the refresh starts")
        time += UpdateNotice.DAY_MILLIS - 1
        notice().after { said += it }
        assertEquals(1, refreshes, "not again the same day")
        time += 1
        notice().after { said += it }
        assertEquals(2, refreshes, "a day later")
        assertTrue(said.isEmpty())
    }

    @Test
    fun aNewerReleaseIsSaidOnceADay() {
        notice().record("1.2.0")
        assertEquals(0, refreshes)
        notice().after { said += it }
        assertEquals(listOf("! ostrom 1.2.0 is available. Run ostrom update.\n"), said)
        assertEquals(0, refreshes, "recorded just now: no refresh")
        notice().after { said += it }
        assertEquals(1, said.size, "once a day")
        time += UpdateNotice.DAY_MILLIS
        notice().after { said += it }
        assertEquals(2, said.size)
        assertEquals(1, refreshes)
        assertEquals("1.2.0", state()["latest"].let { (it as JsonPrimitive).content })
    }

    @Test
    fun anOlderOrTheSameReleaseIsNeverSaid() {
        notice().record("1.0.0")
        notice().after { said += it }
        notice(current = "2.0.0").after { said += it }
        assertTrue(said.isEmpty())
    }

    @Test
    fun anUnknownLatestKeepsTheOneKnownAndIsLookedUpAgainAnHourLater() {
        notice().record("1.2.0")
        notice().record(null)
        assertEquals("1.2.0", (state()["latest"] as JsonPrimitive).content)
        assertEquals(time - UpdateNotice.DAY_MILLIS + UpdateNotice.RETRY_MILLIS, millis(UpdateNotice.CHECKED))
        time += UpdateNotice.RETRY_MILLIS - 1
        notice().after { said += it }
        assertEquals(0, refreshes, "not before the hour")
        time += 1
        notice().after { said += it }
        assertEquals(1, refreshes, "an hour after a lookup that failed")
    }

    @Test
    fun aClockThatWentBackIsDue() {
        notice().record("1.0.0")
        time -= 1
        notice().after { said += it }
        assertEquals(1, refreshes, "an earlier time than the last check is due")
    }

    @Test
    fun theNoticeWaitsWhileItsAdviceDoes() {
        notice().record("1.2.0")
        advice = null
        notice().after { said += it }
        assertTrue(said.isEmpty(), "a session runs: nothing said, and nothing stamped")
        advice = "Homebrew: run brew upgrade ostrom."
        notice().after { said += it }
        assertEquals(listOf("! ostrom 1.2.0 is available. Homebrew: run brew upgrade ostrom.\n"), said)
    }

    @Test
    fun theAdviceNamesThePackageManagerElseWaitsForTheSessionsElseIsOstromUpdate() {
        val idle = AgentSessions { null }
        val busy = AgentSessions { "a local relay" }
        val brew = "/opt/homebrew/Cellar/ostrom/1.0/bin/ostrom"
        assertEquals("Homebrew: run brew upgrade ostrom.", noticeAdvice(brew, busy)())
        assertEquals(null, noticeAdvice("/usr/local/bin/ostrom", busy)())
        assertEquals("Run ostrom update.", noticeAdvice("/usr/local/bin/ostrom", idle)())
        assertEquals("Run ostrom update.", noticeAdvice(null, idle)())
    }

    @Test
    fun anUnreadableFileIsStartedAfresh() {
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write(path.toPath()) { writeUtf8("not json") }
        notice().after { said += it }
        assertEquals(1, refreshes)
        assertNotNull(millis(UpdateNotice.CHECKED))
    }

    @Test
    fun aFolderThatCannotBeWrittenSaysNothingAndStartsNoRefresh() {
        fileSystem.write("/blocked".toPath()) { writeUtf8("a file, not a folder") }
        notice(at = "/blocked/update-check.json").after { said += it }
        assertEquals(0, refreshes)
        notice(at = "/blocked/update-check.json").record("2.0.0")
        assertTrue(said.isEmpty())
        // a file that reads but cannot be written: the notice could not be said once a day only, so it is not said
        val readOnly = object : FileStore by FileStoreImpl(fileSystem) {
            override fun read(path: String) = """{"latest":"2.0.0","checkedAt":$time}"""

            override fun write(path: String, text: String) {
                throw FileWriteException(path, IOException("read-only"))
            }
        }
        repeat(2) { UpdateNotice(readOnly, path, "1.0.0", { time }, { advice }) { true }.after { said += it } }
        assertTrue(said.isEmpty(), said.toString())
    }

    @Test
    fun aRefreshThatCannotStartIsTriedAgainOnlyTheNextDay() {
        started = false
        notice().after { said += it }
        notice().after { said += it }
        assertEquals(1, refreshes)
    }

    @Test
    fun theNoticeLivesInTheCliFolderAndItsRefreshIsThisBinaryDetached() {
        assertNull(updateNoticeOf(null, FileStoreImpl(fileSystem), { null }) { true })
        val folder = "/home/maya/.config/ostrom"
        val notice = assertNotNull(updateNoticeOf(folder, FileStoreImpl(fileSystem), { null }) { true })
        notice.record("0.0.1")
        assertTrue(fileSystem.exists(path.toPath()))
        assertEquals(UPDATE_NOTICE_FILE, path.substringAfterLast('/'))
        assertFalse(BuildInfo.VERSION.isEmpty())

        val detached = mutableListOf<List<String>>()
        val capture = object : ProcessCapture {
            override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult = error("not used")

            override fun detach(command: List<String>) = true.also { detached += command }
        }
        assertFalse(detachedRefresh(null, capture)(), "no binary known: nothing to start")
        assertTrue(detachedRefresh("/opt/ostrom", capture)())
        assertEquals(listOf(listOf("/opt/ostrom", "update", "--record-latest")), detached)
    }
}
