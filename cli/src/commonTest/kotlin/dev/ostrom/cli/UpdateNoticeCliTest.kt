// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.update.UpdateNotice
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The update notice after a command, and `ostrom update --record-latest` that refreshes it. */
class UpdateNoticeCliTest : CliKit() {
    @Test
    fun aPersonAtATerminalIsToldOfANewReleaseOnceADayAfterACommand() {
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write(NOTICE.toPath()) { writeUtf8("""{"latest":"1.2.0","checkedAt":$now}""") }
        val notice = "! ostrom 1.2.0 is available. Run ostrom update.\n"
        assertEquals(0, cli().run(arrayOf("version")))
        assertTrue(console.err.isEmpty(), "not in a pipe")
        console.isInteractive = true
        for (quiet in listOf("CI", "OSTROM_LAUNCH_ID")) {
            assertEquals(0, cli(environment = mapOf("HOME" to "/home/maya", quiet to "1")).run(arrayOf("version")))
        }
        assertEquals(0, cli().run(arrayOf("hook", "gate")), "never after plumbing")
        assertEquals(0, cli().run(arrayOf("--help")), "nor after a help screen")
        console.out.clear()
        assertTrue(console.err.isEmpty(), console.err.toString())
        assertEquals(64, cli().run(arrayOf("cluade")))
        assertTrue(!console.err.contains(notice), "not after a line that names no command")
        console.err.clear()
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals(notice, console.err.toString())
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals(notice, console.err.toString(), "once a day")
        assertEquals(0, refreshes)
    }

    @Test
    fun theDetachedRefreshRecordsTheLatestReleaseAndSaysNothing() {
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        console.out.clear()
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("update", "--record-latest")))
        assertTrue(console.out.isEmpty() && console.err.isEmpty())
        val retry = now - UpdateNotice.DAY_MILLIS + UpdateNotice.RETRY_MILLIS
        val state = fileSystem.read(NOTICE.toPath()) { readUtf8() }
        assertTrue(state.contains("\"checkedAt\":$retry"), "no release found: tried again in an hour, $state")
        assertEquals(1, releaseUrls.size, "the manifest was asked for")
        assertTrue(statistics.isEmpty(), "plumbing nobody typed sends no statistic")
        cli(postHogKey = "phc").run(arrayOf("update", "--check"))
        assertEquals(1, statistics.size, "an update typed is counted")
        for (other in listOf("--rollback", "--check", "--version=1.2.0")) {
            assertEquals(64, cli().run(arrayOf("update", "--record-latest", other)), other)
        }
    }

    @Test
    fun aNoticeThatFailsNeverChangesTheCommandsCode() {
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write(NOTICE.toPath()) { writeUtf8("""{"latest":"1.2.0","checkedAt":$now}""") }
        console.isInteractive = true
        noticeAdvice = { throw IllegalArgumentException("a path the platform refuses") }
        assertEquals(0, cli().run(arrayOf("version")))
        noticeAdvice = { throw IOException("the session probe broke") }
        assertEquals(0, cli().run(arrayOf("version")))
        assertTrue(console.err.isEmpty(), console.err.toString())
        noticeAdvice = { null }
        assertEquals(0, cli().run(arrayOf("version")))
        assertTrue(console.err.isEmpty(), "a session runs: the notice waits")
        noticeAdvice = { "Homebrew: run brew upgrade ostrom." }
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals("! ostrom 1.2.0 is available. Homebrew: run brew upgrade ostrom.\n", console.err.toString())
    }

    @Test
    fun withoutACliFolderThereIsNoNoticeToSayOrRecord() {
        noticeKept = false
        console.isInteractive = true
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals(0, cli().run(arrayOf("update", "--record-latest")))
        assertTrue(console.err.isEmpty(), console.err.toString())
        assertTrue(!fileSystem.exists(NOTICE.toPath()))
    }
}
