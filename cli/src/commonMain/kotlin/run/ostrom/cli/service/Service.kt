// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's service as it is (copy first, polish later): its long lines and dense functions stay.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.GroupCommand
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.serverOf
import run.ostrom.cli.commands.signedIn
import run.ostrom.cli.daemon.runDaemon
import run.ostrom.cli.daemon.systemDaemonPorts
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.holdExclusiveLock

/** `ostrom service print|install|start|stop|restart|status|logs|uninstall|automatic`, and the hidden `serve`. */
internal class ServiceCommand(
    dependencies: CliDependencies,
    manager: ServiceManager,
    platform: () -> ServicePlatform,
    cloudRunner: () -> Boolean,
    daemon: ServiceDaemon,
) : GroupCommand(
    "service",
    "Manage the local service: automatic startup at login or, explicitly, at boot",
    comingSoon = false,
) {
    init {
        val context = ServiceContext(dependencies, manager, platform, cloudRunner, daemon)
        subcommands(
            Install(context),
            Print(context),
            Manage(context, "start"),
            Manage(context, "stop"),
            Manage(context, "restart"),
            Manage(context, "uninstall"),
            Status(context),
            Logs(context),
            Automatic(context),
            Serve(context),
        )
    }
}

/** `ostrom service` on this device: the real OS service manager, platform and cloud runner check. */
// coverage: the wiring of the real OS; every piece of it is tested on its own, and the commands with fakes.
@ExcludeFromCoverage
internal fun serviceCommand(dependencies: CliDependencies): ServiceCommand = ServiceCommand(
    dependencies,
    ServiceManager(SystemServiceRunner, { dependencies.environment["SystemRoot"] }, { delay(it) }),
    ::servicePlatform,
    { insideCloudRunner(dependencies.environment) },
    { settings, report, stop -> runDaemon(dependencies, settings, report, stop, systemDaemonPorts()) },
)

/** What `service serve` runs while it holds its lock: the device's daemon, until the stop request. */
internal typealias ServiceDaemon = suspend (ServiceSettings, (String) -> Unit, () -> Boolean) -> Unit

/** What the subcommands of `service` share. */
internal class ServiceContext(
    val dependencies: CliDependencies,
    val manager: ServiceManager,
    val platform: () -> ServicePlatform,
    val cloudRunner: () -> Boolean,
    val daemon: ServiceDaemon,
) {
    /** The configuration file the service belongs to: `config.toml` in the CLI folder. */
    fun configPath(): String = dependencies.configFile(dependencies.directory())

    /** `service.json`; a boot registration must name the account [platform] says this process runs as. */
    fun load(config: String): ServiceSettings? = loadService(config, platform)

    fun echo(text: String) = dependencies.console.out(text + "\n")

    fun fail(text: String): Nothing = throw PrintMessage("ostrom: $text", ExitCode.FAILED.code, printError = true)
}

private fun ServiceContext.settings(config: String, executable: String?, atBoot: Boolean): ServiceSettings {
    val platform = platform()
    if (platform.userId == "0") fail("Run service installation as your user; Ostrom requests OS authorization only where needed.")
    val home = dependencies.environment.homeDirectory ?: fail("Cannot locate the user home directory.")
    // the stable launcher of a managed installation, never one of its payloads (deleted two updates later)
    return newServiceSettings(config, executable ?: dependencies.executable ?: platform.executable, atBoot, platform, home)
}

/** A new registration's settings: a random id, and the registration path of [platform]'s startup mode. */
internal fun newServiceSettings(
    config: String,
    executable: String,
    atBoot: Boolean,
    platform: ServicePlatform,
    home: String,
): ServiceSettings {
    val id = randomHex(ID_BYTES)
    val label = "$SERVICE_LABEL_PREFIX$id"
    val registration = when (platform.os) {
        "macos" -> if (atBoot) "/Library/LaunchDaemons/$label.plist" else "$home/Library/LaunchAgents/$label.plist"
        "windows" -> config.toPath().parent!!.resolve("service-task.xml").toString()
        else -> "${config.toPath().parent!!.parent}/systemd/user/$label.service"
    }
    return ServiceSettings(id, executable, config, registration, platform.os, platform.userId, atBoot, platform.userName, platform.userHome)
}

private const val ID_BYTES = 16

private const val AT_BOOT_HELP = "Start before login; requires OS authorization and, on Windows, your Windows account password."

