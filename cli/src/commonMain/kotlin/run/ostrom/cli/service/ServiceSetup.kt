// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's service as it is (copy first, polish later): its long lines and dense functions stay.
@file:Suppress(
    "MaxLineLength",
    "ReturnCount",
    "ThrowsCount",
    "TooGenericExceptionCaught",
    "LongMethod",
    "CyclomaticComplexMethod",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `ostrom install`, `ostrom update` and the automatic update job install the local service when it is missing, and check
 * that it starts at login: registered, enabled, loaded and running. What is missing is repaired, without elevation. A
 * registration made with `--at-boot` is left as it is.
 */
internal data class LoginStartup(val registered: Boolean, val enabled: Boolean, val loaded: Boolean, val running: Boolean) {
    val healthy: Boolean get() = registered && enabled && loaded && running
}

/** The OS operations the setup needs, all in the user's own login domain. */
internal interface LoginServiceSystem {
    suspend fun inspect(settings: ServiceSettings): LoginStartup

    /** Writes this configuration's registration file; never replaces one that differs. */
    suspend fun register(settings: ServiceSettings)

    /** Makes the OS start the registration at login. */
    suspend fun enable(settings: ServiceSettings)

    /** Loads the registration if needed and starts the service now. */
    suspend fun start(settings: ServiceSettings)
}

/** The OS service managers: launchd's `gui/UID` domain, the systemd user manager, or a logon task. */
internal class SystemLoginService(private val manager: ServiceManager) : LoginServiceSystem {
    override suspend fun inspect(settings: ServiceSettings): LoginStartup {
        if (!ownsRegistration(settings)) return LoginStartup(registered = false, enabled = false, loaded = false, running = false)
        return when (settings.os) {
            "macos" -> {
                val loaded = manager.query(settings, "status") == 0
                LoginStartup(true, manager.query(settings, "enabled") == 0, loaded, loaded && manager.query(settings, "running") == 0)
            }
            "windows" -> when (manager.query(settings, "status")) {
                TASK_MISSING -> LoginStartup(registered = true, enabled = false, loaded = false, running = false)
                0 -> LoginStartup(true, manager.query(settings, "enabled") == 0, true, manager.query(settings, "running") == 0)
                else -> throw CliktError("Cannot query Windows Task Scheduler.")
            }
            // A unit systemd does not know is reported neither enabled nor active; enabling it reloads the manager first.
            else -> {
                val enabled = manager.query(settings, "enabled") == 0
                LoginStartup(true, enabled, enabled, manager.query(settings, "running") == 0)
            }
        }
    }

    override suspend fun register(settings: ServiceSettings) = installServiceRegistration(settings, manager.runner)

    override suspend fun enable(settings: ServiceSettings) = manager.enableLogin(settings)

    override suspend fun start(settings: ServiceSettings) = manager.start(settings, quiet = true)

    private companion object {
        const val TASK_MISSING = 3
    }
}

/** How the service step ended. */
enum class ServiceSetupOutcome {
    INSTALLED,
    REPAIRED,
    UNCHANGED,
    AT_BOOT,
    OTHER_EXECUTABLE,
    OPTED_OUT,
    CLOUD_RUNNER,
    UNMANAGED,
    FAILED,
}

/**
 * The one line `install` and `update` print about the service, on the error stream when [failed].
 *
 * @property outcome How the step ended.
 * @property line What it says.
 */
data class ServiceSetup(val outcome: ServiceSetupOutcome, val line: String) {
    val failed: Boolean get() = outcome == ServiceSetupOutcome.FAILED
}

/**
 * Installs or repairs the login service of [config], run by [executable] (the managed installation's launcher, or null
 * when this CLI is not one: a build output or a copied binary leaves the service alone).
 * Never throws (except cancellation): a failure is a [ServiceSetupOutcome.FAILED] line naming the command to run, so it
 * never undoes the installation or update that ran before it.
 *
 * @param optOut Why this command skips the service (`--no-service`, `OSTROM_NO_SERVICE`), or null.
 * @param signedIn Whether this device is signed in: without a login the service waits for `ostrom login`.
 * @param sameExecutable Whether a saved service's executable is [executable], for example through another spelling of
 * the same path.
 * @param load Reads service.json; a boot registration's OS identity is checked against this process's.
 */
@Suppress("LongParameterList") // one parameter per thing a test replaces, as the callers name them
internal suspend fun setUpService(
    config: String,
    executable: String?,
    optOut: String?,
    cloudRunner: () -> Boolean,
    platform: () -> ServicePlatform,
    home: () -> String?,
    signedIn: () -> Boolean = { true },
    sameExecutable: (String) -> Boolean = { it == executable },
    system: LoginServiceSystem,
    load: (String) -> ServiceSettings? = ::loadService,
): ServiceSetup {
    fun result(outcome: ServiceSetupOutcome, line: String) = ServiceSetup(outcome, line)
    return try {
        // One pod runs one session, so a cloud session's runner never becomes a device.
        if (cloudRunner()) return result(ServiceSetupOutcome.CLOUD_RUNNER, "Local Ostrom service: not set up in a cloud session's runner.")
        if (optOut != null) return result(ServiceSetupOutcome.OPTED_OUT, "Local Ostrom service: not checked ($optOut).")
        if (!automaticServiceSetup(config)) {
            return result(
                ServiceSetupOutcome.OPTED_OUT,
                "Local Ostrom service: not checked, automatic setup is off (ostrom service automatic on turns it back on).",
            )
        }
        if (executable == null) {
            return result(
                ServiceSetupOutcome.UNMANAGED,
                "Local Ostrom service: not checked, this ostrom is not a managed installation (ostrom service install sets it up).",
            )
        }
        val identity = platform()
        if (identity.userId == "0") throw CliktError("run it as your own user, not as root")
        val existing = load(config)
        if (existing != null && existing.atBoot) {
            return result(ServiceSetupOutcome.AT_BOOT, "Local Ostrom service: starts at boot (--at-boot); its registration is left as it is.")
        }
        if (existing != null && !sameExecutable(existing.executable)) {
            return result(
                ServiceSetupOutcome.OTHER_EXECUTABLE,
                "Local Ostrom service: runs another executable (${existing.executable}); left as it is.",
            )
        }
        val settings = existing ?: createServiceSettings(
            config,
            executable,
            identity,
            home() ?: throw CliktError("cannot locate the user home directory"),
            load,
        )
        val before = system.inspect(settings)
        if (existing != null && before.healthy) {
            return result(ServiceSetupOutcome.UNCHANGED, "Local Ostrom service: starts at login and runs; nothing to change.")
        }
        val done = mutableListOf<String>()
        if (!before.registered) {
            system.register(settings)
            done += "registered"
        }
        if (!before.registered || !before.enabled) {
            system.enable(settings)
            done += "enabled at login"
        }
        if (!before.registered || !before.loaded || !before.running) {
            system.start(settings)
            done += "started"
        }
        val after = system.inspect(settings)
        if (!after.registered || !after.enabled || !after.loaded) {
            throw CliktError("the OS service manager does not report it enabled and loaded")
        }
        val login = if (signedIn()) "" else " It connects once you run ostrom login."
        if (existing == null) {
            result(ServiceSetupOutcome.INSTALLED, "Local Ostrom service: installed and started; it starts at login.$login")
        } else {
            result(ServiceSetupOutcome.REPAIRED, "Local Ostrom service: repaired (${done.joinToString(", ")}); it starts at login.$login")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val reason = failureReason(error)
        result(
            ServiceSetupOutcome.FAILED,
            "Local Ostrom service: not set up ($reason). Ostrom itself is installed; run ostrom service install to retry.",
        )
    }
}

/** Why a setup failed, as a Clikt error says it, or that nothing was expected. */
private fun failureReason(error: Exception): String {
    val message = if (error is CliktError) error.message else null
    return if (message == null) "unexpected error" else reasonOf(message)
}

/** A failure's message as it reads in the middle of a line: no final full stop, first letter lower case. */
private fun reasonOf(message: String): String = message.trimEnd('.').let { it.take(1).lowercase() + it.drop(1) }

/** service.json for a new login registration. A concurrent setup that wrote it first wins, and its settings are used. */
private fun createServiceSettings(
    config: String,
    executable: String,
    identity: ServicePlatform,
    home: String,
    load: (String) -> ServiceSettings?,
): ServiceSettings {
    val created = newServiceSettings(config, executable, atBoot = false, platform = identity, home = home)
    if (createPrivateFile(servicePath(config), serviceJson.encodeToString(ServiceSettings.serializer(), created))) return created
    return requireNotNull(load(config))
}

@Serializable
private class ServiceSetupPreference(val automatic: Boolean = true)

private const val PREFERENCE_BYTES = 4096L

private fun serviceSetupPath(config: String) = config.toPath().parent!!.resolve("service-setup.json")

/** Whether install and update set up the service (`ostrom service automatic`). On unless turned off; an unreadable file counts as on. */
internal fun automaticServiceSetup(config: String): Boolean {
    val path = serviceSetupPath(config)
    val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: return true
    if (!isPlainFile(metadata, PREFERENCE_BYTES)) return true
    return try {
        serviceJson.decodeFromString<ServiceSetupPreference>(FileSystem.SYSTEM.read(path) { readUtf8() }).automatic
    } catch (_: kotlinx.serialization.SerializationException) {
        true
    }
}

internal fun setAutomaticServiceSetup(config: String, enabled: Boolean) {
    if (enabled) {
        FileSystem.SYSTEM.delete(serviceSetupPath(config), mustExist = false)
    } else {
        writePrivateFile(
            serviceSetupPath(config).toString(),
            serviceJson.encodeToString(ServiceSetupPreference.serializer(), ServiceSetupPreference(false)),
        )
    }
}
