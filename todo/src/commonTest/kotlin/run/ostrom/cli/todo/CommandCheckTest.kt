// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.TodoCheck
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandCheckTest {
    private fun outcome(answer: CaptureResult, check: TodoCheck.Command): Pair<Boolean?, String> {
        val result = CommandCheck(FakeCapture("" to answer), OperatingSystem.LINUX).run(todo(), check)
        return result.passed to result.observed
    }

    @Test
    fun theStoredCommandRunsInTheShellAndItsExitCodeDecides() {
        val check = TodoCheck.Command("make check")
        assertEquals(true to "command exited 0: all good", outcome(printed("  all good\n"), check))
        assertEquals(true to "command exited 0", outcome(printed(""), check))
        assertEquals(false to "command exited 1, expected 0: boom", outcome(printed("boom", code = 1), check))
        val expected = TodoCheck.Command("grep -q x f", expectExit = 1)
        assertEquals(true to "command exited 1", outcome(printed("", code = 1), expected))
    }

    @Test
    fun itsOutputIsBoundedAndMasked() {
        val output = "x".repeat(400) + " token ghp_abcdefghijklmnopqrstuvwxyz0123 and " + "a".repeat(64)
        val (_, observed) = outcome(printed(output), TodoCheck.Command("env"))
        assertEquals("command exited 0: " + "x".repeat(190) + " token [REDACTED] and [REDACTED]", observed)
    }

    @Test
    fun aCommandThatDoesNotFinishOrStartIsUnknown() {
        val check = TodoCheck.Command("sleep 999", timeoutSeconds = 5)
        assertEquals(null to "unknown: the command did not finish in 5 s", outcome(CaptureResult.TimedOut, check))
        assertEquals(null to "unknown: the shell could not be started", outcome(CaptureResult.NotFound, check))
        assertEquals(null to "unknown: the shell could not be started", outcome(CaptureResult.Failed, check))
    }

    @Test
    fun theShellIsShOrCmdAndTheFolderIsAnArgumentNotScript() {
        val runs = listOf(
            Triple(OperatingSystem.LINUX, null, listOf("sh", "-c", "make")),
            Triple(
                OperatingSystem.LINUX,
                "/work/a b",
                listOf("sh", "-c", "cd -- \"\$1\" && eval \"\$2\"", "sh", "/work/a b", "make"),
            ),
            Triple(OperatingSystem.WINDOWS, null, listOf("cmd.exe", "/c", "make")),
            Triple(OperatingSystem.WINDOWS, "C:\\work", listOf("cmd.exe", "/c", "cd /d \"C:\\work\" && make")),
        )
        for ((system, cwd, expected) in runs) {
            val capture = FakeCapture("" to printed(""))
            CommandCheck(capture, system).run(todo(), TodoCheck.Command("make", cwd = cwd))
            assertEquals(expected, capture.commands.single())
        }
    }

    @Test
    fun theTimeoutIsBetweenOneSecondAndTwoMinutes() {
        val timeouts = listOf(0, 30, 600).map { seconds ->
            val capture = FakeCapture("" to printed(""))
            val check = TodoCheck.Command("true", timeoutSeconds = seconds)
            CommandCheck(capture, OperatingSystem.LINUX).run(todo(), check)
            capture.timeouts.single()
        }
        assertEquals(listOf(1_000, 30_000, 120_000), timeouts)
    }

    @Test
    fun placeholdersAreReplacedFromTheLinksOrLeaveTheCheckUnknown() {
        val shipped = releaseLink("v1.2;rm")
        val list = todo(
            item("fix", links = listOf(prLink(12, sha = "abc123"))),
            item("ship", links = listOf(shipped)),
            item("bare"),
            item("nosha", links = listOf(prLink(13, sha = null))),
        )
        val capture = FakeCapture("" to printed(""))
        val command = "echo \${pr:fix.number} \${pr:fix.mergeSha} \${release:ship.tag}"
        assertEquals(true, CommandCheck(capture, OperatingSystem.LINUX).run(list, TodoCheck.Command(command)).passed)
        assertEquals("echo 12 abc123 v1.2rm", capture.commands.single()[2], "only a value's own characters go in")
        val unknown = listOf(
            "\${pr:gone.number}" to "waiting for the pull request of item gone",
            "\${release:bare.tag}" to "waiting for the release of item bare",
            "\${pr:nosha.mergeSha}" to "item nosha has no mergeSha yet",
            "\${release:ship.number}" to "item ship has no number yet",
        )
        for ((run, said) in unknown) {
            val nothing = FakeCapture()
            val checks = TodoChecks(GitHub(nothing), CommandCheck(nothing, OperatingSystem.LINUX))
            val outcome = checks.run(list, item("c", TodoCheck.Command(run))).single()
            assertEquals(null to "unknown: $said", outcome.passed to outcome.observed)
            assertEquals(emptyList(), nothing.commands, "nothing runs: $run")
        }
    }
}
