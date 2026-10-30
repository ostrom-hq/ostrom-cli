// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckProcessesTest {
    private class Capture(vararg results: CaptureResult) : ProcessCapture {
        private val queue = ArrayDeque(results.toList())
        val captured = mutableListOf<Pair<List<String>, Int>>()

        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            captured += command to timeoutMillis
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        override fun detach(command: List<String>): Boolean = error("a check starts nothing in the background")
    }

    private val variables = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/lena", "OSTROM_TOKEN" to "secret")
    private val request = ScriptRequest("s1", "/t/sbx/s1.sh", "/w", "/t/sbx", "/t", network = false, 30_000)

    private fun runner(capture: Capture, sandboxExec: Boolean) =
        SandboxedScripts(capture, variables::get, sandboxExec, "/home/lena", { "/real$it" })

    private val shell = listOf(
        "/bin/sh", "-c", "exec 2>&1 && mkdir -p \"$3\" && cd \"$1\" && exec /bin/sh \"$2\"", "ostrom-check", "/w",
        "/t/sbx/s1.sh", "/t/sbx/tmp",
    )
    private val environment = listOf(
        "/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "HOME=/home/lena", "TMPDIR=/t/sbx/tmp", "CI=1",
        "PYTHONDONTWRITEBYTECODE=1", "OSTROM_CHECK=s1",
    )

    @Test
    fun withoutASandboxTheScriptRunsWithACleanEnvironmentInItsFolder() {
        val capture = Capture(CaptureResult.Exited(3, "boom\n"))
        val run = runner(capture, sandboxExec = false).run(request)
        assertEquals(ScriptRun(3, "boom\n", SandboxedScripts.NO_SANDBOX), run)
        assertEquals(environment + shell to 30_000, capture.captured.single(), "no token reaches the script")
    }

    @Test
    fun onMacosTheScriptRunsInsideSandboxExec() {
        val capture = Capture(CaptureResult.Exited(0, ""))
        val run = runner(capture, sandboxExec = true).run(request)
        assertEquals("macos sandbox-exec (no network, no write outside the repository)", run.sandbox)
        val profile = runner(capture, sandboxExec = true).profile(request)
        val confined = environment + listOf("/usr/bin/sandbox-exec", "-p", profile) + shell
        assertEquals(confined, capture.captured.single().first)
        val hidden = listOf(
            ".ssh", ".config/ostrom", ".config/gh", ".aws", ".netrc", ".docker/config.json", ".npmrc", ".gnupg",
            "Library/Keychains",
        ).joinToString(" ") { "(subpath \"/home/lena/$it\")" }
        assertEquals(
            "(version 1)\n(allow default)\n(deny network*)\n(allow network* (remote unix-socket))\n" +
                "(deny file-write*)\n(allow file-write* (subpath \"/real/w\") (subpath \"/real/t/sbx\") " +
                "(literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\") " +
                "(literal \"/dev/dtracehelper\") (subpath \"/dev/fd\"))\n" +
                "(deny file-read* $hidden (subpath \"/real/t/plugin\") (subpath \"/real/t/codex-home\") " +
                "(subpath \"/real/t/opencode\"))\n",
            profile,
        )
        val networked = request.copy(network = true, directory = "/w\"x")
        val open = runner(Capture(CaptureResult.Exited(0, "")), sandboxExec = true)
        val allowed = "macos sandbox-exec (network allowed, no write outside the repository)"
        assertEquals(allowed, open.run(networked).sandbox)
        assertTrue(open.profile(networked).startsWith("(version 1)\n(allow default)\n(deny file-write*)\n"))
        assertTrue("(subpath \"/real/wx\")" in open.profile(networked), "a quote never ends a path of the profile")
    }

    @Test
    fun aScriptStoppedAtItsLimitOrNotStartedHasNoExitCode() {
        val stopped = runner(Capture(CaptureResult.TimedOut), sandboxExec = false).run(request)
        assertEquals(ScriptRun(null, "", SandboxedScripts.NO_SANDBOX), stopped)
        for (result in listOf(CaptureResult.NotFound, CaptureResult.Failed)) {
            val run = runner(Capture(result), sandboxExec = false).run(request)
            assertEquals(ScriptRun(null, "the script could not be started", SandboxedScripts.NO_SANDBOX, false), run)
        }
    }

    @Test
    fun aCleanTreeHasNothingModifiedStagedOrUntracked() {
        fun clean(result: CaptureResult) = GitCheckouts(Capture(result), "/usr/bin/git").clean("/w")
        assertTrue(clean(CaptureResult.Exited(0, "")))
        assertFalse(clean(CaptureResult.Exited(0, "?? new.txt\n")))
        assertFalse(clean(CaptureResult.Exited(128, "")))
        assertFalse(clean(CaptureResult.TimedOut))
        val capture = Capture(CaptureResult.Exited(0, ""))
        GitCheckouts(capture, "/usr/bin/git").clean("/w")
        val status = listOf("/usr/bin/git", "-C", "/w", "status", "--porcelain", "--untracked-files=all")
        assertEquals(status to 5_000, capture.captured.single())
    }

    @Test
    fun aCommitIsCheckedOutDetachedAndRemovedAfterwards() {
        val capture = Capture(CaptureResult.Exited(0, ""))
        val checkouts = GitCheckouts(capture, "git")
        assertTrue(checkouts.add("/w", "/t/sbx/wt-M", "M"))
        checkouts.remove("/w", "/t/sbx/wt-M")
        assertEquals(
            listOf(
                listOf("git", "-C", "/w", "worktree", "add", "--detach", "--force", "/t/sbx/wt-M", "M") to 60_000,
                listOf("git", "-C", "/w", "worktree", "remove", "--force", "/t/sbx/wt-M") to 60_000,
                listOf("git", "-C", "/w", "worktree", "prune") to 5_000,
            ),
            capture.captured,
        )
        assertFalse(GitCheckouts(Capture(CaptureResult.Exited(128, "")), "git").add("/w", "/p", "M"))
        assertFalse(GitCheckouts(Capture(CaptureResult.NotFound), "git").add("/w", "/p", "M"))
    }
}
