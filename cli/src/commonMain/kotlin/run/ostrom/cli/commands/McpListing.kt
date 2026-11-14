// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text

/** The servers the caller reaches: the member's list, with the relay path, the state and the last failure. */
private const val MCP_SERVERS = "/api/v1/mcp/servers?mine=true"

/**
 * `acme  http  -` or `notes  stdio  failed (start_failed)`: one row of `ostrom mcp list`. A hosted command's
 * state is the one the server last recorded; an HTTP server has none, and a state the server could not read is
 * left out (`-`), never an error.
 */
internal fun JsonObject.mcpLine(): String {
    val state = text("state")?.let { state -> text("failure")?.let { "$state ($it)" } ?: state } ?: "-"
    return listOf(text("name") ?: "?", text("transport") ?: "-", state).joinToString("  ")
}

/** `ostrom mcp list [--json]`: the MCP servers you reach and, for the hosted commands, their state. */
internal fun CliDependencies.listMcpServers(org: String?, server: String?, json: Boolean) {
    val access = orgAccess(server, org)
    val answer = access.read(MCP_SERVERS, "No MCP servers on ${access.signed.serverUrl}.")
    val items = answer.list("items").filterIsInstance<JsonObject>()
    if (json) {
        console.data(JsonArray(items).toString() + "\n")
        return
    }
    items.forEach { console.out(it.mcpLine() + "\n") }
    if (items.isEmpty()) console.err("No MCP server. An owner or an admin adds one on the console.\n")
}
