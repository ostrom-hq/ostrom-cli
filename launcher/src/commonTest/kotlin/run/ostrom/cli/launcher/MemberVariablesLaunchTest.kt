// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The member's environment variables in a launch, as TeamCode's `teamcode run` gives them ([MemberVariables]). */
class MemberVariablesLaunchTest : AgentLaunchKit() {
    private val asked = mutableListOf<Set<String>>()
    private val delivered = mutableListOf<Triple<Agent, Map<String, String>, String>>()
    private var refreshing = false
    private var refreshCancelled = false
    private var closed = false
    private var read = VariablesRead(mapOf("API_URL" to "https://api", "PWD" to "/elsewhere"), listOf("ostrom: read"))
    private var delivery = true

    private val port = object : MemberVariables {
        override suspend fun read(site: LaunchSite, features: Set<String>): VariablesRead {
            asked += features
            return read
        }

        override fun deliver(
            agent: Agent,
            variables: Map<String, String>,
            site: LaunchSite,
            directory: String,
            say: (String) -> Unit,
        ): VariablesDelivery? {
            delivered += Triple(agent, variables, directory)
            say("ostrom: before the agent")
            if (!delivery) return null
            return object : VariablesDelivery {
                override val environment = mapOf("CLAUDE_ENV_FILE" to "$directory/environment.sh")

                override suspend fun keepCurrent() {
                    refreshing = true
                    try {
                        say("ostrom: while the agent runs")
                        awaitCancellation()
                    } finally {
                        refreshCancelled = true
                    }
                }

                override fun close() {
                    closed = true
                }
            }
        }
    }

    private fun serve() {
        serveALaunch()
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":["environment.v1"]}"""
        memberVariables = port
    }

    @Test
    fun theAgentStartsWithTheVariablesFirstSoOstromsOwnWinAndTheirFileLast() = runTest {
        serve()
        assertEquals(3, start())
        assertEquals(setOf("environment.v1"), asked.single(), "asked with what the server serves")
        val environment = environments.single()
        assertEquals("https://api", environment["API_URL"])
        assertEquals("/work/acme-api/src", environment["PWD"], "Ostrom's own variable wins")
        assertEquals("$temporary/environment.sh", environment["CLAUDE_ENV_FILE"])
        assertEquals(Triple(Agent.CLAUDE, read.variables!!, temporary), delivered.single())
        assertTrue(refreshing, "kept current while the agent runs")
        assertTrue(refreshCancelled, "and no longer once it has exited")
        assertTrue(closed)
        val notes = said.filter { note -> listOf("read", "before", "while").any { note.startsWith("ostrom: $it") } }
        assertEquals(listOf("ostrom: read", "ostrom: before the agent", "ostrom: while the agent runs"), notes)
        assertTrue(said.indexOf("ostrom: while the agent runs") > said.indexOf("ostrom: before the agent"))
    }

    @Test
    fun noVariablesAreAskedWithNoVariables() = runTest {
        serve()
        assertEquals(3, start(giveVariables = false))
        assertTrue(asked.isEmpty())
        assertTrue(delivered.isEmpty())
        assertFalse("API_URL" in environments.single())
    }

    @Test
    fun anAgentWithoutVariablesOrWithoutADeliveryStartsAsBefore() = runTest {
        serve()
        read = VariablesRead(null, listOf("ostrom: interactive logins only"))
        assertEquals(3, start())
        assertTrue("ostrom: interactive logins only" in said)
        assertTrue(delivered.isEmpty())
        read = VariablesRead(mapOf("API_URL" to "https://api"))
        delivery = false
        assertEquals(3, start())
        assertNull(environments.last()["CLAUDE_ENV_FILE"])
        assertEquals("https://api", environments.last()["API_URL"])
        assertFalse(refreshing)
    }

    @Test
    fun aLaunchWithoutThePortGivesNone() = runTest {
        serveALaunch()
        assertEquals(3, start())
        assertNull(NoMemberVariables.deliver(Agent.CLAUDE, emptyMap(), site(), "/tmp/x") {})
        assertEquals("VariablesRead([API_URL], notes=[])", VariablesRead(mapOf("API_URL" to "secret")).toString())
        assertEquals("VariablesRead(null, notes=[])", VariablesRead(null).toString())
    }

    private fun site() =
        LaunchSite("https://o", Credentials("t", "a"), "/w", "ostrom", "/c", "/tmp", "/h", ':', { null })
}
