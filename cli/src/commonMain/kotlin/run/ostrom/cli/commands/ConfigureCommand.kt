// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.capabilities.CapabilitiesRead
import run.ostrom.cli.capabilities.incompatibility
import run.ostrom.cli.capabilities.tooOld
import run.ostrom.cli.launch.ApiServerTransport
import run.ostrom.cli.launcher.Credentials
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.ConfigStoreImpl
import run.ostrom.cli.platform.FileWriteException
import kotlinx.coroutines.runBlocking
import okio.IOException

private const val ME = "/api/v1/auth/me"
private const val UNAUTHORIZED = 401

/**
 * `ostrom configure [--server URL]`, as TeamCode's `teamcode configure`: saves a server and an access token that
 * already exists (a service key from `ostrom keys create`, or a token made elsewhere), read from standard input so that
 * it never shows in a process list or a shell history. The server is asked for its capabilities with the token before
 * anything is kept: a token it refuses, or a server whose API this ostrom does not speak, saves nothing. Ordinary
 * sign-in is `ostrom login`.
 */
internal class ConfigureCommand(private val dependencies: CliDependencies) : CliktCommand(name = "configure") {
    override fun help(context: Context) =
        "Save a server and an existing access token read from standard input (ostrom login is the ordinary sign-in)"

    private val server by option("--server", help = "Your Ostrom server URL; asked for when omitted")

    override fun run() {
        dependencies.outsideSessions("configure")
        val directory = dependencies.directory()
        val serverUrl = (server ?: ask("Ostrom server URL: ")).trim().trimEnd('/')
        if (serverUrl.isEmpty()) throw usage("no server URL")
        refuseInClear(serverUrl, "the access token")
        dependencies.console.err("Ostrom access token (stdin; do not pass secrets as command arguments):\n")
        val token = dependencies.readLine()?.trim().orEmpty()
        if (token.isEmpty()) throw usage("no access token on standard input")
        runBlocking { check(serverUrl, token) }
        val login = runBlocking { loginOf(serverUrl, token) }
        try {
            dependencies.credentials(directory).save(serverUrl, Credentials(token, login))
            ConfigStoreImpl(dependencies.files, dependencies.configFile(directory)).set(ConfigKey.SERVER, serverUrl)
        } catch (e: FileWriteException) {
            throw cannotWrite(e.path)
        } catch (e: IOException) {
            throw cannotWrite("$directory (${e.message})")
        }
        dependencies.console.out("✓ Saved $serverUrl and its access token in $directory\n")
    }

    private fun ask(prompt: String): String {
        dependencies.console.err(prompt)
        return dependencies.readLine().orEmpty()
    }

    /** Reads what [serverUrl] speaks with [token], before anything is kept: the same checks as `ostrom login`. */
    private suspend fun check(serverUrl: String, token: String) {
        val refusal = when (val read = dependencies.capabilities.read(serverUrl, token)) {
            is CapabilitiesRead.Read -> incompatibility(serverUrl, read.capabilities, BuildInfo.VERSION)
            CapabilitiesRead.Unknown -> tooOld(serverUrl, BuildInfo.VERSION)
            is CapabilitiesRead.Failed -> if (read.status == UNAUTHORIZED) {
                throw PrintMessage(
                    "ostrom: $serverUrl did not accept this access token (HTTP 401). Check the token, or sign in " +
                        "with ostrom login. Nothing was saved.",
                    ExitCode.SIGN_IN_REQUIRED.code,
                    printError = true,
                )
            } else {
                "$serverUrl did not say which Ostrom API it speaks (${read.reason})."
            }
        }
        if (refusal != null) {
            throw PrintMessage("ostrom: $refusal Nothing was saved.", ExitCode.UNREACHABLE.code, printError = true)
        }
    }

    /** The login the token belongs to when the server says it (a service key may not read it), else none. */
    private suspend fun loginOf(serverUrl: String, token: String): String {
        val answer = ServerJson(ApiServerTransport(dependencies.api(serverUrl))).get(ME, token)
        return (answer as? ServerAnswer.Ok)?.body?.child("dev")?.text("login") ?: UNKNOWN_LOGIN
    }

    private fun usage(what: String) = PrintMessage("ostrom: $what", ExitCode.USAGE.code, printError = true)

    private fun cannotWrite(what: String) =
        PrintMessage("ostrom: could not write $what", ExitCode.CANNOT_WRITE.code, printError = true)

    private companion object {
        const val UNKNOWN_LOGIN = "unknown"
    }
}
