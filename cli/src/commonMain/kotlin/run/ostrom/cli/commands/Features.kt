// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.capabilities.CapabilitiesRead
import run.ostrom.cli.capabilities.incompatibility
import run.ostrom.cli.capabilities.tooOld
import kotlinx.coroutines.runBlocking

/**
 * Stops a command of an optional feature when [serverUrl] does not serve [feature] (a key of
 * `run.ostrom.core.api.models.ServerFeatures`), or speaks an API this ostrom does not (exit 69, as a server that does
 * not serve `ostrom login --admin`). Only the commands of an optional feature call it: one more request, on the
 * connection the command opens anyway.
 *
 * A server from before the capabilities route (404) is too old: it stops, as at login. A server that fails to answer
 * (401, 5xx) lets the command go on: the command's own call says what is wrong, in its words. A server that cannot be
 * reached stops here, rather than once more for the command's own call.
 *
 * @param token Any credential of the server.
 * @param missing What to say when the feature is not served, such as `This Ostrom server runs no cloud session.`
 */
internal fun CliDependencies.requireFeature(serverUrl: String, token: String, feature: String, missing: String) {
    val stop = when (val read = runBlocking { capabilities.read(serverUrl, token) }) {
        is CapabilitiesRead.Read -> incompatibility(serverUrl, read.capabilities, BuildInfo.VERSION)
            ?: missing.takeIf { feature !in read.capabilities }
        is CapabilitiesRead.Failed -> if (read.status == null) {
            "Ostrom is unreachable at $serverUrl. Check your network, or the --server URL."
        } else {
            null
        }
        CapabilitiesRead.Unknown -> tooOld(serverUrl, BuildInfo.VERSION)
    }
    if (stop != null) throw PrintMessage("ostrom: $stop", ExitCode.UNREACHABLE.code, printError = true)
}

/** What `ostrom run <agent> --cloud` and `ostrom attach` say to a server that runs no cloud session. */
internal const val NO_CLOUD = "This Ostrom server runs no cloud session: start the agent here, without --cloud."
