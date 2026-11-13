// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

/** What the agent reads when a check of the workflow package refuses an action (prototype `preTool`). */
object CheckTexts {
    /** The lines of a script's output quoted in a refusal. */
    const val REFUSAL_LINES = 25

    /** The lines of a script's output sent with its run report. */
    const val REPORT_LINES = 60

    private const val SHORT_SHA = 12

    /** The hook view of the package cannot be read: nothing it guards can be checked. */
    fun unreadableView(path: String) =
        "Ostrom: the session's workflow package cannot be read ($path), so its checks cannot run and this " +
            "action was not run. Restart the session (`ostrom run <agent>`)."

    /** An enforced check whose script is not in the package: what it guards cannot be checked. */
    fun missingScript(hook: CompiledHook) =
        "Ostrom: check ${hook.id} names a script (${hook.scriptId ?: "none"}) the session's workflow package does " +
            "not hold, so it cannot run and this action was not run. Restart the session (`ostrom run <agent>`)."

    /** A push Ostrom cannot tie to the commits it sends. */
    fun unreadablePush(label: String, reason: String) =
        "Ostrom: check \"$label\" not run, so the action was not run: $reason"

    /** A push of HEAD while the working tree holds changes that would not leave with it. */
    fun uncommitted(label: String, sha: String) =
        "Ostrom: check \"$label\" not run, so the push was not run: the working tree has uncommitted changes, and " +
            "the push sends commit ${sha.take(SHORT_SHA)} without them. Commit what must leave (or stash it), then " +
            "run the same push again. The check tests the commit the push sends."

    /** The commit a push sends could not be checked out to be tested. */
    fun noCheckout(label: String, why: String) =
        "Ostrom: check \"$label\" could not run ($why); the action was not run. Tell the user."

    /** The script could not run (changed since the package was verified, missing, not started). */
    fun couldNotRun(label: String, why: String) =
        "Ostrom: check \"$label\" could not run ($why), so the action was not run."

    /**
     * The script failed or timed out.
     *
     * @param exit Its exit code, or null when it timed out.
     * @param sha The commit it tested, or null for the working tree.
     */
    fun failed(script: CompiledScript, exit: Int?, sha: String?, output: String): String {
        val how = if (exit == null) "timed out after ${script.timeoutSec} s" else "failed (exit $exit)"
        val on = sha?.let { " on commit ${it.take(SHORT_SHA)}" }.orEmpty()
        return "Ostrom: check \"${script.label}\" $how$on before this action, so the action was not run. " +
            "Command: ${script.command}\n${tail(output, REFUSAL_LINES)}\n" +
            "Fix the cause in the code (never skip or weaken the check), commit, then run the same action again."
    }

    /** The last [lines] lines of [text], its trailing blank end left out. */
    fun tail(text: String, lines: Int): String = text.trimEnd().lines().takeLast(lines).joinToString("\n")
}
