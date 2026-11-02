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
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.ServerKeyPins
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `ostrom status [--json]`: who, in which organisation, this repository's harness, the yeses waiting, this device
 * (CLI.md § `ostrom status`). The organisation is the one the CLI acts in (`OSTROM_ORG`, the one `ostrom init` kept,
 * else the person's only one), and the harness and the yeses are read in it. When Ostrom is unreachable or fails
 * (5xx), what could be read is shown, the rest as unknown, and the exit code is 69; `--json` stays JSON, with the same
 * keys, null for what is not known.
 */
internal class StatusCommand(private val dependencies: CliDependencies) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Everything about you, this repository and this device"

    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signed = dependencies.signedIn(server)
        val serverUrl = signed.serverUrl.replace(USER_INFO, "//")
        val kid = pinnedKid(dependencies, dependencies.directory(), signed.serverUrl)
        val device = Device(serverUrl, kid)
        val me = runBlocking { signed.server.get(ME, signed.token) }
        failing(me)?.let { down(it, Facts(signed.login, null, device)) }
        val caller = me.orStop(signed.serverUrl, "No account on ${signed.serverUrl}.")
        // the contract's Me names the developer under `dev`; the Profile the server answers today, at the top
        val login = caller.child("dev")?.text("login") ?: caller.text("login") ?: signed.login
        val orgs = runBlocking { signed.server.get(ORGS, signed.token) }
        val org = (orgs as? ServerAnswer.Ok)?.let { organisationOf(it.body) }
        val scoped = org?.let { ServerJson(ApiServerTransport(dependencies.api(signed.serverUrl), it.id)) }
        val repository = dependencies.currentRepo()
        val (harness, waiting) = runBlocking {
            coroutineScope {
                val harness = async { harnessOf(scoped ?: signed.server, signed.token, repository) }
                val waiting = async { waitingOf(scoped ?: signed.server, signed.token) }
                harness.await() to waiting.await()
            }
        }
        val facts = Facts(login, org, device, repository, harness, waiting)
        dependencies.console.out(if (json) facts.json() + "\n" else facts.text())
        val failed = listOfNotNull(failing(orgs), harness.down, waiting.down).firstOrNull()
        failed?.let { said(it, "some lines are unknown") }
    }

    /** Ostrom did not answer: the device's line (or the JSON of what is known), what happened, and exit 69. */
    private fun down(failed: ServerAnswer.Failed, facts: Facts): Nothing {
        dependencies.console.out(if (json) facts.json() + "\n" else "This device ${facts.device.line}\n")
        said(failed, "server lines not shown")
    }

    private fun said(failed: ServerAnswer.Failed, what: String): Nothing {
        val status = failed.status
        val why = if (status == null) "is unreachable" else "answers HTTP $status"
        dependencies.console.err("! Ostrom $why: $what.\n")
        throw ProgramResult(ExitCode.UNREACHABLE.code)
    }

    /** The organisation the CLI acts in among the caller's (`GET /api/v1/orgs`), or null when none is chosen. */
    private fun organisationOf(body: JsonObject): Org? {
        val orgs = body.list("items").filterIsInstance<JsonObject>().mapNotNull { view ->
            val id = view.text("id")
            id?.let { Org(it, view.text("slug") ?: it, view.text("name") ?: it, view.text("role") ?: MEMBER) }
        }
        val kept = dependencies.keptOrg(null)
        return if (kept == null) orgs.singleOrNull() else orgs.firstOrNull { it.id == kept || it.slug == kept }
    }

    /** The version of the harness in force in [repository]; known as none on a 404 or a revoked package. */
    private suspend fun harnessOf(server: ServerJson, token: String, repository: String?): Read<String> {
        repository ?: return Read(null, known = true)
        val answer = server.get("$PACKAGE${Routes.encode(repository)}", token)
        return when (answer) {
            is ServerAnswer.Ok -> {
                // a revoked package is one the launcher refuses: no harness is in force
                val live = answer.body.takeIf { it["revoked"] != JsonPrimitive(true) }
                Read(live?.text("version"), known = true)
            }
            is ServerAnswer.Failed -> Read(null, known = answer.status == NOT_FOUND, down = failing(answer))
        }
    }

    /** The requests waiting for this developer's yes, every page of the inbox as `ostrom inbox` reads it. */
    private suspend fun waitingOf(server: ServerJson, token: String): Read<Waiting> {
        var count = 0
        var cursor: String? = null
        var page = 0
        do {
            val before = cursor?.let { "&before=${Routes.encode(it)}" }.orEmpty()
            val answer = server.get("$INBOX$before", token)
            if (answer !is ServerAnswer.Ok) return Read(null, known = false, down = failing(answer))
            val body = answer.body
            count += body.list("items").size
            cursor = body.text("next")?.takeIf { it.isNotEmpty() }
            page++
        } while (cursor != null && page < MAX_PAGES)
        return Read(Waiting(count, complete = cursor == null), known = true)
    }

    private companion object {
        const val ME = "/api/v1/auth/me"
        const val ORGS = "/api/v1/orgs"
        const val PACKAGE = "${Routes.PACKAGE}?repo="
        const val INBOX = "/api/v1/approvals?mine=true&status=pending"
        const val SERVER_ERROR = 500
        const val NOT_FOUND = 404
        const val MEMBER = "member"
        const val MAX_PAGES = 20

        /** A failure of the server itself (unreachable, 5xx), or null for an answer or a refusal. */
        fun failing(answer: ServerAnswer): ServerAnswer.Failed? =
            (answer as? ServerAnswer.Failed)?.takeIf { (it.status ?: SERVER_ERROR) >= SERVER_ERROR }
    }
}

