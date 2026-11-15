// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

/**
 * Turns the automatic updates on, off, or removes them, under the settings' lock (TeamCode's
 * `AutomaticUpdateManager`). The opt-in is recorded disabled before the registration changes, so a setup that fails or
 * is cancelled leaves them off; turning them off affects later attempts only, never one under way.
 *
 * @param createSettings The settings of a first `on`.
 */
internal class AutomaticUpdateManager(
    private val policy: AutomaticUpdateStore,
    private val schedule: AutomaticUpdateSchedule,
    private val platform: AutomaticUpdatePlatform,
    private val createSettings: () -> AutomaticUpdateSettings,
) {
    /** Applies [operation] (`on`, `off` or `uninstall`); the settings that hold now, or null when there are none. */
    suspend fun configure(operation: String): AutomaticUpdateSettings? {
        require(operation in OPERATIONS)
        val user = platform.userId ?: refuseInstallation(USER_UNAVAILABLE)
        if (user == ROOT_USER) refuseInstallation(USER_INSTALLATION_REQUIRED)
        val lock = policy.tryLock(policy.managementLock) ?: refuseInstallation("automatic_settings_busy")
        return lock.use {
            val recorded = policy.settings()
            val selected = recorded ?: if (operation == ON) createSettings() else return null
            require(selected.os == platform.os && selected.userId == user)
            val disabled = selected.copy(enabled = false)
            policy.save(disabled) // fail closed before the registration changes or a setup is cancelled
            when (operation) {
                ON -> {
                    schedule.install(disabled)
                    val enabled = disabled.copy(enabled = true)
                    policy.save(enabled)
                    schedule.start(enabled)
                    enabled
                }
                OFF -> disabled.also { schedule.disable(it) }
                else -> {
                    val worker = policy.tryLock(policy.workerLock) ?: refuseInstallation(WORKER_BUSY)
                    worker.use {
                        schedule.uninstall(disabled)
                        policy.remove()
                    }
                    null
                }
            }
        }
    }
}

private const val ON = "on"
private const val OFF = "off"
private val OPERATIONS = setOf(ON, OFF, "uninstall")

/** Another attempt holds the worker's lock. */
internal const val WORKER_BUSY = "automatic_worker_busy"

/** The automatic updates belong to a user, never to root. */
internal const val USER_INSTALLATION_REQUIRED = "release_user_installation_required"
