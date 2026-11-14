// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.MEMORY
import run.ostrom.cli.launcher.adapters.SessionLayout
import run.ostrom.cli.memory.MemoryDocs
import run.ostrom.core.api.models.MemoryApi
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The launcher and the relay depend on no other module: their names of the memory are checked against the memory
 * module's and ostrom-core's.
 */
class MemoryFeatureTest {
    @Test
    fun theLauncherNamesTheMemoryAsTheServerAndTheMemoryModuleDo() {
        assertEquals(ServerFeatures.MEMORY, MEMORY)
        assertEquals(MemoryDocs.LABEL, SessionLayout.MEMORY_SERVER)
        assertEquals(MemoryDocs.PROMPT, SessionLayout.MEMORY_INSTRUCTIONS)
        assertEquals("x-ostrom-origin" to "agent", MemoryApi.ORIGIN_HEADER to MemoryApi.AGENT_ORIGIN)
    }
}
