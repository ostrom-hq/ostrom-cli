// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.CredentialStore
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.login.DeviceLogin
import dev.ostrom.cli.login.LoginOutcome
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import kotlinx.coroutines.runBlocking

/** The server of an account command: `--server`, else `OSTROM_URL`, else the one of the last login, else Ostrom's. */
internal fun CliDependencies.serverOf(option: String?, directory: String): String =
    (option ?: environment["OSTROM_URL"] ?: ConfigStoreImpl(files, configFile(directory)).get(ConfigKey.SERVER)
        ?: DEFAULT_SERVER).trimEnd('/')

/** The CLI folder, or exit 73. */
internal fun CliDependencies.directory(): String = environment.configDirectory
    ?: throw PrintMessage("ostrom: no folder for ostrom's files: set OSTROM_HOME", ExitCode.CANNOT_WRITE.code, true)

/** `ostrom login [--server URL]`: signs this device in with GitHub, through Ostrom (CLI.md § 2.2). */
internal class LoginCommand(private val dependencies: CliDependencies) : CliktCommand(name = "login") {
    override fun help(context: Context) = "Sign in with GitHub"

    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val directory = dependencies.directory()
        val serverUrl = dependencies.serverOf(server, directory)
        val login = DeviceLogin(
            ServerJson(ApiServerTransport(dependencies.api(serverUrl))),
            dependencies.environment["OSTROM_GITHUB_WEB_URL"]?.trimEnd('/') ?: "https://github.com",
            dependencies.sleep,
        ) { dependencies.console.err("$it\n") }
        when (val outcome = runBlocking { login.signIn(serverUrl) }) {
            is LoginOutcome.Stopped -> throw PrintMessage(outcome.message, outcome.exitCode, printError = true)
            is LoginOutcome.SignedIn -> {
                dependencies.credentials(directory).save(serverUrl, outcome.credentials)
                ConfigStoreImpl(dependencies.files, dependencies.configFile(directory)).set(ConfigKey.SERVER, serverUrl)
                dependencies.console.out("✓ Signed in as @${outcome.credentials.login} on $serverUrl\n")
                dependencies.console.out("Next: cd into a repository and run ostrom claude\n")
            }
        }
    }
}

/** `ostrom logout`: revokes this device's session and forgets its token; receipts are kept (CLI.md § 2.2). */
internal class LogoutCommand(private val dependencies: CliDependencies) : CliktCommand(name = "logout") {
    override fun help(context: Context) = "Sign out this device (receipts are kept)"

    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val directory = dependencies.directory()
        val serverUrl = dependencies.serverOf(server, directory)
        val store: CredentialStore = dependencies.credentials(directory)
        val credentials = store.load(serverUrl)
        if (credentials == null) {
            dependencies.console.out("Not signed in. Nothing to do.\n")
            throw ProgramResult(ExitCode.OK.code)
        }
        val api = ServerJson(ApiServerTransport(dependencies.api(serverUrl)))
        val answer = runBlocking { api.post(LOGOUT, objectOf(), credentials.token) }
        store.forget(serverUrl)
        if (answer is ServerAnswer.Failed) {
            val why = answer.message
            dependencies.console.err("! The server could not be told ($why). The session expires on its own.\n")
        }
        val receipts = join(directory, "receipts")
        dependencies.console.out("✓ Signed out @${credentials.login}. Receipts kept in $receipts.\n")
    }

    private companion object {
        const val LOGOUT = "/api/v1/auth/logout"
    }
}

private const val DEFAULT_SERVER = "https://api.ostrom.run"

/**
 * The signed-in device for a command that reads or writes on the server.
 *
 * @property serverUrl The server, without a trailing slash.
 * @property token The device token.
 * @property login The developer's GitHub login.
 * @property server The server's JSON API.
 */
internal class SignedIn(val serverUrl: String, val token: String, val login: String, val server: ServerJson)

/** The device signed in to the server of [option], or exit 65 with `ostrom login` to run. */
internal fun CliDependencies.signedIn(option: String?): SignedIn {
    val directory = directory()
    val serverUrl = serverOf(option, directory)
    val credentials = credentials(directory).load(serverUrl) ?: throw PrintMessage(
        "ostrom: not signed in to $serverUrl: run `ostrom login`",
        ExitCode.SIGN_IN_REQUIRED.code,
        printError = true,
    )
    return SignedIn(serverUrl, credentials.token, credentials.login, ServerJson(ApiServerTransport(api(serverUrl))))
}
