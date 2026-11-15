// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.platform.FileStoreImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom --version` answered before the command tree is built, and only when nothing would follow it. */
class QuickVersionTest : CliKit() {
    private val line = "ostrom ${BuildInfo.VERSION} (kotlin-native, Linux x64)\n"
    private val environment = FakeEnvironment(mapOf("HOME" to "/home/maya"))

    private val noSend: suspend (String, String) -> Unit = { _, _ -> }

    private fun answer(
        vararg args: String,
        key: String = "",
        terminal: Boolean = false,
        printed: StringBuilder = StringBuilder(),
    ) = QuickVersion.answer(
        args.asList(), environment, { QuickVersion.usageState(environment, FileStoreImpl(fileSystem), key, noSend) },
        { terminal }, printed::append,
    )

    @Test
    fun printsTheLineOfTheVersionCommand() {
        val printed = StringBuilder()
        assertTrue(answer("--version", printed = printed))
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals(console.out.toString(), printed.toString())
        assertEquals(line, printed.toString())
    }

    @Test
    fun leavesEveryOtherLineToTheTree() {
        for (args in listOf(
            emptyArray(), arrayOf("version"), arrayOf("--debug", "--version"), arrayOf("--version", "x"),
            arrayOf("doctor", "--version"), arrayOf("--help"),
        )) {
            val printed = StringBuilder()
            assertFalse(answer(*args, printed = printed), args.toList().toString())
            assertTrue(printed.isEmpty())
        }
    }

    @Test
    fun leavesTheLineToTheTreeWhenAStatisticOrANoticeFollows() {
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        val printed = StringBuilder()
        assertFalse(answer("--version", key = "phc", printed = printed), "statistics on: one event follows")
        assertTrue(answer("--version", key = "", printed = printed), "no destination in this build: nothing follows")
        printed.clear()
        assertFalse(answer("--version", terminal = true, printed = printed), "a terminal: the update notice may follow")
        assertTrue(printed.isEmpty())
    }

    @Test
    fun answersOnADeviceWithNoHomeFolder() {
        val printed = StringBuilder()
        val none = FakeEnvironment(emptyMap())
        val usage = { QuickVersion.usageState(none, FileStoreImpl(fileSystem), "phc", noSend) }
        assertTrue(QuickVersion.answer(listOf("--version"), none, usage, { false }, printed::append))
        assertEquals(line, printed.toString())
    }
}
