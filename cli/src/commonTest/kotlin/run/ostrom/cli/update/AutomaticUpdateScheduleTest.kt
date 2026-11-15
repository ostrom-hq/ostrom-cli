// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The registration of the hourly job with each system's scheduler (TeamCode's AutomaticUpdateScheduleTest). */
class AutomaticUpdateScheduleTest {
    private val fs = installationFileSystem()
    private val policy = AutomaticUpdateStore(storeOn(fs, FakeInstallationFiles()).also { it.initialize() })
    private val calls = mutableListOf<String>()

    private fun settings(os: String, user: String = if (os == WINDOWS) "ACME\\maya" else "501") =
        automaticUpdateSettings(policy.paths, AutomaticUpdatePlatform(os, user), variables("HOME" to "/home/dev"), ID)

    private fun schedule(answer: (String) -> Int = { 0 }) = AutomaticUpdateScheduleImpl(fs, WINDIR) { name, args ->
        val call = (listOf(name.substringAfterLast('\\').substringAfterLast('/')) + args.take(2)).joinToString(" ")
        calls += call
        answer(call)
    }

    @Test
    fun repeatedDisableAndUninstallResolveCustomUnitsWithoutStoppingTheWorker() = runTest {
        val settings = settings(LINUX, "1000")
        // The user's manager links custom-path definitions into its own search directory. Disabling removes that
        // link as well as enablement links.
        val systemd = FakeSystemd()
        val schedule = AutomaticUpdateScheduleImpl(fs, WINDIR, systemd::run)
        schedule.install(settings)
        assertTrue(systemd.enabled && systemd.runningTimer)
        schedule.start(settings)
        repeat(2) {
            schedule.disable(settings)
            assertFalse(systemd.enabled || systemd.runningTimer)
            assertTrue(systemd.workerRunning)
            assertTrue(schedule.owns(settings))
        }
        schedule.install(settings)
        assertTrue(systemd.enabled, "on again enables the definitions already in place")
        schedule.uninstall(settings)
        assertFalse(systemd.enabled || systemd.runningTimer)
        assertTrue(systemd.workerRunning)
        assertTrue(systemd.available.isEmpty())
        assertTrue(automaticUpdateDefinitions(settings).keys.none(fs::exists))
        assertFalse(schedule.owns(settings))
    }

    @Test
    fun launchdEnablesBootstrapsOnceAndNeverKillsAnAttempt() = runTest {
        val settings = settings(MACOS)
        val job = "gui/501/${settings.label}"
        var loaded = false
        val schedule = schedule { call ->
            when {
                call.startsWith("launchctl print") -> if (loaded) 0 else 113
                call.startsWith("launchctl bootstrap") -> 0.also { loaded = true }
                call.startsWith("launchctl bootout") -> 0.also { loaded = false }
                else -> 0
            }
        }
        schedule.install(settings)
        assertEquals(
            listOf("launchctl enable $job", "launchctl print $job", "launchctl bootstrap gui/501"),
            calls,
        )
        calls.clear()
        schedule.install(settings)
        assertEquals(listOf("launchctl enable $job", "launchctl print $job"), calls, "already loaded")
        calls.clear()
        schedule.start(settings)
        schedule.disable(settings)
        schedule.uninstall(settings)
        assertEquals(
            listOf(
                "launchctl kickstart $job",
                "launchctl disable $job",
                "launchctl disable $job",
                "launchctl print $job",
                "launchctl bootout $job",
            ),
            calls,
        )
        assertFalse(fs.exists(settings.registration.toPath()))
        fs.createDirectories((settings.registration.toPath() / "..").normalized())
        fs.write(settings.registration.toPath()) { write(automaticUpdateDefinitions(settings).values.single()) }
        calls.clear()
        schedule.uninstall(settings)
        assertEquals(listOf("launchctl disable $job", "launchctl print $job"), calls, "not loaded: no bootout")
    }

