// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private const val LINK = "/api/v1/github/link"
private const val LINKS = "/api/v1/github/links"
private const val WRITE_MILLIS = 15_000L

/** What a command of `ostrom github` says to a server that links no GitHub account. */
private const val NO_LINKS = "This Ostrom server does not link GitHub accounts: ask your admin to update it."

/** What `ostrom cloud github` says to a server that does not share a GitHub account with cloud sessions. */
private const val NO_SESSION_ACCESS =
    "This Ostrom server does not share a GitHub account with cloud sessions: ask your admin to update it."

/** `ostrom github`: the status of your link, unless a subcommand is given. */
internal class GithubCommand(private val dependencies: CliDependencies) :
    GroupCommand("github", "Link your GitHub account to your cloud sessions", "status", comingSoon = false) {
    override fun runDefault() = dependencies.githubStatus(org = null, server = null, json = false)
}

/** The org access of a GitHub command, after the server has said it links GitHub accounts. */
private fun CliDependencies.githubAccess(server: String?, org: String?): OrgAccess =
    orgAccess(server, org).also {
        requireFeature(it.signed.serverUrl, it.signed.token, ServerFeatures.GITHUB_LINK, NO_LINKS)
    }

/** A call that changes something, with the device's token, or the exit code and words the failure deserves. */
private fun OrgAccess.change(
    method: String,
    path: String,
    body: JsonObject?,
    notFound: String,
): JsonObject {
    val answer = runBlocking { server.call(method, path, body, signed.token, WRITE_MILLIS) }
    return answer.orStop(signed.serverUrl, notFound)
}

/** `ostrom github status`: the GitHub account your cloud sessions use, and whether it still works. */
internal class GithubStatusCommand(private val dependencies: CliDependencies) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Show the GitHub account your cloud sessions use"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() = dependencies.githubStatus(org, server, json)
}

private fun CliDependencies.githubStatus(org: String?, server: String?, json: Boolean) {
    val access = githubAccess(server, org)
    val link = access.read(LINK, "No GitHub link here.")
    if (json) {
        console.data("$link\n")
        return
    }
    val login = link.text("login").orEmpty()
    val since = link.text("linkedAt")?.take(DAY)?.let { " since $it" }.orEmpty()
    val said = when (link.text("state")) {
        "linked" -> "✓ Linked to GitHub as @$login$since."
        "reconnect_required" ->
            "GitHub no longer accepts the link of @$login: link again with `ostrom github link`."
        else -> if (link.flag("appConfigured") == false) {
            "No GitHub account linked, and this server's GitHub App cannot link accounts yet: ask an admin."
        } else {
            "No GitHub account linked. Link yours with `ostrom github link`."
        }
    }
    console.out("$said\n")
}

/** The whole number [key], or null when it is absent or not one. */
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** The length of `2026-11-13`, the day of an ISO timestamp. */
private const val DAY = 10

/** `ostrom github links`: the GitHub account of every member (owner or admin). */
internal class GithubLinksCommand(private val dependencies: CliDependencies) : CliktCommand(name = "links") {
    override fun help(context: Context) = "List the GitHub account of every member (admin)"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.githubAccess(server, org)
        val body = access.read(LINKS, "No GitHub links here.")
        if (json) {
            dependencies.console.data("$body\n")
            return
        }
        val items = body.list("items").filterIsInstance<JsonObject>()
        val count = if (items.size == 1) "1 member" else "${items.size} members"
        dependencies.console.out("${access.org.name} · $count with a GitHub link\n")
        items.forEach { item ->
            val login = item.text("login")?.let { "@$it" } ?: "-"
            val member = item.text("memberId").orEmpty()
            dependencies.console.out("  $member  $login  ${item.text("state").orEmpty()}\n")
        }
    }
}

/** `ostrom github unlink [--member ID]`: removes your GitHub link, or a member's (admin). */
internal class GithubUnlinkCommand(private val dependencies: CliDependencies) : CliktCommand(name = "unlink") {
    override fun help(context: Context) = "Remove your GitHub link, or a member's with --member (admin)"

    private val member by option("--member", help = "The member's id, to remove their link (owner or admin)")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        // an agent would cut the developer's cloud sessions off GitHub with the device's token
        dependencies.outsideSessions("github unlink")
        val access = dependencies.githubAccess(server, org)
        val target = member
        if (target == null) {
            access.change("DELETE", LINK, null, "No GitHub link here.")
            dependencies.console.out(
                "✓ Your GitHub link is removed. GitHub keeps the App authorized until you revoke it there.\n",
            )
        } else {
            val gone = "No member $target in ${access.org.slug}."
            access.change("DELETE", "$LINKS/${segment(target)}", null, gone)
            dependencies.console.out("✓ The GitHub link of member $target is removed.\n")
        }
    }
}

/**
 * `ostrom github link`: GitHub's device flow through Ostrom. The server starts it and exchanges the token: no token
 * reaches this terminal. The code and the page are said (and the page opened), then the attempt is polled at the pace
 * the server gives until it is linked, denied or expired.
 */
