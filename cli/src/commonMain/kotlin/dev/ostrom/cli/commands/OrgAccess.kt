// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.RemoteUrls
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * An organisation of the signed-in person, as `GET /api/v1/orgs` lists it (`OrgView` of the contract).
 *
 * @property id The organisation's id, sent as `Ostrom-Org`.
 * @property slug Its short name, the one people type.
 * @property name Its display name.
 * @property role The caller's role in it: `owner`, `admin`, `member`, `approver` or `billing`.
 */
internal data class Org(val id: String, val slug: String, val name: String, val role: String) {
    /** True for the roles that run the organisation's setup: owners and admins. */
    val admin: Boolean get() = role == "owner" || role == "admin"
}

/**
 * What an organisation's command works with.
 *
 * @property signed The device (or `OSTROM_TOKEN`) signed in to the server.
 * @property org The organisation the command acts in.
 * @property server The server's JSON API, every call carrying `Ostrom-Org`.
 * @property console The console of the server, where what the CLI cannot do is done.
 * @property consoleToken True when the token is `OSTROM_TOKEN` (a token made on the console), not the device's.
 */
internal class OrgAccess(
    val signed: SignedIn,
    val org: Org,
    val server: ServerJson,
    val console: String,
    val consoleToken: Boolean,
) {
    /** `GET` [path] in the organisation, or the spec's exit code; [notFound] is said on a 404. */
    fun read(path: String, notFound: String): JsonObject =
        runBlocking { server.get(path, signed.token) }.orStop(signed.serverUrl, notFound)

    /**
     * `GET` [path], or null on a 404: a route another part of the product serves (rules, gate policy), shown when the
     * server has it and left out when it does not, rather than failing the whole command.
     */
    fun readIfServed(path: String): JsonObject? {
        val answer = runBlocking { server.get(path, signed.token) }
        val missing = answer is ServerAnswer.Failed && answer.status == NOT_FOUND
        return if (missing) null else answer.orStop(signed.serverUrl, "")
    }

    /**
     * Sends a change; a 403 to the device's token says what the device cannot do and where it is done instead, since
     * `ostrom login`'s token never holds an organisation's write scopes (S2-04).
     */
    fun write(method: String, path: String, body: JsonObject?, notFound: String): JsonObject {
        val answer = runBlocking { server.call(method, path, body, signed.token, WRITE_MILLIS) }
        if (answer is ServerAnswer.Failed && answer.status == FORBIDDEN && !consoleToken) {
            throw PrintMessage(
                "${answer.message}\nThe token of ostrom login cannot change ${org.slug}: do it on the console " +
                    "($console), or set OSTROM_TOKEN to a token an owner or admin made there.",
                ExitCode.REFUSED.code,
                printError = true,
            )
        }
        return answer.orStop(signed.serverUrl, notFound)
    }

    private companion object {
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val WRITE_MILLIS = 15_000L
    }
}

/**
 * The signed-in device and the organisation it acts in: [wanted] (`--org`), else `OSTROM_ORG`, else the one `ostrom
 * init` kept, else the person's only organisation. [choose] picks among several (`ostrom init` asks); without it,
 * several organisations are a usage error naming them.
 */
internal fun CliDependencies.orgAccess(
    serverOption: String?,
    wanted: String?,
    choose: ((List<Org>) -> Org)? = null,
): OrgAccess {
    val signed = signedIn(serverOption)
    val kept = keptOrg(wanted)
    val orgs = memberOrgs(signed.server, signed.serverUrl, signed.token)
    val console = consoleOf(this, signed.serverUrl)
    val org = if (kept == null) {
        onlyOrChosen(orgs, choose, "@${signed.login} is not a member of an organisation on ${signed.serverUrl} yet. " +
            "Create one on the console ($console), or ask an admin of your team to run: ostrom invite <your e-mail>")
    } else {
        named(orgs, kept)
    }
    val consoleToken = environment[LaunchVariables.LAUNCH_ID].isNullOrEmpty() &&
        !environment[LaunchVariables.DEVICE_TOKEN].isNullOrEmpty()
    return OrgAccess(signed, org, ServerJson(ApiServerTransport(api(signed.serverUrl), org.id)), console, consoleToken)
}

/** The organisations the caller is a member of (`GET /api/v1/orgs`). */
internal fun memberOrgs(server: ServerJson, serverUrl: String, token: String): List<Org> =
    runBlocking { server.get(ORGS, token) }.orStop(serverUrl, "No organisations here.")
        .list("items").filterIsInstance<JsonObject>().mapNotNull(::orgOf)

/** The organisation [kept] names, by id or short name, among [orgs]; one the person is not in is not found (66). */
internal fun named(orgs: List<Org>, kept: String): Org =
    orgs.firstOrNull { it.id == kept || it.slug == kept } ?: throw unknownOrg(kept, orgs)

/**
 * The only organisation in [orgs], else the one [choose] picks. None stops with [noneMessage] (66); several without
 * [choose] are a usage error (64).
 */
private fun onlyOrChosen(orgs: List<Org>, choose: ((List<Org>) -> Org)?, noneMessage: String): Org = when {
    orgs.size == 1 -> orgs.single()
    orgs.isEmpty() -> throw stopWith(ExitCode.NOT_FOUND, noneMessage)
    choose != null -> choose(orgs)
    else -> throw stopWith(
        ExitCode.USAGE,
        "You are a member of ${orgs.size} organisations (${slugs(orgs)}): add --org <name>, or run ostrom init " +
            "to choose one.",
    )
}

private fun unknownOrg(kept: String, orgs: List<Org>) = stopWith(
    ExitCode.NOT_FOUND,
    "You are not a member of an organisation named \"$kept\"" +
        if (orgs.isEmpty()) "." else ". Yours: ${slugs(orgs)}.",
)

private fun slugs(orgs: List<Org>) = orgs.joinToString(", ") { it.slug }

private fun orgOf(view: JsonObject): Org? {
    val id = view.text("id") ?: return null
    val slug = view.text("slug") ?: id
    return Org(id, slug, view.text("name") ?: slug, view.text("role") ?: "member")
}

/**
 * The repository the working folder is in, named as `ostrom <agent>` names it: `OSTROM_REPO`, else `owner/name` of its
 * `origin`, else the folder of the git top; null outside a git repository.
 */
internal fun CliDependencies.currentRepo(): String? =
    environment[LaunchVariables.REPO]?.takeIf { it.isNotEmpty() } ?: gitTopName()

private fun CliDependencies.gitTopName(): String? {
    val top = capture.capture(listOf("git", "-C", workingDirectory(), "rev-parse", "--show-toplevel"), GIT_MILLIS)
    if (top !is CaptureResult.Exited || top.code != 0) return null
    val folder = top.output.trim()
    val origin = capture.capture(listOf("git", "-C", folder, "remote", "get-url", "origin"), GIT_MILLIS)
    val url = if (origin is CaptureResult.Exited && origin.code == 0) origin.output else ""
    return RemoteUrls.ownerName(url)
        ?: folder.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { null }
}

/** The longest `git rev-parse` may take. */
private const val GIT_MILLIS = 5_000

/** `GET`: the caller's organisations (served). */
private const val ORGS = "/api/v1/orgs"
