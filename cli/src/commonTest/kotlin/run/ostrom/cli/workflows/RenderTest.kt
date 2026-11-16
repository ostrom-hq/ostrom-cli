// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** What the readable forms of a run and of a workflow say of the parts a server may leave out. */
class RenderTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aRunWithoutInputsSaysNone() {
        val run = parse(
            """{"id":"r1","workflowName":"Deploy","workflowVersion":2,"status":"succeeded","startedBy":"m1",""" +
                """"startedAt":"2026-11-13T10:00:00Z"}""",
        )
        val text = run.runDetails(0)
        assertTrue("Deploy v2  [r1]  succeeded" in text, text)
        assertTrue("Inputs:" !in text, text)
    }

    @Test
    fun aNodeThatAsksSaysWhat() {
        val workflow = parse(
            """{"id":"w1","name":"Ship","domain":"d","status":"accepted","nodes":[""" +
                """{"id":"n1","kind":"wait","label":"Ask","wait":{"title":"Which region?"}},""" +
                """{"id":"n2","kind":"wait","label":"Hold","wait":{}}],"edges":[]}""",
        )
        val text = workflow.workflowDetails()
        assertTrue("    asks: Which region?" in text, text)
    }
}
