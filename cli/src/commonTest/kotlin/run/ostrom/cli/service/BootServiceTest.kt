// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootServiceTest {
    private val id = "a".repeat(32)
    private val config = "/Users/fixture/config & space/config.toml"

    private fun mac(
        atBoot: Boolean = true,
        name: String? = "fixture",
        registration: String = "/Library/LaunchDaemons/run.ostrom.$id.plist",
    ) = ServiceSettings(id, "/opt/team code/ostrom", config, registration, "macos", "501", atBoot, name, "/Users/fixture")

    @Test
    fun bootDaemonKeepsTheUserIdentityAndBindsTheConfigurationFolderWithoutSecrets() {
        val definition = serviceDefinition(mac())
        assertTrue(definition.startsWith("<?xml"))
        assertTrue(definition.contains("<key>UserName</key><string>fixture</string>"))
        assertTrue(definition.contains("<key>HOME</key><string>/Users/fixture</string>"))
        assertTrue(definition.contains("<key>OSTROM_HOME</key><string>/Users/fixture/config &amp; space</string>"))
        assertTrue(definition.contains("<key>ExitTimeOut</key><integer>660</integer>"))
        assertTrue(definition.contains("<key>Label</key><string>run.ostrom.$id</string>"))
        assertFalse(serviceDefinition(mac(false)).contains("<key>UserName</key>"))
    }

    @Test
    fun privilegedMacPathsAndIdentitiesAreBoundToTheGeneratedLabel() {
        assertFailsWith<IllegalArgumentException> { mac(registration = "/Library/LaunchDaemons/unrelated.plist") }
        assertFailsWith<IllegalArgumentException> { mac(name = null) }
        assertFailsWith<IllegalArgumentException> { mac(name = "root\nAnotherKey") }
        assertFailsWith<IllegalArgumentException> {
            ServiceSettings(id, "/opt/ostrom", "/tmp/config.toml", "/Library/LaunchDaemons/run.ostrom.$id.plist", "macos", "0", true, "root", "/var/root")
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceSettings(id, "/opt/ostrom", "/tmp/config.toml", "/Library/LaunchDaemons/run.ostrom.$id.plist", "macos", "501", true, "fixture", "relative")
        }
    }

    @Test
    fun theSettingsAreCheckedWhenTheyAreBuilt() {
        fun linux(id: String = this.id, executable: String = "/opt/ostrom", os: String = "linux", user: String = "501") =
            ServiceSettings(id, executable, "/tmp/config.toml", "/tmp/ostrom.service", os, user)
        linux()
        assertFailsWith<IllegalArgumentException> { linux(id = "short") }
        assertFailsWith<IllegalArgumentException> { linux(executable = "relative/ostrom") }
        assertFailsWith<IllegalArgumentException> { linux(executable = "/opt/os\ntrom") }
        assertFailsWith<IllegalArgumentException> { linux(os = "plan9") }
        assertFailsWith<IllegalArgumentException> { linux(user = "root") }
        assertFailsWith<IllegalArgumentException> { linux(os = "windows", user = "501") }
        assertEquals("ServiceSettings(id=$id)", linux().toString())
    }

    @Test
    fun savedBootIdentityCannotBeChangedToAnotherAccount() {
        val identity = ServicePlatform("macos", "/opt/ostrom", "501", "fixture", "/Users/fixture")
        requireBootServiceIdentity(mac(), identity)
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(name = "root"), identity) }
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(), identity.copy(userId = "0")) }
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(), identity.copy(userHome = "/different")) }
        assertFailsWith<IllegalArgumentException> { requireBootServiceIdentity(mac(), identity.copy(os = "linux")) }
        val linux = ServiceSettings(id, "/opt/ostrom", "/tmp/config.toml", "/tmp/ostrom.service", "linux", "1000", true)
        requireBootServiceIdentity(linux, ServicePlatform("linux", "/opt/ostrom", "1000"))
    }

    @Test
    fun settingsOfAnotherVersionStillReadAndKeepTheirOriginalManifest() {
        val json = Json { encodeDefaults = true }
        val record = json.encodeToJsonElement(ServiceSettings.serializer(), mac(false)).jsonObject
        val legacy = JsonObject(record - setOf("atBoot", "userName", "userHome") + ("port" to kotlinx.serialization.json.JsonPrimitive(8789)))
        val decoded = serviceJson.decodeFromString(ServiceSettings.serializer(), legacy.toString())
        assertFalse(decoded.atBoot)
        assertNull(decoded.userName)
        assertEquals(serviceDefinition(mac(false)), serviceDefinition(decoded))
    }

    @Test
    fun windowsBootUsesPasswordLogonAndNeverInteractiveTokenOrS4u() {
        val settings = ServiceSettings(
            id, "C:\\Team Code\\ostrom.exe", "C:\\Users\\fixture\\.config\\ostrom\\config.toml",
            "C:\\Users\\fixture\\.config\\ostrom\\service-task.xml", "windows", "S-1-5-21-123-456-789-1001", true,
        )
        val definition = serviceDefinition(settings)
        assertTrue(definition.contains("<BootTrigger>"))
        assertFalse(definition.contains("<LogonTrigger>"))
        assertTrue(definition.contains("<LogonType>Password</LogonType>"))
        assertTrue(definition.contains("<RunLevel>LeastPrivilege</RunLevel>"))
        assertFalse(definition.contains("InteractiveToken"))
        assertFalse(definition.contains("S4U"))
        val script = windowsBootRegistrationScript()
        assertTrue(script.contains("-AsSecureString"))
        assertTrue(script.contains("ZeroFreeBSTR"))
        assertTrue(script.contains("WindowsBuiltInRole"))
        assertTrue(script.contains("RegisterTask("))
        val ownership = windowsBootTaskCheck()
        assertTrue(ownership.contains("LogonType -ne 1"))
        assertTrue(ownership.contains("RunLevel -ne 0"))
        assertTrue(ownership.contains("Type -ne 8"))
        assertTrue(ownership.contains("OSTROM_SERVICE_ARGUMENTS"))
    }
}
