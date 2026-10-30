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
        val variable = SESSION_VARIABLES.firstOrNull { !environment[it].isNullOrEmpty() }
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
        val url = parseObject(settings)?.text("OSTROM_RELAY_URL").orEmpty()
        val match = LOOPBACK_URL.matchEntire(url.trimEnd('/'))
        return match != null && http.exchange(match.groupValues[1].toInt(), PROBE, PROBE_MILLIS).isSuccess
    }
}

/** The variables `ostrom <agent>` gives the agent: any of them set is a session. */
private val SESSION_VARIABLES =
    listOf("OSTROM_LAUNCH_ID", "OSTROM_RELAY_URL", "OSTROM_SESSION_FILE", "OSTROM_RELAY_TOKEN")
private const val RUN_PREFIX = "ostrom-run-"
private const val SETTINGS = "session-env.json"
private const val PROBE_MILLIS = 500
private val PROBE = LoopbackRequest("GET", "/")
private val LOOPBACK_URL = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})")
