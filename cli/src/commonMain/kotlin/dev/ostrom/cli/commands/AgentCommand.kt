// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.cloud.CloudSite
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
import dev.ostrom.cli.launcher.cloud.CloudStart
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import kotlinx.coroutines.runBlocking

/**
 * `ostrom <agent>`: the developer's only entry point. The agent runs in this terminal under the team's harness, its
 * exit code becomes ostrom's (CLI.md § 2.2).
 */
internal class AgentCommand(private val agent: Agent, private val dependencies: CliDependencies) :
    CliktCommand(name = agent.command) {
    override fun help(context: Context) = "Start ${agent.displayName} under your team's harness"

    override val treatUnknownOptionsAsArgs = true
    val repo by option("--repo", help = "Repository, owner/name (else read from origin, else the folder's name)")
    val model by option("--model", help = "Model for the agent")
    val cloud by option("--cloud", help = "Run the session in Ostrom's cloud, attached here (Ctrl-] detaches)")
        .flag()
    val dryRun by option("--dry-run", help = "Check the launch and show the harness that would apply, then exit").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")
    val org by option("--org", help = "Organisation to act in (else OSTROM_ORG, else the one of ostrom init)")
    val agentArguments by argument("agent-args", help = "Passed to the agent as they are").multiple()

    override fun run() {
        if (cloud && dryRun) throw UsageError("--dry-run checks a launch on this machine: it does not go with --cloud")
        // a usage error before the server or the sign-in is looked at: 64, never 65 for a developer signed out
        if (cloud && agentArguments.isNotEmpty()) {
            throw stop(
                ExitCode.USAGE,
                "agent arguments do not go to a cloud session (${agentArguments.joinToString(" ")}): " +
                    "start it with `ostrom ${agent.command} --cloud`, then type in it",
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
        val request = LaunchRequest(agent, agentArguments, repo, model, dryRun)
        throw ProgramResult(runBlocking { dependencies.agents.run(request, site) })
    }

    private fun cwd() = dependencies.workingDirectory()

    /** `--cloud`: the session runs in Ostrom's cloud and this terminal is attached to it. */
    private suspend fun inTheCloud(site: CloudSite): Int =
        dependencies.cloud.start(CloudStart(agent, model, repo), site)

    private fun stop(code: ExitCode, message: String) = PrintMessage("ostrom: $message", code.code, printError = true)

    private companion object {
        const val NO_HOME = "no folder for ostrom's files: set OSTROM_HOME"
        const val SIGN_IN = "no Ostrom server: run `ostrom login`, or set OSTROM_URL"
    }
}
