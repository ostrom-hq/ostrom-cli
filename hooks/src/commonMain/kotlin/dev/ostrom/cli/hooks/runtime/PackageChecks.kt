// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.CommandLocation
import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.hooks.gate.PatternCache
import dev.ostrom.cli.hooks.gate.Push
import dev.ostrom.cli.hooks.gate.PushReader
import dev.ostrom.cli.hooks.gate.ToolKind
import dev.ostrom.cli.hooks.gate.ToolSubject
import dev.ostrom.cli.hooks.gate.UnreadablePushException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

/**
 * What the checks of a package reach outside themselves.
 *
 * @property files The package's files and the sandbox's copy of a script.
 * @property calls Ostrom, through the launcher's relay: the run reports and the notifications.
 * @property git The real `git`, for the repository, HEAD and the commits a push sends.
 * @property checkouts The git operations whose exit status matters (clean tree, detached checkouts).
 * @property scripts Runs a check script.
 * @property now The current time in milliseconds since the epoch, for a run's duration.
 * @property runner The cloud session's runner (`OSTROM_RUNNER_CHECKS`), or null on a workstation. When there is one,
 *     every check is asked of it and never run here, and the hook reports none of the runs: the runner reports them,
 *     attested `isolated_runner`, and a later report of the hook's would replace that proof in the receipt. A check
 *     the hook does not ask (script missing or changed here, uncommitted changes, no checkout) is refused and still
 *     reported here as not run: a changed script is run nowhere.
 */
data class CheckPorts(
    val files: HookFiles,
    val calls: OstromCalls,
    val git: GitRunner,
    val checkouts: CommitCheckouts,
    val scripts: ScriptRunner,
    val now: () -> Long,
    val runner: RunnerChecks? = null,
)

/**
 * The checks and notifications of the session's workflow package (prototype `package.mjs`, with the push rule of
 * CORRECTIFS-3 and the session reopening of CORRECTIFS-4).
 *
 * Before an action, each check that guards it runs in the package's order. A script whose SHA-256 no longer matches
 * the package is not run and the action is refused. An enforced check that guards a push tests each commit the push
 * sends, never the working tree: in place when it is HEAD and the tree is clean, refused when HEAD has uncommitted
 * changes, otherwise in a detached worktree under `<tmp>/sbx`, removed afterwards. A push it cannot tie to commits is
 * refused. A check in shadow is only reported; every other mode blocks, and an enforced check whose script is not in
 * the package refuses the action. A failure or a timeout refuses the action. A package changed since the launch
 * refuses every action (S2-33).
 *
 * A pass is kept per script and commit in this object's memory only, never on disk: every file the hook could keep
 * it in (`<tmp>`, the relay's token file the key of an HMAC would come from) is readable and writable by the agent's
 * own user, so a pass read back from one could be forged to skip an enforced check (S2-20). The hook process lives for
 * one action, so a check runs again at each push: the price of a pass nobody can forge.
 *
 * After an action, Ostrom sends the package's notifications (never the machine). Reports and notifications are best
 * effort: a lost one never holds the agent.
 *
 * @param viewPath The hook view of the package (`OSTROM_PACKAGE`).
 * @param session The session the hooks run in.
 */
