// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `ostrom <agent>` acts in one organisation: `OSTROM_ORG`, else the one `ostrom init` kept, else the server's choice. */
class AgentOrgTest : CliKit() {
    @Test
    fun theLaunchActsInTheOrganisationNamedOrKept() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("claude")))
        assertNull(launches.last().second.org, "none named: the server takes the only one")
        assertEquals(0, cli(environment = withServer).run(arrayOf("config", "set", "org", "acme")))
        assertEquals(7, cli(environment = withServer).run(arrayOf("claude")))
        assertEquals("acme", launches.last().second.org, "the one `ostrom init` kept")
        val named = withServer + ("OSTROM_ORG" to "beta")
        assertEquals(7, cli(environment = named).run(arrayOf("claude")))
        assertEquals("beta", launches.last().second.org, "OSTROM_ORG first")
        assertEquals(7, cli(environment = withServer + ("OSTROM_ORG" to "")).run(arrayOf("claude")))
        assertEquals("acme", launches.last().second.org, "an empty OSTROM_ORG names none")
    }
}
