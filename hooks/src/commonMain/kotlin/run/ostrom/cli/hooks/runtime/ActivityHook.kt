// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply

/**
 * `ostrom hook activity`: Codex's `Stop` and `UserPromptSubmit` in a local session (TeamCode's `teamcode agents hook`
 * with `TEAMCODE_ACTIVITY=1`). It tells the launch's relay the event's name, and nothing else of the payload: the relay
 * answers at once and reports whether the agent works or waits for a person in the background. It prints nothing and
 * never stops the agent, whatever the relay answers.
 *
 * @param calls The relay, with the hooks' capability.
 */
class ActivityHook(private val calls: OstromCalls) {
    /** Tells the relay about the event named [name] (normalised to Claude Code's names). */
    fun handle(name: String): HookReply {
        if (name.isNotEmpty()) calls.call(PATH, jsonOf("hook_event_name" to name), TIMEOUT_MILLIS)
        return HookReply()
    }

    private companion object {
        const val PATH = "/activity"

        /** TeamCode's: the hook waits at most 1.5 s for its report. */
        const val TIMEOUT_MILLIS = 1_500
    }
}
