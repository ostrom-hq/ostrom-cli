// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
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
    val repo by option("--repo", help = "Repository (else read from origin)")
    val model by option("--model", help = "Model for the agent")
    val cloud by option("--cloud", help = "Run the session in Ostrom's cloud, attached to this terminal (coming soon)")
        .flag()
    val dryRun by option("--dry-run", help = "Show the harness that would apply, then exit (coming soon)").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")
    val agentArguments by argument("agent-args", help = "Passed to the agent as they are").multiple()

    override fun run() {
        val path = currentContext.commandNameWithParents()
        if (cloud) throw notImplemented(path + "--cloud")
        if (dryRun) throw notImplemented(path + "--dry-run")
        val environment = dependencies.environment
        val directory = environment.configDirectory ?: throw stop(ExitCode.CANNOT_WRITE, NO_HOME)
        val saved = ConfigStoreImpl(dependencies.files, dependencies.configFile(directory)).get(ConfigKey.SERVER)
        val serverUrl = (server ?: environment["OSTROM_URL"] ?: saved)?.trimEnd('/')
            ?: throw stop(ExitCode.SIGN_IN_REQUIRED, SIGN_IN)
        val credentials = dependencies.credentials(directory).load(serverUrl)
            ?: throw stop(ExitCode.SIGN_IN_REQUIRED, "not signed in to $serverUrl: run `ostrom login`")
        val site = LaunchSite(
            serverUrl = serverUrl,
            credentials = credentials,
            cwd = dependencies.workingDirectory(),
            executable = dependencies.executable ?: "ostrom",
            configDirectory = directory,
            temporaryDirectory = dependencies.temporaryDirectory,
            home = environment.homeDirectory.orEmpty(),
            pathSeparator = environment.pathListSeparator,
            variable = { environment[it] },
        )
        val request = LaunchRequest(agent, agentArguments, repo, model)
        throw ProgramResult(runBlocking { dependencies.agents.run(request, site) })
    }

    private fun stop(code: ExitCode, message: String) = PrintMessage("ostrom: $message", code.code, printError = true)

    private companion object {
        const val NO_HOME = "no folder for ostrom's files: set OSTROM_HOME"
        const val SIGN_IN = "no Ostrom server: run `ostrom login`, or set OSTROM_URL"
    }
}
