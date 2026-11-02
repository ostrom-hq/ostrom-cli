// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
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
import dev.ostrom.cli.login.AdminTokens
import dev.ostrom.cli.login.DeviceLogin
import dev.ostrom.cli.login.LoginOutcome
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import dev.ostrom.cli.platform.FileWriteException
import kotlinx.coroutines.runBlocking
import okio.IOException
import kotlin.time.Instant

/** The server of an account command: `--server`, else `OSTROM_URL`, else the one of the last login, else Ostrom's. */
internal fun CliDependencies.serverOf(option: String?, directory: String): String =
    (option ?: environment["OSTROM_URL"] ?: ConfigStoreImpl(files, configFile(directory)).get(ConfigKey.SERVER)
        ?: DEFAULT_SERVER).trimEnd('/')

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
internal val USER_INFO = Regex("//[^/]*@")

/**
 * Refuses an account command inside an agent's session: the agent would sign the developer out, or point the device
 * at another server (security audit S2-32).
 */
internal fun CliDependencies.outsideSessions(command: String) {
    if (!environment[LaunchVariables.LAUNCH_ID].isNullOrEmpty()) {
        val message = "ostrom: ostrom $command does not run inside an agent's session: run it in your own terminal"
        throw PrintMessage(message, ExitCode.USAGE.code, true)
    }
}

/** The CLI folder, or exit 73. */
internal fun CliDependencies.directory(): String = environment.configDirectory
    ?: throw PrintMessage("ostrom: no folder for ostrom's files: set OSTROM_HOME", ExitCode.CANNOT_WRITE.code, true)

/** The admin tokens of `ostrom login --admin` in the CLI folder [directory], apart from the device's token. */
internal fun CliDependencies.adminTokens(directory: String) =
    AdminTokens(files, join(directory, "admin.json"), secrets, clock)

/**
 * `ostrom login [--server URL] [--admin]`: signs this device in with GitHub, through Ostrom (CLI.md § 2.2). With
 * `--admin`, asks for a token that writes the organisation's governance instead (decision of 02/11/2026): its scopes
 * are shown in the browser, an owner or admin approves them, and it is kept apart, 8 hours at most, never renewed.
 */
