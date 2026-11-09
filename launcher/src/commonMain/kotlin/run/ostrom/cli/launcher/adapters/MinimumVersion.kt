// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture

/**
 * The oldest version of an agent in which Ostrom can keep its hooks on, and how the developer updates it.
 *
 * @property version The version, `major.minor.patch`.
 * @property update The command that updates the agent.
 */
data class MinimumVersion(val version: String, val update: String)

/**
 * Why [executable] is not started for [minimum]: its `--version` names an older version, or no version at all. Null
 * when it names [minimum] or a newer one.
 */
fun versionRefusal(agent: Agent, executable: String, minimum: MinimumVersion, capture: ProcessCapture): String? {
    val result = capture.capture(listOf(executable, "--version"), VERSION_MILLIS)
    // a --version that fails proves nothing, whatever it printed
    val output = (result as? CaptureResult.Exited)?.takeIf { it.code == 0 }?.output.orEmpty()
    val found = VERSION.find(output)?.value
    val update = " Update it: ${minimum.update}"
    return when {
        found == null -> "${agent.displayName}'s version cannot be read (`${agent.executable} --version`): Ostrom " +
            "needs ${minimum.version} or newer to keep its hooks on, so it is not started.$update"
        olderThan(found, minimum.version) -> "${agent.displayName} $found is older than ${minimum.version}: a " +
            "repository could turn Ostrom's hooks off in it, so it is not started.$update"
        else -> null
    }
}

/** Whether [found] comes before [minimum], both `major.minor` or `major.minor.patch`; a missing part is a zero. */
fun olderThan(found: String, minimum: String): Boolean {
    val pairs = parts(found).zip(parts(minimum))
    return pairs.firstOrNull { (theirs, least) -> theirs != least }?.let { (theirs, least) -> theirs < least } ?: false
}

private fun parts(version: String): List<Int> = (version.split('.').map { it.toInt() } + listOf(0, 0)).take(PARTS)

/** `major.minor` or `major.minor.patch`, each part short enough to be a number. */
private val VERSION = Regex("""\d{1,6}\.\d{1,6}(\.\d{1,6})?""")
private const val PARTS = 3
private const val VERSION_MILLIS = 15_000
