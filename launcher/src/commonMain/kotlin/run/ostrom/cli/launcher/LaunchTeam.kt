// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.team.TEAM_SKILLS
import run.ostrom.cli.launcher.team.TeamSkills
import run.ostrom.cli.launcher.team.skillsFolder

/** What a launch takes of the member's team before the agent starts: its skills. */
internal class LaunchTeam(private val dependencies: LaunchDependencies) {
    /** The team skills synced into the agent's skills folder, when the server serves them ([TeamSkills]). */
    suspend fun syncSkills(agent: Agent, site: LaunchSite, features: Set<String>, say: (String) -> Unit) {
        val folder = skillsFolder(agent, site.home, site.variable)
        if (folder != null && TEAM_SKILLS in features) {
            TeamSkills(dependencies.server, dependencies.skillFiles, dependencies.restrict)
                .sync(folder, site.credentials.token, say)
        }
    }
}
