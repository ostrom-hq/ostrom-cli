// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.test.runTest
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.Download
import run.ostrom.cli.api.Downloads
import run.ostrom.cli.platform.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom update --automatic` and its scheduled attempt, on a managed installation and Ostrom's signed release. */
class AutomaticUpdateCommandTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val said = mutableListOf<String>()
    private val calls = mutableListOf<List<String>>()
    private var session: String? = null
    private val routes = mutableMapOf<String, () -> Download>()
    private val home = variables("HOME" to "/home/dev")
    private val installations = Installations(fake, files, "linux-x64", home)
    private val store = installations.at(ROOT)
    private val request = UpdateRequest("1.0.0", "linux-x64", null, "https://releases.example/")
    private val archive = "https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"

    init {
        val latest = "https://releases.example/latest/download/latest.json"
        val (manifest, signature) = ReleaseVectors.MANIFESTS.getValue("LATEST")
        routes[latest] = { Download.Bytes(manifest.encodeToByteArray()) }
        routes["$latest.sig"] = { Download.Bytes(signature.encodeToByteArray()) }
        routes[archive] = { Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray()) }
        managed(store)
    }

    private fun managed(at: InstallationStore, launcher: Boolean = true) {
        at.initialize()
        val version = stageOn(fake, at, "SAME", "1.0.0", "binary of 1.0.0")
        if (launcher) at.ensureLauncher(version)
        at.lock(exclusive = true).use { at.activate(version) }
    }

    private fun updater(trust: ReleaseTrust = ReleaseTrust(ReleaseVectors.KEY_PEM)) = SelfUpdate(
        downloads = Downloads { url, _ -> routes[url]?.invoke() ?: Download.Missing(404) },
        fileSystem = fake,
        unpack = { _, into -> true.also { fake.write("$into/ostrom".toPath()) { writeUtf8("binary of 9.9.9") } } },
        say = { said += "update: $it" },
        sessions = { session },
        probe = { _, _, _ -> true },
        trust = trust,
        installations = installations,
    )

    @Suppress("LongParameterList") // one default per collaborator a test replaces
    private fun command(
        root: String? = ROOT,
        executable: String? = null,
        environment: Environment = home,
        all: Installations? = installations,
        platform: AutomaticUpdatePlatform = AutomaticUpdatePlatform(LINUX, "1000"),
        updates: SelfUpdate = updater(),
    ) = AutomaticUpdateCommand(
        installations = all,
        root = root,
        executable = executable,
        platform = platform,
        environment = environment,
        schedule = { at -> AutomaticUpdateScheduleImpl(at.fileSystem, "C:\\W") { _, a -> 0.also { calls += a } } },
        fetch = { updates.releaseCheck(request) },
        install = { at, candidate, _ -> updates.automaticInstall(at, request, candidate) { 2 } },
        say = { said += it },
    )

    @Test
    fun onRegistersTheJobAndAnAttemptInstallsTheLatestStableRelease() = runTest {
        assertEquals(0, command().configure("on"))
        assertEquals(listOf("Automatic updates: on.", "Registration present: yes."), said)
        assertTrue(calls.any { it.take(3) == listOf("--user", "enable", "--now") }, calls.toString())
        said.clear()
        assertEquals(0, command().attempt())
        assertTrue(said.single().startsWith("Automatic update: UPDATED (9.9.9) at "), said.toString())
        assertEquals("9.9.9", store.state()?.current?.version)
        said.clear()
        assertEquals(0, command().attempt())
        assertTrue(said.single().startsWith("Automatic update: NO_UPDATE (9.9.9) at "), "already selected: $said")
        said.clear()
        assertEquals(0, command(root = null, executable = "$ROOT/bin/ostrom").configure("status"))
        assertEquals("Automatic updates: on.", said.first())
        assertTrue(said.last().startsWith("Last attempt: NO_UPDATE (9.9.9) at "), said.toString())
    }

    @Test
    fun offKeepsTheRegistrationAndUninstallRemovesIt() = runTest {
        assertEquals(0, command().configure("status"))
        assertEquals(listOf("Automatic updates: off.", "Registration present: no."), said)
        command().configure("on")
        said.clear()
        assertEquals(0, command().configure("off"))
        assertEquals(listOf("Automatic updates: off.", "Registration present: yes."), said)
        said.clear()
        assertEquals(0, command().attempt())
        assertEquals(listOf("Automatic update: DISABLED."), said, "off: nothing runs")
        val label = requireNotNull(AutomaticUpdateStore(store).settings()).label
        fake.delete("/home/dev/.config/systemd/user/$label.service".toPath())
        said.clear()
        assertEquals(0, command().configure("status"))
        assertEquals("Registration present: no.", said[1], "a definition is missing")
        assertEquals(0, command().configure("on"), "on puts it back")
        said.clear()
        assertEquals(0, command().configure("uninstall"))
        assertEquals(listOf("Automatic updates: off.", "Registration present: no."), said)
    }

    @Test
    fun theEnvironmentOverrideTurnsEveryAttemptOff() = runTest {
        command().configure("on")
        said.clear()
        val locked = variables("HOME" to "/home/dev", "OSTROM_DISABLE_AUTOUPDATE" to "1")
        assertEquals(0, command(environment = locked).attempt())
        assertEquals(listOf("Automatic update: DISABLED."), said)
        said.clear()
        assertEquals(0, command(environment = locked).configure("status"))
        assertEquals("Environment override: OSTROM_DISABLE_AUTOUPDATE turns automatic updates off here.", said.last())
        assertEquals("1.0.0", store.state()?.current?.version)
    }

    @Test
    fun aFailedAttemptIsRecordedWithItsDiagnosticAndASessionDefersIt() = runTest {
        command().configure("on")
        said.clear()
        routes[archive] = { Download.Bytes("tampered".encodeToByteArray()) }
        assertEquals(1, command().attempt())
        assertTrue(said.single().contains("FAILED [release_signature_refused]"), said.toString())
        said.clear()
        assertEquals(1, command(updates = updater(ReleaseTrust(ReleaseVectors.KEY_PEM, floor = "10.0.0"))).attempt())
        assertTrue(said.single().contains("FAILED [release_refused]"), said.toString())
        said.clear()
        session = "OSTROM_LAUNCH_ID is set"
        assertEquals(0, command().attempt())
        assertTrue(said.single().contains("DEFERRED [$SESSION_RUNNING]"), said.toString())
        assertEquals("1.0.0", store.state()?.current?.version)
    }

    @Test
    fun automaticUpdatesNeedAManagedInstallation() = runTest {
        val bare = installations.at("/home/dev/bare").also { it.initialize() }
        managed(installations.at("/home/dev/nolauncher"), launcher = false)
        val commands = listOf(
            command(all = null),
            command(root = null),
            command(root = null, executable = "/opt/ostrom"),
            command(root = bare.paths.root.toString()),
            command(root = "/home/dev/nolauncher"),
        )
        for (each in commands) {
            said.clear()
            assertEquals(ExitCode.REFUSED.code, each.configure("on"))
            assertEquals(automaticRefusal(NOT_MANAGED).second, said.single())
        }
        assertEquals(ExitCode.USAGE.code, command(root = "relative").attempt())
        val root = command(platform = AutomaticUpdatePlatform(LINUX, ROOT_USER))
        assertEquals(ExitCode.REFUSED.code, root.configure("on"))
        assertEquals(0, command().configure("on"))
        said.clear()
        assertEquals(ExitCode.FAILED.code, command(platform = AutomaticUpdatePlatform(LINUX, "1001")).attempt())
        assertEquals(automaticRefusal(AutomaticUpdateStore.SETTINGS_INVALID).second, said.single(), "another user's")
    }

    @Test
    fun aFileThatCannotBeReadStopsWithItsReason() = runTest {
        val failing = object : ForwardingFileSystem(fake) {
            override fun canonicalize(path: Path): Path = throw IOException("disk gone")
        }
        val all = Installations(failing, files, "linux-x64", home)
        said.clear()
        assertEquals(ExitCode.CANNOT_WRITE.code, command(all = all).configure("status"))
        assertEquals("✗ ostrom could not read or write its automatic updates: disk gone", said.single())
    }

    @Test
    fun anAutomaticInstallIntoAnEmptyInstallationSelectsItsFirstVersion() = runTest {
        val empty = installations.at("/home/dev/empty")
        val updates = updater()
        val selected = updates.automaticInstall(empty, request, updates.releaseCheck(request)) { 2 }
        assertEquals("9.9.9", selected?.version)
        assertNull(updates.automaticInstall(empty, request, updates.releaseCheck(request)) { 2 })
    }

    @Test
    fun everyRefusalHasItsExitCodeAndWords() {
        val codes = mapOf(
            NOT_MANAGED to ExitCode.REFUSED,
            USER_INSTALLATION_REQUIRED to ExitCode.REFUSED,
            USER_UNAVAILABLE to ExitCode.FAILED,
            "automatic_settings_busy" to ExitCode.FAILED,
            WORKER_BUSY to ExitCode.FAILED,
            "automatic_registration_modified" to ExitCode.REFUSED,
            "automatic_registration_missing" to ExitCode.FAILED,
            "automatic_manager_rejected" to ExitCode.FAILED,
            "automatic_manager_unavailable" to ExitCode.FAILED,
            AutomaticUpdateStore.SETTINGS_INVALID to ExitCode.FAILED,
            InstallationStore.NOT_OWNED to ExitCode.REFUSED,
        )
        for ((code, exit) in codes) {
            val (actual, text) = automaticRefusal(code)
            assertEquals(exit, actual, code)
            assertTrue(text.startsWith("✗"), text)
        }
    }
}
