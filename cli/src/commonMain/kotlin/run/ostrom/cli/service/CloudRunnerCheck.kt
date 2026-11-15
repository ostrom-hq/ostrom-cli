// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.Environment

/**
 * Whether this process runs inside a cloud session's runner pod: one pod runs one session, so it never installs a
 * service that would make it a device. The runner's sockets (`OSTROM_AGENT_SOCKET`, `OSTROM_CREDENTIAL_SOCKET`, or
 * their files in `/run/ostrom`) are what a pod has and a developer's device does not.
 */
internal fun insideCloudRunner(
    environment: Environment,
    exists: (String) -> Boolean = { FileSystem.SYSTEM.exists(it.toPath()) },
): Boolean = CLOUD_RUNNER_VARIABLES.any { !environment[it].isNullOrBlank() } || CLOUD_RUNNER_PATHS.any(exists)

private val CLOUD_RUNNER_VARIABLES = listOf("OSTROM_AGENT_SOCKET", "OSTROM_CREDENTIAL_SOCKET")
private val CLOUD_RUNNER_PATHS = listOf("/run/ostrom/agent.sock", "/run/ostrom/credential.sock")

/** What a command prints, in a cloud session's runner, instead of installing or running the service. */
internal const val CLOUD_RUNNER_REFUSAL =
    "The local service is not set up in a cloud session's runner: one pod runs one session."
