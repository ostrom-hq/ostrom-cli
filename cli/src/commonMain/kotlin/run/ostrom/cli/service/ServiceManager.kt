// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's service as it is (copy first, polish later): its long lines and dense functions stay.
@file:Suppress(
    "MaxLineLength",
    "LongMethod",
    "CyclomaticComplexMethod",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.quoteWindowsArgument

/** The OS service manager command for an action; queries (`status`, `enabled`, `running`, `active`) never change anything. */
internal class ServiceManagerCall(val invocation: ServiceManagerInvocation, val environment: Map<String, String>)

internal val SERVICE_QUERIES = setOf("status", "enabled", "running", "active")

private const val STOP_MILLIS = 670_000L
private const val BOOT_MILLIS = 180_000L
private const val ORDINARY_MILLIS = 15_000L
private const val STOP_POLL_MILLIS = 250L
private const val TASK_POLL_MILLIS = 500L

/** What `launchctl`, `systemctl --user` or `schtasks` is asked to do for [action], and with which environment. */
internal fun serviceManagerCall(
    settings: ServiceSettings,
    action: String,
    quiet: Boolean = false,
    systemRoot: String? = null,
): ServiceManagerCall {
    val command: String
    val args: List<String>
    val root = if (settings.os == "windows") requireNotNull(systemRoot) else ""
    if (settings.os == "windows") {
        val system = "$root\\System32"
        if (settings.atBoot && action == "load") {
            command = "$system\\WindowsPowerShell\\v1.0\\powershell.exe"
            args = listOf("-NoLogo", "-NoProfile", "-Command", windowsBootRegistrationScript())
        } else if (action in SERVICE_QUERIES) {
            command = "$system\\WindowsPowerShell\\v1.0\\powershell.exe"
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                try {
                    ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'
                    ${'$'}scheduler.Connect()
                    ${'$'}task = ${'$'}scheduler.GetFolder('\').GetTasks(1) | Where-Object { ${'$'}_.Name -eq ${'$'}env:OSTROM_SERVICE_TASK }
                    if (-not ${'$'}task) { exit 3 }
                    ${if (settings.atBoot) windowsBootTaskCheck() else ""}
                    if (${'$'}env:OSTROM_SERVICE_OPERATION -in @('active', 'running') -and ${'$'}task.GetInstances(0).Count -eq 0) { exit 3 }
                    if (${'$'}env:OSTROM_SERVICE_OPERATION -eq 'enabled' -and -not ${'$'}task.Enabled) { exit 3 }
                    exit 0
                } catch { exit 2 }
            """.trimIndent()
            args = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
        } else {
            command = "$system\\schtasks.exe"
            args = when (action) {
                "load" -> listOf("/Create", "/TN", settings.label, "/XML", settings.registration)
                "enable" -> listOf("/Change", "/TN", settings.label, "/Enable")
                "disable" -> listOf("/Change", "/TN", settings.label, "/Disable")
                "start" -> listOf("/Run", "/TN", settings.label)
                "delete" -> listOf("/Delete", "/TN", settings.label, "/F")
                else -> error("Unsupported Task Scheduler operation.")
            }
        }
    } else if (settings.os == "macos") {
        val domain = if (settings.atBoot) "system" else "gui/${settings.userId}"
        val target = "$domain/${settings.label}"
        if (action == "enabled" || action == "running") {
            // launchctl has no exit status for these; read its report without a shell evaluating any value.
            command = "/bin/sh"
            args = listOf(
                "-c",
                if (action == "enabled") {
                    "! /bin/launchctl print-disabled \"${'$'}1\" | /usr/bin/grep -F -q -e \"\\\"${'$'}2\\\" => disabled\" -e \"\\\"${'$'}2\\\" => true\""
                } else {
                    "/bin/launchctl print \"${'$'}1/${'$'}2\" | /usr/bin/grep -q 'state = running'"
                },
                "sh",
                domain,
                settings.label,
            )
        } else {
            command = "/bin/launchctl"
            args = when (action) {
                "status" -> listOf("print", target)
                "start" -> listOf("kickstart", target)
                "load" -> listOf("bootstrap", domain, settings.registration)
                "stop" -> listOf("bootout", target)
                "enable" -> listOf("enable", target)
                else -> error("Unsupported launchd operation.")
            }
        }
    } else {
        command = "/usr/bin/systemctl"
        args = listOf("--user") + when (action) {
            "daemon-reload" -> listOf(action)
            "enable" -> listOf(action, settings.registration)
            "enabled" -> listOf("is-enabled", "--quiet", "${settings.label}.service")
            "running" -> listOf("is-active", "--quiet", "${settings.label}.service")
            else -> listOf(action, "${settings.label}.service")
        }
    }
    val invocation = if (settings.atBoot && settings.os == "macos" && action !in SERVICE_QUERIES) {
        ServiceManagerInvocation("/usr/bin/sudo", (if (quiet) listOf("-n") else emptyList()) + command + args)
    } else {
        ServiceManagerInvocation(command, args)
    }
    return ServiceManagerCall(invocation, if (settings.os == "windows") windowsEnvironment(settings, action, root) else emptyMap())
}

private fun windowsEnvironment(settings: ServiceSettings, action: String, systemRoot: String) = mapOf(
    "OSTROM_SERVICE_TASK" to settings.label,
    "OSTROM_SERVICE_OPERATION" to action,
    "OSTROM_SERVICE_REGISTRATION" to settings.registration,
    "OSTROM_SERVICE_USER" to settings.userId,
    "OSTROM_SERVICE_EXECUTABLE" to settings.executable,
    "OSTROM_SERVICE_ARGUMENTS" to serviceArguments(settings.config).joinToString(" ", transform = ::quoteWindowsArgument),
    // A caller running PowerShell 7 may export modules incompatible with Windows PowerShell 5.1.
    "PSModulePath" to "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\Modules",
)

/**
 * The OS service manager of this system: runs the calls of [serviceManagerCall] through [runner], and the start, stop
 * and login-startup sequences made of them.
 */
internal class ServiceManager(
    val runner: ServiceRunner,
    private val systemRoot: () -> String?,
    private val pause: suspend (Long) -> Unit,
) {
    suspend fun manager(settings: ServiceSettings, action: String, checkResult: Boolean = true, quiet: Boolean = false): Int {
        val call = serviceManagerCall(settings, action, quiet, systemRoot())
        val timeout = when {
            action in setOf("stop", "restart") -> STOP_MILLIS
            settings.atBoot && !quiet -> BOOT_MILLIS
            else -> ORDINARY_MILLIS
        }
        val result = runner.run(
            call.invocation.command,
            call.invocation.arguments,
            call.environment,
            quiet = quiet || action in SERVICE_QUERIES,
            timeoutMillis = timeout,
        )
        if (checkResult && result != 0) throw CliktError("The OS service manager rejected the operation.")
        return result
    }

    suspend fun start(settings: ServiceSettings, quiet: Boolean = false) {
        if (!ownsRegistration(settings)) throw CliktError("The service registration is missing. Run service install.")
        if (settings.os == "windows") {
            when (manager(settings, "status", false)) {
                TASK_MISSING -> manager(settings, "load", quiet = quiet)
                0 -> Unit
                else -> error("Cannot query Windows Task Scheduler.")
            }
            FileSystem.SYSTEM.delete(serviceStopPath(settings.config).toPath(), mustExist = false)
            manager(settings, "enable", quiet = quiet)
            manager(settings, "start", quiet = quiet)
            return
        }
        if (settings.os == "macos" && manager(settings, "status", false) != 0) {
            manager(settings, "load", quiet = quiet)
        } else {
            manager(settings, "start", quiet = quiet)
        }
    }

    suspend fun stop(settings: ServiceSettings, quiet: Boolean = false) {
        if (!ownsRegistration(settings)) {
            throw CliktError("The service registration is missing; refusing to stop an unknown service.")
        }
        if (settings.os == "windows") {
            stopWindowsTask(settings, quiet)
            return
        }
        if (settings.os != "macos" || manager(settings, "status", false) == 0) manager(settings, "stop", quiet = quiet)
        // bootout can return while the old process is still draining. Do not race its replacement.
        if (settings.os == "macos") {
            var waited = 0L
            while (manager(settings, "status", false) == 0) {
                if (waited >= STOP_MILLIS) throw CliktError("The service did not stop in time.")
                pause(STOP_POLL_MILLIS)
                waited += STOP_POLL_MILLIS
            }
        }
    }

    private suspend fun stopWindowsTask(settings: ServiceSettings, quiet: Boolean) {
        when (manager(settings, "status", false)) {
            TASK_MISSING -> return
            0 -> Unit
            else -> error("Cannot query Windows Task Scheduler.")
        }
        manager(settings, "disable", quiet = quiet)
        writePrivateFile(serviceStopPath(settings.config), settings.id)
        var waited = 0L
        while (true) {
            when (manager(settings, "active", false)) {
                TASK_MISSING -> break
                0 -> {
                    if (waited >= STOP_MILLIS) throw CliktError("The service did not stop in time.")
                    pause(TASK_POLL_MILLIS)
                    waited += TASK_POLL_MILLIS
                }
                else -> error("Cannot verify that the Windows task has stopped.")
            }
        }
    }

    /** The exit status of a query (`status`, `enabled`, `running`): 0 when true. Never elevated. */
    suspend fun query(settings: ServiceSettings, query: String): Int {
        require(query in SERVICE_QUERIES)
        return manager(settings, query, checkResult = false, quiet = true)
    }

    /** Login startup: a systemd user unit enabled after a reload, a launchd job no longer disabled, a logon task created and enabled. */
    suspend fun enableLogin(settings: ServiceSettings) {
        require(!settings.atBoot)
        when (settings.os) {
            "linux" -> {
                manager(settings, "daemon-reload", quiet = true)
                manager(settings, "enable", quiet = true)
            }
            "macos" -> manager(settings, "enable", quiet = true)
            else -> {
                when (manager(settings, "status", false, quiet = true)) {
                    TASK_MISSING -> manager(settings, "load", quiet = true)
                    0 -> Unit
                    else -> error("Cannot query Windows Task Scheduler.")
                }
                manager(settings, "enable", quiet = true)
            }
        }
    }

    suspend fun running(settings: ServiceSettings): Boolean =
        manager(settings, if (settings.os == "windows") "active" else "status", false) == 0

    private companion object {
        /** What the Task Scheduler query exits with when there is no such task (or no instance). */
        const val TASK_MISSING = 3
    }
}