    @Test
    fun taskSchedulerCreatesTheTaskOnceAndRefusesWhenItCannotSay() = runTest {
        val settings = settings(WINDOWS)
        var present = false
        var status = { if (present) 0 else 3 }
        val schedule = schedule { call ->
            when {
                call.startsWith("powershell.exe") -> status()
                call.startsWith("schtasks.exe /Create") -> 0.also { present = true }
                call.startsWith("schtasks.exe /Delete") -> 0.also { present = false }
                else -> 0
            }
        }
        schedule.install(settings)
        schedule.install(settings)
        schedule.start(settings)
        schedule.disable(settings)
        schedule.uninstall(settings)
        val ps = "powershell.exe -NoLogo -NoProfile"
        assertEquals(
            listOf(
                ps, "schtasks.exe /Create /TN", "schtasks.exe /Change /TN",
                ps, "schtasks.exe /Change /TN",
                "schtasks.exe /Run /TN",
                ps, "schtasks.exe /Change /TN",
                ps, "schtasks.exe /Change /TN", ps, "schtasks.exe /Delete /TN",
            ),
            calls,
        )
        fs.write(settings.registration.toPath()) { write(automaticUpdateDefinitions(settings).values.single()) }
        calls.clear()
        schedule.uninstall(settings)
        assertEquals(listOf(ps, ps), calls, "no task: nothing to disable or delete")
        status = { 2 }
        val unavailable = assertFailsWith<InstallationException> { schedule.install(settings) }
        assertEquals("automatic_manager_unavailable", unavailable.code)
        status = { 1 }
        assertFailsWith<InstallationException> { schedule.disable(settings) }
    }

    @Test
    fun onlyTheExactRecordedDefinitionsAreManaged() = runTest {
        val settings = settings(MACOS)
        val path = settings.registration.toPath()
        val schedule = schedule()
        val missing = assertFailsWith<InstallationException> { schedule.start(settings) }
        assertEquals("automatic_registration_missing", missing.code)
        val bytes = automaticUpdateDefinitions(settings).values.single()
        fs.createDirectories((path / "..").normalized())
        val changed = listOf(
            bytes.copyOf(bytes.size + 1),
            bytes.copyOf(bytes.size - 1),
            bytes.copyOf().also { it[0] = 'x'.code.toByte() },
        )
        for (other in changed) {
            fs.write(path) { write(other) }
            assertEquals("automatic_registration_modified", assertFailsWith<InstallationException> {
                schedule.owns(settings)
            }.code)
        }
        fs.delete(path)
        fs.createDirectories(path)
        assertFailsWith<InstallationException> { schedule.owns(settings) }
        fs.delete(path)
        val agents = (path / "..").normalized()
        fs.deleteRecursively(agents)
        fs.createDirectories("/home/dev/elsewhere".toPath())
        fs.createSymlink(agents, "/home/dev/elsewhere".toPath())
        val linked = assertFailsWith<InstallationException> { schedule.install(settings) }
        assertEquals("automatic_registration_modified", linked.code)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aManagerThatRefusesStopsTheChange() = runTest {
        val settings = settings(LINUX, "1000")
        val rejected = assertFailsWith<InstallationException> { schedule { 1 }.install(settings) }
        assertEquals("automatic_manager_rejected", rejected.code)
    }

    /** A user's systemd: it links custom-path definitions, and disabling removes those links too. */
    private class FakeSystemd {
        val available = mutableSetOf<String>()
        var enabled = false
        var runningTimer = false
        var workerRunning = true

        fun run(executable: String, arguments: List<String>): Int {
            assertEquals("/usr/bin/systemctl", executable)
            assertEquals("--user", arguments.first())
            val unit = arguments.last().toPath().name
            return when (arguments[1]) {
                "link" -> 0.also { available.add(unit) }
                "enable" -> 0.also {
                    available.add(unit)
                    enabled = true
                    runningTimer = true
                }
                "daemon-reload", "start" -> 0
                "disable" -> disable(unit, arguments)
                else -> error("Unexpected systemd operation")
            }
        }

        private fun disable(unit: String, arguments: List<String>): Int {
            if (!available.remove(unit)) return 1
            if (unit.endsWith(".timer")) {
                enabled = false
                runningTimer = false
            }
            if (unit.endsWith(".service") && "--now" in arguments) workerRunning = false
            return 0
        }
    }

    private companion object {
        const val WINDIR = "C:\\Windows"
        val ID = "b".repeat(32)
    }
}
