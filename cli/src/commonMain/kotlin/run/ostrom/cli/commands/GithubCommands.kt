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
import run.ostrom.cli.launcher.TypedAnswer
import run.ostrom.cli.launcher.typedAnswer
import run.ostrom.cli.launcher.CliJson
import run.ostrom.core.api.models.GitHubDeviceLinkStatus
import run.ostrom.core.api.models.GitHubLink
import run.ostrom.core.api.models.GitHubLinkPage
import run.ostrom.core.api.models.GitHubLinkState
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking

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
    val link = access.typed("No GitHub link here.") { githubLink.get() }
    if (json) {
        console.data(CliJson.encodeToString(GitHubLink.serializer(), link) + "\n")
        return
    }
    val login = link.login.orEmpty()
    val linkedAt = link.linkedAt
    val since = if (linkedAt == null) "" else " since ${linkedAt.take(DAY)}"
    val said = when (link.state) {
        GitHubLinkState.LINKED -> "✓ Linked to GitHub as @$login$since."
        GitHubLinkState.RECONNECT_REQUIRED ->
            "GitHub no longer accepts the link of @$login: link again with `ostrom github link`."
        else -> if (!link.appConfigured) {
            "No GitHub account linked, and this server's GitHub App cannot link accounts yet: ask an admin."
        } else {
            "No GitHub account linked. Link yours with `ostrom github link`."
        }
    }
    console.out("$said\n")
}

/** The statuses of a poll that did not get to GitHub: none (unreachable) and 503. */
private val LOST = setOf<Int?>(null, 503)

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
        val page = access.typed("No GitHub links here.") { githubLink.list() }
        if (json) {
            dependencies.console.data(CliJson.encodeToString(GitHubLinkPage.serializer(), page) + "\n")
            return
        }
        val items = page.items
        val count = if (items.size == 1) "1 member" else "${items.size} members"
        dependencies.console.out("${access.org.name} · $count with a GitHub link\n")
        items.forEach { item ->
            val login = if (item.login == null) "-" else "@${item.login}"
            val state = item.state.value
            dependencies.console.out("  ${item.memberId}  $login  $state\n")
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
            access.typed("No GitHub link here.") { githubLink.unlink() }
            dependencies.console.out(
                "✓ Your GitHub link is removed. GitHub keeps the App authorized until you revoke it there.\n",
            )
        } else {
            val gone = "No member $target in ${access.org.slug}."
            access.typed(gone) { githubLink.unlinkMember(target) }
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
        val started = access.typed("Cloud sessions are off on this server.") { githubLink.startDevice() }
        val id = started.id
        val page = started.verificationUri
        val code = started.userCode
        if (id.isEmpty() || page.isEmpty() || code.isEmpty()) {
            throw stopWith(ExitCode.UNREACHABLE, "Ostrom answered without a code to enter. Try again later.")
        }
        val console = dependencies.console
        val opened = page.startsWith(GITHUB_PAGES) && dependencies.openUrl(page)
        console.out((if (opened) "Opened $page" else "Open $page") + " and enter the code: $code\n")
        console.out("Waiting for you to authorize on GitHub (Ctrl-C to stop)...\n")
        val link = wait(access, id, started.interval.coerceIn(1, MAX_SECONDS))
        val name = link?.login
        val login = if (name == null) "your account" else "@$name"
        console.out("✓ Linked to GitHub as $login.\n")
    }

    /** Polls until the attempt ends; the link when it is linked, else stops with the reason. */
    private fun wait(access: OrgAccess, id: String, first: Int): GitHubLink? {
        var seconds = first
        var waited = 0L
        while (true) {
            access.pause(seconds * MILLIS)
            waited += seconds * MILLIS
            val answer = runBlocking { typedAnswer { access.writeClient.githubLink.pollDevice(id) } }
            if (answer is TypedAnswer.Failed && !transient(answer.failure)) {
                throw answer.failure.refusal(access.signed.serverUrl, OVER)
            }
            val poll = (answer as? TypedAnswer.Ok)?.value
            if (poll?.status == GitHubDeviceLinkStatus.LINKED) return poll.link
            val gaveUp = if (waited > GIVE_UP_MILLIS) stopWith(ExitCode.INCOMPLETE, EXPIRED) else null
            (ended(poll?.status) ?: gaveUp)?.let { throw it }
            seconds = (poll?.interval ?: seconds).coerceIn(1, MAX_SECONDS)
        }
    }

    /** What a denied or expired attempt says, null while it goes on. */
    private fun ended(status: GitHubDeviceLinkStatus?) = when (status) {
        GitHubDeviceLinkStatus.DENIED -> stopWith(ExitCode.INCOMPLETE, "ostrom: GitHub authorization was denied.")
        GitHubDeviceLinkStatus.EXPIRED -> stopWith(ExitCode.INCOMPLETE, EXPIRED)
        else -> null
    }

    /** A poll that did not reach GitHub or the server: the next one may. */
    private fun transient(answer: ServerAnswer.Failed) = answer.status in LOST

    private companion object {
        const val GITHUB_PAGES = "https://github.com/"
        const val MAX_SECONDS = 60
        const val MILLIS = 1_000L

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
        val answer = runBlocking { typedAnswer { access.writeClient.cloudSessions.setGitHubAccess(session, mode) } }
        if (answer is TypedAnswer.Failed && answer.failure.type == "session_not_paused") {
            throw stopWith(
                ExitCode.USAGE,
                "Pause the session first: a running agent keeps the token it already holds until it expires.",
            )
        }
        if (answer is TypedAnswer.Failed) {
            throw answer.failure.refusal(access.signed.serverUrl, "No cloud session $session.")
        }
        dependencies.console.out("✓ GitHub access of session $session is now $mode.\n")
    }
}
