// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import kotlinx.coroutines.delay
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.commands.serverOf
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.update.InstallationStore
import run.ostrom.cli.update.Installations

/**
 * The local service step at the end of `ostrom install` and `ostrom update` (TeamCode's `setUpInstallationService`):
 * install the login service run by this installation's launcher, or check and repair its login startup. The install or
 * the update that ran before it is never undone, whatever happens here.
 */
fun interface ServiceStep {
    /**
     * Runs the step; its line, or null when this device has no CLI folder to keep a service in.
     *
     * @param noService True for `--no-service`; `OSTROM_NO_SERVICE` opts out as well.
     * @param installed `ostrom install` just installed into this root (null: its default one); the service is run by
     * that installation's launcher, whatever binary ran the install. Absent for `ostrom update`, which is the
     * installation the running binary belongs to, if any.
     */
    suspend fun setUp(dependencies: CliDependencies, noService: Boolean, installed: InstalledAt?): ServiceSetup?

    /** The steps every wiring has. */
    companion object {
        /** No step: what a wiring without a service does. */
        val None = ServiceStep { _, _, _ -> null }
    }
}

/**
 * The installation `ostrom install` has just made.
 *
 * @property root Its folder, or null for this user's default one.
 */
data class InstalledAt(val root: String?)

/** The step of [installations]: the service of this device's `config.toml`, run by the launcher of its installation. */
internal class InstallationServiceStep(
    private val installations: Installations,
    private val cloudRunner: (CliDependencies) -> Boolean,
    private val platform: () -> ServicePlatform,
    private val system: (CliDependencies) -> LoginServiceSystem,
) : ServiceStep {
    override suspend fun setUp(
        dependencies: CliDependencies,
        noService: Boolean,
        installed: InstalledAt?,
    ): ServiceSetup? {
        val environment = dependencies.environment
        val directory = environment.configDirectory ?: return null
        val config = dependencies.configFile(directory)
        val store = if (installed != null) {
            installations.at(installed.root)
        } else {
            installations.managed(dependencies.executable)
        }
        val optOut = when {
            noService -> "--no-service"
            !environment[NO_SERVICE_VARIABLE].isNullOrEmpty() -> NO_SERVICE_VARIABLE
            else -> null
        }
        val signedIn = {
            dependencies.credentials(directory).load(dependencies.serverOf(null, directory)) != null
        }
        // a binary outside a managed installation is reported and left alone
        return if (store == null) {
            setUpService(
                config, null, optOut, { cloudRunner(dependencies) }, platform, { environment.homeDirectory },
                signedIn, system = system(dependencies),
            )
        } else {
            setUpService(
                config, store.paths.launcher.toString(), optOut, { cloudRunner(dependencies) }, platform,
                { environment.homeDirectory }, signedIn,
                sameExecutable = { sameInstallation(it, store) },
                system = system(dependencies),
            )
        }
    }

    /** Whether [executable] belongs to the installation [store]: another spelling of the same path is the same one. */
    private fun sameInstallation(executable: String, store: InstallationStore): Boolean {
        val other = installations.managed(executable)
        return other != null && other.paths.root == store.paths.root
    }
}

/** What `ostrom install --no-service` is as an environment variable, for scripts and CI. */
internal const val NO_SERVICE_VARIABLE = "OSTROM_NO_SERVICE"

/** The step on this system: the real OS service managers. */
// coverage: the wiring of the real OS; the step is tested with a fake manager, the managers on their own.
@ExcludeFromCoverage
internal fun systemServiceStep(installations: Installations): ServiceStep = InstallationServiceStep(
    installations,
    { insideCloudRunner(it.environment) },
    ::servicePlatform,
    { SystemLoginService(ServiceManager(SystemServiceRunner, { it.environment["SystemRoot"] }, { delay(it) })) },
)
