// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceManagerTest {
    private val windows = "C:\\Windows"

    private fun call(settings: ServiceSettings, action: String, quiet: Boolean = false) =
        serviceManagerCall(settings, action, quiet, windows).invocation

    private fun settings(os: String, atBoot: Boolean = false, user: String = if (os == "windows") "S-1-5-21-1" else "501") = ServiceSettings(
        "a".repeat(32), "/opt/ostrom", "/home/u/.config/ostrom/config.toml",
        if (atBoot && os == "macos") "/Library/LaunchDaemons/run.ostrom.${"a".repeat(32)}.plist" else "/home/u/reg",
        os, user, atBoot, "u", "/home/u",
    )

    @Test
    fun linuxUsesTheSystemdUserManager() {
        val linux = settings("linux")
        val unit = "run.ostrom.${"a".repeat(32)}.service"
        assertEquals(listOf("--user", "daemon-reload"), call(linux, "daemon-reload").arguments)
        assertEquals(listOf("--user", "enable", "/home/u/reg"), call(linux, "enable").arguments)
        assertEquals(listOf("--user", "is-enabled", "--quiet", unit), call(linux, "enabled").arguments)
        assertEquals(listOf("--user", "is-active", "--quiet", unit), call(linux, "running").arguments)
        assertEquals(listOf("--user", "stop", unit), call(linux, "stop").arguments)
        assertEquals("/usr/bin/systemctl", call(linux, "start").command)
    }

    @Test
    fun macosUsesItsLaunchdDomainAndElevatesOnlyBootChanges() {
        val agent = settings("macos")
        val label = "run.ostrom.${"a".repeat(32)}"
        assertEquals(listOf("print", "gui/501/$label"), call(agent, "status").arguments)
        assertEquals(listOf("kickstart", "gui/501/$label"), call(agent, "start").arguments)
        assertEquals(listOf("bootstrap", "gui/501", "/home/u/reg"), call(agent, "load").arguments)
        assertEquals(listOf("bootout", "gui/501/$label"), call(agent, "stop").arguments)
        assertEquals(listOf("enable", "gui/501/$label"), call(agent, "enable").arguments)
        assertEquals("/bin/sh", call(agent, "enabled").command)
        assertTrue(call(agent, "enabled").arguments.last() == label && "print-disabled" in call(agent, "enabled").arguments[1])
        assertTrue("state = running" in call(agent, "running").arguments[1])
        assertFailsWith<IllegalStateException> { call(agent, "disable") }

        val boot = settings("macos", atBoot = true)
        assertEquals(listOf("/bin/launchctl", "kickstart", "system/$label"), call(boot, "start").arguments)
        assertEquals("/usr/bin/sudo", call(boot, "start").command)
        assertEquals("-n", call(boot, "start", quiet = true).arguments.first())
        assertEquals("/bin/launchctl", call(boot, "status").command)
    }

    @Test
    fun windowsUsesSchtasksAndAPowerShellQueryWithItsValuesInTheEnvironment() {
        val login = settings("windows")
        val label = "run.ostrom.${"a".repeat(32)}"
        assertEquals("C:\\Windows\\System32\\schtasks.exe", call(login, "enable").command)
        assertEquals(listOf("/Create", "/TN", label, "/XML", "/home/u/reg"), call(login, "load").arguments)
        assertEquals(listOf("/Change", "/TN", label, "/Enable"), call(login, "enable").arguments)
        assertEquals(listOf("/Change", "/TN", label, "/Disable"), call(login, "disable").arguments)
        assertEquals(listOf("/Run", "/TN", label), call(login, "start").arguments)
        assertEquals(listOf("/Delete", "/TN", label, "/F"), call(login, "delete").arguments)
        assertFailsWith<IllegalStateException> { call(login, "stop") }
        val query = serviceManagerCall(login, "running", systemRoot = windows)
        assertTrue(query.invocation.command.endsWith("powershell.exe"))
        assertTrue("-NonInteractive" in query.invocation.arguments)
        assertFalse("exit 5" in query.invocation.arguments.last(), "a login task is not checked as a boot task")
        assertEquals(label, query.environment["OSTROM_SERVICE_TASK"])
        assertEquals("running", query.environment["OSTROM_SERVICE_OPERATION"])
        assertEquals("\"service\" \"serve\" \"--config\" \"/home/u/.config/ostrom/config.toml\"", query.environment["OSTROM_SERVICE_ARGUMENTS"])
        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\Modules", query.environment["PSModulePath"])

        val boot = settings("windows", atBoot = true)
        assertTrue("exit 5" in serviceManagerCall(boot, "status", systemRoot = windows).invocation.arguments.last())
        val register = serviceManagerCall(boot, "load", systemRoot = windows).invocation
        assertTrue("RegisterTask" in register.arguments.last())
        assertFailsWith<IllegalArgumentException> { serviceManagerCall(login, "status") }
    }

    @Test
    fun theManagerRunsEachCallWithItsTimeoutAndRefusesAFailure() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        val runner = FakeRunner { if ("restart" in it.arguments) 1 else 0 }
        val manager = serviceManager(runner, null)
        manager.manager(settings, "daemon-reload")
        manager.manager(settings, "stop")
        manager.manager(settings, "enabled")
        assertEquals(listOf(15_000L, 670_000L, 15_000L), runner.calls.map { it.timeoutMillis })
        assertEquals(listOf(false, false, true), runner.calls.map { it.quiet }, "queries are quiet")
        assertEquals(1, manager.manager(settings, "restart", checkResult = false))
        assertFailsWith<CliktError> { manager.manager(settings, "restart") }
        assertFailsWith<IllegalArgumentException> { manager.query(settings, "stop") }
        assertTrue(serviceManager(FakeRunner(), null).running(settings))

        val boot = settings("linux", atBoot = true)
        serviceManager(runner, null).manager(boot, "daemon-reload")
        assertEquals(180_000L, runner.calls.last().timeoutMillis)
        serviceManager(runner, null).manager(boot, "daemon-reload", quiet = true)
        assertEquals(15_000L, runner.calls.last().timeoutMillis)
    }

    @Test
    fun startNeedsItsRegistrationAndLoadsOrKicksPerSystem() = serviceScratch { scratch ->
        val missing = scratch.settings("linux")
        assertFailsWith<CliktError> { serviceManager(FakeRunner()).start(missing) }

        val linux = scratch.install(scratch.settings("linux"))
        val linuxRunner = FakeRunner()
        serviceManager(linuxRunner, null).start(linux, quiet = true)
        assertEquals(listOf("--user start ${linux.label}.service"), linuxRunner.calls.map { it.line.removePrefix("/usr/bin/systemctl ") })

        val mac = scratch.install(scratch.settings("macos"))
        val loaded = FakeRunner()
        serviceManager(loaded, null).start(mac)
        assertEquals(listOf("print", "kickstart"), loaded.calls.map { it.arguments.first() })
        val unloaded = FakeRunner { if (it.arguments.first() == "print") 113 else 0 }
        serviceManager(unloaded, null).start(mac)
        assertEquals(listOf("print", "bootstrap"), unloaded.calls.map { it.arguments.first() })
    }

    @Test
    fun startOnWindowsCreatesTheTaskIfNeededClearsTheStopRequestAndRunsIt() = serviceScratch { scratch ->
        val task = scratch.install(scratch.settings("windows"))
        writePrivateFile(serviceStopPath(task.config), task.id)
        fun actions(runner: FakeRunner) = runner.calls.map { it.environment["OSTROM_SERVICE_OPERATION"] ?: it.arguments.first() }
        val existing = FakeRunner()
        serviceManager(existing, "C:\\Windows").start(task)
        assertEquals(listOf("status", "enable", "start"), actions(existing))
        assertFalse(FileSystem.SYSTEM.exists(serviceStopPath(task.config).toPath()))
        val absent = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "status") 3 else 0 }
        serviceManager(absent, "C:\\Windows").start(task, quiet = true)
        assertEquals(listOf("status", "load", "enable", "start"), actions(absent))
        for (code in listOf(1, 2)) {
            val broken = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "status") code else 0 }
            assertFailsWith<IllegalStateException> { serviceManager(broken, "C:\\Windows").start(task) }
        }
    }

    @Test
    fun stopNeedsItsRegistrationAndWaitsForTheServiceToLeave() = serviceScratch { scratch ->
        assertFailsWith<CliktError> { serviceManager(FakeRunner()).stop(scratch.settings("linux")) }

        val linux = scratch.install(scratch.settings("linux"))
        val linuxRunner = FakeRunner()
        serviceManager(linuxRunner, null).stop(linux)
        assertEquals(1, linuxRunner.calls.size)

        val mac = scratch.install(scratch.settings("macos"))
        val statuses = ArrayDeque(listOf(0, 0, 0, 113))
        val pauses = mutableListOf<Long>()
        val draining = FakeRunner { if (it.arguments.first() == "print") statuses.removeFirst() else 0 }
        serviceManager(draining, null, { pauses += it }).stop(mac)
        assertEquals(listOf("print", "bootout", "print", "print", "print"), draining.calls.map { it.arguments.first() })
        assertEquals(listOf(250L, 250L), pauses)

        val gone = FakeRunner { if (it.arguments.first() == "print") 113 else 0 }
        serviceManager(gone, null).stop(mac)
        assertEquals(listOf("print", "print"), gone.calls.map { it.arguments.first() }, "an unloaded job is not stopped")

        val stuck = FakeRunner()
        assertFailsWith<CliktError> { serviceManager(stuck, null, {}).stop(mac) }
    }

    @Test
    fun stopOnWindowsDisablesTheTaskAsksForAStopAndWaitsForNoInstance() = serviceScratch { scratch ->
        val task = scratch.install(scratch.settings("windows"))
        fun operation(call: ToolCall) = call.environment["OSTROM_SERVICE_OPERATION"] ?: call.arguments.first()
        val none = FakeRunner { if (operation(it) == "status") 3 else 0 }
        serviceManager(none, "C:\\Windows").stop(task, quiet = true)
        assertEquals(listOf("status"), none.calls.map(::operation))

        val active = ArrayDeque(listOf(0, 0, 3))
        val pauses = mutableListOf<Long>()
        val draining = FakeRunner { if (operation(it) == "active") active.removeFirst() else 0 }
        serviceManager(draining, "C:\\Windows", { pauses += it }).stop(task)
        assertEquals(listOf("status", "disable", "active", "active", "active"), draining.calls.map(::operation))
        assertEquals(task.id, FileSystem.SYSTEM.read(serviceStopPath(task.config).toPath()) { readUtf8() })
        assertEquals(listOf(500L, 500L), pauses)

        for (code in listOf(1, 2)) {
            val broken = FakeRunner { if (operation(it) == "status") code else 0 }
            assertFailsWith<IllegalStateException> { serviceManager(broken, "C:\\Windows").stop(task) }
            val unknown = FakeRunner { if (operation(it) == "active") code else 0 }
            assertFailsWith<IllegalStateException> { serviceManager(unknown, "C:\\Windows").stop(task) }
        }
        val stuck = FakeRunner()
        assertFailsWith<CliktError> { serviceManager(stuck, "C:\\Windows", {}).stop(task) }
    }

    @Test
    fun loginStartupIsEnabledInTheUsersOwnDomainOnly() = serviceScratch { scratch ->
        fun operation(call: ToolCall) = call.environment["OSTROM_SERVICE_OPERATION"] ?: call.arguments.first()
        val linux = FakeRunner()
        serviceManager(linux, null).enableLogin(scratch.settings("linux"))
        assertEquals(listOf("daemon-reload", "enable"), linux.calls.map { it.arguments[1] })
        assertTrue(linux.calls.all { it.quiet })
        val mac = FakeRunner()
        serviceManager(mac, null).enableLogin(scratch.settings("macos"))
        assertEquals(listOf("enable"), mac.calls.map { it.arguments.first() })

        val known = FakeRunner()
        serviceManager(known, "C:\\Windows").enableLogin(scratch.settings("windows"))
        assertEquals(listOf("status", "enable"), known.calls.map(::operation))
        val create = FakeRunner { if (operation(it) == "status") 3 else 0 }
        serviceManager(create, "C:\\Windows").enableLogin(scratch.settings("windows"))
        assertEquals(listOf("status", "load", "enable"), create.calls.map(::operation))
        for (code in listOf(1, 2)) {
            val broken = FakeRunner { if (operation(it) == "status") code else 0 }
            assertFailsWith<IllegalStateException> { serviceManager(broken, "C:\\Windows").enableLogin(scratch.settings("windows")) }
        }
        assertFailsWith<IllegalArgumentException> { serviceManager(FakeRunner()).enableLogin(scratch.settings("linux", atBoot = true)) }
    }

    @Test
    fun theLoginServiceSystemReadsTheOsStateOfEachSystem() = serviceScratch { scratch ->
        fun operation(call: ToolCall) = call.environment["OSTROM_SERVICE_OPERATION"] ?: call.arguments.first()
        val none = SystemLoginService(serviceManager(FakeRunner { error("no OS call without a registration") }))
        assertEquals(LoginStartup(false, false, false, false), none.inspect(scratch.settings("linux")))

        val linux = scratch.install(scratch.settings("linux"))
        fun system(answer: (ToolCall) -> Int) = SystemLoginService(serviceManager(FakeRunner(answer), "C:\\Windows"))
        assertTrue(system { 0 }.inspect(linux).healthy)
        assertEquals(LoginStartup(true, true, true, false), system { if ("is-active" in it.arguments) 3 else 0 }.inspect(linux))

        val mac = scratch.install(scratch.settings("macos"))
        assertTrue(system { 0 }.inspect(mac).healthy)
        assertEquals(LoginStartup(true, true, false, false), system { if (it.arguments.first() == "print") 113 else 0 }.inspect(mac))

        val task = scratch.install(scratch.settings("windows"))
        assertTrue(system { 0 }.inspect(task).healthy)
        assertEquals(LoginStartup(true, false, false, false), system { 3 }.inspect(task))
        assertFailsWith<CliktError> { system { 2 }.inspect(task) }
        assertFailsWith<CliktError> { system { 1 }.inspect(task) }
        assertTrue(serviceManager(FakeRunner()).running(task))
        assertFalse(serviceManager(FakeRunner { 3 }).running(task))

        val actions = FakeRunner()
        val real = SystemLoginService(serviceManager(actions, null))
        real.register(scratch.settings("linux"))
        real.enable(linux)
        real.start(linux)
        assertEquals(listOf(true, true, true), actions.calls.map { it.quiet }, "no step of the login setup is interactive")
    }
}
