// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

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
        for (quiet in listOf("CI", "OSTROM_LAUNCH_ID", "OSTROM_NO_UPDATE_NOTICE")) {
            assertEquals(0, cli(environment = mapOf("HOME" to "/home/maya", quiet to "1")).run(arrayOf("version")))
        }
        assertEquals(0, cli().run(arrayOf("hook", "gate")), "never after plumbing")
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
        assertEquals(0, cli().run(arrayOf("update", "--record-latest")))
        assertTrue(console.out.isEmpty() && console.err.isEmpty())
        assertTrue(fileSystem.read(NOTICE.toPath()) { readUtf8() }.contains("\"checkedAt\":$now"))
        assertEquals(1, releaseUrls.size, "the manifest was asked for")
    }
}
