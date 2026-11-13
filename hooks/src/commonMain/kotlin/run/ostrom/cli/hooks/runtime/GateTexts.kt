// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.gate.Gate
import run.ostrom.cli.hooks.gate.RealRepository

/** What the gate tells the agent, word for word the prototype's (`ostrom-gate.mjs`, `hook_gate`). */
@Suppress("TooManyFunctions") // One text per outcome of a gate, kept together so that they read alike.
object GateTexts {
    private val Gate.name: String get() = label ?: "gate"

    /** An event the gate could not read (too large, late or not JSON): refused, never let through (S2-30). */
    const val UNREAD_EVENT =
        "Ostrom: denied. The gate could not read this tool call (too large, too slow or not JSON), so it was not " +
            "run. Split the command or the file, then try again."

    /**
     * The gate failed on this tool call (a case it never met): refused, never let through. Its causes mostly last (a
     * file it cannot read, a broken setup), and an unreadable event may come after the tool ran: never "try again".
     * `ostrom doctor` checks nothing of the gate's: the user is sent to a new session.
     */
    const val GATE_FAILED =
        "Ostrom: denied. The gate failed on this tool call. Do not run it again nor work around it: tell the " +
            "user, and wait for their answer. A new session (`ostrom run <agent>`) starts the gate again."

    /**
     * No gate policy to trust (unsigned, expired, the relay or Ostrom unreachable, the package changed): every action
     * is held (S2-20, S2-38).
     */
    fun held(reason: String): String =
        "Ostrom: denied. $reason, so every action of this session is held and this one was not run. Do not work " +
            "around it; tell the user. A new session (`ostrom run <agent>`) reads the policy again."

    /** A guarded action run in another repository than the session's. */
    fun foreign(context: HookContext, mine: RealRepository, real: RealRepository): String {
        val launchTop = context.launchTop
        val session = context.repository
        val mineLabel = if (mine.origin.isNotEmpty() && mine.origin != session) {
            "$session (${mine.origin}, $launchTop)"
        } else {
            "$session ($launchTop)"
        }
        return "Ostrom: denied. This session was opened for the repository $mineLabel, but this command runs in the " +
            "repository ${real.label} (${real.top}). The session's checks and gates do not cover ${real.label}, so " +
            "the action was not run. Work only in $launchTop; do not run it elsewhere."
    }

    /** A push whose landing is unknown while a gate on main is enforced (audit A9). */
    fun unknownLanding(action: String, gate: Gate, reason: String): String =
        "Ostrom: denied. Ostrom cannot tell which branches this push lands on ($reason), and " +
            "\"$action\" needs a yes from ${gate.approversPhrase} (gate \"${gate.name}\"). The action was not run. " +
            "Run `git push <remote> <branch>` as its own command."

    /** A guarded push that sends git elsewhere (audit A6). */
    fun redirected(action: String, gate: Gate, how: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (gate \"${gate.name}\"), and this " +
            "command sends git elsewhere ($how). Run a plain `git push <remote> <branch>` as its own command."

    /** An enforced push whose commits Ostrom cannot read. */
    fun unreadable(action: String, gate: Gate, reason: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (gate \"${gate.name}\"), and $reason"

    /** A command whose program is a shell variable or a substitution, while a gate is enforced (S2-26). */
    fun dynamicCommand(action: String, gate: Gate, word: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (gate \"${gate.name}\"), and Ostrom " +
            "cannot read which program `$word` runs. Write the command out in full, as its own command."

    /** A change to git's remotes, aliases or push settings while a gate on pushes is enforced (S2-26, S2-27). */
    fun gitSetting(action: String, gate: Gate, setting: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (gate \"${gate.name}\"), and this " +
            "command changes $setting, which decides where a push goes. Ask the user to change it themselves."

    /** The session is closed and could not be reopened. */
    fun closed(action: String, message: String): String =
        "Ostrom: denied. \"$action\" needs a yes, and this session is closed on Ostrom's side and could not " +
            "be reopened ($message). The action was not run. Tell the user; a new session (`ostrom run <agent>`) " +
            "asks again."

    /** Ostrom is unreachable and the organisation fails open. */
    fun letThrough(action: String, message: String): String =
        "Ostrom unreachable ($message): \"$action\" let through (the organisation's fail mode is open)."

    /** Ostrom answered the request with a refusal (a 4xx), [message] its reason. */
    fun refused(action: String, gate: Gate, message: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (${gate.name}), and Ostrom refused " +
            "the request: ${sentence(message)} The action was not run. Do not work around this gate; tell the user."

    /** [text] ended as a sentence. */
    private fun sentence(text: String): String = text.trimEnd().let { if (ENDED.containsMatchIn(it)) it else "$it." }

    private val ENDED = Regex("[.!?]$")

    /** Ostrom is unreachable or failed (a 5xx) and the organisation fails closed. */
    fun unreachable(action: String, gate: Gate, message: String): String =
        "Ostrom: denied. \"$action\" needs a yes from ${gate.approversPhrase} (${gate.name}), and Ostrom is " +
            "unreachable ($message). The action was not run. Do not work around this gate; tell the user."

    /** Someone said yes. */
    fun approved(answer: GateAnswer): String =
        "Ostrom: ${answer.by} said yes to \"${answer.label}\" (gate \"${answer.gateLabel}\", receipt " +
            "${answer.receiptId ?: "pending"})."

    /** The gate is only proposed. */
    fun shadow(answer: GateAnswer): String =
        "Ostrom: \"${answer.label}\" falls under the proposed gate \"${answer.gateLabel}\" (${answer.who}). Not " +
            "decided yet, so it is only logged."

    /** Someone said no. */
    fun rejected(answer: GateAnswer): String =
        "Ostrom: ${answer.by} said no to \"${answer.label}\" (gate \"${answer.gateLabel}\", receipt " +
            "${answer.receiptId ?: "-"}). The action was not run. Do not retry it or work around it; report the " +
            "refusal to the user."

    /**
     * No answer yet. The instruction comes first: Grok shows the model only the first ~200 characters of a refusal
     * (measured 28/10).
     */
    fun waiting(answer: GateAnswer): String =
        "Ostrom: waiting for a yes. Run `ostrom wait ${answer.id}` as its own command, then the exact same command " +
            "again once it says approved. Not run yet: \"${answer.label}\" needs a yes from ${answer.who} (gate " +
            "\"${answer.gateLabel}\"); the request is in the Ostrom console. Do not work around it."

    /**
     * The gate crashed on this tool call (a stack overflow, an abort): refused, never let through (M1). It says
     * nothing of whether the tool ran (after a tool call, PostToolUse, it already had), so it never asks for a retry:
     * that would repeat a deployment that already went through.
     */
    const val GATE_CRASHED =
        "Ostrom: the gate stopped on this tool call. Before the tool, that refuses it; after it, the tool had " +
            "already run. Do not run it again on your own: tell the user, who can run `ostrom doctor`."
}