/** A line status read: its [value], whether it is [known], and the server's failure that left it unknown. */
private class Read<T>(val value: T?, val known: Boolean, val down: ServerAnswer.Failed? = null)

/** The yeses waiting: [count], and whether every page was read ([complete]). */
private class Waiting(val count: Int, val complete: Boolean)

/** This device: the server it is signed in to (without a user or password) and the key it pinned for it. */
private class Device(val serverUrl: String, val kid: String?) {
    val host: String = serverUrl.substringAfter("://").substringBefore('/')
    val line: String = "ostrom ${BuildInfo.VERSION} · " + if (kid == null) "no key pinned yet" else "key $kid pinned"
}

/** What status shows. */
private class Facts(
    val login: String,
    val org: Org?,
    val device: Device,
    val repository: String? = null,
    val harness: Read<String> = Read(null, known = false),
    val waiting: Read<Waiting> = Read(null, known = false),
) {
    fun text(): String {
        val where = if (org == null) device.host else "${org.name} (${device.host})"
        val who = listOfNotNull("@$login", where, org?.role).joinToString(" · ")
        val repo = when {
            repository == null -> "not in a git repository"
            !harness.known -> "$repository · harness unknown"
            harness.value == null -> "$repository · no harness yet"
            else -> "$repository · harness ${harness.value}"
        }
        val count = waiting.value
        val more = if (count?.complete == false) "+" else ""
        val yes = when (count?.count) {
            null -> "unknown"
            0 -> "nothing waits for your yes"
            1 -> "1$more request needs your yes (ostrom inbox)"
            else -> "${count.count}$more requests need your yes (ostrom inbox)"
        }
        return "Signed in   $who\nRepository  $repo\nWaiting     $yes\nThis device ${device.line}\n"
    }

    /** Always the same keys (CLI.md principle 6): what is not known is null, never missing. */
    fun json(): String = JsonObject(
        mapOf(
            "login" to JsonPrimitive(login),
            "organization" to JsonPrimitive(org?.name),
            "role" to JsonPrimitive(org?.role),
            "server" to JsonPrimitive(device.serverUrl),
            "repository" to JsonPrimitive(repository),
            "harness" to JsonPrimitive(harness.value),
            "waiting" to JsonPrimitive(waiting.value?.count),
            "waitingComplete" to JsonPrimitive(waiting.value?.complete),
            "version" to JsonPrimitive(BuildInfo.VERSION),
            "pinnedKey" to JsonPrimitive(device.kid),
        ),
    ).toString()
}

/** The kid of the signing key this device pinned for [serverUrl], or null. */
internal fun pinnedKid(dependencies: CliDependencies, directory: String, serverUrl: String): String? =
    ServerKeyPins.all(dependencies.files.read(ServerKeyPins.path(directory))).child(serverUrl)?.text("kid")
