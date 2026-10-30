// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.cloud.CloudSite
import kotlinx.coroutines.runBlocking

/** `ostrom attach <session>`: this terminal to a session running in Ostrom's cloud; Ctrl-] detaches. */
internal class AttachCommand(private val dependencies: CliDependencies) : CliktCommand(name = "attach") {
    override fun help(context: Context) = "Attach this terminal to a session in Ostrom's cloud (Ctrl-] detaches)"

    private val session by argument("session", help = "The cloud session's id")
    private val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signedIn = dependencies.signedIn(server)
        val site = CloudSite(signedIn.serverUrl, signedIn.token, dependencies.workingDirectory())
        throw ProgramResult(runBlocking { dependencies.cloud.attach(session, site) })
    }
}
