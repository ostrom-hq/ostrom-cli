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
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.login.DeviceLogin
import dev.ostrom.cli.login.LoginOutcome
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import dev.ostrom.cli.platform.FileWriteException
import kotlinx.coroutines.runBlocking
import okio.IOException

/** The server of an account command: `--server`, else `OSTROM_URL`, else the one of the last login, else Ostrom's. */
private fun CliDependencies.serverOf(option: String?, directory: String): String {
    val saved = { ConfigStoreImpl(files, configFile(directory)).get(ConfigKey.SERVER) }
    return (option ?: environment["OSTROM_URL"] ?: saved() ?: DEFAULT_SERVER).trimEnd('/')
}

/**
 * True when [url] would carry the device's codes and token in clear: not `https://`, nor `http://` to this machine (a
 * server run for development). The scheme is read in any case, like `ostrom config set server` reads it.
 */
internal fun inClear(url: String): Boolean = !HTTPS.matches(url) && !LOCAL_HTTP.matches(url)

/** Stops a command whose server is [inClear] (exit 64), naming it without the user and password it may hold. */
internal fun refuseInClear(url: String, what: String) {
    if (inClear(url)) {
        val shown = url.replace(USER_INFO, "//")
        val message = "ostrom: $shown is not an HTTPS address: $what would travel in clear. " +
            "Use https:// (http:// only for localhost, 127.0.0.1 or [::1])"
        throw PrintMessage(message, ExitCode.USAGE.code, true)
    }
}

private val HTTPS = Regex("https://.*", RegexOption.IGNORE_CASE)

/** `http://` to this machine, with or without a port and a path. */
private val LOCAL_HTTP = Regex("""http://(localhost|127\.0\.0\.1|\[::1])(:\d{1,5})?(/.*)?""", RegexOption.IGNORE_CASE)
// up to the last @ before the path: a password may hold one
private val USER_INFO = Regex("//[^/]*@")

/**
 * Refuses an account command inside an agent's session: the agent would sign the developer out, or point the device
 * at another server (security audit S2-32).
 */
private fun CliDependencies.outsideSessions(command: String) {
    if (!environment[LaunchVariables.LAUNCH_ID].isNullOrEmpty()) {
        val message = "ostrom: ostrom $command does not run inside an agent's session: run it in your own terminal"
        throw PrintMessage(message, ExitCode.USAGE.code, true)
    }
}

/** The CLI folder, or exit 73. */
private fun CliDependencies.directory(): String = environment.configDirectory
    ?: throw PrintMessage("ostrom: no folder for ostrom's files: set OSTROM_HOME", ExitCode.CANNOT_WRITE.code, true)

/** `ostrom login [--server URL]`: signs this device in with GitHub, through Ostrom (CLI.md § 2.2). */
internal class LoginCommand(private val dependencies: CliDependencies) : CliktCommand(name = "login") {
    override fun help(context: Context) = "Sign in with GitHub"

    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        dependencies.outsideSessions("login")
        val directory = dependencies.directory()
        val serverUrl = dependencies.serverOf(server, directory)
        refuseInClear(serverUrl, "the sign-in code and the token")
        // the console the server sends to: OSTROM_CONSOLE_URL, else the server's with app. for api.
        val console = dependencies.environment["OSTROM_CONSOLE_URL"]
            ?: serverUrl.replaceFirst("://api.", "://app.", ignoreCase = true)
        val login = DeviceLogin(
            ServerJson(ApiServerTransport(dependencies.api(serverUrl))),
            dependencies.environment["OSTROM_GITHUB_WEB_URL"]?.trimEnd('/') ?: "https://github.com",
            console,
            dependencies.sleep,
        ) { dependencies.console.err("$it\n") }
        when (val outcome = runBlocking { login.signIn(serverUrl) }) {
            is LoginOutcome.Stopped -> throw PrintMessage(outcome.message, outcome.exitCode, printError = true)
            is LoginOutcome.SignedIn -> {
                keep(directory, serverUrl, outcome)
                dependencies.console.out("✓ Signed in as @${outcome.credentials.login} on $serverUrl\n")
                dependencies.console.out("Next: cd into a repository and run ostrom claude\n")
            }
        }
    }

    /** Keeps the token and the server; a folder that cannot be written is exit 73 (the session expires on its own). */
    private fun keep(directory: String, serverUrl: String, outcome: LoginOutcome.SignedIn) {
        val written = try {
            dependencies.credentials(directory).save(serverUrl, outcome.credentials)
            ConfigStoreImpl(dependencies.files, dependencies.configFile(directory)).set(ConfigKey.SERVER, serverUrl)
            null
        } catch (e: FileWriteException) {
            e.path
        } catch (e: IOException) {
            "$directory (${e.message})"
        }
        if (written != null) {
            throw PrintMessage("ostrom: signed in, but could not write $written", ExitCode.CANNOT_WRITE.code, true)
        }
    }
}

/** `ostrom logout`: revokes this device's session and forgets its token; receipts are kept (CLI.md § 2.2). */
internal class LogoutCommand(private val dependencies: CliDependencies) : CliktCommand(name = "logout") {
    override fun help(context: Context) = "Sign out this device (receipts are kept)"

    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        dependencies.outsideSessions("logout")
        val directory = dependencies.directory()
        val serverUrl = dependencies.serverOf(server, directory)
        val store: CredentialStore = dependencies.credentials(directory)
        // the saved entry, never OSTROM_TOKEN: a CI token in the environment is not this device's session
        val credentials = store.saved(serverUrl)
        if (inClear(serverUrl)) {
            // signed out here all the same: the token is forgotten, only the server is not told
            store.forget(serverUrl)
            refuseInClear(serverUrl, "the token")
        }
        if (credentials == null) {
            // S2-32: a token left only in the other store (the encrypted file of an SSH session) is forgotten too
            store.forget(serverUrl)
            dependencies.console.out("Not signed in. Nothing to do.\n")
            throw ProgramResult(ExitCode.OK.code)
        }
        val api = ServerJson(ApiServerTransport(dependencies.api(serverUrl)))
        val answer = runBlocking { api.post(LOGOUT, objectOf(), credentials.token) }
        store.forget(serverUrl)
        // a session already expired or revoked (401) has nothing left to revoke
        if (answer is ServerAnswer.Failed && answer.status != UNAUTHORIZED) {
            val why = answer.message
            dependencies.console.err("! The server could not be told ($why). The session expires on its own.\n")
        }
        val receipts = join(directory, "receipts")
        dependencies.console.out("✓ Signed out @${credentials.login}. Receipts kept in $receipts.\n")
    }

    private companion object {
        const val LOGOUT = "/api/v1/auth/logout"
        const val UNAUTHORIZED = 401
    }
}

private const val DEFAULT_SERVER = "https://api.ostrom.run"
