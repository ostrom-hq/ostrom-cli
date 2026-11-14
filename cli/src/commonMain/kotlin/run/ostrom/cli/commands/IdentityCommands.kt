// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val KEYS = "/api/v1/service-keys"
private const val AUTH_SESSIONS = "/api/v1/auth/sessions"
private const val WRITE_MILLIS = 15_000L
private const val DEFAULT_DAYS = 30

// TeamCode's `teamcode keys` and `teamcode auth sessions` ("an interactive login is required"): the person's own
// `ostrom login`, never a service key or an agent's token. An older server answers 403 `session_required`: its words
// are said.

/** `ostrom keys`: the service keys of your organisation, unless a subcommand is given. */
internal class KeysCommand(private val dependencies: CliDependencies) :
    GroupCommand("keys", "Service keys for CI and scripts (owners and admins)", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listKeys(org = null, server = null, json = false)
}

/** `ostrom keys list`: the organisation's service keys, never their secrets. */
internal class KeysListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the organisation's service keys"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() = dependencies.listKeys(org, server, json)
}

private fun CliDependencies.listKeys(org: String?, server: String?, json: Boolean) {
    val access = orgAccess(server, org)
    val answer = access.read(KEYS, "No service keys here.")
    if (json) {
        console.data("$answer\n")
        return
    }
    val keys = answer.list("items").filterIsInstance<JsonObject>()
    if (keys.isEmpty()) console.err("No service keys in ${access.org.slug}.\n")
    keys.forEach { key ->
        val scopes = key.list("scopes").filterIsInstance<JsonPrimitive>().joinToString(",") { it.content }
        val expires = key.text("expiresAt") ?: "never"
        console.out("${key.text("id")}\t${key.text("name")}\t$scopes\texpires $expires\n")
    }
}

/** `ostrom keys create --name N [--scopes a,b] [--days N | --no-expiry] [--json]`. */
internal class KeysCreateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create a service key; its secret is printed once"

    private val name by option("--name", help = "What the key is for").required()
    private val scopes by option("--scopes", help = "Comma-separated scopes, e.g. inference:use,usage:read")
        .default("inference:use")
    private val days by option("--days", help = "Lifetime in days (default $DEFAULT_DAYS); exclusive with --no-expiry")
        .int()
    private val noExpiry by option("--no-expiry", help = "A key that never expires; revoke it to end it").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("keys create")
        if (noExpiry && days != null) throw UsageError("--days and --no-expiry cannot be combined.")
        val wanted = scopes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) throw UsageError("--scopes names no scope.")
        val access = dependencies.orgAccess(server, org)
        val body = objectOf(
            "name" to name,
            "scopes" to JsonArray(wanted.map(::JsonPrimitive)),
            "expiresInDays" to if (noExpiry) null else days ?: DEFAULT_DAYS,
        )
        val created = runBlocking { access.server.call("POST", KEYS, body, access.signed.token, WRITE_MILLIS) }
            .orStop(access.signed.serverUrl, "No service keys here.")
        dependencies.console.err("Save this one-time credential in your CI secret store: it is not shown again.\n")
        if (json) dependencies.console.data("$created\n") else dependencies.console.data("${created.text("secret")}\n")
    }
}

/** `ostrom keys revoke ID`. */
internal class KeysRevokeCommand(private val dependencies: CliDependencies) : CliktCommand(name = "revoke") {
    override fun help(context: Context) = "Revoke a service key: it stops working at once"

    private val id by argument("id", help = "The key's id (ostrom keys list)")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("keys revoke")
        val access = dependencies.orgAccess(server, org)
        runBlocking { access.server.call("DELETE", "$KEYS/$id", null, access.signed.token, WRITE_MILLIS) }
            .orStop(access.signed.serverUrl, "No service key $id.")
        dependencies.console.out("Service key revoked.\n")
    }
}

/** `ostrom auth`: authentication; its help unless a subcommand is given. */
internal class AuthCommand :
    GroupCommand("auth", "Your sign-in sessions (agent sessions are separate)", comingSoon = false)

/** `ostrom auth sessions`: your signed-in browser sessions, unless a subcommand is given. */
internal class AuthSessionsCommand(private val dependencies: CliDependencies) :
    GroupCommand("sessions", "Your signed-in browser sessions", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listAuthSessions(server = null, json = false)
}

/** `ostrom auth sessions list`. */
internal class AuthSessionsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List your signed-in browser sessions"

    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() = dependencies.listAuthSessions(server, json)
}

private fun CliDependencies.listAuthSessions(server: String?, json: Boolean) {
    val signed = signedIn(server)
    val answer = runBlocking { signed.server.get(AUTH_SESSIONS, signed.token) }.orStop(signed.serverUrl, "No sessions.")
    if (json) {
        console.data("$answer\n")
        return
    }
    answer.list("items").filterIsInstance<JsonObject>().forEach { session ->
        val state = if (session.flag("current") == true) "current" else "other"
        val device = session.text("device") ?: "-"
        console.out("${session.text("id")}\t$device\t$state\tlast seen ${session.text("lastSeenAt") ?: "-"}\n")
    }
}

/** `ostrom auth sessions revoke ID`. */
internal class AuthSessionsRevokeCommand(private val dependencies: CliDependencies) : CliktCommand(name = "revoke") {
    override fun help(context: Context) = "Sign a browser session out"

    private val id by argument("id", help = "The session's id (ostrom auth sessions list)")
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("auth sessions revoke")
        val signed = dependencies.signedIn(server)
        runBlocking { signed.server.call("DELETE", "$AUTH_SESSIONS/$id", null, signed.token, WRITE_MILLIS) }
            .orStop(signed.serverUrl, "No session $id.")
        dependencies.console.out("Session revoked.\n")
    }
}
