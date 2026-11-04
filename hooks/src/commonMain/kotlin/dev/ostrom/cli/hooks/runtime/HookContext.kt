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
 * @property instanceKeys The instance's signing keys (`OSTROM_INSTANCE_KEYS`, the body of `GET /api/v1/instance/keys`
 * the launcher read before the agent started), which the gate policy must be signed with (S2-20); null outside a
 * session.
 * @property packageSeal The SHA-256 of the session package's manifest (`OSTROM_PACKAGE_SEAL`, S2-33), or null.
 * @property packageManifest The manifest of the session package (`OSTROM_PACKAGE_MANIFEST`), or null.
 * @property relayTokenFile The file that holds the relay's capability for the hooks (`OSTROM_RELAY_TOKEN_FILE`): it is
 * never in the agent's environment (S2-25); null outside a session.
 * @property runnerChecks The Unix socket of the cloud session's runner (`OSTROM_RUNNER_CHECKS`), which runs the
 * package's checks on its own host: null on a workstation, where the hook runs them.
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
    val instanceKeys: String?,
    val packageSeal: String?,
    val packageManifest: String?,
    val relayTokenFile: String?,
    val runnerChecks: String?,
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
                instanceKeys = variable("OSTROM_INSTANCE_KEYS"),
                packageSeal = variable("OSTROM_PACKAGE_SEAL"),
                packageManifest = variable("OSTROM_PACKAGE_MANIFEST"),
                relayTokenFile = variable("OSTROM_RELAY_TOKEN_FILE"),
                runnerChecks = variable("OSTROM_RUNNER_CHECKS")?.ifEmpty { null },
            )
        }
    }
}
