// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
    "LongParameterList",
)

package run.ostrom.cli.service

import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.CliKit
import run.ostrom.cli.platform.holdExclusiveLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `ostrom service …`, on a scratch home folder, with the OS tools replaced by a [FakeRunner]. */
class ServiceCommandTest : CliKit() {
    private val fs = FileSystem.SYSTEM
    private val server = "https://ostrom.example"

    private fun run(
        scratch: ServiceScratch,
        vararg args: String,
        os: String = "linux",
        userId: String = "501",
        cloud: Boolean = false,
        signedIn: Boolean = true,
        runner: FakeRunner = FakeRunner(),
        executable: String? = scratch.launcher,
        environment: Map<String, String> = mapOf("HOME" to scratch.home.toString(), "OSTROM_URL" to server),
    ): Int {
        if (signedIn) signIn((scratch.home / ".config" / "ostrom").toString())
        val platform = ServicePlatform(os, scratch.launcher, if (os == "windows") "S-1-5-21-123-456-789-1001" else userId, "maya", scratch.home.toString())
        val manager = ServiceManager(runner, { "C:\\Windows" }, {})
        return cli(environment = environment, api = serverOf(emptyMap()), executable = executable, service = { ServiceCommand(it, manager, { platform }, { cloud }, { _, report, _ -> report("Daemon ran.") }) })
            .run(arrayOf("service") + args)
    }

    private fun launcher(scratch: ServiceScratch) {
        fs.createDirectories(scratch.launcher.toPath().parent!!)
        fs.write(scratch.launcher.toPath()) { writeUtf8("binary") }
    }

    private fun saved(scratch: ServiceScratch) = assertNotNull(loadService(scratch.config))

    @Test
    fun installRegistersEnablesAndStartsTheLoginServiceOfALinuxUser() = blockingScratch { scratch ->
        launcher(scratch)
        val runner = FakeRunner()
        assertEquals(0, run(scratch, "install", runner = runner))
        val settings = saved(scratch)
        assertFalse(settings.atBoot)
        assertEquals(scratch.launcher, settings.executable)
        assertTrue(ownsRegistration(settings))
        assertEquals(
            listOf("--user daemon-reload", "--user enable ${settings.registration}", "--user start ${settings.label}.service"),
            runner.calls.map { it.line.removePrefix("/usr/bin/systemctl ") },
        )
        assertOut("Installed service ${settings.label}; it starts automatically at login.")
        assertOut("Use ostrom service status to check it.")

        console.out.clear()
        assertEquals(0, run(scratch, "install", "--binary", scratch.launcher))
        assertEquals(settings.id, saved(scratch).id, "installing again keeps the same service")
        assertTrue(automaticServiceSetup(scratch.config))
    }

    @Test
    fun installNeedsASavedLoginAnExistingBinaryAndAUser() = blockingScratch { scratch ->
        assertEquals(65, run(scratch, "install", signedIn = false))
        assertEquals(1, run(scratch, "install"))
        assertTrue("The executable must exist" in console.err.toString(), console.err.toString())
        assertEquals(1, run(scratch, "install", userId = "0"))
        assertTrue("as your user" in console.err.toString(), console.err.toString())
        assertEquals(73, run(scratch, "install", environment = emptyMap()))
        val noHome = mapOf("OSTROM_HOME" to (scratch.home / ".config" / "ostrom").toString(), "OSTROM_URL" to server)
        console.err.clear()
        assertEquals(1, run(scratch, "install", environment = noHome))
        assertTrue("home directory" in console.err.toString(), console.err.toString())
    }

    @Test
    fun installRefusesToChangeAnExistingInstallation() = blockingScratch { scratch ->
        launcher(scratch)
        assertEquals(0, run(scratch, "install"))
        assertEquals(1, run(scratch, "install", "--at-boot"))
        assertTrue("Uninstall before switching from login to boot" in console.err.toString(), console.err.toString())
        assertEquals(1, run(scratch, "install", "--binary", "/elsewhere/ostrom"))
        assertTrue("Uninstall before changing the service executable" in console.err.toString(), console.err.toString())
    }

