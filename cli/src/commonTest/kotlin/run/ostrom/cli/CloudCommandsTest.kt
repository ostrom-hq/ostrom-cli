// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.cloud.CloudSite
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.cloud.CloudStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> --cloud` and `ostrom attach`. */
class CloudCommandsTest : CliKit() {
    @Test
    fun agentArgumentsWithCloudAreAUsageErrorBeforeTheSignIn() {
        val arguments = arrayOf("run", "claude", "--cloud", "--", "-p", "hello")
        assertEquals(64, cli(environment = withServer).run(arguments), "not 65")
        val said = console.err.toString()
        assertTrue(said.startsWith("ostrom: agent arguments do not go to a cloud session"), said)
    }

    @Test
    fun cloudStartsTheAgentInOstromsCloudWithTheCredentialsOfThisDevice() {
        signIn("/home/maya/.config/ostrom")
        val arguments = arrayOf("run", "codex", "--cloud", "--repo", "acme/api", "--model", "gpt-5")
        val cloud = serverOf(mapOf(capabilities("runners.cloud.v1")))
        assertEquals(5, cli(environment = withServer, api = cloud).run(arguments), "the session's exit code")
        val site = CloudSite("https://ostrom.example", "ost_1", "/work/acme")
        assertEquals(listOf(CloudStart(Agent.CODEX, "gpt-5", "acme/api") to site), clouds)
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--", "-p", "hello")))
        val said = "ostrom: agent arguments do not go to a cloud session (-p hello): start it with " +
            "`ostrom run claude --cloud`, then type in it\n"
        assertTrue(console.err.endsWith(said), console.err.toString())
        assertEquals(1, clouds.size, "nothing started")
        assertTrue(launches.isEmpty(), "never launched here")
    }

    @Test
    fun attachGivesThisTerminalToACloudSession() {
        val none = serverOf(mapOf(capabilities("runners.cloud.v1")))
        assertEquals(65, cli(environment = withServer, api = none).run(arrayOf("attach", "cs1")), "not signed in")
        signIn("/home/maya/.config/ostrom")
        assertEquals(6, cli(environment = withServer, api = none).run(arrayOf("attach", "cs1")))
        assertEquals(listOf("cs1" to CloudSite("https://ostrom.example", "ost_1", "/work/acme")), attached)
        assertEquals(64, cli(environment = withServer).run(arrayOf("attach")), "the session is required")
        // `cloud attach`, as TeamCode has it too
        assertEquals(6, cli(environment = withServer, api = none).run(arrayOf("cloud", "attach", "cs2")))
        assertEquals("cs2", attached.last().first)
    }
}
