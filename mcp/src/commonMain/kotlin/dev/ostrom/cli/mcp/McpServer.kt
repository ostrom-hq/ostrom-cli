package dev.ostrom.cli.mcp

import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.ostrom.cli.platform.Console

/** One tool the local MCP server offers to the agent. The tools agent registers the real ones. */
interface McpTool {
    /** The tool's name, unique in the server. */
    val name: String

    /** The `tools/list` entry: name, description and input schema. */
    val definition: JsonObject

    /** Runs the tool with [arguments] and returns the MCP `CallToolResult` object. */
    fun call(arguments: JsonObject): JsonObject
}

/**
 * The JSON-RPC 2.0 half of the local MCP server (`ostrom mcp`), one message per line on stdio (MCP stdio transport).
 * It answers `initialize`, `ping`, `tools/list` and `tools/call`; notifications get no reply.
 *
 * @param version The CLI version, given as `serverInfo.version`.
 * @param tools The tools offered; none in the socle.
 */
class McpDispatcher(private val version: String, tools: List<McpTool> = emptyList()) {
    private val tools = tools.associateBy { it.name }

    /**
     * Handles one line received on stdin.
     *
     * @param line One JSON-RPC message.
     *
     * @return The line to write back (without its newline), or null for a notification.
     */
    fun handle(line: String): String? {
        val message = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val id = message?.get("id")
        val method = (message?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reply = when {
            message == null -> error(JsonNull, PARSE_ERROR, "Parse error")
            id == null -> null
            method == null -> error(id, INVALID_REQUEST, "Invalid request")
            else -> answer(id, method, message["params"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        return reply?.toString()
    }

    private fun answer(id: JsonElement, method: String, params: JsonObject): JsonObject = when (method) {
        "initialize" -> result(id, initializeResult(params))
        "ping" -> result(id, JsonObject(emptyMap()))
        "tools/list" -> result(id, JsonObject(mapOf("tools" to JsonArray(tools.values.map { it.definition }))))
        "tools/call" -> callTool(id, params)
        else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
        return JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(asked.takeIf { it in SUPPORTED_VERSIONS } ?: NEWEST_VERSION),
                "capabilities" to JsonObject(mapOf("tools" to JsonObject(emptyMap()))),
                "serverInfo" to JsonObject(
                    mapOf("name" to JsonPrimitive("ostrom"), "version" to JsonPrimitive(version)),
                ),
            ),
        )
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content
        val tool = name?.let { tools[it] } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome = try {
            tool.call(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A failing tool is the agent's to read (MCP tool error), never the end of the server and its other calls.
            toolError("${tool.name} failed: ${e.message ?: e::class.simpleName}")
        }
        return result(id, outcome)
    }

    private fun toolError(text: String) = JsonObject(
        mapOf(
            "content" to JsonArray(
                listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))),
            ),
            "isError" to JsonPrimitive(true),
        ),
    )

    private fun result(id: JsonElement, result: JsonObject) =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to result))

    private fun error(id: JsonElement, code: Int, message: String) = JsonObject(
        mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to id,
            "error" to JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))),
        ),
    )

    private companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
    }
}

/** Answered to a client asking for a protocol version this server does not know. */
private const val NEWEST_VERSION = "2025-06-18"

/** The MCP protocol versions this server speaks. */
private val SUPPORTED_VERSIONS = setOf(NEWEST_VERSION, "2025-03-26", "2024-11-05")

/** Runs the local MCP server on stdio until stdin closes. */
interface McpServer {
    /** Serves until the agent closes stdin; returns the exit code. */
    fun serve(): Int
}

/**
 * [McpServer] reading one message per line. Replies go through [Console.out] (unbuffered), so stdout only ever
 * carries JSON-RPC; nothing else is printed.
 *
 * @param readLine Reads the next line of stdin, or null at its end.
 */
class McpServerImpl(
    private val dispatcher: McpDispatcher,
    private val console: Console,
    private val readLine: () -> String? = ::readlnOrNull,
) : McpServer {
    override fun serve(): Int {
        while (true) {
            val line = readLine() ?: return 0
            if (line.isBlank()) continue
            dispatcher.handle(line)?.let { console.out(it + "\n") }
        }
    }
}