    @Test
    fun aCloudSessionsRunnerNeverBecomesADevice() = blockingScratch { scratch ->
        launcher(scratch)
        val runner = FakeRunner()
        assertEquals(0, run(scratch, "install", cloud = true, runner = runner))
        assertOut(CLOUD_RUNNER_REFUSAL)
        assertTrue(runner.calls.isEmpty())
        assertEquals(null, loadService(scratch.config))
        assertEquals(0, run(scratch, "serve", "--config", scratch.config, cloud = true))
        assertTrue(CLOUD_RUNNER_REFUSAL in console.err.toString())
    }

    @Test
    fun installAtBootOnLinuxEnablesLingeringThroughSudo() = blockingScratch { scratch ->
        launcher(scratch)
        val runner = FakeRunner()
        assertEquals(0, run(scratch, "install", "--at-boot", runner = runner))
        assertOut("Enabling systemd user lingering for boot startup.")
        assertOut("starts automatically at boot")
        // not loadService: a boot registration is checked against the account that runs this test
        val record = fs.read(servicePath(scratch.config).toPath()) { readUtf8() }
        assertTrue(serviceJson.decodeFromString(ServiceSettings.serializer(), record).atBoot)
        assertEquals("/usr/bin/sudo /usr/bin/loginctl enable-linger 501", runner.calls.first().line)
    }

    @Test
    fun aBootInstallThatTheOsRefusesLeavesNoServiceJsonBehind() = blockingScratch { scratch ->
        launcher(scratch)
        assertNotEquals(0, run(scratch, "install", "--at-boot", runner = FakeRunner { 1 }))
        assertEquals(null, fs.metadataOrNull(servicePath(scratch.config).toPath()))
    }

    @Test
    fun theServiceRunsTheLauncherOfTheInstallationNotThePayloadOrThePlatformsBinary() = blockingScratch { scratch ->
        launcher(scratch)
        val other = (scratch.root / "install" / "bin" / "stable").toString()
        fs.write(other.toPath()) { writeUtf8("launcher") }
        assertEquals(0, run(scratch, "install", executable = other))
        assertEquals(other, saved(scratch).executable)
        assertEquals(0, run(scratch, "uninstall"))
        assertEquals(0, run(scratch, "install", executable = null))
        assertEquals(scratch.launcher, saved(scratch).executable)
    }

    @Test
    fun theBootNoticeNamesWhatEachSystemAsks() = blockingScratch { scratch ->
        launcher(scratch)
        assertFailsWith<IllegalStateException> { run(scratch, "install", "--system", os = "windows", runner = FakeRunner { 1 }) }
        assertOut("Boot startup needs an elevated terminal and your Windows account password")
    }

