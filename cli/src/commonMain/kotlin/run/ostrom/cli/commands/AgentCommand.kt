// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.cloud.CloudSite
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import run.ostrom.cli.launcher.LaunchSite
import run.ostrom.cli.launcher.cloud.CloudStart
import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.ConfigStoreImpl
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking

/**
 * `ostrom run <agent> [options] [-- <agent-args>]`: the developer's entry point, as TeamCode's `teamcode run <agent>`.
 * The agent runs in this terminal under the team's harness, its exit code becomes ostrom's (CLI.md § 2.2). What
 * follows `--` goes to the agent untouched; an option before it that is not Ostrom's is a usage error, never passed
 * on.
 *
 * @param deprecated True for the former path `ostrom <agent>`, kept hidden for one release: it says on stderr that
 * it goes away, then runs as it did, the agent's options mixed with Ostrom's.
 */
internal class AgentCommand(
    private val agent: Agent,
    private val dependencies: CliDependencies,
    private val deprecated: Boolean = false,
) : CliktCommand(name = agent.command) {
    override fun help(context: Context) = "Start ${agent.displayName} under your team's harness"

    override val hiddenFromHelp = deprecated

    override val treatUnknownOptionsAsArgs = deprecated
    val repo by option("--repo", help = "Repository, owner/name (else read from origin, else the folder's name)")
    val model by option("--model", help = "Model for the agent")
    val cloud by option("--cloud", help = "Run the session in Ostrom's cloud, attached here (Ctrl-] detaches)")
        .flag()
    val dryRun by option("--dry-run", help = "Check the launch and show the harness that would apply, then exit").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")
    val org by option("--org", help = "Organisation to act in (else OSTROM_ORG, else the one of ostrom init)")
    val trustProjectSettings by option(
        TRUST_PROJECT_SETTINGS,
        help = "Claude Code: read this repository's settings, hooks and MCP servers too; Gemini CLI: load its .env " +
            "files too. They can run its programs and turn Ostrom's gate off: only for a repository your team trusts",
    ).flag()
    val noTeamHooks by option(
        "--no-team-hooks",
        help = "Run none of the team hooks you approved in this session (ostrom hooks list)",
    ).flag()
    val agentArguments by argument("agent-args", help = "After --: passed to the agent as they are").multiple()

    override fun run() {
        if (deprecated) {
            dependencies.console.err(deprecation(agent.command, "${runPath(agent)} [options] -- <its arguments>"))
        }
        refuseOptionsTogether()
        // a usage error before the server or the sign-in is looked at: 64, never 65 for a developer signed out
        if (cloud && agentArguments.isNotEmpty()) {
            throw stop(
                ExitCode.USAGE,
                "agent arguments do not go to a cloud session (${agentArguments.joinToString(" ")}): " +
                    "start it with `${runPath(agent)} --cloud`, then type in it",
            )
        }
        val environment = dependencies.environment
        val directory = environment.configDirectory ?: throw stop(ExitCode.CANNOT_WRITE, NO_HOME)
        val config = ConfigStoreImpl(dependencies.files, dependencies.configFile(directory))
        val saved = config.get(ConfigKey.SERVER)
        val chosen = server ?: environment["OSTROM_URL"] ?: saved ?: throw stop(ExitCode.SIGN_IN_REQUIRED, SIGN_IN)
        val serverUrl = chosen.trimEnd('/')
        refuseInClear(serverUrl, "the device's token")
        val credentials = dependencies.credentials(directory).load(serverUrl)
            ?: throw stop(ExitCode.SIGN_IN_REQUIRED, "not signed in to $serverUrl: run `ostrom login`")
        if (cloud) dependencies.requireFeature(serverUrl, credentials.token, ServerFeatures.RUNNERS_CLOUD, NO_CLOUD)
        val orgId = dependencies.launchOrg(serverUrl, credentials.token, org)
        if (cloud) {
            throw ProgramResult(runBlocking { inTheCloud(CloudSite(serverUrl, credentials.token, cwd(), orgId)) })
        }
        val site = LaunchSite(
            serverUrl = serverUrl,
            credentials = credentials,
            cwd = cwd(),
            executable = dependencies.executable ?: "ostrom",
            configDirectory = directory,
            temporaryDirectory = dependencies.temporaryDirectory,
            home = environment.homeDirectory.orEmpty(),
            pathSeparator = environment.pathListSeparator,
            variable = { environment[it] },
            org = orgId,
            foldsCase = environment.operatingSystem.foldsCase,
        )
        val request = LaunchRequest(agent, agentArguments, repo, model, dryRun, trustProjectSettings, !noTeamHooks)
        throw ProgramResult(runBlocking { dependencies.agents.run(request, site) })
    }

    private fun cwd() = dependencies.workingDirectory()

    /** Options that do not go together, a usage error before anything is read. */
    private fun refuseOptionsTogether() {
        if (cloud && dryRun) throw UsageError("--dry-run checks a launch on this machine: it does not go with --cloud")
        // the agents that read a repository's own configuration only when the developer asks
        val trusting = agent == Agent.CLAUDE || agent == Agent.GEMINI
        if (trustProjectSettings && (cloud || !trusting)) {
            throw UsageError(
                "$TRUST_PROJECT_SETTINGS is for `${runPath(Agent.CLAUDE)}` and `${runPath(Agent.GEMINI)}` " +
                    "on this machine",
            )
        }
    }

    /** `--cloud`: the session runs in Ostrom's cloud and this terminal is attached to it. */
    private suspend fun inTheCloud(site: CloudSite): Int =
        dependencies.cloud.start(CloudStart(agent, model, repo), site)

    private fun stop(code: ExitCode, message: String) = PrintMessage("ostrom: $message", code.code, printError = true)

    private companion object {
        const val NO_HOME = "no folder for ostrom's files: set OSTROM_HOME"
        const val TRUST_PROJECT_SETTINGS = "--trust-project-settings"
        const val SIGN_IN = "no Ostrom server: run `ostrom login`, or set OSTROM_URL"
    }
}

/** `ostrom run <agent>`, the path that starts [agent]. */
internal fun runPath(agent: Agent) = "ostrom $RUN_COMMAND ${agent.command}"
