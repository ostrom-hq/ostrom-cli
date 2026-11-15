// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.CliKit
import run.ostrom.cli.update.FakeInstallationFiles
import run.ostrom.cli.update.Installations
import run.ostrom.cli.update.ROOT
import run.ostrom.cli.update.installationFileSystem
import run.ostrom.cli.update.stageOn
import run.ostrom.cli.update.storeOn
import run.ostrom.cli.update.variables
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The service step of `ostrom install` and `ostrom update`, over a fake installation and a fake OS manager. */
class InstallationServiceStepTest : CliKit() {
    private class System(val healthy: Boolean = false) : LoginServiceSystem {
        val calls = mutableListOf<String>()

        override suspend fun inspect(settings: ServiceSettings): LoginStartup {
            calls += "inspect"
            val owned = ownsRegistration(settings)
            return LoginStartup(owned, healthy || calls.size > 1 && owned, healthy || calls.size > 1 && owned, healthy)
        }

        override suspend fun register(settings: ServiceSettings) {
            calls += "register"
            writeRegistration(settings)
        }

        override suspend fun enable(settings: ServiceSettings) {
            calls += "enable"
        }

        override suspend fun start(settings: ServiceSettings) {
            calls += "start"
        }
    }

    private val fake: FakeFileSystem = installationFileSystem()
    private val installations =
        Installations(fake, FakeInstallationFiles(), "linux-x64", variables("HOME" to "/home/dev"))

    private fun managedInstallation(root: String = ROOT): String {
        val store = storeOn(fake, FakeInstallationFiles(), root)
        store.initialize()
        store.ensureLauncher(stageOn(fake, store))
        return store.paths.launcher.toString()
    }

    private fun step(system: System, cloud: Boolean = false) =
        InstallationServiceStep(installations, { cloud }, { platform }, { system })

    private lateinit var platform: ServicePlatform

    /** Runs [block] with the dependencies of a command line whose binary is [executable], at the scratch home. */
    private fun withDependencies(
        scratch: ServiceScratch,
        executable: String?,
        variables: Map<String, String> = emptyMap(),
        signedIn: Boolean = false,
        block: suspend (CliDependencies) -> Unit,
    ) {
        platform = ServicePlatform("linux", executable ?: "/opt/ostrom", "501", "maya", scratch.home.toString())
        var captured: CliDependencies? = null
        val environment = mapOf("HOME" to scratch.home.toString(), "OSTROM_URL" to "https://ostrom.example") + variables
        cli(environment = environment, executable = executable, service = { dependencies ->
            ServiceNothing(dependencies).also { captured = dependencies }
        }).run(arrayOf("version"))
        if (signedIn) signIn((scratch.home / ".config" / "ostrom").toString())
        runBlocking { block(assertNotNull(captured)) }
    }

