// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * [AgentSessions] by the variables `ostrom <agent>` gives the agent, then by the launches' private folders on this
 * device (`<temporary>/ostrom-run-*`): one whose local relay still answers on the loopback is a session running, even
 * when the agent cleared its own variables before calling `ostrom update`.
 *
 * @param temporary Where the launches make their folders.
 */
class AgentSessionsImpl(
    private val environment: Environment,
    private val fileSystem: FileSystem,
    private val temporary: String,
    private val http: LoopbackHttp,
) : AgentSessions {
    override fun running(): String? {
        val variable = VARIABLES.firstOrNull { !environment[it].isNullOrEmpty() }
        return if (variable != null) "$variable is set" else liveRelay()
    }

    private fun liveRelay(): String? {
        val folders = try {
            fileSystem.list(temporary.toPath())
        } catch (_: IOException) {
            emptyList()
        }
        val live = folders.filter { it.name.startsWith(RUN_PREFIX) }.firstOrNull(::answers)
        return live?.let { "an Ostrom session runs on this device, its relay answers ($it)" }
    }

    private fun answers(folder: Path): Boolean {
        val settings = try {
            fileSystem.read(folder / SETTINGS) { readUtf8() }
        } catch (_: IOException) {
            null
        }
        val url = parseObject(settings)?.text("OSTROM_RELAY_URL")
        val port = url?.let { PORT.matchEntire(it.trimEnd('/')) }?.groupValues?.get(1)?.toInt()
        return port != null && http.exchange(port, LoopbackRequest("GET", "/"), PROBE_MILLIS).isSuccess
    }

    private companion object {
        val VARIABLES = listOf("OSTROM_LAUNCH_ID", "OSTROM_RELAY_URL", "OSTROM_SESSION_FILE", "OSTROM_RELAY_TOKEN")
        const val RUN_PREFIX = "ostrom-run-"
        const val SETTINGS = "session-env.json"
        const val PROBE_MILLIS = 500
        val PORT = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})")
    }
}
