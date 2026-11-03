// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.login.AdminTokens
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.Console
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
 * @param dependencies The tokens of `ostrom login --admin`, and the clock, the terminal and the pause a governance
 *   confirmation is waited on with.
 */
internal class OrgAccess(
    val signed: SignedIn,
    val org: Org,
    val server: ServerJson,
    val console: String,
    val consoleToken: Boolean,
    private val dependencies: CliDependencies,
) {
    /** The tokens of `ostrom login --admin`, read only by a governance write. */
    val admin: AdminTokens = dependencies.adminTokens(dependencies.directory())

    /** The time, in milliseconds since the epoch. */
    val clock: () -> Long get() = dependencies.clock

    /** Where a governance confirmation's link and wait are said. */
    val terminal: Console get() = dependencies.console

    /** Waits [millis] (between two polls of a governance confirmation). */
    fun pause(millis: Long) = runBlocking { dependencies.sleep(millis) }

    /** `GET` [path] in the organisation, or the spec's exit code; [notFound] is said on a 404. */
    fun read(path: String, notFound: String): JsonObject =
        runBlocking { server.get(path, signed.token) }.also(::lackedRight).orStop(signed.serverUrl, notFound)

    /**
     * `GET` [path] that a governance write reads before it is made (the model policy, whose default and members'
     * lists `ostrom policy set` keeps): with the token the write goes with, the admin token while it is valid, as the
     * device's token cannot read it (`inference:read`), and refused as the write would be. Without one, as [read].
     */
    fun readToGovern(path: String, notFound: String): JsonObject {
        // OSTROM_TOKEN, set on purpose, is never swapped for the admin token (S2-52)
        val elevated = if (consoleToken) null else admin.valid(signed.serverUrl)
        if (elevated == null) return read(path, notFound)
        val answer = runBlocking { server.get(path, elevated) }
        if (answer is ServerAnswer.Failed) refused(answer, true, elevated)
        return answer.orStop(signed.serverUrl, notFound)
    }

    /**
     * `GET` [path], or null on a 404: a route another part of the product serves (rules, gate policy), shown when the
     * server has it and left out when it does not, rather than failing the whole command.
     */
    fun readIfServed(path: String): JsonObject? {
        val answer = runBlocking { server.get(path, signed.token) }.also(::lackedRight)
        val missing = answer is ServerAnswer.Failed && answer.status == NOT_FOUND
        return if (missing) null else answer.orStop(signed.serverUrl, "")
    }

    /**
     * Sends a change; a 403 to the device's token says what the device cannot do and where it is done instead, since
     * `ostrom login`'s token never holds an organisation's write scopes (S2-04).
     *
     * A [governance] write (the policy, its rules, the harness) goes with the token of `ostrom login --admin` while it
     * is valid: no other token writes the governance (S2-52, decision of 02/11/2026). Without one, a 403 says to run
     * it; a 401 to it (revoked, or expired on the server) forgets it and says the same.
     *
     * Made with a CLI token, a governance write is held by the server until an owner or an admin confirms it on the
     * console (202 `GovernanceConfirmationPending`, audit H1): any write held so is waited on,
     * the link said, and the write's result is what the confirmation returns (77 refused, 75 not answered in time).
     */
    fun write(
        method: String,
        path: String,
        body: JsonObject?,
        notFound: String,
        governance: Boolean = false,
    ): JsonObject {
        // OSTROM_TOKEN, set on purpose, is the token the change goes with: never swapped for the admin token
        val elevated = if (governance && !consoleToken) admin.valid(signed.serverUrl) else null
        val token = elevated ?: signed.token
        val answer = runBlocking { server.call(method, path, body, token, WRITE_MILLIS) }
        if (answer is ServerAnswer.Failed) refused(answer, governance, elevated)
        val done = answer.orStop(signed.serverUrl, notFound)
        // a write the server holds for a confirmation is waited on, whichever: nothing was written yet
        val confirmation = done.text("confirmationId") ?: return done
        return ConfirmationWait(this, token, elevated != null).await(done, confirmation)
    }

    /**
     * Stops on a read the device's token lacks a right for that `ostrom login` gives today: a token made before the
     * server added it (`rules:read`, server#160) gets it by signing in again. A right `ostrom login` never gives, a
     * token set in `OSTROM_TOKEN`, a command in an agent's session, any other refusal: left to the server's own words.
     */
    private fun lackedRight(answer: ServerAnswer) {
        // only the device's own token, outside an agent's session (where ostrom login does not run), signs in again
        val signsInAgain = !consoleToken && dependencies.environment[LaunchVariables.LAUNCH_ID].isNullOrEmpty()
        if (answer !is ServerAnswer.Failed || answer.status != FORBIDDEN || !signsInAgain) return
        val sentence = answer.message
        val right = sentence.removePrefix(LACKS).removeSuffix(".")
        if (!sentence.startsWith(LACKS) || !sentence.endsWith(".") || right !in LOGIN_READ_RIGHTS) return
        throw stopWith(ExitCode.REFUSED, "${answer.message}\n$SIGN_IN_AGAIN")
    }

    /** Stops on a refusal that has more to say than the server's message: which token the change takes, and where. */
    private fun refused(answer: ServerAnswer.Failed, governance: Boolean, elevated: String?) {
        val message = when {
            elevated != null -> if (answer.status == UNAUTHORIZED) {
                admin.forget(signed.serverUrl)
                throw stopWith(ExitCode.SIGN_IN_REQUIRED, "Your admin token is no longer valid. $ELEVATE")
            } else {
                null
            }
            answer.status != FORBIDDEN -> null
            governance -> "Changing the policy or the harness of ${org.slug} takes an admin token. $ELEVATE"
            consoleToken -> null
            else -> "The token of ostrom login cannot change ${org.slug}: do it on the console ($console), or set " +
                "OSTROM_TOKEN to a token an owner or admin made there."
        }
        if (message != null) throw stopWith(ExitCode.REFUSED, "${answer.message}\n$message")
    }

    private companion object {
        const val ELEVATE =
            "Run ostrom login --admin: an owner or admin approves it in the browser, for 8 hours at most."
        const val FORBIDDEN = 403

        /** The server's refusal of a right the token lacks (`requireScope`), before the right's name. */
        const val LACKS = "This credential does not allow "

        const val SIGN_IN_AGAIN =
            "This device's token is older than this right: run `ostrom login` again for a new one."
        const val NOT_FOUND = 404
        const val WRITE_MILLIS = 15_000L
    }
}

/**
 * The read rights the token of `ostrom login` holds: the `:read` entries of `CLI_SCOPES` in ostrom-server's
 * `modules/identity` (`Policies.kt`), to follow when the server changes them.
 */
private val LOGIN_READ_RIGHTS = setOf(
    "profile:read", "orgs:read", "workflows:read", "receipts:read", "gates:read", "mcp:read", "rules:read",
)

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
    val server = ServerJson(ApiServerTransport(api(signed.serverUrl), org.id))
    return OrgAccess(signed, org, server, console, consoleToken, this)
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
    return RemoteUrls.origin(folder, capture, GIT_MILLIS) ?: RemoteUrls.folderName(folder).ifEmpty { null }
}

/** The longest `git rev-parse` may take. */
private const val GIT_MILLIS = 5_000

/** 401, an Int? like the answer's status: no null test for a status the refusals always have. */
@Suppress("MayBeConst") // a const cannot be an Int?, and an Int would bring the null test back
private val UNAUTHORIZED: Int? = 401

/** `GET`: the caller's organisations (served). */
private const val ORGS = "/api/v1/orgs"
