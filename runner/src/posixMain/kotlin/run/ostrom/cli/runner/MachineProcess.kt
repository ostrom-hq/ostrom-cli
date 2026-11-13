// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.signal
import kotlin.system.exitProcess

/**
 * A registered runner (`ostrom cloud machine`): heartbeats with its key until the server refuses it three times in a
 * row (exit 3) or the machine stops it (SIGTERM, exit 0). With a Docker Engine (`OSTROM_DOCKER_API_URL`), it runs the
 * cloud sessions the server hands it. A bad configuration, an unreadable key file included, exits with 2.
 */
internal fun machine(environment: Map<String, String>): Nothing {
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    val config = try {
        MachineConfig.from(environment) { path ->
            try {
                SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
            } catch (error: IOException) {
                throw IllegalArgumentException("OSTROM_RUNNER_KEY_FILE cannot be read (${error::class.simpleName}).")
            }
        }
    } catch (error: IllegalArgumentException) {
        log("invalid configuration: ${error.message}")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }
    log("starting $config")
    val code = runBlocking {
        val client = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                requestTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
        }
        // An image pull can take minutes: the engine's calls have their own, longer timeout.
        val engine = config.docker?.let {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = DOCKER_TIMEOUT_MILLIS
                }
            }
        }
        try {
            val sessions = config.docker?.let { docker ->
                MachineSessions(
                    HttpDockerEngine(checkNotNull(engine), docker.apiUrl, config.runnerId),
                    PlanGuard(config.runnerId, docker.network, docker.image),
                    docker.maxSessions,
                    ::log,
                )
            }
            MachineRunner(HttpMachineApi(client, config), config.interval, ::log, sessions, MachineRunner.WAIT).run()
        } finally {
            client.close()
            engine?.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

/** An image pull, the longest call to the engine. */
private const val DOCKER_TIMEOUT_MILLIS = 600_000L
