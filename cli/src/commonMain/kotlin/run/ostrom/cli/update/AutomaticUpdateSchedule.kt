// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** The operating system's registration of the hourly user job (TeamCode's `AutomaticUpdateSchedule`). */
internal interface AutomaticUpdateSchedule {
    /**
     * True when the exact recorded definitions of [settings] are in place, false when one is missing; refused
     * (`automatic_registration_modified`) when one was changed.
     */
    fun owns(settings: AutomaticUpdateSettings): Boolean

    /** Writes the missing definitions, once every existing one is checked, and registers and enables the job. */
    suspend fun install(settings: AutomaticUpdateSettings)

    /** Starts one attempt now, without waiting for it. */
    suspend fun start(settings: AutomaticUpdateSettings)

    /** Disables the job; an attempt under way goes on. */
    suspend fun disable(settings: AutomaticUpdateSettings)

    /** Disables and unregisters the job, then deletes its definitions. */
    suspend fun uninstall(settings: AutomaticUpdateSettings)
}

/**
 * [AutomaticUpdateSchedule] through `launchctl` (macOS), `systemctl --user` (Linux) and `schtasks.exe` (Windows),
 * on the exact recorded definitions only, never stopping an attempt under way.
 *
 * @param systemRoot `%SystemRoot%`, where `schtasks.exe` and PowerShell are.
 * @param execute Runs a manager quietly (no terminal, its output dropped) and returns its exit code, -1 when it could
 * not run or did not end in time.
 */
@Suppress("TooManyFunctions") // One function per change, and one per manager it goes through.
internal class AutomaticUpdateScheduleImpl(
    private val fileSystem: FileSystem,
    private val systemRoot: String,
    private val execute: suspend (String, List<String>) -> Int,
) : AutomaticUpdateSchedule {
    override fun owns(settings: AutomaticUpdateSettings): Boolean =
        automaticUpdateDefinitions(settings).all { (path, bytes) -> matches(path, bytes) ?: false }

    /** True when [path] holds exactly [bytes], null when it is absent; refused when it holds something else. */
    private fun matches(path: Path, bytes: ByteArray): Boolean? {
        val metadata = fileSystem.metadataOrNull(path) ?: return null
        // okio's metadata never follows a link: a link is not a regular file
        val same = metadata.isRegularFile && fileSystem.source(path).buffer().use { source ->
            // never more than the definition is read: a larger file is another one
            !source.request(bytes.size + 1L) && source.readByteArray().contentEquals(bytes)
        }
        return same || refuseInstallation(MODIFIED)
    }

    private fun requireOwned(settings: AutomaticUpdateSettings) {
        if (!owns(settings)) refuseInstallation("automatic_registration_missing")
    }

    override suspend fun install(settings: AutomaticUpdateSettings) {
        val definitions = automaticUpdateDefinitions(settings)
        // every existing file is checked before any missing one is written
        val missing = definitions.filter { (path, bytes) -> matches(path, bytes) == null }
        missing.forEach { (path, bytes) ->
            val parent = (path / "..").normalized()
            if (fileSystem.metadataOrNull(parent)?.symlinkTarget != null) refuseInstallation(MODIFIED)
            fileSystem.createDirectories(parent)
            fileSystem.write(path, mustCreate = true) { write(bytes) }
        }
        when (settings.os) {
            MACOS -> {
                // enabled first: launchd refuses to bootstrap a job that is disabled
                launch(settings, "enable")
                if (launch(settings, "print", check = false) != 0) {
                    command(LAUNCHCTL, listOf("bootstrap", "gui/${settings.userId}", settings.registration))
                }
            }
            LINUX -> {
                val service = (settings.registration.toPath() / ".." / "${settings.label}.service").normalized()
                system(listOf("link", service.toString()))
                system(listOf("daemon-reload"))
                system(listOf("enable", "--now", settings.registration))
            }
            else -> {
                if (windowsTask(settings) == MISSING) {
                    windows(listOf("/Create", "/TN", settings.label, "/XML", settings.registration))
                }
                windows(listOf("/Change", "/TN", settings.label, "/Enable"))
            }
        }
    }

    override suspend fun start(settings: AutomaticUpdateSettings) {
        requireOwned(settings)
        when (settings.os) {
            MACOS -> launch(settings, "kickstart") // no -k: never kill an attempt under way
            LINUX -> system(listOf("start", "--no-block", "${settings.label}.service"))
            else -> windows(listOf("/Run", "/TN", settings.label))
        }
    }

    override suspend fun disable(settings: AutomaticUpdateSettings) {
        requireOwned(settings)
        when (settings.os) {
            MACOS -> launch(settings, "disable")
            LINUX -> {
                // disable removes the links of definitions outside the manager's search path: only our verified timer
                // is linked back before a repeated off or uninstall; linking neither enables nor starts it
                system(listOf("link", settings.registration))
                system(listOf("disable", "--now", "${settings.label}.timer"))
            }
            else -> if (windowsTask(settings) == PRESENT) windows(listOf("/Change", "/TN", settings.label, "/Disable"))
        }
    }

    override suspend fun uninstall(settings: AutomaticUpdateSettings) {
        disable(settings)
        when (settings.os) {
            MACOS -> if (launch(settings, "print", check = false) == 0) launch(settings, "bootout")
            LINUX -> system(listOf("disable", "${settings.label}.service"))
            else -> if (windowsTask(settings) == PRESENT) windows(listOf("/Delete", "/TN", settings.label, "/F"))
        }
        automaticUpdateDefinitions(settings).keys.forEach { fileSystem.delete(it, mustExist = false) }
        if (settings.os == LINUX) system(listOf("daemon-reload"))
    }

    private suspend fun command(executable: String, arguments: List<String>, check: Boolean = true): Int {
        val result = execute(executable, arguments)
        if (check && result != 0) refuseInstallation("automatic_manager_rejected")
        return result
    }

    private suspend fun launch(settings: AutomaticUpdateSettings, action: String, check: Boolean = true) =
        command(LAUNCHCTL, listOf(action, "gui/${settings.userId}/${settings.label}"), check)

    private suspend fun system(arguments: List<String>) = command(SYSTEMCTL, listOf("--user") + arguments)

    private suspend fun windows(arguments: List<String>) = command("$systemRoot\\System32\\schtasks.exe", arguments)

    /** [PRESENT] or [MISSING]: whether Task Scheduler holds the task; refused when it cannot say. */
    private suspend fun windowsTask(settings: AutomaticUpdateSettings): Int {
        // the label is `run.ostrom.updates.<hex>`: nothing in it needs quoting inside the script
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'; ${'$'}scheduler.Connect()
                ${'$'}task = ${'$'}scheduler.GetFolder('\').GetTasks(1) | Where-Object { ${'$'}_.Name -eq '${settings.label}' }
                if (-not ${'$'}task) { exit 3 }; exit 0
            } catch { exit 2 }
        """.trimIndent()
        val powershell = "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        val status = command(powershell, listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), false)
        return status.takeIf { it == PRESENT || it == MISSING } ?: refuseInstallation("automatic_manager_unavailable")
    }

    private companion object {
        const val MODIFIED = "automatic_registration_modified"
        const val LAUNCHCTL = "/bin/launchctl"
        const val SYSTEMCTL = "/usr/bin/systemctl"
        const val PRESENT = 0
        const val MISSING = 3
    }
}