internal class GithubLinkCommand(private val dependencies: CliDependencies) : CliktCommand(name = "link") {
    override fun help(context: Context) = "Link your GitHub account (device code, entered on github.com)"

    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        // the link binds the person's GitHub account: only they, at their terminal
        dependencies.outsideSessions("github link")
        val access = dependencies.githubAccess(server, org)
        val started = access.change("POST", "$LINK/device", null, "Cloud sessions are off on this server.")
        val id = started.text("id").orEmpty()
        val page = started.text("verificationUri").orEmpty()
        val code = started.text("userCode").orEmpty()
        if (id.isEmpty() || page.isEmpty() || code.isEmpty()) {
            throw stopWith(ExitCode.UNREACHABLE, "Ostrom answered without a code to enter. Try again later.")
        }
        val console = dependencies.console
        val opened = page.startsWith(GITHUB_PAGES) && dependencies.openUrl(page)
        console.out((if (opened) "Opened $page" else "Open $page") + " and enter the code: $code\n")
        console.out("Waiting for you to authorize on GitHub (Ctrl-C to stop)...\n")
        val link = wait(access, id, (started.int("interval") ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS))
        val login = link.text("login")?.let { "@$it" } ?: "your account"
        console.out("✓ Linked to GitHub as $login.\n")
    }

    /** Polls until the attempt ends; the link when it is linked, else stops with the reason. */
    private fun wait(access: OrgAccess, id: String, first: Int): JsonObject {
        var seconds = first
        var waited = 0L
        while (waited <= GIVE_UP_MILLIS) {
            access.pause(seconds * MILLIS)
            waited += seconds * MILLIS
            val answer = runBlocking {
                access.server.call("POST", "$LINK/device/${segment(id)}", null, access.signed.token, WRITE_MILLIS)
            }
            if (answer is ServerAnswer.Failed && !transient(answer)) {
                throw answer.refusal(access.signed.serverUrl, OVER)
            }
            val poll = (answer as? ServerAnswer.Ok)?.body
            when (poll?.text("status")) {
                "linked" -> return poll.link()
                "denied" -> throw stopWith(ExitCode.INCOMPLETE, "ostrom: GitHub authorization was denied.")
                "expired" -> throw stopWith(ExitCode.INCOMPLETE, EXPIRED)
                else -> seconds = (poll?.int("interval") ?: seconds).coerceIn(1, MAX_SECONDS)
            }
        }
        throw stopWith(ExitCode.INCOMPLETE, EXPIRED)
    }

    /** A poll that did not reach GitHub or the server: the next one may. */
    private fun transient(answer: ServerAnswer.Failed) = answer.status == null || answer.status == UNAVAILABLE

    private fun JsonObject.link(): JsonObject = child("link") ?: JsonObject(emptyMap())

    private companion object {
        const val GITHUB_PAGES = "https://github.com/"
        const val DEFAULT_SECONDS = 5
        const val MAX_SECONDS = 60
        const val MILLIS = 1_000L
        const val UNAVAILABLE = 503

        /** GitHub ends a device code in 15 minutes: a server that never says so is not waited on for longer. */
        const val GIVE_UP_MILLIS = 20 * 60 * MILLIS
        const val OVER = "This link attempt is over: run ostrom github link again."
        const val EXPIRED = "ostrom: the code expired. Run ostrom github link again."
    }
}

/** `ostrom cloud`: your sessions in Ostrom's cloud. */
internal class CloudCommand : GroupCommand("cloud", "Manage your sessions in Ostrom's cloud", null, comingSoon = false)

/** `ostrom cloud github SESSION --access owner|shared|none`: who may use the session's GitHub token. */
internal class CloudGithubCommand(private val dependencies: CliDependencies) : CliktCommand(name = "github") {
    override fun help(context: Context) = "Choose who may use your GitHub account in a cloud session"

    private val session by argument("session", help = "The cloud session's id")
    private val mode by option(
        "--access",
        help = "owner (only you instruct the agent), shared (anyone who may use the session) or none (no GitHub token)",
    ).choice("owner", "shared", "none").required()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        // an agent would widen its own access to the developer's GitHub account
        dependencies.outsideSessions("cloud github")
        val access = dependencies.orgAccess(server, org)
        dependencies.requireFeature(
            access.signed.serverUrl, access.signed.token, ServerFeatures.RUNNERS_CLOUD_GITHUB, NO_SESSION_ACCESS,
        )
        val path = "/api/v1/cloud/sessions/${segment(session)}/github"
        val answer = runBlocking {
            access.server.call("PUT", path, objectOf("access" to mode), access.signed.token, WRITE_MILLIS)
        }
        if (answer is ServerAnswer.Failed && answer.type == "session_not_paused") {
            throw stopWith(
                ExitCode.USAGE,
                "Pause the session first: a running agent keeps the token it already holds until it expires.",
            )
        }
        answer.orStop(access.signed.serverUrl, "No cloud session $session.")
        dependencies.console.out("✓ GitHub access of session $session is now $mode.\n")
    }
}
