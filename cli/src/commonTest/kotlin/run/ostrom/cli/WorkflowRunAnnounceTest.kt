// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ostrom workflows run` said from an answer of the server that lacks fields: it says what it can. */
class WorkflowRunAnnounceTest : SetupKit() {
    private val show = "GET /api/v1/workflows/deploy"
    private val start = "POST /api/v1/workflows/deploy/runs"

    @Test
    fun aStartedRunThatLacksItsVersionIsStillAnnounced() {
        val routes = mapOf(
            capabilities("workflows.v1"),
            show to (200 to workflowJson("deploy", "Deploy")),
            start to (201 to """{"id":"r5"}"""),
        )
        assertEquals(0, run(routes, "workflows", "run", "deploy"))
        assertEquals(
            "Started run r5 of Deploy.\nFollow it: ostrom workflows run-show r5   " +
                "Stop it: ostrom workflows cancel r5\n",
            console.out.toString(),
        )
    }

    @Test
    fun aStartedRunWithoutItsIdFailsLoudly() {
        val routes = mapOf(
            capabilities("workflows.v1"),
            show to (200 to workflowJson("deploy", "Deploy")),
            start to (201 to "{}"),
        )
        assertFailsWith<NoSuchElementException> { run(routes, "workflows", "run", "deploy") }
    }
}
