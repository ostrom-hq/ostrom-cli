// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ServiceAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootStartupTest {
    @Test
    fun everySystemHasItsName() {
        assertEquals(
            listOf("macos", "linux", "windows"),
            listOf(OperatingSystem.MACOS, OperatingSystem.LINUX, OperatingSystem.WINDOWS).map(::osNameOf),
        )
    }

    @Test
    fun theHostPlatformIsTheCanonicalBinaryAndTheAccount() {
        assertTrue(hostOs() in setOf("macos", "linux", "windows"))
        val binary = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()
        val platform = servicePlatform("linux", binary, ServiceAccount("501", "maya", "/home/maya"))
        assertEquals(ServicePlatform("linux", FileSystem.SYSTEM.canonicalize(binary.toPath()).toString(), "501", "maya", "/home/maya"), platform)
        assertFailsWith<CliktError> { servicePlatform("linux", null, ServiceAccount("501")) }
        assertTrue(servicePlatform().userId.isNotEmpty())
    }

    @Test
    fun aLoginRegistrationIsAFileTheUserOwns() = serviceScratch { scratch ->
        val settings = scratch.settings("linux")
        val runner = FakeRunner { error("a login registration needs no OS tool") }
        installServiceRegistration(settings, runner)
        assertTrue(ownsRegistration(settings))
        removeServiceRegistration(settings, runner)
        assertFalse(ownsRegistration(settings))
        assertFailsWith<IllegalArgumentException> { removeServiceRegistration(settings, runner) }
    }

    @Test
    fun aModifiedRegistrationIsNeitherReplacedNorRemoved() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        FileSystem.SYSTEM.write(settings.registration.toPath()) { writeUtf8("edited by hand") }
        assertFailsWith<CliktError> { installServiceRegistration(settings, FakeRunner()) }
        assertFailsWith<CliktError> { removeServiceRegistration(settings, FakeRunner()) }
        assertFailsWith<CliktError> { ownsRegistration(settings) }
    }

    @Test
    fun lingeringForBootStartupIsAskedThroughSudoForTheUsersOwnId() = serviceScratch { scratch ->
        val settings = scratch.settings("linux", atBoot = true)
        val runner = FakeRunner()
        enableLinuxBootStartup(settings, runner)
        val call = runner.calls.single()
        assertEquals("/usr/bin/sudo /usr/bin/loginctl enable-linger 501", call.line)
        assertFalse(call.quiet, "the person may be asked for a password")
        assertFailsWith<CliktError> { enableLinuxBootStartup(settings, FakeRunner { 1 }) }
        assertFailsWith<IllegalArgumentException> { enableLinuxBootStartup(scratch.settings("linux"), runner) }
        val mac = ServiceSettings(
            settings.id, settings.executable, settings.config, "/Library/LaunchDaemons/${settings.label}.plist",
            "macos", "501", true, "fixture", "/Users/fixture",
        )
        assertFailsWith<IllegalArgumentException> { enableLinuxBootStartup(mac, runner) }
        val root = ServiceSettings(settings.id, settings.executable, settings.config, settings.registration, "linux", "0", true)
        assertFailsWith<IllegalArgumentException> { enableLinuxBootStartup(root, runner) }
    }
}
