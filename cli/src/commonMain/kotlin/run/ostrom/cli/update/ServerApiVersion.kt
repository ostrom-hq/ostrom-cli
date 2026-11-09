// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.capabilities.CapabilitiesRead
import run.ostrom.cli.capabilities.ServerCapabilities
import run.ostrom.core.api.models.ServerFeatures

/**
 * The API version the binary `ostrom update` installs must speak: the one of the server this device is signed in to.
 *
 * Without a server signed in to, or when the server cannot say (unreachable, a server from before the capabilities
 * route), the binary is checked against the API this ostrom speaks, with a warning: updating ostrom without the
 * server at hand (on a plane, before the server is up, to fix ostrom itself) is legitimate, and the binary is still
 * proven to run here and to be the version signed. TeamCode refuses instead (`release_server_unavailable`), but its
 * update also restarts a local service that needs its server.
 *
 * @param capabilities Reads what a server speaks.
 * @param say Writes a line for the developer.
 */
class ServerApiVersion(private val capabilities: ServerCapabilities, private val say: (String) -> Unit) {
    /**
     * @param serverUrl The server this device works with, or null.
     * @param token This device's token for it, or null when not signed in.
     */
    suspend operator fun invoke(serverUrl: String?, token: String?): Int {
        val read = if (serverUrl != null && token != null) capabilities.read(serverUrl, token) else null
        val why = when (read) {
            null, is CapabilitiesRead.Read -> null
            CapabilitiesRead.Unknown -> "it is older than this ostrom"
            is CapabilitiesRead.Failed -> if (read.status == null) "unreachable" else "HTTP ${read.status}"
        }
        if (why != null) {
            say(
                "! $serverUrl did not say which Ostrom API it speaks ($why): the new ostrom is checked against the " +
                    "API v${ServerFeatures.API_VERSION} this ostrom speaks.",
            )
        }
        return if (read is CapabilitiesRead.Read) read.capabilities.apiVersion else ServerFeatures.API_VERSION
    }
}
