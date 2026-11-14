// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.posix.fflush
import kotlin.system.exitProcess

/**
 * The agent's `ostrom-artifacts` MCP server (`ostrom cloud artifacts-mcp`), in the agent's container: forwards each
 * line of stdin to the server with the launch's agent token ([ArtifactsMcp]). Without the server or the token, it ends
 * at once, and the agent goes on without the server.
 */
internal fun artifactsMcp(environment: Map<String, String>): Nothing {
    val url = environment[ArtifactsMcp.URL_VARIABLE]?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val token = environment[ArtifactsMcp.TOKEN_VARIABLE]?.takeIf { it.isNotBlank() }
    if (url == null || token == null) {
        log("artifacts-mcp: no server or agent token")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }
    val code = runBlocking {
        val client = HttpClient {
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
        }
        try {
            ArtifactsMcp(client, url, token, ::readlnOrNull) { message ->
                print(message)
                fflush(null)
            }.serve()
        } finally {
            client.close()
        }
    }
    exitProcess(code)
}
