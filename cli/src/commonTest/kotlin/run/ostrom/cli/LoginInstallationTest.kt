// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.DeviceLimits
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom login` names this workstation, so the server registers it as one device (TeamCode's docs/devices.md). */
class LoginInstallationTest : CliKit() {
    private val identity = "/home/maya/.config/ostrom/installation-id"

    private fun started() = requests.last { it.second.path == "/api/v1/auth/device" }.second.body

    private fun login(vararg arguments: String) =
        cli(environment = withServer, api = serverOf(deviceFlow)).run(arrayOf("login") + arguments)

    @Test
    fun theSignInSendsTheInstallationItsDefaultNameAndThePlatform() {
        assertEquals(0, login())
        val id = fileSystem.read(identity.toPath()) { readUtf8() }
        assertTrue(DeviceLimits.INSTALLATION_ID.matches(id), id)
        assertEquals("""{"installationId":"$id","deviceName":"Ostrom CLI","platform":"linux"}""", started())
        // the same installation at every later sign-in, on any server
        assertEquals(0, login("--server", "https://other.example", "--device", "Maya's laptop"))
        assertEquals("""{"installationId":"$id","deviceName":"Maya's laptop","platform":"linux"}""", started())
    }

    @Test
    fun everySystemIsNamedAsTheContractNamesIt() {
        val names = mapOf(OperatingSystem.MACOS to "macos", OperatingSystem.WINDOWS to "windows")
        for ((system, name) in names) {
            operatingSystem = system
            val environment = withServer + ("OSTROM_HOME" to "/home/maya/$name")
            assertEquals(0, cli(environment = environment, api = serverOf(deviceFlow)).run(arrayOf("login")))
            assertTrue(started().orEmpty().endsWith(""""platform":"$name"}"""), started())
        }
    }

    @Test
    fun anIdentityThatCannotBeUsedStopsTheSignInBeforeTheServerIsAsked() {
        fileSystem.createDirectories(identity.toPath().parent!!)
        fileSystem.write(identity.toPath()) { writeUtf8("copied by hand") }
        assertEquals(73, login())
        assertTrue(
            console.err.contains("ostrom: $identity is not an Ostrom installation identity: remove it, then run " +
                "ostrom login again"),
            console.err.toString(),
        )
        assertTrue(requests.none { it.second.path == "/api/v1/auth/device" }, "nothing was sent")
        // a folder that cannot be written: the CLI folder is a file
        fileSystem.deleteRecursively("/home/maya/.config/ostrom".toPath())
        fileSystem.write("/home/maya/.config/ostrom".toPath()) { writeUtf8("") }
        console.err.clear()
        assertEquals(73, login())
        assertTrue(console.err.contains("ostrom: cannot write"), console.err.toString())
    }
}
