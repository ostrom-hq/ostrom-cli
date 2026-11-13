// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

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

    /** `GET`, unauthenticated: every signing key of the instance, which signs the hooks' gate policy (S2-20). */
    const val INSTANCE_KEYS = "/api/v1/instance/keys"

    /**
     * `GET`: only the MCP servers the session's agent will reach, without upstream URLs or secret names (the
     * contract's `listMcpServers` with `mine`). The whole list is the console's, never asked by the CLI, an owner's or
     * an admin's token included: least privilege.
     */
    const val MCP_MINE = "/api/v1/mcp/servers?mine=true"

    /** `GET`: the models of the inference gateway; a 2xx with models means the organisation relays inference. */
    const val INFERENCE_MODELS = "/api/v1/inference/v1/models"

    /** `GET`: what the server speaks and serves (the keys of `ServerFeatures`). */
    const val CAPABILITIES = "/api/v1/capabilities"

    /** `POST {launchId}`: the launcher's sign of life. */
    fun alive(session: String) = "/api/v1/sessions/${encode(session)}/alive"

    /** `POST {launchId}`: a new segment chained to a session closed for silence. */
    fun reopen(session: String) = "/api/v1/sessions/${encode(session)}/reopen"

    /** `POST {source, exitCode, model}`: the end of the session; the server signs its receipt. */
    fun end(session: String) = "/api/v1/sessions/${encode(session)}/end"

    /** `POST {digest, agent, model}`: the workflow receipt of the session. */
    fun workflowReceipt(session: String) = "/api/v1/sessions/${encode(session)}/workflow-receipt"

    /** `DELETE`, with the launch's agent token: the end of `ostrom <agent>`, which revokes that token at once. */
    fun launch(launchId: String) = "/api/v1/launches/${encode(launchId)}"

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
