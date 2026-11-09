// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.cloud.CloudSite
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking

/** `ostrom attach <session>`: this terminal to a session running in Ostrom's cloud; Ctrl-] detaches. */
internal class AttachCommand(private val dependencies: CliDependencies) : CliktCommand(name = "attach") {
    override fun help(context: Context) = "Attach this terminal to a session in Ostrom's cloud (Ctrl-] detaches)"

    private val session by argument("session", help = "The cloud session's id")
    private val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")
    private val org by option("--org", help = "Organisation to act in (else OSTROM_ORG, else the one of ostrom init)")

    override fun run() {
        val signedIn = dependencies.signedIn(server)
        dependencies.requireFeature(signedIn.serverUrl, signedIn.token, ServerFeatures.RUNNERS_CLOUD, NO_CLOUD)
        val orgId = dependencies.launchOrg(signedIn.serverUrl, signedIn.token, org)
        val site = CloudSite(signedIn.serverUrl, signedIn.token, dependencies.workingDirectory(), orgId)
        throw ProgramResult(runBlocking { dependencies.cloud.attach(session, site) })
    }
}
