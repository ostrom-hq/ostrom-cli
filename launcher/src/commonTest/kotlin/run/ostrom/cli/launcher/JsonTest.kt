// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {
    private val sample = checkNotNull(parseObject("""{"s":"x","n":1,"b":true,"o":{"k":"v"},"a":[1,2],"z":null}"""))

    @Test
    fun fieldsAreReadOnlyWithTheirType() {
        assertEquals("x", sample.text("s"))
        assertNull(sample.text("n"))
        assertNull(sample.text("missing"))
        assertEquals("v", sample.child("o")?.text("k"))
        assertNull(sample.child("s"))
        assertEquals(2, sample.list("a").size)
        assertEquals(emptyList(), sample.list("s"))
        assertEquals(true, sample.flag("b"))
        assertNull(sample.flag("s"), "a string is not a boolean")
        assertNull(sample.flag("n"), "a number is not a boolean")
        assertNull(sample.flag("o"))
        assertNull(sample.flag("missing"))
    }

    @Test
    fun onlyAnObjectIsParsed() {
        assertNull(parseObject(null))
        assertNull(parseObject("[1]"))
        assertNull(parseObject("{not json"))
        assertEquals(JsonObject(emptyMap()), parseObject("{}"))
    }

    @Test
    fun kotlinValuesBecomeJson() {
        assertEquals(JsonNull, jsonOf(null))
        assertEquals(JsonPrimitive("a"), jsonOf("a"))
        assertEquals(JsonPrimitive(2L), jsonOf(2L))
        assertEquals(JsonPrimitive(false), jsonOf(false))
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonNull)), jsonOf(listOf(1, null)))
        assertEquals("""{"k":{"x":"y"}}""", jsonOf(mapOf("k" to mapOf("x" to "y"))).toString())
        assertEquals(JsonPrimitive("kept"), jsonOf(JsonPrimitive("kept")))
        assertFailsWith<IllegalArgumentException> { jsonOf(Any()) }
        assertEquals("""{"a":1}""", objectOf("a" to 1, "b" to null).toString())
    }

    @Test
    fun aQuotedValueIsAJsonAndTomlString() {
        assertEquals("\"a \\\"b\\\" \\\\ c\\n\"", quoted("a \"b\" \\ c\n"))
        assertEquals("{\n    \"a\": 1\n}", pretty(objectOf("a" to 1)))
    }
}
