// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.mcp

import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a bridge sends the agent's messages: the local relay of the session and one of its MCP servers.
 *
 * @property port The relay's port on 127.0.0.1.
 * @property token The relay's capability for this launch (`OSTROM_RELAY_TOKEN`).
 * @property server The relayed MCP server, served by the relay under `/mcp/<server>`.
 */
data class BridgeTarget(val port: Int, val token: String, val server: String) {
    override fun toString() = "BridgeTarget(port=$port, token=***, server=$server)"

    /** Reads the target from the session's settings (`session-env.json`). */
    companion object {
        private val RELAY_URL = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})/?")

        private val SERVER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /**
         * The target for [server], or null when [settings] name no relay, or [server] is not a plain name (it goes
         * into the request line: `x/../v1/messages`, a `?` or a new line would reach another route of the relay).
         */
        fun of(settings: String?, server: String): BridgeTarget? {
            val values = parsed(settings)
            val port = RELAY_URL.matchEntire(values["OSTROM_RELAY_URL"].orEmpty())?.let { it.groupValues[1] }
            val token = values["OSTROM_RELAY_TOKEN"]
            val plain = SERVER_NAME.matches(server) && ".." !in server
            return if (port == null || token == null || !plain) null else BridgeTarget(port.toInt(), token, server)
        }

        private fun parsed(settings: String?): Map<String, String> {
            val element = try {
                Json.parseToJsonElement(settings ?: "{}")
            } catch (_: SerializationException) {
                JsonNull
            }
            return (element as? JsonObject).orEmpty()
                .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it.content } }.toMap()
        }
    }
}

/**
 * `ostrom mcp --session <file> --server <name>`: a stdio MCP server for agents that only start stdio servers (Gemini
 * CLI, Grok, Hermes, OpenCode, Pi). Each line from the agent is posted to the local relay, which adds the launch's
 * token and forwards it to Ostrom's MCP gateway; the answer goes back on stdout, one message per line.
 *
 * @param readLine Reads the next line of stdin, or null at its end.
 */
class McpBridge(
    private val target: BridgeTarget,
    private val http: LoopbackHttp,
    private val console: Console,
    private val readLine: () -> String? = ::readlnOrNull,
) : McpServer {
    override fun serve(): Int {
        var line = readLine()
        while (line != null) {
            if (line.isNotBlank()) forward(line)
            line = readLine()
        }
        return 0
    }

    private fun forward(line: String) {
        val id = idOf(line)
        val request = LoopbackRequest(
            method = "POST",
            path = "/mcp/${target.server}",
            headers = mapOf(
                "Authorization" to "Bearer ${target.token}",
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/event-stream",
            ),
            body = line,
        )
        val answer = http.exchange(target.port, request, TIMEOUT_MILLIS).fold(
            onSuccess = { messagesOf(it, id) },
            onFailure = { failure -> listOfNotNull(id?.let { error(it, failure.message) }) },
        )
        answer.forEach { console.data(it + "\n") }
    }

    /** The messages of an answer: a JSON body, or the `data:` lines of an event stream; an error for a request. */
    private fun messagesOf(response: LoopbackResponse, id: JsonElement?): List<String> {
        val body = response.body.trim()
        return when {
            response.status !in SUCCESS -> listOfNotNull(id?.let { error(it, "Ostrom answered ${response.status}") })
            body.startsWith("event:") || body.startsWith("data:") ->
                body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            body.isEmpty() -> emptyList()
            else -> listOf(body)
        }
    }

    /** The id of a request, or null for a notification (or a line that is not JSON: nothing to answer). */
    private fun idOf(line: String): JsonElement? = try {
        (Json.parseToJsonElement(line) as? JsonObject)?.get("id")?.takeIf { it != JsonNull }
    } catch (_: SerializationException) {
        null
    }

    private fun error(id: JsonElement, why: String?): String = JsonObject(
        mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to id,
            "error" to JsonObject(
                mapOf("code" to JsonPrimitive(UNREACHABLE), "message" to JsonPrimitive("Ostrom: $why")),
            ),
        ),
    ).toString()

    /** The bridge's replies and limits. */
    companion object {
        private val SUCCESS = 200..299
        private const val UNREACHABLE = -32000

        /** A request for approval waits for a human: as long as Ostrom's own wait, and a margin. */
        private const val TIMEOUT_MILLIS = 320_000
    }
}
