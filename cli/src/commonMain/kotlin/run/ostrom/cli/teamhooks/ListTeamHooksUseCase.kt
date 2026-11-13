// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.teamhooks

import run.ostrom.cli.launcher.team.EffectiveHook
import run.ostrom.cli.launcher.team.HookApprovals
import run.ostrom.cli.launcher.team.HookState
import run.ostrom.cli.launcher.team.TeamHooks

/** The team hooks delivered to the member, each with where it stands, and how many this ostrom cannot install. */
internal class TeamHookListing(val hooks: List<Pair<EffectiveHook, HookState>>, val skipped: Int)

/** `ostrom hooks list`: the delivered hooks and the member's decision on each version. */
internal class ListTeamHooksUseCase(private val hooks: TeamHooks, private val approvals: HookApprovals) {
    suspend operator fun invoke(): TeamHookListing {
        val delivered = hooks.delivered()
        val decisions = approvals.decisions()
        return TeamHookListing(delivered.hooks.map { it to approvals.stateOf(it, decisions) }, delivered.skipped)
    }
}
