// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.imports.ImportTeamCodeRequest
import dev.ostrom.cli.imports.ImportTeamCodeUseCase
import dev.ostrom.cli.imports.TeamCodeDatabase
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.ServerJson
import kotlinx.coroutines.runBlocking

/** `ostrom import`: brings a team from another tool into Ostrom (admin). */
internal class ImportCommand :
    GroupCommand("import", "Bring a team from another tool into Ostrom (admin)", comingSoon = false)

/**
 * `ostrom import teamcode --db URL --key-file FILE [--team T] [--yes]`: TeamCode's MCP servers, secrets, tool
 * policies and shared accounts into this organisation (ostrom-server#28). Shows Ostrom's plan; imports with `--yes`.
 */
internal class ImportTeamCodeCommand(private val dependencies: CliDependencies) : CliktCommand(name = "teamcode") {
    override fun help(context: Context) = "Import a TeamCode team: MCP servers, secrets, tool policies, accounts"

    private val db by option("--db", help = "TeamCode's MySQL: mysql://user@host[:port]/database (password: MYSQL_PWD)")
        .required()
    private val keyFile by option("--key-file", help = "The file holding TeamCode's TEAMCODE_ENCRYPTION_KEY").required()
    private val team by option("--team", help = "The TeamCode team id").default("default")
    private val yes by option("--yes", help = "Import after showing the plan").flag()
    private val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        // an agent would send every secret of the team, opened, to the server it names (S2-32)
        dependencies.outsideSessions("import teamcode")
        val directory = dependencies.directory()
        val serverUrl = dependencies.serverOf(server, directory)
        // Ostrom refuses the device token of `ostrom login` for import:write: only a console token imports
        val token = dependencies.environment["OSTROM_TOKEN"]?.takeIf { it.isNotEmpty() } ?: throw PrintMessage(
            "ostrom: the import needs an owner's or admin's console token (scope import:write) in OSTROM_TOKEN; " +
                "the device token of ostrom login cannot import",
            ExitCode.SIGN_IN_REQUIRED.code,
            printError = true,
        )
        val import = ImportTeamCodeUseCase(
            TeamCodeDatabase(dependencies.capture),
            ServerJson(ApiServerTransport(dependencies.api(serverUrl))),
            token,
            { dependencies.console.out(it) },
        ) { dependencies.console.err(it) }
        val request = ImportTeamCodeRequest(db, dependencies.files.read(keyFile), team, yes)
        throw ProgramResult(runBlocking { import(request) })
    }
}
