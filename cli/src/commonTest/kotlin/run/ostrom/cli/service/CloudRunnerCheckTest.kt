// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudRunnerCheckTest {
    private fun environment(vararg values: Pair<String, String>) = object : Environment {
        override fun get(name: String) = values.toMap()[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    @Test
    fun aRunnersSocketsMakeAPodNotADevice() {
        assertFalse(insideCloudRunner(environment()) { false })
        assertTrue(insideCloudRunner(environment("OSTROM_AGENT_SOCKET" to "/run/ostrom/agent.sock")) { false })
        assertTrue(insideCloudRunner(environment("OSTROM_CREDENTIAL_SOCKET" to "/x")) { false })
        assertTrue(insideCloudRunner(environment()) { it == "/run/ostrom/credential.sock" })
        assertTrue(insideCloudRunner(environment("OSTROM_AGENT_SOCKET" to "  ")) { it == "/run/ostrom/agent.sock" })
        assertFalse(insideCloudRunner(environment("OSTROM_AGENT_SOCKET" to "  ")) { false })
    }

    @Test
    fun aDevicesOwnFilesAreNeverTakenForARunner() {
        assertFalse(insideCloudRunner(environment("OSTROM_HOME" to "/home/u/.config/ostrom")))
    }
}
