// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FailureNoticesTest {
    @Test
    fun ktorsMessagesNeverReachTheTerminalAndAnErrorIsReportedOnceWithoutItsText() {
        val reports = mutableListOf<String>()
        val logger = silentLogger(FailureNotices { reports.add(it) })
        val cause = IllegalStateException("GET $SERVER/api/v1/gates with $AGENT_TOKEN")
        logger.error("GET $SERVER/api/v1/gates failed")
        logger.error("again", cause)
        logger.warn("warn")
        logger.warn("warn", cause)
        logger.info("info")
        logger.info("info", cause)
        logger.debug("debug")
        logger.debug("debug", cause)
        logger.trace("trace")
        logger.trace("trace", cause)
        assertEquals(listOf("Ostrom's local relay could not complete a request."), reports)
    }

    @Test
    fun theConfigurationPrintsWithoutTheAgentToken() {
        val printed = relayConfig().toString()
        assertFalse(printed.contains(AGENT_TOKEN))
        assertTrue(printed.contains("agentToken=<redacted>"))
        assertEquals(
            "RelayConfig(serverUrl=$SERVER/, agentToken=<redacted>, userAgent=$USER_AGENT, launchId=$LAUNCH, " +
                "mcpServers=[github], inference=false, background=false)",
            printed,
        )
    }

    @Test
    fun aRefusalIsToldOncePerStatusAndCodeEveryTenMinutes() = runTest {
        val reports = mutableListOf<String>()
        val clock = TestTimeSource()
        val notices = FailureNotices(clock) { reports.add(it) }
        var reads = 0
        val message: suspend () -> String? = { reads++; "The monthly budget is spent." }
        assertTrue(notices.refused(429, "budget_exhausted", message))
        assertFalse(notices.refused(429, "budget_exhausted", message), "A retry is not told again")
        assertTrue(notices.refused(503, "no_credential") { null }, "Another cause is told at once")
        clock += 9.minutes + 59.seconds
        assertFalse(notices.refused(429, "budget_exhausted", message))
        clock += 1.seconds
        assertTrue(notices.refused(429, "budget_exhausted", message), "Told again after ten minutes")
        assertEquals(2, reads, "The cause is read only when it is told")
        assertEquals(
            listOf(
                "Ostrom answered HTTP 429 (budget_exhausted): The monthly budget is spent.",
                "Ostrom answered HTTP 503 (no_credential): see the agent's error.",
                "Ostrom answered HTTP 429 (budget_exhausted): The monthly budget is spent.",
            ),
            reports,
        )
    }

    @Test
    fun pastSixtyFourCausesTheRefusalsToldAreForgotten() = runTest {
        val notices = FailureNotices(TestTimeSource()) { }
        for (i in 0 until 64) assertTrue(notices.refused(429, "code_${'a' + i % 26}${'a' + i / 26}") { null })
        assertTrue(notices.refused(429, "one_more") { null })
        assertTrue(notices.refused(429, "code_aa") { null }, "Forgotten, so told again")
        assertFalse(notices.refused(429, "one_more") { null }, "The newest is remembered")
    }
}
