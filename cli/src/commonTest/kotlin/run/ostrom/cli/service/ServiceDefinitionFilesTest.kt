// SPDX-License-Identifier: Apache-2.0
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is read from, and written to, the user's files: refused when it is not a plain file of the right size. */
class ServiceDefinitionFilesTest {
    private val fs = FileSystem.SYSTEM
    private val account = ServicePlatform("linux", "/opt/ostrom", "501", "fixture", "/home/fixture")

    @Test
    fun theSavedServiceIsReadForItsOwnConfigurationOnly() = serviceScratch { scratch ->
        assertNull(loadService(scratch.config) { account })
        val settings = scratch.install(scratch.settings("linux"))
        assertEquals(settings.id, loadService(scratch.config) { account }?.id)
        assertFailsWith<CliktError> { loadService((scratch.home / "other" / "config.toml").toString().also {
            fs.createDirectories(it.toPath().parent!!)
            fs.copy(servicePath(scratch.config).toPath(), servicePath(it).toPath())
        }) { account } }
    }

    @Test
    fun aSavedBootServiceMustNameThisAccount() = serviceScratch { scratch ->
        val boot = scratch.install(scratch.settings("linux", atBoot = true))
        assertEquals(boot.id, loadService(scratch.config) { account }?.id)
        assertFailsWith<IllegalArgumentException> { loadService(scratch.config) { account.copy(userId = "502") } }
        // the real identity of the machine that runs the test is not the fixture's
        assertFailsWith<IllegalArgumentException> { loadService(scratch.config) }
    }

    @Test
    fun aServiceFileThatIsNotAPlainSmallFileIsRefused() = serviceScratch { scratch ->
        val path = servicePath(scratch.config).toPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(" ".repeat(70_000)) }
        assertFailsWith<IllegalArgumentException> { loadService(scratch.config) { account } }
        fs.delete(path)
        fs.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { loadService(scratch.config) { account } }
        fs.delete(path)
        if (hostOs() == "windows") return@serviceScratch
        fs.write(scratch.home / "target.json") { writeUtf8("{}") }
        fs.createSymlink(path, scratch.home / "target.json")
        assertFailsWith<IllegalArgumentException> { loadService(scratch.config) { account } }
    }

    @Test
    fun aRegistrationThatIsNotAPlainSmallFileIsRefused() = serviceScratch { scratch ->
        val settings = scratch.settings("linux")
        val path = settings.registration.toPath()
        assertFalse(ownsRegistration(settings))
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(" ".repeat(70_000)) }
        assertFailsWith<IllegalArgumentException> { ownsRegistration(settings) }
        fs.delete(path)
        fs.createDirectory(path)
        assertFailsWith<IllegalArgumentException> { ownsRegistration(settings) }
        fs.delete(path)
        if (hostOs() == "windows") return@serviceScratch
        fs.write(scratch.home / "target.service") { writeUtf8("[Unit]") }
        fs.createSymlink(path, scratch.home / "target.service")
        assertFailsWith<IllegalArgumentException> { ownsRegistration(settings) }
        fs.delete(path)
        val linked = scratch.home / "linked"
        fs.createSymlink(linked, path.parent!!)
        val behindLink = ServiceSettings(settings.id, settings.executable, settings.config, (linked / "unit.service").toString(), "linux", "501")
        assertFailsWith<IllegalArgumentException> { writeRegistration(behindLink) }
    }

    @Test
    fun theBootNoticeNamesWhatEachSystemAsks() {
        assertTrue("LaunchDaemon" in bootNotice("macos"))
        assertTrue("Windows account password" in bootNotice("windows"))
        assertTrue("lingering" in bootNotice("linux"))
    }

    @Test
    fun aNewMacosBootServiceIsRegisteredAsALaunchDaemon() {
        val mac = ServicePlatform("macos", "/opt/ostrom", "501", "fixture", "/Users/fixture")
        val boot = newServiceSettings("/Users/fixture/.config/ostrom/config.toml", "/opt/ostrom", true, mac, "/Users/fixture")
        assertEquals("/Library/LaunchDaemons/${boot.label}.plist", boot.registration)
        val login = newServiceSettings("/Users/fixture/.config/ostrom/config.toml", "/opt/ostrom", false, mac, "/Users/fixture")
        assertEquals("/Users/fixture/Library/LaunchAgents/${login.label}.plist", login.registration)
    }
}