    @Test
    fun anInstallSetsTheServiceUpAtTheRootItInstalledInto() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        val system = System()
        // the binary that ran the install is not the installation: the root it installed into is
        withDependencies(scratch, "/downloads/ostrom") { dependencies ->
            val setup = assertNotNull(step(system).setUp(dependencies, false, InstalledAt(ROOT)))
            assertEquals(ServiceSetupOutcome.INSTALLED, setup.outcome, setup.line)
            assertEquals(
                "Local Ostrom service: installed and started; it starts at login. " +
                    "It connects once you run ostrom login.",
                setup.line,
            )
            assertEquals(listOf("inspect", "register", "enable", "start", "inspect"), system.calls)
            assertEquals(launcher, loadService(scratch.config)?.executable)
        }
    }

    @Test
    fun aSignedInDeviceHasNothingMoreToDo() = blockingScratch { scratch ->
        managedInstallation()
        withDependencies(scratch, "/downloads/ostrom", signedIn = true) { dependencies ->
            val setup = assertNotNull(step(System()).setUp(dependencies, false, InstalledAt(ROOT)))
            assertEquals("Local Ostrom service: installed and started; it starts at login.", setup.line)
        }
    }

    @Test
    fun anUpdateChecksTheServiceOfTheInstallationItRunsFrom() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        scratch.install(scratch.settings(executable = launcher))
        withDependencies(scratch, launcher) { dependencies ->
            val setup = assertNotNull(step(System(healthy = true)).setUp(dependencies, false, null))
            assertEquals(ServiceSetupOutcome.UNCHANGED, setup.outcome, setup.line)
        }
    }

    @Test
    fun aServiceRunByAnotherExecutableIsLeftAlone() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        scratch.install(scratch.settings(executable = "/other/ostrom"))
        withDependencies(scratch, launcher) { dependencies ->
            val setup = assertNotNull(step(System()).setUp(dependencies, false, null))
            assertEquals(ServiceSetupOutcome.OTHER_EXECUTABLE, setup.outcome, setup.line)
        }
    }

    @Test
    fun aServiceRunByAnotherInstallationsLauncherIsLeftAlone() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        val other = managedInstallation("/home/dev/other")
        scratch.install(scratch.settings(executable = other))
        withDependencies(scratch, launcher) { dependencies ->
            val setup = assertNotNull(step(System()).setUp(dependencies, false, null))
            assertEquals(ServiceSetupOutcome.OTHER_EXECUTABLE, setup.outcome, setup.line)
        }
    }

    @Test
    fun aBinaryThatIsNotAManagedInstallationIsReportedAndLeftAlone() = blockingScratch { scratch ->
        val system = System()
        withDependencies(scratch, "/opt/ostrom") { dependencies ->
            val setup = assertNotNull(step(system).setUp(dependencies, false, null))
            assertEquals(ServiceSetupOutcome.UNMANAGED, setup.outcome, setup.line)
            assertEquals(emptyList(), system.calls)
        }
        withDependencies(scratch, null) { dependencies ->
            assertEquals(ServiceSetupOutcome.UNMANAGED, step(system).setUp(dependencies, false, null)?.outcome)
        }
    }

    @Test
    fun theOptOutsAreTheFlagAndTheVariable() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        val system = System()
        withDependencies(scratch, launcher) { dependencies ->
            assertEquals(
                "Local Ostrom service: not checked (--no-service).",
                step(system).setUp(dependencies, true, null)?.line,
            )
        }
        withDependencies(scratch, launcher, mapOf("OSTROM_NO_SERVICE" to "1")) { dependencies ->
            assertEquals(
                "Local Ostrom service: not checked (OSTROM_NO_SERVICE).",
                step(system).setUp(dependencies, false, null)?.line,
            )
        }
        withDependencies(scratch, "/opt/ostrom", mapOf("OSTROM_NO_SERVICE" to "1")) { dependencies ->
            assertEquals(ServiceSetupOutcome.OPTED_OUT, step(system).setUp(dependencies, false, null)?.outcome)
        }
        withDependencies(scratch, launcher, mapOf("OSTROM_NO_SERVICE" to "")) { dependencies ->
            assertEquals(ServiceSetupOutcome.INSTALLED, step(system).setUp(dependencies, false, null)?.outcome)
        }
        assertTrue(system.calls.isNotEmpty())
    }

    @Test
    fun aCloudSessionsRunnerNeverBecomesADevice() = blockingScratch { scratch ->
        val launcher = managedInstallation()
        withDependencies(scratch, launcher) { dependencies ->
            val setup = assertNotNull(step(System(), cloud = true).setUp(dependencies, false, null))
            assertEquals(ServiceSetupOutcome.CLOUD_RUNNER, setup.outcome)
        }
    }

    @Test
    fun withoutACliFolderThereIsNoStep() = blockingScratch { scratch ->
        platform = ServicePlatform("linux", "/opt/ostrom", "501", "maya", scratch.home.toString())
        var captured: CliDependencies? = null
        cli(environment = emptyMap(), service = { dependencies ->
            ServiceNothing(dependencies).also { captured = dependencies }
        }).run(arrayOf("version"))
        assertNull(runBlocking { step(System()).setUp(assertNotNull(captured), false, null) })
    }

    @Test
    fun theDefaultStepDoesNothing() = runBlocking {
        platform = ServicePlatform("linux", "/opt/ostrom", "501", "maya", "/home/maya")
        var captured: CliDependencies? = null
        cli(service = { dependencies -> ServiceNothing(dependencies).also { captured = dependencies } })
            .run(arrayOf("version"))
        assertNull(ServiceStep.None.setUp(assertNotNull(captured), false, null))
    }
}

/** A `service` command that does nothing: a test only wants the dependencies it is given. */
private class ServiceNothing(@Suppress("unused") val dependencies: CliDependencies) :
    com.github.ajalt.clikt.core.CliktCommand(name = "service") {
    override fun run() = Unit
}
