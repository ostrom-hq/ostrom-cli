// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import okio.Path.Companion.toPath

/** The routes of the v1 contract (`ostrom-core/api/openapi.yaml`) the launcher calls. */
object Routes {
    /** `GET ?agent&dev&model`: may this developer launch this agent with this model. */
    const val POLICY = "/api/v1/policy/agents"

    /** `POST {repo, agent, model}`: the launch and its agent token. */
    const val LAUNCHES = "/api/v1/launches"

    /** `GET ?repo=`: the signed session package of a repository. */
    const val PACKAGE = "/api/v1/packages/current"

    /** `GET`: the public keys of the instance. */
    const val KEYS = "/api/v1/receipts/keys"

    /** `GET`: the MCP servers of the organisation, relayed by Ostrom. */
    const val MCP_SERVERS = "/api/v1/mcp/servers"

    /** `GET`: the models of the inference gateway; a 2xx with models means the organisation relays inference. */
    const val INFERENCE_MODELS = "/api/v1/inference/v1/models"

    /** `POST {launchId}`: the launcher's sign of life. */
    fun alive(session: String) = "/api/v1/sessions/${encode(session)}/alive"

    /** `POST {launchId}`: a new segment chained to a session closed for silence. */
    fun reopen(session: String) = "/api/v1/sessions/${encode(session)}/reopen"

    /** `POST {source, exitCode, model}`: the end of the session; the server signs its receipt. */
    fun end(session: String) = "/api/v1/sessions/${encode(session)}/end"

    /** `POST {digest, agent, model}`: the workflow receipt of the session. */
    fun workflowReceipt(session: String) = "/api/v1/sessions/${encode(session)}/workflow-receipt"

    /** `GET`: a receipt with what it cites, to verify offline. */
    fun bundle(receipt: String) = "/api/v1/receipts/${encode(receipt)}/bundle"

    /** [value] percent-encoded for a path segment or a query value (RFC 3986 unreserved characters kept). */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (char.isLetterOrDigit() && code < ASCII_END || char in UNRESERVED) {
                append(char)
            } else {
                append('%').append(HEX[code shr NIBBLE_BITS]).append(HEX[code and NIBBLE_MASK])
            }
        }
    }

    private const val UNRESERVED = "-._~"
    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xFF
    private const val ASCII_END = 0x80
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
}

/** [first] and [rest] joined as a path of this platform. */
fun join(first: String, vararg rest: String): String =
    rest.fold(first.toPath()) { path, part -> path / part }.toString()
