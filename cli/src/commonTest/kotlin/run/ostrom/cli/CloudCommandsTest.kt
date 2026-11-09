// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.cloud.CloudSite
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.cloud.CloudStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom <agent> --cloud` and `ostrom attach`. */
class CloudCommandsTest : CliKit() {
    @Test
    fun agentArgumentsWithCloudAreAUsageErrorBeforeTheSignIn() {
        assertEquals(64, cli(environment = withServer).run(arrayOf("claude", "--cloud", "-p", "hello")), "not 65")
        val said = console.err.toString()
        assertTrue(said.startsWith("ostrom: agent arguments do not go to a cloud session"), said)
    }

    @Test
    fun cloudStartsTheAgentInOstromsCloudWithTheCredentialsOfThisDevice() {
        signIn("/home/maya/.config/ostrom")
        val arguments = arrayOf("codex", "--cloud", "--repo", "acme/api", "--model", "gpt-5")
        assertEquals(5, cli(environment = withServer).run(arguments), "the session's exit code")
        val site = CloudSite("https://ostrom.example", "ost_1", "/work/acme")
        assertEquals(listOf(CloudStart(Agent.CODEX, "gpt-5", "acme/api") to site), clouds)
        assertEquals(64, cli(environment = withServer).run(arrayOf("claude", "--cloud", "-p", "hello")))
        val said = "ostrom: agent arguments do not go to a cloud session (-p hello): start it with " +
            "`ostrom claude --cloud`, then type in it\n"
        assertTrue(console.err.endsWith(said), console.err.toString())
        assertEquals(1, clouds.size, "nothing started")
        assertTrue(launches.isEmpty(), "never launched here")
    }

    @Test
    fun attachGivesThisTerminalToACloudSession() {
        val none = serverOf(emptyMap())
        assertEquals(65, cli(environment = withServer, api = none).run(arrayOf("attach", "cs1")), "not signed in")
        signIn("/home/maya/.config/ostrom")
        assertEquals(6, cli(environment = withServer, api = none).run(arrayOf("attach", "cs1")))
        assertEquals(listOf("cs1" to CloudSite("https://ostrom.example", "ost_1", "/work/acme")), attached)
        assertEquals(64, cli(environment = withServer).run(arrayOf("attach")), "the session is required")
    }
}
