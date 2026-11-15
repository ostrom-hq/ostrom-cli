// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's tests as they are (copy first, polish later).
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The OS registrations are well-formed documents that keep literal paths, and hold no credential. */
class ServiceManifestTest {
    private fun settings(os: String) = ServiceSettings(
        "a".repeat(32),
        "/home/user/Team & Code/\"100%\$x\"",
        "/home/user/config dir/config.toml",
        "/home/user/service",
        os,
        "501",
    )

    @Test
    fun manifestsPreserveLiteralPaths() {
        val mac = settings("macos")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(serviceDefinition(mac).toByteArray()))
        val strings = document.getElementsByTagName("array").item(0).childNodes
        val arguments = (0 until strings.length).map { strings.item(it).textContent }
        assertEquals(listOf(mac.executable, "service", "serve", "--config", mac.config), arguments)
        val linux = serviceDefinition(settings("linux"))
        assertTrue(linux.contains("100%%\$\$x"))
        assertTrue(linux.contains("WantedBy=default.target"))
        assertTrue(linux.contains("TimeoutStopSec=660"))
        assertTrue(linux.contains("UMask=0077"))
    }

    @Test
    fun windowsTaskUsesTheCurrentUserWithoutRequiringElevation() {
        val windows = ServiceSettings(
            "a".repeat(32), "C:\\Program Files\\Ostrom\\ostrom.exe",
            "C:\\Users\\équipe\\.config\\ostrom\\config.toml", "C:\\Users\\équipe\\.config\\ostrom\\service-task.xml",
            "windows", "S-1-5-21-123-456-789-1001",
        )
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(registrationBytes(windows)))
        assertEquals(windows.executable, document.getElementsByTagName("Command").item(0).textContent)
        assertEquals("\"service\" \"serve\" \"--config\" \"${windows.config}\"", document.getElementsByTagName("Arguments").item(0).textContent)
        assertEquals("InteractiveToken", document.getElementsByTagName("LogonType").item(0).textContent)
        assertEquals("LeastPrivilege", document.getElementsByTagName("RunLevel").item(0).textContent)
        assertEquals("IgnoreNew", document.getElementsByTagName("MultipleInstancesPolicy").item(0).textContent)
        assertEquals("false", document.getElementsByTagName("AllowHardTerminate").item(0).textContent)
        assertFalse(serviceDefinition(windows).contains("Password"))
    }
}