private class Print(private val context: ServiceContext) : CliktCommand(name = "print") {
    private val atBoot by option("--at-boot", "--system", help = AT_BOOT_HELP).flag()
    private val binary by option("--binary", help = "Stable absolute path to the native Ostrom executable.")

    override fun help(context: Context) = "Print the OS registration without installing it"

    override fun run() {
        val config = context.configPath()
        val selected = context.load(config) ?: context.settings(config, binary, atBoot)
        if (atBoot && !selected.atBoot) context.fail("Uninstall before switching startup modes.")
        context.echo(serviceDefinition(selected).trimEnd('\n'))
    }
}

private class Install(private val context: ServiceContext) : CliktCommand(name = "install") {
    private val atBoot by option("--at-boot", "--system", help = AT_BOOT_HELP).flag()
    private val binary by option("--binary", help = "Stable absolute path; defaults to the running executable.")

    override fun help(context: Context) = "Install the service and start it now; it starts at login (or at boot)"

    override fun run() {
        // One pod runs one session, so a cloud session never installs a service that would make it a device.
        if (context.cloudRunner()) {
            context.echo(CLOUD_RUNNER_REFUSAL)
            return
        }
        runBlocking { register() }
    }

    private suspend fun register() {
        context.dependencies.signedIn(null)
        val config = context.configPath()
        val existing = context.load(config)
        val selected = existing ?: context.settings(config, binary, atBoot)
        if (atBoot && !selected.atBoot) context.fail("Uninstall before switching from login to boot startup.")
        if (binary != null && binary != selected.executable) context.fail("Uninstall before changing the service executable.")
        if (FileSystem.SYSTEM.metadataOrNull(selected.executable.toPath())?.isRegularFile != true) {
            context.fail("The executable must exist at a stable absolute path.")
        }
        // An explicit installation is the opposite of the opt-out an earlier uninstall recorded.
        setAutomaticServiceSetup(config, true)
        if (selected.atBoot) context.echo(bootNotice(selected.os))
        // The OS authorization (sudo, an elevated task) comes first: a declined one leaves no service.json behind.
        installServiceRegistration(selected, context.manager.runner)
        if (selected.atBoot && selected.os == "linux") enableLinuxBootStartup(selected, context.manager.runner)
        if (existing == null) writePrivateFile(servicePath(config), serviceJson.encodeToString(ServiceSettings.serializer(), selected))
        if (selected.os == "linux") {
            context.manager.manager(selected, "daemon-reload")
            context.manager.manager(selected, "enable")
        }
        context.manager.start(selected)
        context.echo("Installed service ${selected.label}; it starts automatically at ${if (selected.atBoot) "boot" else "login"}.")
        context.echo("Use ostrom service status to check it.")
    }

}

/** What `service install --at-boot` says before it asks the OS for authorization. */
internal fun bootNotice(os: String) = when (os) {
    "macos" -> "Installing a LaunchDaemon running as your user. macOS may request administrator authorization."
    "windows" -> "Boot startup needs an elevated terminal and your Windows account password in a masked OS prompt. " +
        "Ostrom does not store it."
    else -> "Enabling systemd user lingering for boot startup. OS authorization may be requested; " +
        "lingering is retained after uninstall."
}

private class Manage(private val context: ServiceContext, private val operation: String) : CliktCommand(name = operation) {
    override fun help(context: Context) = when (operation) {
        "start" -> "Start the installed service"
        "stop" -> "Stop the service, which finishes what it hosts first (up to 10 minutes)"
        "restart" -> "Stop the service, then start it again"
        else -> "Remove the service and its registration (your login and logs stay)"
    }

    override fun run() {
        val selected = context.load(context.configPath())
        if (selected == null) {
            if (operation != "uninstall" && operation != "stop") context.fail("Run ostrom service install first.")
            context.echo("No Ostrom service is installed for this configuration.")
            return
        }
        runBlocking { operate(selected) }
    }

    private suspend fun operate(selected: ServiceSettings) {
        val manager = context.manager
        when (operation) {
            "start" -> manager.start(selected)
            "stop" -> {
                manager.stop(selected)
                if (!selected.atBoot && automaticServiceSetup(selected.config)) {
                    context.echo(
                        "Stopped. It starts again at your next login, and ostrom install or ostrom update " +
                            "start it unless ostrom service automatic is off.",
                    )
                }
            }
            "restart" -> {
                context.echo("Waiting for the service to finish what it hosts before restarting, for up to 10 minutes.")
                manager.stop(selected)
                manager.start(selected)
            }
            else -> uninstall(selected)
        }
    }

