// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom devices`, as TeamCode's `teamcode devices list|rename|revoke`. */
class DevicesCommandsTest : SetupKit() {
    private val devices = "/api/v1/devices"
    private val listed = """{"items":[{"id":"d1","name":"laptop","activeSessions":2,"current":true},""" +
        """{"id":"d2","name":"desktop"}]}"""

    @Test
    fun devicesListShowsEachWorkstationAndWhichOneThisIs() {
        assertEquals(0, run(mapOf("GET $devices" to (200 to listed)), "devices", "list"))
        assertEquals("d1\tlaptop\t2 active sessions\tcurrent\nd2\tdesktop\t0 active sessions\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("GET $devices" to (200 to listed)), "devices"), "the list is the default")
        assertTrue(console.out.contains("d1\tlaptop"))
        console.out.clear()
        assertEquals(0, run(mapOf("GET $devices" to (200 to listed)), "devices", "list", "--json"))
        assertEquals("$listed\n", console.out.toString())
    }

    @Test
    fun devicesRenameSendsTheNewName() {
        assertEquals(0, run(mapOf("PATCH $devices/d1" to (200 to "{}")), "devices", "rename", "d1", "--name", "work"))
        assertEquals("Device renamed.\n", console.out.toString())
        assertEquals("""{"name":"work"}""", bodyOf("PATCH $devices/d1"))
        assertEquals(66, run(emptyMap(), "devices", "rename", "nope", "--name", "x"))
        assertEquals(64, run(emptyMap(), "devices", "rename", "d1", "--name", "x", environment = inSession))
    }

    @Test
    fun devicesRevokeRemovesAWorkstationAndSaysWhenItIsUnknown() {
        assertEquals(0, run(mapOf("DELETE $devices/d1" to (204 to "")), "devices", "revoke", "d1"))
        assertEquals("Device removed and its sessions signed out.\n", console.out.toString())
        assertEquals(66, run(emptyMap(), "devices", "revoke", "nope"))
        assertEquals(64, run(emptyMap(), "devices", "revoke", "d1", environment = inSession))
    }
}
