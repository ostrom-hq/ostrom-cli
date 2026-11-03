// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture

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
fun versionRefusal(agent: Agent, executable: String, minimum: MinimumVersion, capture: ProcessCapture): String? = null

/** Whether [found] comes before [minimum], both `major.minor` or `major.minor.patch`. */
fun olderThan(found: String, minimum: String): Boolean = false
