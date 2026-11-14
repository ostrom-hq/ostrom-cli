// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A local launch's values file kept current, as TeamCode's `AgentEnvironmentTest` (EnvironmentRefresh). */
class EnvironmentRefreshTest {
    private val written = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private var answer: () -> Map<String, String> = { mapOf("A" to "1") }

    private fun refresh(initial: Map<String, String> = mapOf("A" to "1"), given: Set<String> = initial.keys) =
        EnvironmentRefresh({ answer() }, { written += it }, initial, given, sleep = {}, log = { logged += it })

    @Test
    fun theFileStartsWithWhatTheAgentStartsWith() {
        refresh(mapOf("A" to "1", "PATH" to "/evil")).start()
        assertTrue("export A='1'" in written.single())
        assertFalse("PATH" in written.single(), "a reserved name is never written")
    }

    @Test
    fun anUnchangedReadWritesNothingAChangeRewritesAndUnsetsWhatWasRemoved() = runTest {
        val refresh = refresh(given = setOf("A", "OLD"))
        assertFalse(refresh.refresh())
        assertTrue(written.isEmpty() && logged.isEmpty())
        answer = { mapOf("B" to "2") }
        assertTrue(refresh.refresh())
        assertTrue("unset A" in written.last() && "unset OLD" in written.last() && "export B='2'" in written.last())
        assertEquals("ostrom: environment variables updated for the agent's next commands: B", logged.single())
        answer = { emptyMap() }
        assertTrue(refresh.refresh())
        assertTrue("unset B" in written.last())
        assertEquals("ostrom: environment variables updated for the agent's next commands: none", logged.last())
    }

    @Test
    fun aFailedReadKeepsTheLastValuesAndIsSaidOnceThenItsRecovery() = runTest {
        val refresh = refresh()
        answer = { throw IOException("down") }
        assertFalse(refresh.refresh())
        assertFalse(refresh.refresh())
        answer = { mapOf("A" to "1") }
        assertFalse(refresh.refresh())
        assertEquals(
            listOf(
                "ostrom: environment variables could not be refreshed; the agent keeps the last values",
                "ostrom: environment variables are refreshed again",
            ),
            logged,
        )
        assertTrue(written.isEmpty())
    }

    @Test
    fun aCancellationGoesThrough() = runTest {
        answer = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { refresh().refresh() }
        assertEquals(60_000L, EnvironmentRefresh.INTERVAL_MILLIS)
    }
}
