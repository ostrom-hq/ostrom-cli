// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `--json` is one JSON document and a newline, of a shape that only grows (docs/MODULES.md). */
class JsonOutputTest : CliKit() {
    @Test
    fun doctorJsonIsOneDocumentOfAKnownShapeEvenWithColoursForced() {
        assertEquals(0, cli().run(arrayOf("config", "set", "color", "always")))
        console.out.clear()
        cli().run(arrayOf("doctor", "--json"))
        val out = console.out.toString()
        assertTrue(out.endsWith("}\n") && out.count { it == '\n' } == 1, out)
        assertFalse(out.contains("\u001B"), out)
        val json = Json.parseToJsonElement(out) as JsonObject
        assertEquals(setOf("checks", "problems", "warnings"), json.keys)
        json.getValue("checks").jsonArray.forEach { check ->
            assertEquals(setOf("status", "message"), check.jsonObject.keys)
            assertTrue(check.jsonObject.getValue("status").jsonPrimitive.content in setOf("ok", "warning", "problem"))
        }
    }
}
