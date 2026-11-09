// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.capabilities

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.api.apiError
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.ServerFeatures

/** The route that says what a server speaks and serves (ostrom-core `api/openapi.yaml`, `capabilities`). */
internal const val CAPABILITIES_ROUTE = "/api/v1/capabilities"

/** What reading a server's capabilities gave. */
sealed interface CapabilitiesRead {
    /** The server said what it speaks and serves. */
    data class Read(val capabilities: Capabilities) : CapabilitiesRead

    /**
     * The server answered 404: an Ostrom server from before the capabilities route (only development builds of
     * November 2026 are), too old for this ostrom ([tooOld]).
     */
    data object Unknown : CapabilitiesRead

    /**
     * No usable answer.
     *
     * @property status The HTTP status, or null when the server was not reached.
     * @property reason What happened, without the body: the contract's error message, or the network's reason.
     */
    data class Failed(val status: Int?, val reason: String) : CapabilitiesRead
}

/**
 * The capabilities of the servers this process talks to. Read only by the commands that need them (`ostrom login`,
 * `doctor`, `update`, and the commands of an optional feature): a command that does not never pays the call.
 */
interface ServerCapabilities {
    /**
     * What [serverUrl] speaks and serves, as [token] sees it.
     *
     * @param token Any credential of the server: the device token, an admin or a console token.
     */
    suspend fun read(serverUrl: String, token: String): CapabilitiesRead
}

/**
 * [ServerCapabilities] read once per server and token for the whole process, over the command's client of that server
 * (one connection, `CliDependencies.create`). Nothing is kept on disk: a command that needs them talks to the server
 * anyway, so the read costs one request on a connection already open, and a copy on disk would go on saying what a
 * server spoke before its upgrade. A failure is not kept: the next read asks again.
 *
 * @param api The client of a server URL.
 */
class ServerCapabilitiesImpl(private val api: (String) -> OstromApi) : ServerCapabilities {
    private val known = mutableMapOf<Pair<String, String>, CapabilitiesRead>()

    override suspend fun read(serverUrl: String, token: String): CapabilitiesRead {
        val key = serverUrl.trimEnd('/') to token
        known[key]?.let { return it }
        val read = fetch(key.first, token)
        if (read !is CapabilitiesRead.Failed) known[key] = read
        return read
    }

    private suspend fun fetch(serverUrl: String, token: String): CapabilitiesRead {
        val response = try {
            api(serverUrl).send(ServerRequest("GET", CAPABILITIES_ROUTE, token = token))
        } catch (e: ServerUnreachableException) {
            return CapabilitiesRead.Failed(null, e.reason)
        }
        return when (response.status) {
            in OK..LAST_SUCCESS -> decoded(response.body)?.let { CapabilitiesRead.Read(it) }
                ?: CapabilitiesRead.Failed(response.status, "its capabilities cannot be read")
            NOT_FOUND -> CapabilitiesRead.Unknown
            else -> {
                val error = response.apiError()
                val reason = if (error == null) "HTTP ${response.status}" else error.message
                CapabilitiesRead.Failed(response.status, reason)
            }
        }
    }

    private fun decoded(body: String): Capabilities? = try {
        OstromJson.decodeFromString(Capabilities.serializer(), body)
    } catch (_: IllegalArgumentException) {
        // A body that is not the contract's is a SerializationException, which is an IllegalArgumentException.
        null
    }

    private companion object {
        const val OK = 200
        const val LAST_SUCCESS = 299
        const val NOT_FOUND = 404
    }
}

/**
 * Why this ostrom cannot work with a server speaking [capabilities], naming both API versions and the side to update,
 * or null when it speaks it.
 *
 * @param serverUrl The server, as the developer named it.
 * @param version This ostrom's version.
 */
internal fun incompatibility(serverUrl: String, capabilities: Capabilities, version: String): String? {
    if (capabilities.isSupported) return null
    val spoken = ServerFeatures.SUPPORTED_API_VERSIONS.sorted().joinToString(", ") { "v$it" }
    val newest = ServerFeatures.SUPPORTED_API_VERSIONS.max()
    val advice = if (capabilities.apiVersion > newest) {
        "update this ostrom: ostrom update."
    } else {
        "ask your admin to update the server."
    }
    val server = "Ostrom ${shown(capabilities.serverVersion)}"
    return "$serverUrl speaks the Ostrom API v${capabilities.apiVersion} ($server), and ostrom $version speaks " +
        "$spoken: $advice"
}

/**
 * What this ostrom says of a server from before the capabilities route (404), which it refuses as the app and the
 * console do.
 *
 * @param serverUrl The server, as the developer named it.
 * @param version This ostrom's version.
 */
internal fun tooOld(serverUrl: String, version: String): String =
    "$serverUrl is too old for ostrom $version (it does not say which Ostrom API it speaks): " +
        "ask your admin to update the server."

/** A server's version as it may be printed: never an escape sequence nor a line of its own. */
internal fun shown(version: String): String = if (PRINTABLE.matches(version)) version else "?"

private val PRINTABLE = Regex("""[0-9A-Za-z._+-]{1,64}""")