    private suspend fun uninstall(selected: ServiceSettings) {
        val manager = context.manager
        if (!ownsRegistration(selected)) context.fail("The registration is missing. Repair it with service install before uninstalling.")
        manager.stop(selected)
        if (selected.os == "linux") manager.manager(selected, "disable")
        if (selected.os == "windows") {
            when (manager.manager(selected, "status", false)) {
                0 -> manager.manager(selected, "delete")
                WINDOWS_TASK_MISSING -> Unit
                else -> error("Cannot query Windows Task Scheduler.")
            }
            FileSystem.SYSTEM.delete(serviceStopPath(selected.config).toPath(), mustExist = false)
        }
        removeServiceRegistration(selected, manager.runner)
        if (selected.os == "linux") manager.manager(selected, "daemon-reload")
        FileSystem.SYSTEM.delete(servicePath(selected.config).toPath())
        // Otherwise the next automatic setup would install it again.
        setAutomaticServiceSetup(selected.config, false)
        context.echo("Removed the Ostrom service. Saved login and logs are preserved.")
        context.echo("ostrom service install or ostrom service automatic on sets it up again.")
    }

    private companion object {
        const val WINDOWS_TASK_MISSING = 3
    }
}

private class Status(private val context: ServiceContext) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Say whether the service is installed, running, and signed in"

    override fun run() {
        val selected = context.load(context.configPath())
        context.echo("Installed: ${selected != null && ownsRegistration(selected)}")
        if (selected != null) context.echo("Startup: ${if (selected.atBoot) "boot (before login)" else "user login"}")
        context.echo("Running: ${selected != null && serviceRunning(selected.config)}")
        val directory = context.dependencies.directory()
        val login = context.dependencies.credentials(directory).load(context.dependencies.serverOf(null, directory))?.login
        context.echo(if (login == null) "Authenticated: no saved login" else "Authenticated: @$login")
    }
}

/** Whether a service holds the lock of this configuration: it holds `daemon.lock` for its whole run. */
internal fun serviceRunning(config: String): Boolean {
    val lock = try {
        holdExclusiveLock(daemonLockPath(config))
    } catch (_: IOException) {
        // a lock file that cannot be opened is no running service
        return false
    }
    lock?.close()
    return lock == null
}

private class Logs(private val context: ServiceContext) : CliktCommand(name = "logs") {
    override fun help(context: Context) = "Print the service's log (at most 128 lines)"

    override fun run() {
        val path = serviceLogPath(context.configPath()).toPath()
        val meta = FileSystem.SYSTEM.metadataOrNull(path)
        if (meta == null) {
            context.echo("No service logs yet.")
            return
        }
        require(isPlainFile(meta, LOG_BYTES))
        context.dependencies.console.out(FileSystem.SYSTEM.read(path) { readUtf8() })
    }

    private companion object {
        const val LOG_BYTES = 65_536L
    }
}

private class Automatic(private val context: ServiceContext) : CliktCommand(name = "automatic") {
    private val state by argument("on|off|status").choice("on", "off", "status").optional()

    override fun help(context: Context) =
        "Whether ostrom install, ostrom update and the automatic update job install this service when it is missing " +
            "and repair its login startup (on by default; --no-service skips it once)."

    override fun run() {
        val config = context.configPath()
        when (state) {
            "on" -> setAutomaticServiceSetup(config, true)
            "off" -> setAutomaticServiceSetup(config, false)
        }
        context.echo("Automatic service setup: ${if (automaticServiceSetup(config)) "on" else "off"}.")
    }
}

/** `ostrom service serve --config PATH`: what the OS service manager starts. Not for people. */
private class Serve(private val context: ServiceContext) : CliktCommand(name = "serve") {
    private val config by option("--config", help = "Absolute saved configuration path, supplied by the service manager.").required()

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Run the service (started by the OS service manager)"

    override fun run() {
        require(config.toPath().isAbsolute)
        if (context.cloudRunner()) {
            context.dependencies.console.err(CLOUD_RUNNER_REFUSAL + "\n")
            return
        }
        val settings = requireNotNull(context.load(config)) { "Service settings are missing." }
        // not an error: the OS manager would restart a service that exits with one
        val busy = { context.dependencies.console.err("Another Ostrom service already runs for this configuration.\n") }
        runBlocking { serve(settings, busy, context.daemon) }
    }
}
