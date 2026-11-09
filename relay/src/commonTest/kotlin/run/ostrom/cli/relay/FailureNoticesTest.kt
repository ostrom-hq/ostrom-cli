// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                "mcpServers=[github], inference=false)",
            printed,
        )
    }
}
