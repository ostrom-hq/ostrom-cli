// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject

/**
 * The refusals the hooks make on the workstation without asking Ostrom (a push that redirects git or cannot be read, a
 * landing that cannot be told, a program named by a variable, a change to where pushes go, another repository, a gate
 * policy that cannot be trusted). Each is said as it always was, then told to Ostrom once, for the organisation's
 * audit log (`POST /api/v1/gates/local-denials`, audit A6): within [DENIAL_MILLIS], its answer dropped. Whatever
 * Ostrom answers, or not at all, the reply and its exit code are the same. Without a session yet, nothing is sent:
 * Ostrom files a refusal under a session.
 */
class LocalRefusals(
    private val context: HookContext,
    private val writes: SessionWrites,
    private val ids: SessionIds,
    private val replies: HookReplies,
) {
    /**
     * Refuses, then tells Ostrom.
     *
     * @param event The agent's event, for its session.
     * @param actionClass The class of the refused action, or null when none is known ([UNKNOWN_CLASS] is sent).
     * @param reason The contract's reason (`redirected`, `unreadable`, ...).
     * @param command The command as the hook read it; masked and cut before it leaves the workstation.
     * @param text The refusal the agent is shown.
     * @return The refusal.
     */
    fun deny(event: JsonObject, actionClass: String?, reason: String, command: String, text: String): HookReply {
        val reply = replies.deny(text)
        val session = ids.of(event.string("session_id"))
        if (session.isNotEmpty()) {
            val body = jsonOf(
                "sessionId" to session,
                "class" to (actionClass ?: UNKNOWN_CLASS),
                "reason" to reason,
                "command" to CommandSecrets.masked(command).take(MAX_COMMAND),
                "repo" to context.repository,
            )
            writes.report(Routes.LOCAL_DENIALS, body, DENIAL_MILLIS)
        }
        return reply
    }

    /** The reasons of the contract's `GateLocalDenial`. */
    companion object {
        /** The push redirects git away from the repository the gate covers. */
        const val REDIRECTED = "redirected"

        /** The push cannot be read. */
        const val UNREADABLE = "unreadable"

        /** The branches a push lands on cannot be told. */
        const val UNKNOWN_LANDING = "unknown_landing"

        /** A program named by a variable or a substitution. */
        const val DYNAMIC_COMMAND = "dynamic_command"

        /** A change to a setting that decides where a push goes. */
        const val GIT_SETTING = "git_setting"

        /** The command acts in another repository than the session's. */
        const val FOREIGN_REPOSITORY = "foreign_repository"

        /** The session's gate policy could not be verified. */
        const val POLICY_HELD = "policy_held"

        /** The class sent when no action of the policy is known (a policy that cannot be trusted). */
        const val UNKNOWN_CLASS = "unknown"

        /** The call's limit. */
        const val DENIAL_MILLIS = 1_000

        /** The contract's bound on the command. */
        const val MAX_COMMAND = 4_096
    }
}