class PackageChecks(
    private val viewPath: String,
    private val session: HookContext,
    private val ports: CheckPorts,
) : WorkflowChecks {
    private val seal = PackageSeal(session.packageSeal, session.packageManifest, ports.files)
    private val replies = HookReplies(session.dialect)
    private val ids = SessionIds(session.sessionFile, ports.files)
    private val writes = SessionWrites(ports.calls, ids, session.launchId)
    private val location = CommandLocation(ports.git, session.home)
    private val pushes = PushReader(ports.git, session.home)
    private val patterns = PatternCache()
    private val passes = mutableSetOf<String>()
    private val view: PackageView? by lazy { ports.files.read(viewPath)?.let(PackageView::parse) }

    override val digest: String? get() = view?.digest

    override val context: String? get() = view?.context

    override fun guards(subject: ToolSubject): Boolean = view?.hooks.orEmpty().any {
        it.kind == CompiledHook.CHECK && it.event == CompiledHook.BEFORE && it.enforced &&
            it.matches(subject, patterns)
    }

    override fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String): HookReply? {
        val view = this.view
        val hooks = view?.hooks.orEmpty().filter {
            it.kind == CompiledHook.CHECK && it.event == CompiledHook.BEFORE && it.matches(subject, patterns)
        }.sortedBy { it.order }
        // the gate checked the seal a moment ago: it is read again only when the package is about to be trusted
        val refusal = when {
            view == null -> seal.broken() ?: CheckTexts.unreadableView(viewPath)
            hooks.isEmpty() -> null
            else -> seal.broken() ?: CheckPass(view, event, subject, cwd).run(hooks)
        }
        return refusal?.let(replies::deny)
    }

    override fun afterTool(event: JsonObject, subject: ToolSubject) {
        val view = this.view
        val hooks = view?.hooks.orEmpty().filter {
            it.kind == CompiledHook.NOTIFY && it.event == CompiledHook.AFTER && it.matches(subject, patterns)
        }.sortedBy { it.order }
        // a package changed since the launch is not trusted with what Ostrom sends in the session's name
        if (view == null || hooks.isEmpty() || seal.broken() != null) return
        val agentSession = event.string("session_id")
        for (hook in hooks) {
            writes.send(ids.of(agentSession), agentSession, REPORT_MILLIS) { id ->
                Routes.NOTIFICATIONS to jsonOf(
                    "sessionId" to id,
                    "digest" to view.digest,
                    "nodeId" to hook.nodeId,
                    "notifyId" to hook.notifyId,
                    "command" to subject.text,
                )
            }
        }
    }

    /** What a check run tells Ostrom (the contract's `ReportCheckRequest`, its session and script aside). */
    private class Report(
        val exit: Int?,
        val outcome: String,
        val output: String,
        val sandbox: String,
        val head: String = "",
        val durationMs: Long = 0,
    ) {
        /** A pass taken from an earlier run on the same commit. */
        val cached: Boolean get() = sandbox == CACHED
    }

    /** A push read from a command, or the refusal it leads to. */
    private class PushRead(val push: Push?, val refusal: String?)

    /** The checks of one action, run in [repository]. */
    private inner class CheckPass(
        private val view: PackageView,
        private val event: JsonObject,
        private val subject: ToolSubject,
        cwd: String,
    ) {
        private val directory = location.directory(cwd, subject.text)
        private val repository = ports.git.run(directory, listOf("rev-parse", "--show-toplevel")).ifEmpty { cwd }
        private val sandbox = "${view.tmp}/sbx"

        /** @return The refusal, or null when every check passed. */
        fun run(hooks: List<CompiledHook>): String? {
            val first = hooks.firstOrNull { it.enforced }
            val read = if (first != null && subject.kind == ToolKind.BASH) readPush(first) else PushRead(null, null)
            return read.refusal ?: hooks.firstNotNullOfOrNull { hook -> runHook(hook, read.push) }
        }

        private fun readPush(first: CompiledHook): PushRead = try {
            PushRead(pushes.read(directory, subject.text), null)
        } catch (e: UnreadablePushException) {
            PushRead(null, CheckTexts.unreadablePush(labelOf(view.script(first.scriptId)), e.reason))
        }

        private fun runHook(hook: CompiledHook, push: Push?): String? {
            val script = view.script(hook.scriptId)
            return when {
                script == null -> if (hook.enforced) CheckTexts.missingScript(hook) else null
                !hook.enforced -> {
                    report(hook, script, script.sha256, Report(null, SKIPPED, "", "not run (shadow)"))
                    null
                }
                // what this check tests: each commit the push sends, else the working tree as it is
                else -> (if (push == null) listOf(null) else push.shas).firstNotNullOfOrNull { runOn(hook, script, it) }
            }
        }

        private fun runOn(hook: CompiledHook, script: CompiledScript, sha: String?): String? {
            val here = ports.git.run(repository, listOf("rev-parse", "HEAD"))
            val key = sha?.let { "${script.id}:commit:$it" }
            val head = sha ?: here
            return when {
                key != null && key in passes -> {
                    // the runner's report of that pass stays the last run of the check
                    if (ports.runner == null) {
                        val passed = Report(0, PASSED, "passed earlier on the same commit", CACHED, head)
                        report(hook, script, script.sha256, passed)
                    }
                    null
                }
                sha == null -> verified(hook, script, repository, head, null)
                here != sha -> inCheckout(hook, script, sha)
                ports.checkouts.clean(repository) -> verified(hook, script, repository, head, key)
                else -> {
                    val notRun = Report(null, FAILED, "not run: uncommitted changes", NOT_RUN, head)
                    report(hook, script, script.sha256, notRun)
                    CheckTexts.uncommitted(script.label, sha)
                }
            }
        }

        /** Another commit than HEAD: tested in a detached worktree of its own, removed afterwards whatever happens. */
        private fun inCheckout(hook: CompiledHook, script: CompiledScript, sha: String): String? {
            val path = "$sandbox/wt-${sha.take(SHORT_SHA)}"
            ports.checkouts.remove(repository, path)
            return if (ports.checkouts.add(repository, path, sha)) {
                try {
                    verified(hook, script, path, sha, "${script.id}:commit:$sha")
                } finally {
                    ports.checkouts.remove(repository, path)
                }
            } else {
                val why = "cannot check out $sha to test it"
                report(hook, script, script.sha256, Report(null, FAILED, why, NOT_RUN, sha))
                CheckTexts.noCheckout(script.label, why)
            }
        }

        /** The script, checked against the package's SHA-256 before it runs: a changed script is never run. */
        private fun verified(
            hook: CompiledHook,
            script: CompiledScript,
            at: String,
            head: String,
            key: String?,
        ): String? {
            val text = ports.files.read("${view.tmp}/${script.path}")
            val sha = text?.let { it.encodeUtf8().sha256().hex() }.orEmpty()
            val runner = ports.runner
            // a script changed here is sent nowhere: refused and reported below, as on a workstation
            if (runner != null && text != null && sha == script.sha256) {
                val sessionId = ids.of(event.string("session_id"))
                val check = RunnerCheck(
                    sessionId, view.digest, hook.nodeId, script.id, text, at, head, timeoutOf(script),
                )
                return onRunner(runner.run(check), script, head, key)
            }
            val copy = "$sandbox/${script.id}.sh"
            val why = when {
                text == null -> "script ${script.id} is missing"
                sha != script.sha256 -> "script ${script.id} was changed on this machine after the package was " +
                    "verified: not run"
                // the copy runs, not the file the agent can rewrite between the check of its digest and its run
                !ports.files.write(copy, text) -> "script ${script.id} could not be copied into the sandbox"
                else -> null
            }
            return if (why == null) {
                execute(hook, script, ScriptTarget(copy, at, head, key))
            } else {
                report(hook, script, sha, Report(null, FAILED, why, NOT_RUN, head))
                CheckTexts.couldNotRun(script.label, why)
            }
        }

        private fun execute(hook: CompiledHook, script: CompiledScript, target: ScriptTarget): String? {
            val started = ports.now()
            val request = ScriptRequest(
                script.id, target.copy, target.directory, sandbox, view.tmp, script.network, timeoutOf(script),
            )
            val run = ports.scripts.run(request)
            val outcome = when {
                run.exit == 0 -> PASSED
                run.exit == null && run.started -> TIMEOUT
                else -> FAILED
            }
            val tail = CheckTexts.tail(run.output, CheckTexts.REPORT_LINES)
            val duration = ports.now() - started
            report(hook, script, script.sha256, Report(run.exit, outcome, tail, run.sandbox, target.head, duration))
            return when {
                !run.started -> CheckTexts.couldNotRun(script.label, run.output)
                outcome == PASSED -> {
                    target.key?.let(passes::add)
                    null
                }
                else -> CheckTexts.failed(script, run.exit, target.key?.let { target.head }, run.output)
            }
        }

        /** What the runner made of a check: a pass, or the refusal. */
        private fun onRunner(run: ScriptRun, script: CompiledScript, head: String, key: String?) =
            when {
                !run.started -> CheckTexts.couldNotRun(script.label, run.output)
                run.exit == 0 -> {
                    key?.let(passes::add)
                    null
                }
                else -> CheckTexts.failed(script, run.exit, key?.let { head }, run.output)
            }

        private fun report(hook: CompiledHook, script: CompiledScript, scriptSha: String, report: Report) {
            val agentSession = event.string("session_id")
            val body = mapOf(
                "digest" to JsonPrimitive(view.digest),
                "nodeId" to JsonPrimitive(hook.nodeId),
                "scriptId" to JsonPrimitive(script.id),
                "scriptSha" to JsonPrimitive(scriptSha),
                "exit" to (if (report.exit == null) JsonNull else JsonPrimitive(report.exit)),
                "outcome" to JsonPrimitive(report.outcome),
                "durationMs" to JsonPrimitive(report.durationMs),
                "head" to JsonPrimitive(report.head),
                "cached" to JsonPrimitive(report.cached),
                "sandbox" to JsonPrimitive(report.sandbox),
                "outputTail" to JsonPrimitive(report.output),
            )
            // best effort: a report Ostrom does not get never holds the agent
            writes.send(ids.of(agentSession), agentSession, REPORT_MILLIS) { id ->
                Routes.CHECKS to JsonObject(mapOf("sessionId" to JsonPrimitive(id)) + body)
            }
        }
    }

    /**
     * Where a verified script runs.
     *
     * @property copy The sandbox's copy of the script.
     * @property directory The repository or the checkout of the commit.
     * @property head The commit it tests.
     * @property key The cache key of a pass, or null when it tests the working tree.
     */
    private class ScriptTarget(val copy: String, val directory: String, val head: String, val key: String?)

    private companion object {
        /** The label of a check whose script is not in the package. */
        fun labelOf(script: CompiledScript?): String = if (script == null) "check" else script.label

        /** The script's time limit. */
        fun timeoutOf(script: CompiledScript): Int = script.timeoutSec * MILLIS_PER_SECOND

        const val REPORT_MILLIS = 3_000
        const val MILLIS_PER_SECOND = 1_000
        const val SHORT_SHA = 12
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val TIMEOUT = "timeout"
        const val SKIPPED = "skipped"
        const val NOT_RUN = "not run"
        const val CACHED = "cached"
    }
}
