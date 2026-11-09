// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.cloud.CloudSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ostrom <agent>` acts in one organisation, sent as its id: `--org`, else `OSTROM_ORG`, else the one `ostrom init`
 * kept, else none (the server takes the person's only one).
 */
class AgentOrgTest : SetupKit() {
    private val both = mapOf(orgs(acme, beta))

    @Test
    fun noOrganisationNamedAsksTheServerNothing() {
        assertEquals(7, run(both, "claude"))
        assertNull(launches.last().second.org, "the server takes the only one")
        assertEquals(7, run(both, "claude", "--org", ""))
        assertNull(launches.last().second.org, "an empty --org names none")
        assertTrue(requests.isEmpty(), "no lookup when none is named")
    }

    @Test
    fun theOrganisationNamedIsSentAsItsId() {
        assertEquals(0, run(both, "config", "set", "org", "acme"))
        assertEquals(7, run(both, "claude"))
        assertEquals("o1", launches.last().second.org, "the one `ostrom init` kept, by its short name")
        assertEquals(7, run(both, "claude", environment = withServer + ("OSTROM_ORG" to "beta")))
        assertEquals("o2", launches.last().second.org, "OSTROM_ORG before the config")
        assertEquals(7, run(both, "claude", "--org", "o1", environment = withServer + ("OSTROM_ORG" to "beta")))
        assertEquals("o1", launches.last().second.org, "--org first, by id")
        assertEquals(7, run(both, "claude", environment = withServer + ("OSTROM_ORG" to "")))
        assertEquals("o1", launches.last().second.org, "an empty OSTROM_ORG names none")
    }

    @Test
    fun anOrganisationOneIsNotInStopsTheLaunch() {
        assertEquals(66, run(both, "claude", "--org", "gamma"))
        assertErr("You are not a member of an organisation named \"gamma\". Yours: acme, beta.\n")
        assertTrue(launches.isEmpty())
    }

    @Test
    fun aCloudSessionActsInTheOrganisationNamedToo() {
        assertEquals(5, run(both, "claude", "--cloud", "--org", "beta"))
        assertEquals(CloudSite("https://ostrom.example", "ost_1", "/work/acme", "o2"), clouds.single().second)
        assertEquals(6, run(both, "attach", "cs1", environment = withServer + ("OSTROM_ORG" to "acme")))
        assertEquals(CloudSite("https://ostrom.example", "ost_1", "/work/acme", "o1"), attached.single().second)
    }
}
