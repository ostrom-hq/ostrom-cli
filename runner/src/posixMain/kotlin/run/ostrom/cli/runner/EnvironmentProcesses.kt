// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import io.ktor.network.selector.SelectorManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import platform.posix.chmod
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * The owner's variables for the agent's Bash commands: the `CLAUDE_ENV_FILE` script, which holds no value, and the
 * environment socket it asks, both next to the credential socket and open to the agent's uid.
 */
internal fun CoroutineScope.environmentAccess(
    selector: SelectorManager,
    config: RunnerConfig,
    server: HttpServerApi,
): Pair<RunnerVariables, Job> {
    val variables = RunnerVariables(server::variables, Clock.System, ::log)
    val socket = EnvironmentSocket.socketFor(config.credentialSocket)
    val script = EnvironmentSocket.scriptFor(config.credentialSocket)
    try {
        EnvironmentSocket.writeScript(script, config.binary, socket)
        chmod(script, SCRIPT_MODE.convert())
    } catch (error: IOException) {
        log("the environment script could not be written: ${error::class.simpleName}")
    }
    return variables to launch {
        EnvironmentSocket.serve(selector, socket, variables, ::log) { chmod(it, OPEN_MODE.convert()) }
    }
}

/**
 * The agent's `CLAUDE_ENV_FILE` script (`ostrom cloud environment --socket PATH`), in the agent's container: prints the
 * owner's current variables as shell code, asked of the runner. Without an answer it prints nothing and succeeds, so
 * the Bash command runs with what it already has.
 */
internal fun environmentScript(args: List<String>): Nothing {
    val socket = CloudProcess.socket(args)
    if (socket != null) {
        val answer = runBlocking {
            val selector = SelectorManager(Dispatchers.IO)
            try {
                EnvironmentSocket.ask({ AgentSocket.connect(selector, socket) })
            } finally {
                selector.close()
            }
        }
        if (answer != null) print(AgentEnvironment.script(answer.variables, answer.withdrawn))
    }
    exitProcess(0)
}

/** `0644`: the `CLAUDE_ENV_FILE` script, read by the agent's uid, written by the runner only. */
private const val SCRIPT_MODE = 0x1A4
