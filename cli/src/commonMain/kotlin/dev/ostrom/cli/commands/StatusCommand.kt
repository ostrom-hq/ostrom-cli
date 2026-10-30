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
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okio.Path
import okio.Path.Companion.toPath

/**
 * `ostrom status [--json]`: who, which organization, this repository's harness, the yeses waiting, this device
 * (CLI.md § `ostrom status`). Offline, only the device's line, and 69.
 */
internal class StatusCommand(private val dependencies: CliDependencies) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Everything about you, this repository and this device"

    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signed = dependencies.signedIn(server)
        val host = signed.serverUrl.substringAfter("://").substringBefore('/')
        val kid = pinnedKid(dependencies, signed.directory, signed.serverUrl)
        val device = "ostrom ${BuildInfo.VERSION} · " + if (kid == null) "no key pinned yet" else "key $kid pinned"
        val me = runBlocking { signed.server.get(ME, signed.token) }
        if (me is ServerAnswer.Failed && me.status == null) {
            dependencies.console.out("This device $device\n")
            dependencies.console.err("! Ostrom is unreachable: server lines not shown.\n")
            throw ProgramResult(ExitCode.UNREACHABLE.code)
        }
        val caller = me.orStop(signed.serverUrl, "No account on ${signed.serverUrl}.")
        val repository = repositoryOf(dependencies)
        val (harness, waiting) = runBlocking {
            val pack = repository?.let { signed.server.get("$PACKAGE${Routes.encode(it)}", signed.token) }
            val inbox = signed.server.get(INBOX, signed.token)
            val version = (pack as? ServerAnswer.Ok)?.let { it.body.text("version") }
            version to (inbox as? ServerAnswer.Ok)?.let { it.body.list("items").size }
        }
        val facts = Facts(caller, signed.login, host, repository, harness, waiting)
        dependencies.console.out(if (json) facts.json(signed.serverUrl, kid) + "\n" else facts.text(device))
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
        val login: String = caller.child("dev")?.text("login") ?: signedInAs
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

        fun json(serverUrl: String, kid: String?): String = objectOf(
            "login" to login,
            "organization" to organization,
            "role" to role,
            "server" to serverUrl,
            "repository" to repository,
            "harness" to harness,
            "waiting" to waiting,
            "version" to BuildInfo.VERSION,
            "pinnedKey" to kid,
        ).toString()
    }

    private companion object {
        const val ME = "/api/v1/auth/me"
        const val PACKAGE = "${Routes.PACKAGE}?repo="
        const val INBOX = "/api/v1/approvals?mine=true&status=pending"
    }
}

/** The kid of the signing key this device pinned for [serverUrl], or null. */
internal fun pinnedKid(dependencies: CliDependencies, directory: String, serverUrl: String): String? =
    parseObject(dependencies.files.read(join(directory, "server-keys.json")))?.child(serverUrl)?.text("kid")

/** The repository `ostrom` runs in, named as a launch names it (the folder of the git top), or null outside one. */
internal fun repositoryOf(dependencies: CliDependencies): String? {
    var folder: Path? = dependencies.workingDirectory().toPath()
    while (folder != null) {
        if (dependencies.files.exists((folder / ".git").toString())) return folder.name
        folder = folder.parent
    }
    return null
}
