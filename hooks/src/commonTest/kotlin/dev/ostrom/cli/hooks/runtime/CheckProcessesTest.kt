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

    private fun runner(capture: Capture, sandboxExec: Boolean, budget: HookBudget = HookBudget.NONE) =
        SandboxedScripts(capture, variables::get, sandboxExec, "/home/lena", { "/real$it" }, budget, null)

    /** What every git command of a check is given before its own arguments. */
    private val safe = listOf(
        "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "filter.lfs.smudge=",
        "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=", "-c", "filter.lfs.required=false",
    )

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
        ).joinToString(" ") { "(subpath \"/real/home/lena/$it\")" }
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
        assertTrue("(subpath \"/real/w\\\"x\")" in open.profile(networked), "a quote never ends a path of the profile")
        val linked = SandboxedScripts(
            Capture(), variables::get, true, "/home/lena", { it.replace("/home", "/Users") }, configDirectory = null,
        )
        assertTrue(
            "(subpath \"/Users/lena/.ssh\")" in linked.profile(request),
            "a credential folder reached through a link is hidden at its real path",
        )
    }

    @Test
    fun theCliFolderIsHiddenWhereverOstromHomeOrXdgConfigHomePutsIt() {
        // security audit L3: credentials.json and admin.json follow OSTROM_HOME or XDG_CONFIG_HOME, so does the sandbox
        val moved = variables + ("XDG_CONFIG_HOME" to "/srv/xdg")
        fun sandbox(variable: (String) -> String?, folder: String) =
            SandboxedScripts(Capture(), variable, true, "/home/lena", { "/real$it" }, configDirectory = folder)
        val profile = sandbox(moved::get, "/srv/ostrom").profile(request)
        assertTrue("(subpath \"/real/srv/ostrom\")" in profile, "the CLI's folder, where OSTROM_HOME puts it")
        assertTrue("(subpath \"/real/srv/xdg/gh\")" in profile, "GitHub's CLI's, where XDG_CONFIG_HOME puts it")
        assertTrue("(subpath \"/real/home/lena/.config/ostrom\")" in profile, "the default folder stays hidden")
        val gh = sandbox((moved + ("GH_CONFIG_DIR" to "/srv/gh"))::get, "/srv/ostrom").profile(request)
        assertTrue("(subpath \"/real/srv/gh\")" in gh, "GH_CONFIG_DIR comes before XDG_CONFIG_HOME, as for gh")
        assertFalse("/real/srv/xdg/gh" in gh)
        // a quote or a backslash is escaped: stripped, the path would name another folder and leave this one readable
        val odd = sandbox(variables::get, "/srv/o\"p\\s").profile(request)
        assertTrue("(subpath \"/real/srv/o\\\"p\\\\s\")" in odd, odd)
        val usual = sandbox(variables::get, "/home/lena/.config/ostrom").profile(request)
        assertEquals(runner(Capture(), sandboxExec = true).profile(request), usual, "the usual folder, named once")
    }

    @Test
    fun aScriptIsCutToTheHookBudgetAndNotStartedOnceItIsSpent() {
        var now = 0L
        val budget = HookBudget(11_000) { now }
        val capture = Capture(CaptureResult.TimedOut)
        assertEquals(ScriptRun(null, "", SandboxedScripts.NO_SANDBOX), runner(capture, false, budget).run(request))
        assertEquals(10_000, capture.captured.single().second, "the script ends a second before the agent's limit")
        now = 10_000
        val spent = runner(capture, false, budget).run(request)
        val why = "the agent's time limit for a hook is reached, so the script was not started"
        assertEquals(ScriptRun(null, why, SandboxedScripts.NO_SANDBOX, started = false), spent)
        assertEquals(1, capture.captured.size, "nothing is started once the budget is spent")
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
        val status = listOf("/usr/bin/git") + safe +
            listOf("-C", "/w", "status", "--porcelain", "--untracked-files=all")
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
                git("worktree", "add", "--detach", "--force", "/t/sbx/wt-M", "M") to 60_000,
                git("worktree", "remove", "--force", "/t/sbx/wt-M") to 60_000,
                git("worktree", "prune") to 5_000,
            ),
            capture.captured,
        )
        assertFalse(GitCheckouts(Capture(CaptureResult.Exited(128, "")), "git").add("/w", "/p", "M"))
        assertFalse(GitCheckouts(Capture(CaptureResult.NotFound), "git").add("/w", "/p", "M"))
    }

    private fun git(vararg arguments: String) = listOf("git") + safe + listOf("-C", "/w") + arguments

    @Test
    fun gitIsCutToTheHookBudgetAndNotRunOnceItIsSpent() {
        var now = 0L
        val capture = Capture(CaptureResult.Exited(0, ""))
        val checkouts = GitCheckouts(capture, "git", HookBudget(4_000) { now })
        assertTrue(checkouts.add("/w", "/p", "M"))
        assertEquals(3_000, capture.captured.single().second, "the checkout ends a second before the agent's limit")
        now = 3_000
        assertFalse(checkouts.clean("/w"), "no time to read the tree: it is not taken as clean")
        assertFalse(checkouts.add("/w", "/p", "M"))
        checkouts.remove("/w", "/p")
        assertEquals(1, capture.captured.size, "nothing is run once the budget is spent")
    }
}
