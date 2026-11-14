// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudProcessTest {
    @Test
    fun theHiddenCommandsAreRecognised() {
        assertEquals("runner", CloudProcess.of(listOf("cloud", "runner")))
        assertEquals("agent-supervisor", CloudProcess.of(listOf("cloud", "agent-supervisor")))
        assertEquals("check-host", CloudProcess.of(listOf("cloud", "check-host")))
        assertEquals("credential", CloudProcess.of(listOf("cloud", "credential", "get")))
        assertEquals("machine", CloudProcess.of(listOf("cloud", "machine")))
        assertEquals("agent-state", CloudProcess.of(listOf("cloud", "agent-state")))
        assertEquals("environment", CloudProcess.of(listOf("cloud", "environment", "--socket", "/s")))
    }

    @Test
    fun otherCommandsGoThroughTheTree() {
        assertNull(CloudProcess.of(listOf("cloud", "list")))
        assertNull(CloudProcess.of(listOf("cloud")))
        assertNull(CloudProcess.of(listOf("runner")))
        assertNull(CloudProcess.of(emptyList()))
    }

    @Test
    fun theEnvironmentCommandReadsItsSocket() {
        val socket = "/run/ostrom/environment.sock"
        assertEquals(socket, CloudProcess.socket(listOf("--socket", socket)))
        assertEquals("/s", CloudProcess.socket(listOf("--socket=/s")))
        assertNull(CloudProcess.socket(listOf("--socket")))
        assertNull(CloudProcess.socket(listOf("--socket", " ")))
        assertNull(CloudProcess.socket(emptyList()))
    }
}
