// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.BuildInfo
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerKeyPins
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import okio.Path.Companion.toPath

/**
 * `ostrom status [--json]`: who, which organization, this repository's harness, the yeses waiting, this device
 * (CLI.md § `ostrom status`). When Ostrom is unreachable or fails (5xx), only the device's line, and 69; `--json`
 * stays JSON, with the same keys, null for what is not known.
 */
internal class StatusCommand(private val dependencies: CliDependencies) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Everything about you, this repository and this device"

    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signed = dependencies.signedIn(server)
        val host = signed.serverUrl.substringAfter("://").substringBefore('/')
        val kid = pinnedKid(dependencies, dependencies.directory(), signed.serverUrl)
        val device = "ostrom ${BuildInfo.VERSION} · " + if (kid == null) "no key pinned yet" else "key $kid pinned"
        val me = runBlocking { signed.server.get(ME, signed.token) }
        val failed = me as? ServerAnswer.Failed
        val status = failed?.status
        if (failed != null && (status == null || status >= SERVER_ERROR)) {
            val known = Facts(JsonObject(emptyMap()), signed.login, host, null, null, null)
            down(failed, known, signed.serverUrl, kid, device)
        }
        val caller = me.orStop(signed.serverUrl, "No account on ${signed.serverUrl}.")
        val repository = repositoryOf(dependencies)
        val (harness, waiting) = runBlocking {
            val pack = repository?.let { signed.server.get("$PACKAGE${Routes.encode(it)}", signed.token) }
            // a revoked package is one the launcher refuses: no harness is in force
            val live = (pack as? ServerAnswer.Ok)?.let { ok -> ok.body.takeIf { it["revoked"] != JsonPrimitive(true) } }
            val version = live?.text("version")
            version to waitingOf(signed)
        }
        val facts = Facts(caller, signed.login, host, repository, harness, waiting)
        dependencies.console.out(if (json) facts.json(signed.serverUrl, kid) + "\n" else facts.text(device))
    }

    /** Ostrom did not answer: the device's line (or the JSON of what is known), what happened, and exit 69. */
    private fun down(me: ServerAnswer.Failed, facts: Facts, serverUrl: String, kid: String?, device: String): Nothing {
        val out = if (json) facts.json(serverUrl, kid) + "\n" else "This device $device\n"
        dependencies.console.out(out)
        val why = if (me.status == null) "is unreachable" else "answers HTTP ${me.status}"
        dependencies.console.err("! Ostrom $why: server lines not shown.\n")
        throw ProgramResult(ExitCode.UNREACHABLE.code)
    }

    /** The requests waiting for this developer's yes, every page of the inbox (as `ostrom inbox`), or null. */
    private suspend fun waitingOf(signed: SignedIn): Int? {
        var count = 0
        var cursor: String? = null
        var page = 0
        do {
            val before = if (cursor == null) "" else "&before=${Routes.encode(cursor)}"
            val answer = signed.server.get("$INBOX$before", signed.token) as? ServerAnswer.Ok ?: return null
            count += answer.body.list("items").size
            cursor = answer.body.text("next")?.takeIf { it.isNotEmpty() }
            page++
        } while (cursor != null && page < MAX_PAGES)
        return count
    }

    /** What status shows, read from the server's answers. */
    private class Facts(
        caller: JsonObject,
        signedInAs: String,
        val host: String,
        val repository: String?,
        val harness: String?,
        val waiting: Int?,
    ) {
        // the contract's Me names the developer under `dev`; the Profile the server answers today, at the top
        val login: String = caller.child("dev")?.text("login") ?: caller.text("login") ?: signedInAs
        val organization: String? = caller.child("organization")?.text("name")
        val role: String? = caller.child("member")?.text("role")

        fun text(device: String): String {
            val where = if (organization == null) host else "$organization ($host)"
            val who = listOfNotNull("@$login", where, role).joinToString(" · ")
            val repo = when {
                repository == null -> "not in a git repository"
                harness == null -> "$repository · no harness yet"
                else -> "$repository · harness $harness"
            }
            val yes = when (waiting) {
                null -> "unknown"
                0 -> "nothing waits for your yes"
                1 -> "1 request needs your yes (ostrom inbox)"
                else -> "$waiting requests need your yes (ostrom inbox)"
            }
            return "Signed in   $who\nRepository  $repo\nWaiting     $yes\nThis device $device\n"
        }

        /** Always the same keys (CLI.md principle 6): what is not known is null, never missing. */
        fun json(serverUrl: String, kid: String?): String = JsonObject(
            mapOf(
                "login" to JsonPrimitive(login),
                "organization" to JsonPrimitive(organization),
                "role" to JsonPrimitive(role),
                "server" to JsonPrimitive(serverUrl),
                "repository" to JsonPrimitive(repository),
                "harness" to JsonPrimitive(harness),
                "waiting" to JsonPrimitive(waiting),
                "version" to JsonPrimitive(BuildInfo.VERSION),
                "pinnedKey" to JsonPrimitive(kid),
            ),
        ).toString()
    }

    private companion object {
        const val ME = "/api/v1/auth/me"
        const val PACKAGE = "${Routes.PACKAGE}?repo="
        const val INBOX = "/api/v1/approvals?mine=true&status=pending"
        const val SERVER_ERROR = 500
        const val MAX_PAGES = 20
    }
}

/** The kid of the signing key this device pinned for [serverUrl], or null. */
internal fun pinnedKid(dependencies: CliDependencies, directory: String, serverUrl: String): String? =
    ServerKeyPins.all(dependencies.files.read(ServerKeyPins.path(directory))).child(serverUrl)?.text("kid")

/**
 * The repository `ostrom` runs in, named as a launch names it: `OSTROM_REPO` when set, else the folder of the git top;
 * null outside one.
 */
internal fun repositoryOf(dependencies: CliDependencies): String? {
    val named = dependencies.environment[LaunchVariables.REPO]
    return if (named.isNullOrEmpty()) gitTopOf(dependencies) else named
}

/** The folder name of the git top `ostrom` runs under, or null. */
private fun gitTopOf(dependencies: CliDependencies): String? {
    var folder: Path? = dependencies.workingDirectory().toPath()
    while (folder != null) {
        if (dependencies.files.exists((folder / ".git").toString())) return folder.name
        folder = folder.parent
    }
    return null
}
