// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import run.ostrom.cli.mcp.McpServerImpl
import run.ostrom.cli.memory.MemoryTools
import run.ostrom.cli.memory.RelayMemoryApi
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.runner.CloudMcpSession
import run.ostrom.cli.runner.McpForwarder
import run.ostrom.cli.runner.ServerHttp

/**
 * `ostrom cloud mcp --server <name>` (hidden): one of Ostrom's MCP servers for the agent of a cloud session, on stdio,
 * in the agent's container (TeamCode's `teamcode mcp …` in the pod), with the launch's agent token and the server's
 * URL the runner gave the agent (`OSTROM_AGENT_TOKEN`, `OSTROM_URL`). A server the server serves
 * ([CloudMcpSession.FORWARDED]) is forwarded to its route; the memory's is served here, as `ostrom run` serves it,
 * its calls made straight to the server ([ServerHttp]) instead of through a local relay.
 *
 * @param environment The process environment.
 * @param console Where the messages go (stdout) and the refusals (stderr).
 * @param client Makes the HTTP calls; closed when the server ends.
 * @param version The binary's version, the MCP server's.
 * @param readLine Reads the next line of stdin, or null at its end.
 */
class CloudMcp(
    private val environment: Environment,
    private val console: Console,
    private val client: HttpClient,
    private val version: String,
    private val readLine: () -> String? = ::readlnOrNull,
) {
    /**
     * Serves until stdin ends.
     *
     * @param args The arguments after `cloud mcp`: `--server <name>`.
     *
     * @return The exit code: 0, or 2 for an unknown server or a missing variable.
     */
    fun serve(args: List<String>): Int {
        val name = args.zipWithNext().firstOrNull { it.first == "--server" }?.second
        // An unset or empty variable reads null.
        val url = environment[McpForwarder.URL_VARIABLE]?.trimEnd('/')
        val token = environment[McpForwarder.TOKEN_VARIABLE]
        val route = CloudMcpSession.FORWARDED[name]
        return client.use {
            when {
                url == null || token == null -> refuse("no server or agent token in the environment")
                route != null -> runBlocking {
                    val read: suspend () -> String? = { withContext(Dispatchers.IO) { readLine() } }
                    McpForwarder(client, url, route, token, read, console::data).serve()
                }
                name == CloudMcpSession.MEMORY -> {
                    val http = ServerHttp(client, url)
                    val start = RelayMemoryApi(http, NO_PORT, token, RelayMemoryApi.START_MILLIS)
                    val tools = MemoryTools(RelayMemoryApi(http, NO_PORT, token), start = start)
                    McpServerImpl(tools.dispatcher(version), console, readLine).serve()
                }
                else -> refuse("unknown server ${name ?: "(none)"}")
            }
        }
    }

    private fun refuse(why: String): Int {
        console.err("ostrom cloud mcp: $why\n")
        return USAGE
    }

    private companion object {
        /** The relay's port, which [ServerHttp] does not use. */
        const val NO_PORT = 0
        const val USAGE = 2
    }
}
