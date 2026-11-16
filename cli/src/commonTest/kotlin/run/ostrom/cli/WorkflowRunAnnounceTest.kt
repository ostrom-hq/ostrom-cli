// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom workflows run` said from an answer of the server that lacks fields: it still says what it can. */
class WorkflowRunAnnounceTest : SetupKit() {
    private val show = "GET /api/v1/workflows/deploy"
    private val start = "POST /api/v1/workflows/deploy/runs"

    @Test
    fun aStartedRunThatLacksItsFieldsIsStillAnnounced() {
        val routes = mapOf(
            capabilities("workflows.v1"),
            show to (200 to workflowJson("deploy", "Deploy")),
            start to (201 to "{}"),
        )
        assertEquals(0, run(routes, "workflows", "run", "deploy"))
        assertEquals(
            "Started run ? of Deploy.\nFollow it: ostrom workflows run-show ?   Stop it: ostrom workflows cancel ?\n",
            console.out.toString(),
        )
    }
}
