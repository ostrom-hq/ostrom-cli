// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.hooks.runtime.CallResult
import dev.ostrom.cli.hooks.runtime.GateWait
import dev.ostrom.cli.hooks.runtime.OstromCalls
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom wait`: the line the model reads, and the gate's code. */
class WaitCommandTest : CliKit() {
    @Test
    fun waitPrintsTheLineTheModelReadsAndExitsWithItsCode() {
        val paths = mutableListOf<String>()
        val calls = object : OstromCalls {
            override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
                paths += path
                val request = JsonObject(mapOf("status" to JsonPrimitive("rejected")))
                return CallResult.Ok(JsonObject(mapOf("gate" to request)))
            }
        }
        val wait = { GateWait(calls, { 0L }, {}, {}) }
        assertEquals(2, cli(gateWait = wait).run(arrayOf("wait", "g1", "--timeout", "5")))
        assertOut("rejected by the gate owner (receipt -). Do not run the command; tell the user.\n")
        assertEquals(listOf("/api/v1/gates/g1?wait=5"), paths)
        assertEquals(64, cli(gateWait = wait).run(arrayOf("wait", "g1", "--timeout", "0")), "at least a second")
        assertEquals(0, cli(gateWait = wait).run(arrayOf("wait", "--help")))
        assertOut("Seconds to wait (default 110)")
    }
}
