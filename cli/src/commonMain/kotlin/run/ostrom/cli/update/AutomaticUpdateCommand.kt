// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.IOException
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.Environment

/**
 * `ostrom update --automatic on|off|status|uninstall` and the scheduled attempt (TeamCode's `automaticUpdateCommand`,
 * without its service): only for a managed installation, the one [root] names or the one [executable] runs from. An
 * ordinary command never registers a job.
 *
 * @param installations The managed installations of this system; null where there are none.
 * @param platform The system and the user the job belongs to.
 * @param environment Where the CLI folder and the home are, and `OSTROM_DISABLE_AUTOUPDATE`.
 * @param schedule The registration of the job, for the installation given.
 * @param fetch Reads the signed latest release.
 * @param install Installs a release into the installation with the recorded settings: the version selected, or null.
 * @param say Writes a line for the developer.
 */
@Suppress("LongParameterList") // One parameter per collaborator, each replaced in tests.
internal class AutomaticUpdateCommand(
    private val installations: Installations?,
    private val root: String?,
    private val executable: String?,
    private val platform: AutomaticUpdatePlatform,
    private val environment: Environment,
    private val schedule: (InstallationStore) -> AutomaticUpdateSchedule,
    private val fetch: suspend () -> ReleaseCheck?,
    private val install: suspend (InstallationStore, ReleaseCheck, AutomaticUpdateSettings) -> InstalledVersion?,
    private val say: (String) -> Unit,
) {
    private val disabled = environment["OSTROM_DISABLE_AUTOUPDATE"] != null

    /** Applies [operation] (`on`, `off`, `uninstall`, or `status` alone) and says the state; the exit code. */
    suspend fun configure(operation: String): Int = guarded {
        val store = managed()
        val policy = AutomaticUpdateStore(store)
        val registration = schedule(store)
        if (operation != STATUS) {
            AutomaticUpdateManager(policy, registration, platform) {
                automaticUpdateSettings(policy.paths, platform, environment)
            }.configure(operation)
        }
        val settings = policy.settings()
        say("Automatic updates: ${if (settings?.enabled == true) "on" else "off"}.")
        say("Registration present: ${if (settings != null && registration.owns(settings)) "yes" else "no"}.")
        policy.result()?.let { say("Last attempt: ${describe(it)}.") }
        if (disabled) say("Environment override: OSTROM_DISABLE_AUTOUPDATE turns automatic updates off here.")
        ExitCode.OK.code
    }

    /** One scheduled attempt, when the updates are on; the exit code (1 when it failed). */
    suspend fun attempt(): Int = guarded {
        val store = managed()
        val result = AutomaticUpdateRunner(AutomaticUpdateStore(store), platform, disabled)
            .run(fetch) { candidate, settings -> install(store, candidate, settings) }
        say("Automatic update: ${if (result == null) "DISABLED" else describe(result)}.")
        if (result?.outcome == AutomaticUpdateResult.FAILED) ExitCode.FAILED.code else ExitCode.OK.code
    }

    /** The managed installation, checked, with its launcher; refused when there is none. */
    private fun managed(): InstallationStore {
        val all = installations ?: refuseInstallation(NOT_MANAGED)
        val store = if (root != null) all.at(root) else executable?.let(all::of)
        val current = store?.state() ?: refuseInstallation(NOT_MANAGED)
        store.validate(current.current)
        if (store.launcherVersion() == null) refuseInstallation(NOT_MANAGED)
        store.ensureLauncher(current.current)
        return store
    }

    private suspend fun guarded(block: suspend () -> Int): Int = try {
        block()
    } catch (refused: InstallationException) {
        val (code, text) = automaticRefusal(refused.code)
        say(text)
        code.code
    } catch (failure: IOException) {
        say("✗ ostrom could not read or write its automatic updates: ${failure.message}")
        ExitCode.CANNOT_WRITE.code
    } catch (_: IllegalArgumentException) {
        // settings recorded for another user or system, or a CLI folder or home that is not an absolute path
        val (code, text) = automaticRefusal(AutomaticUpdateStore.SETTINGS_INVALID)
        say(text)
        code.code
    }

    private fun describe(result: AutomaticUpdateResult): String =
        result.outcome + result.version?.let { " ($it)" }.orEmpty() + result.diagnostic?.let { " [$it]" }.orEmpty() +
            " at ${result.attemptedAtMillis}"

    private companion object {
        const val STATUS = "status"
    }
}

/** No managed installation here: `ostrom install` makes one. */
internal const val NOT_MANAGED = "release_not_managed"

/** The exit code and the words of the automatic updates' refusal [code]. */
internal fun automaticRefusal(code: String): Pair<ExitCode, String> = when (code) {
    NOT_MANAGED -> ExitCode.REFUSED to
        "✗ Automatic updates need a managed installation: install ostrom with ostrom install, then run this again."
    USER_INSTALLATION_REQUIRED -> ExitCode.REFUSED to
        "✗ Automatic updates belong to a user: run this as yourself, not as root."
    USER_UNAVAILABLE -> ExitCode.FAILED to "✗ ostrom cannot tell which user this is. Nothing was changed."
    "automatic_settings_busy", WORKER_BUSY -> ExitCode.FAILED to
        "✗ An automatic update or another change of its settings is running. Try again once it has ended."
    "automatic_registration_modified" -> ExitCode.REFUSED to
        "✗ The registration of the automatic updates was changed outside ostrom: it is left alone. " +
        "Remove it yourself, then run this again."
    "automatic_registration_missing" -> ExitCode.FAILED to
        "✗ The registration of the automatic updates is missing: run ostrom update --automatic on to put it back."
    "automatic_manager_rejected", "automatic_manager_unavailable" -> ExitCode.FAILED to
        "✗ The system's scheduler refused the automatic updates ($code). They stay off."
    AutomaticUpdateStore.SETTINGS_INVALID -> ExitCode.FAILED to
        "✗ The settings of the automatic updates are damaged ($code): delete automatic-update*.json in the " +
        "installation's folder, then run ostrom update --automatic on."
    else -> installationRefusal(code)
}
