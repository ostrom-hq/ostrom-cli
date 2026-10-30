// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.ShellPaths

/**
 * The session a hook runs in, read from the environment the launcher gave the agent (`ostrom <agent>`).
 *
 * @property repository The repository the session was opened for (`OSTROM_REPO`), else the folder name of [launchTop].
 * @property developer Who runs the session (`OSTROM_DEV`).
 * @property harnessVersion The version of the harness laid down (`OSTROM_HARNESS_VERSION`).
 * @property agent The agent (`OSTROM_AGENT`: `claude-code`, `codex`…).
 * @property model The model asked at launch (`OSTROM_MODEL`), or null.
 * @property gatesFile The gate catalogue of the session's signed package (`OSTROM_GATES`), or null: it may only add
 * gated actions to Ostrom's (S2-20).
 * @property sessionFile Where the launch keeps its Ostrom session id (`OSTROM_SESSION_FILE`), or null.
 * @property launchTop The top of the repository the session was launched in (`OSTROM_LAUNCH_TOP`).
 * @property launchId The id of this launch (`OSTROM_LAUNCH_ID`), or null.
 * @property dialect The agent family whose hook protocol is spoken (`OSTROM_HOOK_DIALECT`, default `claude`).
 * @property git The `git` the launcher resolved (`OSTROM_GIT`), else `git`.
 * @property home The user's home folder, for `cd ~`.
 */
data class HookContext(
    val repository: String,
    val developer: String,
    val harnessVersion: String,
    val agent: String,
    val model: String?,
    val gatesFile: String?,
    val sessionFile: String?,
    val launchTop: String,
    val launchId: String?,
    val dialect: String,
    val git: String,
    val home: String,
) {
    /** Reads the session from the environment. */
    companion object {
        /**
         * @param variable Reads one environment variable (null when unset or empty).
         */
        fun read(variable: (String) -> String?): HookContext {
            val launchTop = variable("OSTROM_LAUNCH_TOP").orEmpty()
            return HookContext(
                repository = variable("OSTROM_REPO") ?: ShellPaths.fileName(launchTop),
                developer = variable("OSTROM_DEV") ?: variable("USER") ?: "unknown",
                harnessVersion = variable("OSTROM_HARNESS_VERSION").orEmpty(),
                agent = variable("OSTROM_AGENT") ?: "claude-code",
                model = variable("OSTROM_MODEL"),
                gatesFile = variable("OSTROM_GATES"),
                sessionFile = variable("OSTROM_SESSION_FILE"),
                launchTop = launchTop,
                launchId = variable("OSTROM_LAUNCH_ID"),
                dialect = variable("OSTROM_HOOK_DIALECT") ?: "claude",
                git = variable("OSTROM_GIT") ?: "git",
                home = variable("HOME") ?: variable("USERPROFILE").orEmpty(),
            )
        }
    }
}
