// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import run.ostrom.cli.mcp.McpDispatcher
import run.ostrom.cli.mcp.McpServerImpl
import run.ostrom.cli.memory.MemoryTools
import run.ostrom.cli.memory.RelayMemoryApi
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ProcessCaptureImpl
import run.ostrom.cli.runner.CloudMcpSession
import run.ostrom.cli.runner.McpForwarder
import run.ostrom.cli.runner.ServerHttp
import run.ostrom.cli.todo.CommandCheck
import run.ostrom.cli.todo.GitHub
import run.ostrom.cli.todo.RelayTodoApi
import run.ostrom.cli.todo.TodoChecks
import run.ostrom.cli.todo.TodoTools

/**
 * `ostrom cloud mcp --server <name>` (hidden): one of Ostrom's MCP servers for the agent of a cloud session, on stdio,
 * in the agent's container (TeamCode's `teamcode mcp …` in the pod), with the launch's agent token and the server's
 * URL the runner gave the agent (`OSTROM_AGENT_TOKEN`, `OSTROM_URL`). A server the server serves
 * ([CloudMcpSession.FORWARDED]: artifacts, waits, workflow delivery) is forwarded to its route; the memory's and the
 * todo's are served here, as `ostrom run` serves them, their calls made straight to the server ([ServerHttp]) instead
 * of through a local relay. No session id is sent: the server binds a cloud launch's agent token to its cloud session.
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
        val missing = listOf(McpForwarder.URL_VARIABLE to url, McpForwarder.TOKEN_VARIABLE to token)
            .filter { it.second == null }.map { it.first }
        return client.use {
            when {
                route == null && name !in CloudMcpSession.SERVED_HERE -> refuse("unknown server ${name ?: "(none)"}")
                url == null || token == null -> refuse("${missing.joinToString(" and ")} not set")
                route != null -> runBlocking {
                    val read: suspend () -> String? = { withContext(Dispatchers.IO) { readLine() } }
                    McpForwarder(client, url, route, token, read, console::data).serve()
                }
                // The memory or the todo, served here.
                else -> McpServerImpl(dispatcher(name, ServerHttp(client, url), token), console, readLine).serve()
            }
        }
    }

    /** The tools of a server served here: the todo, its checks run in the agent's container, or the memory. */
    private fun dispatcher(name: String?, http: ServerHttp, token: String): McpDispatcher =
        if (name == CloudMcpSession.TODO) {
            val capture = ProcessCaptureImpl()
            val checks = TodoChecks(GitHub(capture), CommandCheck(capture, environment.operatingSystem))
            TodoTools(RelayTodoApi(http, NO_PORT, token), checks).dispatcher(version)
        } else {
            val start = RelayMemoryApi(http, NO_PORT, token, RelayMemoryApi.START_MILLIS)
            MemoryTools(RelayMemoryApi(http, NO_PORT, token), start = start).dispatcher(version)
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
