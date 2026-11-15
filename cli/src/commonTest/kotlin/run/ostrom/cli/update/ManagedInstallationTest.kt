// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the managed installation lives, which binary is its launcher, and what the launcher runs. */
class ManagedInstallationTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val installations = Installations(fake, files, "linux-x64", variables("HOME" to "/home/dev"))
    private val store = storeOn(fake, files).also { it.initialize() }
    private val ran = mutableListOf<Pair<String, List<String>>>()
    private val said = mutableListOf<String>()

    private fun launch(vararg arguments: String, executable: String? = store.paths.launcher.toString()) =
        launchInstalled(arguments.toList(), executable, installations, { path, args -> ran += path to args; 7 }) {
            said += it
        }

    private fun installed(): InstalledVersion = stageOn(fake, store).also {
        store.ensureLauncher(it)
        store.activate(it)
    }

    @Test
    fun theDefaultRootIsTheUsersApplicationData() {
        fun root(target: String, vararg variables: Pair<String, String>) =
            Installations(fake, files, target, variables(*variables)).at(null).paths.root.toString()
        assertEquals("/home/dev/.local/share/ostrom", root("linux-x64", "HOME" to "/home/dev"))
        assertEquals("/data/ostrom", root("linux-arm64", "XDG_DATA_HOME" to "/data"))
        assertEquals("/home/dev/Library/Application Support/Ostrom", root("macos-arm64", "HOME" to "/home/dev"))
        assertEquals("/data/Ostrom", root("macos-arm64", "XDG_DATA_HOME" to "/data"))
        assertEquals("/l/Ostrom", root("windows-x64", "LOCALAPPDATA" to "/l"))
        assertEquals("/u/AppData/Local/Ostrom", root("windows-x64", "USERPROFILE" to "/u"))
        val refused = assertFailsWith<InstallationException> { root("linux-x64") }
        assertEquals(HOME_UNAVAILABLE, refused.code)
        assertEquals("/opt/o", installations.at("/opt/o").paths.root.toString())
        assertEquals("release_root_invalid", assertFailsWith<InstallationException> { installations.at("o") }.code)
    }

    @Test
    fun onlyTheLauncherAndThePayloadsOfAnInstallationAreRecognised() {
        val version = installed()
        assertEquals(ROOT, installations.of(store.paths.launcher.toString())?.paths?.root?.toString())
        assertEquals(ROOT, installations.of(store.paths.binary(version).toString())?.paths?.root?.toString())
        fake.createDirectories("/home/dev/.local/bin".toPath())
        fake.createSymlink("/home/dev/.local/bin/ostrom".toPath(), store.paths.launcher)
        assertEquals(ROOT, installations.of("/home/dev/.local/bin/ostrom")?.paths?.root?.toString(), "through the link")
        assertNull(installations.of("/home/dev/missing"))
        assertNull(installations.of("/home/dev"), "a folder")
        fake.write("/ostrom".toPath()) { }
        assertNull(installations.of("/ostrom"), "no parent folder to read")
        fake.write("/home/dev/ostrom.bin".toPath()) { }
        assertNull(installations.of("/home/dev/ostrom.bin"), "neither bin/ nor versions/")
        fake.write(store.paths.launcherDirectory / "other") { }
        assertNull(installations.of((store.paths.launcherDirectory / "other").toString()), "another name")
        fake.createDirectories("/home/dev/x/versions/${"a".repeat(64)}".toPath())
        fake.write("/home/dev/x/versions/${"a".repeat(64)}/ostrom".toPath()) { }
        assertNull(installations.of("/home/dev/x/versions/${"a".repeat(64)}/ostrom"), "no launcher record")
        fake.createDirectories("/home/dev/y/versions/zz".toPath())
        fake.write("/home/dev/y/versions/zz/ostrom".toPath()) { }
        assertNull(installations.of("/home/dev/y/versions/zz/ostrom"), "not a digest")
        fake.createDirectories("/home/dev/a\u0001b/bin".toPath())
        fake.write("/home/dev/a\u0001b/bin/ostrom".toPath()) { }
        assertNull(installations.of("/home/dev/a\u0001b/bin/ostrom"), "a root no installation has")
    }

    @Test
    fun hooksNameTheStableLauncherNeverAPayload() {
        val version = installed()
        val launcher = store.paths.launcher.toString()
        assertEquals(launcher, installations.stable(store.paths.binary(version).toString()))
        assertEquals("/usr/bin/ostrom", installations.stable("/usr/bin/ostrom"))
        assertNull(installations.stable(null))
        fake.write(store.paths.launcherRecord) { writeUtf8("broken") }
        assertEquals(launcher, installations.stable(launcher), "a broken record: the binary itself")
        val failing = FailingFileSystem(fake)
        failing.fail = { function, _ -> function == "canonicalize" }
        assertEquals(launcher, Installations(failing, files, "linux-x64", variables()).stable(launcher))
    }

    @Test
    fun theLauncherRunsTheSelectedPayloadWithEveryArgument() {
        val version = installed()
        assertEquals(7, launch("hook", "gate"))
        assertEquals(listOf(store.paths.binary(version).toString() to listOf("hook", "gate")), ran)
        ran.clear()
        assertNull(launch("hook", executable = store.paths.binary(version).toString()), "a payload runs itself")
        assertNull(launch("hook", executable = "/usr/bin/ostrom"), "not an installation")
        assertNull(launch("hook", executable = null))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun aPayloadThatDoesNotMatchItsAttestationNeverRuns() {
        val version = installed()
        fake.write(store.paths.binary(version)) { writeUtf8("bad") }
        assertEquals(1, launch("status"))
        assertEquals(
            listOf("ostrom: the installation cannot start (release_binary_invalid). Run the installer again.\n"),
            said,
        )
        assertTrue(ran.isEmpty())
    }

    @Test
    fun aLauncherWithNothingSelectedOnlyInstallsOrUpdates() {
        val version = stageOn(fake, store)
        store.ensureLauncher(version)
        assertNull(launch("install"))
        assertNull(launch("--debug", "update"))
        assertEquals(1, launch("status"))
        assertEquals(
            "ostrom: the installation cannot start (release_installation_missing). Run the installer again.\n",
            said.single(),
        )
    }

    @Test
    fun anInterruptedSelectionIsPutBackBeforeTheLauncherRuns() {
        val first = installed()
        val second = stageOn(fake, store, "LATEST_FLOOR", binary = "two")
        val journal = InstallationJournal(InstallationState(first), InstallationState(second, first))
        fake.write(store.paths.state) { writeUtf8(journal.after.json().toString()) }
        fake.write(store.paths.journal) { writeUtf8(journal.json().toString()) }
        val installer = store.lock(exclusive = true)
        assertEquals(7, launch("status"), "an installer holds the lock: it recovers, this launch runs on")
        assertEquals(store.paths.binary(second).toString(), ran.last().first)
        installer.close()
        assertEquals(7, launch("status"))
        assertEquals(store.paths.binary(first).toString(), ran.last().first)
    }

    @Test
    fun aLaunchThatCannotReadItsFilesSaysSo() {
        installed()
        val failing = FailingFileSystem(fake)
        val broken = Installations(failing, files, "linux-x64", variables())
        failing.fail = { function, path -> function == "source" && path.name == "ostrom" }
        val code = launchInstalled(listOf("status"), store.paths.launcher.toString(), broken, { _, _ -> 0 }) {
            said += it
        }
        assertEquals(1, code)
        assertTrue(said.single().startsWith("ostrom: the installation cannot start: source "))
        assertFailsWith<IOException> { failing.source(store.paths.launcher) }
    }
}
