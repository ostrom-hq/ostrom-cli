// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.doctor

import dev.ostrom.cli.api.ServerResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** The verdict on one answer of the health route, whatever the version of the contract this build speaks. */
class HealthCheckTest {
    private val healthy = ServerResponse(200, """{"status":"ok","version":"0.3.1"}""", 5)

    private fun check(response: ServerResponse, contract: String) =
        healthCheck("ostrom.example", "https://ostrom.example", response, contract)

    @Test
    fun theSameMajorAndMinorAsTheContractIsOk() {
        assertEquals(CheckStatus.OK, check(healthy, "0.3.0").status)
        assertEquals(CheckStatus.OK, check(healthy, "v0.3.9").status)
    }

    @Test
    fun anotherMinorOrMajorOrAVersionWithoutNumbersIsAWarning() {
        assertEquals(CheckStatus.WARNING, check(healthy, "0.4.0").status)
        assertEquals(CheckStatus.WARNING, check(healthy, "1.3.0").status)
        val unnumbered = ServerResponse(200, """{"status":"ok","version":"dev"}""", 5)
        assertEquals(CheckStatus.WARNING, check(unnumbered, "0.3.0").status)
    }

    @Test
    fun aBuildWithoutTheContractsVersionComparesNothing() {
        val verdict = check(healthy, "")
        assertEquals(CheckStatus.OK, verdict.status)
        assertEquals("ostrom.example reachable (5 ms) · Ostrom 0.3.1", verdict.message)
    }
}