    @Test
    fun installOnWindowsAndMacosLoginUsesTheirOwnManagers() = blockingScratch { scratch ->
        launcher(scratch)
        val mac = FakeRunner()
        assertEquals(0, run(scratch, "install", os = "macos", runner = mac))
        assertEquals(listOf("print", "kickstart"), mac.calls.map { it.arguments.first() })
        assertEquals(0, run(scratch, "uninstall", os = "macos", runner = FakeRunner { if (it.arguments.first() == "print") 113 else 0 }))
        val windows = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "status") 3 else 0 }
        assertEquals(0, run(scratch, "install", os = "windows", runner = windows))
        assertEquals(listOf("status", "load", "enable", "start"), windows.calls.map { it.environment["OSTROM_SERVICE_OPERATION"] })
    }

    @Test
    fun printRendersTheRegistrationWithoutInstallingAnything() = blockingScratch { scratch ->
        val runner = FakeRunner()
        assertEquals(0, run(scratch, "print", runner = runner))
        // systemd escapes a backslash and takes such a path through env (Windows paths, as the tests run there too)
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\") + "\""
        val wrapper = if ('\\' in scratch.launcher) "\"/usr/bin/env\" \"--\" " else ""
        assertOut("ExecStart=$wrapper${quoted(scratch.launcher)} \"service\" \"serve\" \"--config\" ${quoted(scratch.config)}")
        assertEquals(null, fs.metadataOrNull(scratch.config.toPath().parent!!.resolve("service.json")))
        assertTrue(runner.calls.isEmpty())
        console.out.clear()
        assertEquals(0, run(scratch, "print", "--at-boot", "--binary", scratch.launcher, os = "windows"))
        assertOut("<BootTrigger>")

        launcher(scratch)
        run(scratch, "install")
        console.out.clear()
        assertEquals(0, run(scratch, "print"))
        assertOut("ExecStart=")
        assertEquals(1, run(scratch, "print", "--at-boot"))
        assertTrue("Uninstall before switching startup modes" in console.err.toString())
    }

    @Test
    fun startStopRestartAndUninstallActOnTheInstalledService() = blockingScratch { scratch ->
        launcher(scratch)
        run(scratch, "install")
        val settings = saved(scratch)
        val unit = "${settings.label}.service"

        val runner = FakeRunner()
        assertEquals(0, run(scratch, "start", runner = runner))
        assertEquals(listOf("--user start $unit"), runner.calls.map { it.line.removePrefix("/usr/bin/systemctl ") })
        runner.calls.clear()
        assertEquals(0, run(scratch, "stop", runner = runner))
        assertOut("Stopped. It starts again at your next login, and ostrom install or ostrom update start it unless")
        assertEquals(listOf("--user stop $unit"), runner.calls.map { it.line.removePrefix("/usr/bin/systemctl ") })
        runner.calls.clear()
        assertEquals(0, run(scratch, "restart", runner = runner))
        assertOut("Waiting for the service to finish what it hosts")
        assertEquals(listOf("stop", "start"), runner.calls.map { it.arguments[1] })

        runner.calls.clear()
        assertEquals(0, run(scratch, "automatic", "off"))
        console.out.clear()
        assertEquals(0, run(scratch, "stop", runner = runner))
        assertFalse("Stopped." in console.out.toString(), "the opt-out is already recorded")

        runner.calls.clear()
        assertEquals(0, run(scratch, "uninstall", runner = runner))
        assertEquals(listOf("stop", "disable", "daemon-reload"), runner.calls.map { it.arguments[1] })
        assertOut("Removed the Ostrom service.")
        assertEquals(null, loadService(scratch.config))
        assertFalse(fs.exists(settings.registration.toPath()))
        assertFalse(automaticServiceSetup(scratch.config), "an uninstall opts out of the automatic setup")
    }

    @Test
    fun withoutAServiceOnlyStopAndUninstallAreHarmless() = blockingScratch { scratch ->
        for (command in listOf("stop", "uninstall")) {
            console.out.clear()
            assertEquals(0, run(scratch, command))
            assertOut("No Ostrom service is installed for this configuration.")
        }
        for (command in listOf("start", "restart")) {
            assertEquals(1, run(scratch, command))
            assertTrue("Run ostrom service install first." in console.err.toString(), console.err.toString())
        }
    }

    @Test
    fun uninstallRefusesAMissingRegistrationAndRemovesAWindowsTask() = blockingScratch { scratch ->
        launcher(scratch)
        run(scratch, "install")
        fs.delete(saved(scratch).registration.toPath())
        assertEquals(1, run(scratch, "uninstall"))
        assertTrue("The registration is missing" in console.err.toString(), console.err.toString())
        fs.delete(servicePath(scratch.config).toPath())

        val windows = scratch.settings("windows").let { scratch.install(it) }
        val present = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "active") 3 else 0 }
        assertEquals(0, run(scratch, "uninstall", os = "windows", runner = present))
        assertEquals(listOf("status", "disable", "active", "status", "delete"), present.calls.map { it.environment["OSTROM_SERVICE_OPERATION"] })
        assertFalse(fs.exists(windows.registration.toPath()))

        val gone = scratch.settings("windows").let { scratch.install(it) }
        val absent = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "status") 3 else 0 }
        assertEquals(0, run(scratch, "uninstall", os = "windows", runner = absent))
        assertFalse(fs.exists(gone.registration.toPath()))
        val broken = scratch.settings("windows").let { scratch.install(it) }
        assertFailsWith<IllegalStateException> { run(scratch, "uninstall", os = "windows", runner = FakeRunner { if (it.environment["OSTROM_SERVICE_OPERATION"] == "status") 2 else 0 }) }
        assertTrue(fs.exists(broken.registration.toPath()))
    }

    @Test
    fun statusSaysWhatIsInstalledRunningAndSignedIn() = blockingScratch { scratch ->
        assertEquals(0, run(scratch, "status", signedIn = false))
        assertOut("Installed: false\nRunning: false\nAuthenticated: no saved login\n")
        launcher(scratch)
        run(scratch, "install")
        console.out.clear()
        assertEquals(0, run(scratch, "status"))
        assertOut("Installed: true\nStartup: user login\nRunning: false\nAuthenticated: @maya\n")
        val lock = assertNotNull(holdExclusiveLock(daemonLockPath(scratch.config)))
        try {
            console.out.clear()
            run(scratch, "status")
            assertOut("Running: true")
            // a service that holds the lock stops a second one
            assertFailsWith<IllegalArgumentException> { run(scratch, "serve", "--config", "relative.toml") }
            assertEquals(0, run(scratch, "serve", "--config", scratch.config), "not an error: the OS would restart it")
            assertTrue("Another Ostrom service already runs" in console.err.toString(), console.err.toString())
        } finally {
            lock.close()
        }
    }

    @Test
    fun logsPrintsTheServiceLogWhenThereIsOne() = blockingScratch { scratch ->
        assertEquals(0, run(scratch, "logs"))
        assertOut("No service logs yet.")
        writePrivateFile(serviceLogPath(scratch.config), "2026-11-15T10:00:00Z Service started.\n")
        console.out.clear()
        assertEquals(0, run(scratch, "logs"))
        assertOut("Service started.")
    }

    @Test
    fun automaticSetupIsOnUnlessTurnedOff() = blockingScratch { scratch ->
        assertEquals(0, run(scratch, "automatic"))
        assertOut("Automatic service setup: on.")
        console.out.clear()
        assertEquals(0, run(scratch, "automatic", "off"))
        assertOut("Automatic service setup: off.")
        console.out.clear()
        assertEquals(0, run(scratch, "automatic", "status"))
        assertOut("Automatic service setup: off.")
        console.out.clear()
        assertEquals(0, run(scratch, "automatic", "on"))
        assertOut("Automatic service setup: on.")
    }

    @Test
    fun theServiceIsInTheHelpAndItsServeIsHidden() {
        assertEquals(0, cli().run(arrayOf("service", "--help")))
        val help = console.out.toString()
        assertTrue("install" in help && "uninstall" in help && "automatic" in help, help)
        assertFalse("serve" in help.substringBefore("Examples:"), help)
    }

    @Test
    fun aDirectoryIsNoExecutable() = blockingScratch { scratch ->
        assertEquals(1, run(scratch, "install", "--binary", scratch.home.toString()))
        assertTrue("The executable must exist" in console.err.toString(), console.err.toString())
    }

    @Test
    fun aBootServiceIsReportedAsStartingBeforeLoginAndIsNotToldAboutTheAutomaticSetup() = blockingScratch { scratch ->
        launcher(scratch)
        run(scratch, "install", "--at-boot")
        console.out.clear()
        assertEquals(0, run(scratch, "status"))
        assertOut("Startup: boot (before login)")
        assertEquals(0, run(scratch, "stop"))
        assertFalse("Stopped." in console.out.toString())
    }

    @Test
    fun aWindowsTaskThatStopsAnsweringBlocksTheUninstall() = blockingScratch { scratch ->
        scratch.install(scratch.settings("windows"))
        for (code in listOf(1, 2)) {
            val statuses = ArrayDeque(listOf(0, code))
            val runner = FakeRunner {
                when (it.environment["OSTROM_SERVICE_OPERATION"]) {
                    "status" -> statuses.removeFirst()
                    "active" -> 3
                    else -> 0
                }
            }
            assertFailsWith<IllegalStateException> { run(scratch, "uninstall", os = "windows", runner = runner) }
            assertEquals(listOf("status", "disable", "active", "status"), runner.calls.map { it.environment["OSTROM_SERVICE_OPERATION"] })
        }
    }

    @Test
    fun serveNeedsItsSettingsAndLogsRefuseAnythingButAPlainFile() = blockingScratch { scratch ->
        assertFailsWith<IllegalArgumentException> { run(scratch, "serve", "--config", scratch.config) }
        val log = serviceLogPath(scratch.config).toPath()
        fs.createDirectories(log.parent!!)
        fs.write(log) { writeUtf8(" ".repeat(70_000)) }
        assertFailsWith<IllegalArgumentException> { run(scratch, "logs") }
        fs.delete(log)
        fs.createDirectory(log)
        assertFailsWith<IllegalArgumentException> { run(scratch, "logs") }
        fs.delete(log)
        if (hostOs() == "windows") return@blockingScratch
        fs.write(scratch.home / "elsewhere.log") { writeUtf8("x") }
        fs.createSymlink(log, scratch.home / "elsewhere.log")
        assertFailsWith<IllegalArgumentException> { run(scratch, "logs") }
    }
}