internal class LoginCommand(private val dependencies: CliDependencies) : CliktCommand(name = "login") {
    override fun help(context: Context) = "Sign in with GitHub"

    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")
    val admin by option(
        "--admin",
        help = "Ask for an 8-hour token that changes the policy and the harness (an owner or admin approves it)",
    ).flag()

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
            (dependencies.environment["OSTROM_GITHUB_WEB_URL"] ?: "https://github.com").trimEnd('/'),
            console,
            dependencies.sleep,
        ) { dependencies.console.err("$it\n") }
        val scopes = if (admin) AdminTokens.SCOPES else emptyList()
        if (admin) {
            dependencies.console.err("! Asking for an admin token: ${scopes.joinToString(", ")}. An owner or admin " +
                "approves it in the browser; it lasts 8 hours at most and is never renewed.\n")
        }
        when (val outcome = runBlocking { login.signIn(serverUrl, scopes) }) {
            is LoginOutcome.Stopped -> throw PrintMessage(outcome.message, outcome.exitCode, printError = true)
            is LoginOutcome.SignedIn ->
                if (admin) keepAdmin(directory, serverUrl, outcome) else keepDevice(directory, serverUrl, outcome)
        }
    }

    /** Keeps the device's token and the server. */
    private fun keepDevice(directory: String, serverUrl: String, outcome: LoginOutcome.SignedIn) {
        keep(directory) {
            dependencies.credentials(directory).save(serverUrl, outcome.credentials)
            ConfigStoreImpl(dependencies.files, dependencies.configFile(directory)).set(ConfigKey.SERVER, serverUrl)
        }
        dependencies.console.out("✓ Signed in as @${outcome.credentials.login} on $serverUrl\n")
        dependencies.console.out("Next: cd into a repository and run ostrom claude\n")
    }

    /** Revokes [token] on [serverUrl], as best it can: its answer changes nothing here. */
    private suspend fun signedOut(serverUrl: String, token: String) {
        ServerJson(ApiServerTransport(dependencies.api(serverUrl))).post(LOGOUT_PATH, objectOf(), token)
    }

    /**
     * Keeps the admin token apart, until the server's expiry or 8 hours from now, the sooner; a token without every
     * admin scope (a server that does not serve `--admin` yet) is not kept.
     */
    private fun keepAdmin(directory: String, serverUrl: String, outcome: LoginOutcome.SignedIn) {
        val missing = AdminTokens.SCOPES - outcome.scopes.toSet()
        val now = dependencies.clock()
        val latest = now + AdminTokens.MAX_MILLIS
        val until = minOf(outcome.expiresAt?.let(::millisOf) ?: latest, latest)
        val refusal = when {
            missing.isNotEmpty() -> "$serverUrl did not grant ${missing.joinToString(", ")}: it does not serve " +
                "ostrom login --admin yet."
            until <= now -> "$serverUrl answered a token that has already expired (check this machine's clock)."
            else -> null
        }
        if (refusal != null) {
            // the token is of no use here: the server is told, so that it does not stay open on it
            runBlocking { signedOut(serverUrl, outcome.credentials.token) }
            throw PrintMessage("ostrom: $refusal Nothing was kept.", ExitCode.UNREACHABLE.code, printError = true)
        }
        keep(directory) { dependencies.adminTokens(directory).save(serverUrl, outcome.credentials.token, until) }
        val login = outcome.credentials.login
        val device = dependencies.credentials(directory).saved(serverUrl)?.login
        if (device != null && device != login) {
            dependencies.console.err("! The admin token is @$login's, not @$device's (this device): the policy and " +
                "the harness change in @$login's name.\n")
        }
        val instant = Instant.fromEpochMilliseconds(until)
        dependencies.console.out("✓ Admin token for @$login on $serverUrl until $instant, never renewed.\n")
        dependencies.console.out("It changes the policy and the harness: ostrom policy set, ostrom harness enforce\n")
    }

    /** The instant [text] says, in milliseconds; null when it is not an ISO 8601 instant. */
    private fun millisOf(text: String): Long? = try {
        Instant.parse(text).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Runs [write]; a folder that cannot be written is exit 73 (the session expires on its own). */
    private fun keep(directory: String, write: () -> Unit) {
        val written = try {
            write()
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
        // the admin token of ostrom login --admin goes too: revoked first (as best it can), then forgotten
        val tokens = dependencies.adminTokens(directory)
        val admin = tokens.valid(serverUrl)
        if (inClear(serverUrl)) {
            // signed out here all the same: the tokens are forgotten, only the server is not told
            tokens.forget(serverUrl)
            store.forget(serverUrl)
            refuseInClear(serverUrl, "the token")
        }
        // made only when there is something to revoke
        val api by lazy { ServerJson(ApiServerTransport(dependencies.api(serverUrl))) }
        if (admin != null) runBlocking { api.post(LOGOUT_PATH, objectOf(), admin) }
        tokens.forget(serverUrl)
        if (credentials == null) {
            // S2-32: a token left only in the other store (the encrypted file of an SSH session) is forgotten too
            store.forget(serverUrl)
            dependencies.console.out("Not signed in. Nothing to do.\n")
            throw ProgramResult(ExitCode.OK.code)
        }
        val answer = runBlocking { api.post(LOGOUT_PATH, objectOf(), credentials.token) }
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
        const val UNAUTHORIZED = 401
    }
}

private const val DEFAULT_SERVER = "https://api.ostrom.run"

/** `POST`: revokes the token it is sent with (served). */
private const val LOGOUT_PATH = "/api/v1/auth/logout"

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
