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
    }

    @Test
    fun otherCommandsGoThroughTheTree() {
        assertNull(CloudProcess.of(listOf("cloud", "list")))
        assertNull(CloudProcess.of(listOf("cloud")))
        assertNull(CloudProcess.of(listOf("runner")))
        assertNull(CloudProcess.of(emptyList()))
    }
}
