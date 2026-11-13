// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.team.EffectiveHook
import run.ostrom.cli.launcher.team.HookApprovals
import run.ostrom.cli.launcher.team.HookDefinition
import run.ostrom.cli.launcher.team.HookFiles
import run.ostrom.cli.launcher.team.HookInstaller
import run.ostrom.cli.launcher.team.HookManifest
import run.ostrom.cli.launcher.team.HookState
import run.ostrom.cli.launcher.team.TEAM_HOOKS
import run.ostrom.cli.launcher.team.TEAM_SKILLS
import run.ostrom.cli.launcher.team.TeamHook
import run.ostrom.cli.launcher.team.TeamHooks
import run.ostrom.cli.launcher.team.TeamSkills
import run.ostrom.cli.launcher.team.skillsFolder
import okio.IOException

/** The events of each agent a team hook may run on: Codex has no `Notification`. */
private val EVENTS = mapOf(
    Agent.CLAUDE to HookDefinition.EVENTS.toSet(),
    Agent.CODEX to HookDefinition.EVENTS.toSet() - "Notification",
)

/** What a launch takes of the member's team before the agent starts: its skills, and the hooks the member approved. */
internal class LaunchTeam(private val dependencies: LaunchDependencies) {
    /** The team skills synced into the agent's skills folder, when the server serves them ([TeamSkills]). */
    suspend fun syncSkills(agent: Agent, site: LaunchSite, features: Set<String>, say: (String) -> Unit) {
        val folder = skillsFolder(agent, site.home, site.variable)
        if (folder != null && TEAM_SKILLS in features) {
            TeamSkills(dependencies.server, dependencies.skillFiles, dependencies.restrict)
                .sync(folder, site.credentials.token, say)
        }
    }

    /**
     * The team hooks this session runs (TeamCode TC-HOK-8): for Claude Code and Codex, when the server serves them and
     * the launch did not say `--no-team-hooks`, the delivered versions the member approved on this computer
     * (`ostrom hooks approve`), each verified and installed ([HookInstaller]), downloaded only when missing. Versions
     * awaiting a decision are named; refused ones are left out. Never stops a launch: a hook that cannot be read,
     * verified or installed is left out of the session, and said. None runs on Windows yet, and that is said.
     */
    suspend fun teamHooks(request: LaunchRequest, site: LaunchSite, features: Set<String>, say: (String) -> Unit) =
        run {
            val asked = request.teamHooks && request.agent in EVENTS && TEAM_HOOKS in features
            val hooks = TeamHooks(dependencies.server, site.credentials.token)
            val delivered = if (asked) delivered(hooks, say) else emptyList()
            when {
                delivered.isEmpty() -> emptyList()
                site.pathSeparator == WINDOWS -> emptyList<TeamHook>().also {
                    say("ostrom: team hooks are not run on Windows yet: none of those delivered to you runs here")
                }
                else -> approved(hooks, delivered, EVENTS.getValue(request.agent), site, say)
            }
        }

    private suspend fun delivered(hooks: TeamHooks, say: (String) -> Unit): List<EffectiveHook> =
        when (val manifest = hooks.manifest()) {
            is HookManifest.Read -> manifest.hooks
            is HookManifest.Failed -> emptyList<EffectiveHook>().also { say(unread(manifest.failure.message)) }
            HookManifest.Unreadable -> emptyList<EffectiveHook>().also { say(unread("its list cannot be read")) }
        }

    private suspend fun approved(
        hooks: TeamHooks,
        delivered: List<EffectiveHook>,
        events: Set<String>,
        site: LaunchSite,
        say: (String) -> Unit,
    ): List<TeamHook> {
        val files = dependencies.files
        // a launch only reads the decisions: `ostrom hooks approve` writes them
        val approvals = HookApprovals(site.configDirectory, files::read)
        val decisions = approvals.decisions()
        val states = delivered.associateWith { approvals.stateOf(it, decisions) }
        val awaiting = delivered.filter { states[it] == HookState.AWAITING || states[it] == HookState.NEW_VERSION }
        if (awaiting.isNotEmpty()) {
            say(
                "ostrom: team hooks awaiting your approval, not run: " + awaiting.joinToString { it.name } +
                    " (ostrom hooks list)",
            )
        }
        val installer = HookInstaller(
            dependencies.skillFiles,
            join(site.configDirectory, "hooks", "installed"),
            dependencies.restrict,
        )
        val running = delivered.filter { states[it] == HookState.APPROVED }.mapNotNull { hook ->
            val on = hook.definition.events.filter { it in events }.sorted()
            val command = if (on.isEmpty()) null else ready(hook, hooks, installer, say)
            command?.let { TeamHook(hook.name, on, hook.definition.matcher, it, hook.definition.timeoutSeconds) }
        }
        if (running.isNotEmpty()) say("ostrom: team hooks in this session: " + running.joinToString { it.name })
        return running
    }

    /** The installed executable of [hook], downloaded, verified and installed when it is not there yet. */
    private suspend fun ready(hook: EffectiveHook, hooks: TeamHooks, installer: HookInstaller, say: (String) -> Unit) =
        installer.installed(hook) ?: when (val files = hooks.files(hook)) {
            is HookFiles.Verified -> try {
                installer.install(hook, files.files)
            } catch (e: IOException) {
                null.also { say(notRun(hook, "it cannot be installed: ${e.message}")) }
            }
            is HookFiles.Refused -> null.also { say(notRun(hook, "refused: ${files.reason}")) }
            is HookFiles.ServerFailure -> null.also { say(notRun(hook, files.failure.message)) }
        }

    private companion object {
        const val WINDOWS = ';'

        fun unread(reason: String) = "ostrom: team hooks not read ($reason): none runs in this session"

        fun notRun(hook: EffectiveHook, reason: String) =
            "ostrom: team hook ${hook.name} ${hook.version} not run ($reason)"
    }
}
