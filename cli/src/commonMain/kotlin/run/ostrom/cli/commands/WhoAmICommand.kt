// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val ME = "/api/v1/auth/me"
private const val ORGANISATIONS = "/api/v1/orgs"

/**
 * `ostrom whoami [--json]`, as TeamCode's `teamcode whoami`: the login this device is signed in as (`GET
 * /api/v1/auth/me`), then each organisation it is a member of with its role. `--json` prints the server's `Me` with the
 * organisations under `organizations`.
 */
internal class WhoAmICommand(private val dependencies: CliDependencies) : CliktCommand(name = "whoami") {
    override fun help(context: Context) = "Say who this device is signed in as, and your role in each organisation"

    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val signed = dependencies.signedIn(server)
        val me = runBlocking { signed.server.get(ME, signed.token) }.orStop(signed.serverUrl, "No account here.")
        val orgs = runBlocking { signed.server.get(ORGANISATIONS, signed.token) }
            .orStop(signed.serverUrl, "No organisations here.").list("items").filterIsInstance<JsonObject>()
        val console = dependencies.console
        if (json) {
            console.data(JsonObject(me + ("organizations" to JsonArray(orgs))).toString() + "\n")
            return
        }
        console.out((me.child("dev")?.text("login") ?: signed.login) + "\n")
        orgs.forEach { console.out("${it.text("slug") ?: it.text("id")}  ${it.text("role") ?: "member"}\n") }
        if (orgs.isEmpty()) console.err("Not a member of an organisation on ${signed.serverUrl} yet.\n")
    }
}
