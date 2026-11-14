// SPDX-License-Identifier: Apache-2.0
// A failure of the network is answered to the agent or read as "cannot publish"; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `ostrom cloud artifacts-mcp`: the `ostrom-artifacts` MCP server of a cloud session's agent (TeamCode's `teamcode mcp
 * artifacts` in the pod), on stdio. Each line from the agent is posted to the server's artifacts MCP route with the
 * launch's agent token, which publishes as the member the session runs for; the answer goes back one message per line.
 * It reaches the server directly ([OSTROM_URL][URL_VARIABLE]), as every container of the session does, so it works
 * the same under the Kubernetes and the Docker drivers (the Docker containers share no loopback).
 *
 * @param client An HTTP client.
 * @param serverUrl The server (`OSTROM_URL`).
 * @param token The launch's agent token (`OSTROM_AGENT_TOKEN`).
 * @param readLine Reads the next line of stdin, or null at its end.
 * @param write Writes one message, its newline included, to stdout.
 */
class ArtifactsMcp(
    private val client: HttpClient,
    private val serverUrl: String,
    private val token: String,
    private val readLine: () -> String?,
    private val write: (String) -> Unit,
) {
    /** The server's MCP session, when it gives one (`Mcp-Session-Id`), sent back with each later message. */
    private var session: String? = null

    /**
     * Serves until stdin ends.
     *
     * @return The exit code.
     */
    suspend fun serve(): Int {
        var line = readLine()
        while (line != null) {
            if (line.isNotBlank()) forward(line)
            line = readLine()
        }
        return 0
    }

    private suspend fun forward(line: String) {
        val id = idOf(line)
        val messages = try {
            val response = client.post("$serverUrl$MCP_ROUTE") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                session?.let { header(SESSION_HEADER, it) }
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
                setBody(line)
            }
            response.headers[SESSION_HEADER]?.let { session = it }
            if (response.status.isSuccess()) {
                messagesOf(response.bodyAsText())
            } else {
                listOfNotNull(id?.let { error(it, "Ostrom answered ${response.status.value}") })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            listOfNotNull(id?.let { error(it, "the server could not be reached (${error::class.simpleName})") })
        }
        messages.forEach { write(it + "\n") }
    }

    /** The messages of an answer: a JSON body, or the `data:` lines of an event stream. */
    private fun messagesOf(text: String): List<String> {
        val body = text.trim()
        return when {
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

    private fun error(id: JsonElement, why: String): String = JsonObject(
        mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to id,
            "error" to JsonObject(
                mapOf("code" to JsonPrimitive(UNREACHABLE), "message" to JsonPrimitive("Ostrom: $why")),
            ),
        ),
    ).toString()

    /** Where the server's artifacts MCP is and how the agent's command finds it. */
    companion object {
        /** The server, in the agent's environment ([Runner]'s launch variables). */
        const val URL_VARIABLE = "OSTROM_URL"

        /** The launch's agent token, in the agent's environment. */
        const val TOKEN_VARIABLE = "OSTROM_AGENT_TOKEN"

        private const val MCP_ROUTE = "/api/v1/artifacts/mcp"
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val UNREACHABLE = -32000
        private val REQUEST_TIMEOUT = 120.seconds
    }
}

/**
 * Whether the agent of an opened launch may publish now: the server's publication policy, asked with its agent token
 * (TeamCode's runner asks with `tcex_`, 20 s at most). A refusal, an error or no answer in time is "no": the agent then
 * starts with its own tools.
 */
fun interface ArtifactsPolicy {
    /**
     * Asks.
     *
     * @param agentToken The launch's agent token.
     *
     * @return Whether it may publish.
     */
    suspend fun canPublish(agentToken: String): Boolean
}

/**
 * [ArtifactsPolicy] over HTTP: `GET /api/v1/artifacts/policy`, `canPublish`.
 *
 * @param client An HTTP client.
 * @param serverUrl The server.
 * @param limit How long the server has to answer.
 */
class HttpArtifactsPolicy(
    private val client: HttpClient,
    private val serverUrl: String,
    private val limit: Duration = 20.seconds,
) : ArtifactsPolicy {
    override suspend fun canPublish(agentToken: String): Boolean = try {
        withTimeoutOrNull(limit) {
            val response = client.get("$serverUrl/api/v1/artifacts/policy") { bearerAuth(agentToken) }
            if (response.status.isSuccess()) {
                val body = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                (body?.get("canPublish") as? JsonPrimitive)?.booleanOrNull == true
            } else {
                false
            }
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

/**
 * What the session script reads to connect `ostrom-artifacts` (SessionScript.FIRST): for Claude Code an
 * `--mcp-config` JSON and the instruction, its own artifact tools turned off by the script; for Codex a
 * `-c mcp_servers.ostrom-artifacts=…` override, which lists the two variables the command needs (Codex passes a stdio
 * server only the variables it names), and a `developer_instructions` one. Paths and names only, never the token.
 */
object ArtifactsSession {
    /** The server's entry (Claude Code's JSON, Codex's override); the script connects nothing without it. */
    const val MCP_VARIABLE = "OSTROM_ARTIFACTS_MCP"

    /** The instruction (Claude Code's text, Codex's override). */
    const val INSTRUCTIONS_VARIABLE = "OSTROM_ARTIFACTS_INSTRUCTIONS"

    /** The name the server takes in the agent, as `ostrom run` connects it. */
    const val SERVER = "ostrom-artifacts"

    /**
     * The launcher's instruction (`SessionLayout.ARTIFACTS_INSTRUCTIONS`, TeamCode's): every page for people goes
     * through the server, never another artifact system.
     */
    const val INSTRUCTIONS =
        "Publish every page meant for people (an HTML page, a document, a report, a plan, a dashboard) with the " +
            "`publish` tool of the `ostrom-artifacts` MCP server, never with another artifact system, and give " +
            "the link it returns."

    /**
     * The variables for [agentCommand], or none for an agent the script does not connect.
     *
     * @param agentCommand The agent's command.
     * @param binary The `ostrom` binary of the session.
     *
     * @return The variables.
     */
    fun variables(agentCommand: String, binary: String): Map<String, String> {
        val arguments = JsonArray(listOf(JsonPrimitive("cloud"), JsonPrimitive(CloudProcess.ARTIFACTS_MCP)))
        return when (agentCommand) {
            "claude" -> {
                val server = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("stdio"),
                        "command" to JsonPrimitive(binary),
                        "args" to arguments,
                    ),
                )
                val config = JsonObject(mapOf("mcpServers" to JsonObject(mapOf(SERVER to server))))
                mapOf(MCP_VARIABLE to config.toString(), INSTRUCTIONS_VARIABLE to INSTRUCTIONS)
            }
            "codex" -> {
                // A JSON string or list is a TOML one too.
                val names = JsonArray(
                    listOf(ArtifactsMcp.URL_VARIABLE, ArtifactsMcp.TOKEN_VARIABLE).map(::JsonPrimitive),
                )
                val server = "mcp_servers.$SERVER={command=${JsonPrimitive(binary)},args=$arguments,env_vars=$names}"
                mapOf(
                    MCP_VARIABLE to server,
                    INSTRUCTIONS_VARIABLE to "developer_instructions=${JsonPrimitive(INSTRUCTIONS)}",
                )
            }
            else -> emptyMap()
        }
    }
}
