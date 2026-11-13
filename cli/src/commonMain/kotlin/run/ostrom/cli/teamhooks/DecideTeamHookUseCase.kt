// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.teamhooks

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.team.EffectiveHook
import run.ostrom.cli.launcher.team.HookApprovals
import run.ostrom.cli.launcher.team.HookDecision
import run.ostrom.cli.launcher.team.TeamHooks

/**
 * `ostrom hooks approve` and `ostrom hooks refuse`: the member's decision on the delivered version of a hook, kept on
 * this computer. An approval takes the version `ostrom hooks show` printed last, or the one `--digest` names, and never
 * one delivered since (TeamCode audit M5); its files are verified first. It records the member's review step: a
 * program running as the member can write the approvals file anyway, so this is no defence against local code.
 */
internal class DecideTeamHookUseCase(private val hooks: TeamHooks, private val approvals: HookApprovals) {
    /** Approves [name]'s delivered version, the one [digest] names or the one shown last. */
    suspend fun approve(name: String, digest: String?): EffectiveHook {
        val hook = hooks.delivered(name)
        val problem = problem(hook, digest, approvals.shown()[hook.name])
        if (problem != null) throw TeamHookStop(problem.first, problem.second)
        verified(hooks, hook)
        approvals.decide(hook.name, HookDecision(hook.digest, approved = true))
        return hook
    }

    /** Refuses [name]'s delivered version: no launch runs it, none asks about it again until a new one. */
    suspend fun refuse(name: String): EffectiveHook {
        val hook = hooks.delivered(name)
        approvals.decide(hook.name, HookDecision(hook.digest, approved = false))
        return hook
    }

    private fun problem(hook: EffectiveHook, requested: String?, read: String?): Pair<ExitCode, String>? {
        val given = requested?.trim()?.lowercase()
        val wanted = given?.let { if (it.startsWith(PREFIX)) it else PREFIX + it }
        val expected = wanted ?: read
        val name = shown(hook.name)
        val delivered = shown(hook.digest)
        val checked = shown(expected.orEmpty())
        return when {
            given != null && !DIGEST.matches(given) -> ExitCode.USAGE to
                "Give --digest as sha256: and 64 hexadecimal digits, as ostrom hooks show prints it."
            expected == null -> ExitCode.USAGE to
                "Read it first with ostrom hooks show $name, or give the digest you checked with --digest."
            expected == hook.digest -> null
            wanted != null -> ExitCode.REFUSED to
                "The server delivers $delivered, not the --digest you gave ($checked). Read it with ostrom hooks " +
                "show $name."
            else -> ExitCode.REFUSED to "The delivered version changed since you read it (you read $checked, the " +
                "server now delivers $delivered). Read it again with ostrom hooks show $name."
        }
    }

    private companion object {
        const val PREFIX = "sha256:"
        val DIGEST = Regex("(sha256:)?[0-9a-f]{64}")
    }
}
