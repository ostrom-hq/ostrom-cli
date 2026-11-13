// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `--var`, `--variables-file` and the payloads of `automations`, checked before anything is sent. */
class AutomationVariablesTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun refused(block: () -> Unit): String =
        assertFailsWith<AutomationInputException> { block() }.message.orEmpty()

    private fun refusedVar(spec: String) = refused { parseVariable(spec) }

    @Test
    fun aVariableIsANameAndAPathAndOnlyWhatDiffersFromTheDefaultsIsSent() {
        assertEquals(json("""{"name":"ticket","path":"${'$'}.ticket.id"}"""), parseVariable("ticket=\$.ticket.id"))
        assertEquals(
            json(
                """{"name":"n.x","path":"${'$'}.a[0]","type":"int","maxLength":10,"required":false,"sensitive":true}""",
            ),
            parseVariable("n.x=\$.a[0];type=int;max=10;optional;sensitive"),
        )
        assertEquals(
            json("""{"name":"s","path":"${'$'}.s","type":"enum","values":["a","b"]}"""),
            parseVariable("s=\$.s; values=a, b"),
        )
        for (type in listOf("string", "int", "number", "boolean")) {
            assertEquals(type, parseVariable("v=\$.v;type=$type")["type"].toString().trim('"'))
        }
    }

    @Test
    fun aVariableThatIsNotWrittenAsDocumentedIsNamedAndRefused() {
        assertTrue("NAME=" in refusedVar("ticket"))
        assertTrue("NAME=" in refusedVar("=\$.x"))
        assertTrue("a name is letters" in refusedVar("bad name=\$.x"))
        assertTrue("starts with" in refusedVar("ticket=ticket.id"))
        assertTrue("\"type\" is given twice" in refusedVar("t=\$.t;type=int;type=string"))
        assertTrue("optional takes no value" in refusedVar("t=\$.t;optional=yes"))
        assertTrue("type needs a value" in refusedVar("t=\$.t;type"))
        assertTrue("unknown option \"color\"" in refusedVar("t=\$.t;color=red"))
        assertTrue("type is string" in refusedVar("t=\$.t;type=date"))
        assertTrue("leave type= out" in refusedVar("t=\$.t;type=int;values=a"))
        assertTrue("non-empty values" in refusedVar("t=\$.t;values=a,,b"))
        assertTrue("non-empty values" in refusedVar("t=\$.t;values=" + (1..51).joinToString(",")))
        for (max in listOf("x", "0", "4097")) assertTrue("max is a number" in refusedVar("t=\$.t;max=$max"), max)
    }

    @Test
    fun theVariablesOfSeveralOptionsAreAtMostThirtyTwoWithOneNameEach() {
        assertEquals(2, parseVariables(listOf("a=\$.a", "b=\$.b")).size)
        assertTrue("Two variables are named a" in refused { parseVariables(listOf("a=\$.a", "a=\$.b")) })
        assertTrue("at most 32" in refused { parseVariables((1..33).map { "v$it=\$.v" }) })
    }

    @Test
    fun aVariablesFileIsAnArrayOfObjectsWithANameAndAPath() {
        val file = """[{"name":"a","path":"${'$'}.a","type":"string"},{"name":"b","path":"${'$'}.b"}]"""
        assertEquals(listOf("a", "b"), variablesFromFile(file).map { it["name"].toString().trim('"') })
        assertEquals(emptyList(), variablesFromFile("[]"))
        for (bad in listOf("{bad", "{}", "[1]", """[{"name":"a"}]""", """[{"path":"${'$'}.a"}]""")) {
            assertTrue("variable" in refused { variablesFromFile(bad) }, bad)
        }
        val twice = """[{"name":"a","path":"${'$'}.a"},{"name":"a","path":"${'$'}.b"}]"""
        assertTrue("Two variables" in refused { variablesFromFile(twice) })
    }

    @Test
    fun aPayloadIsAJsonObjectOfAtMost64KibAndTheErrorNeverRepeatsIt() {
        assertEquals(json("""{"a":1}"""), parsePayload("""{"a":1}""", "payload"))
        val tooLarge = refused { parsePayload("{\"a\":\"" + "x".repeat(MAX_PAYLOAD_BYTES) + "\"}", "payload") }
        assertEquals("The payload is larger than 64 KiB.", tooLarge)
        val notObject = refused { parsePayload("""["secret-value"]""", "sample payload") }
        assertEquals("The sample payload is not a JSON object.", notObject)
        assertEquals("The payload is not a JSON object.", refused { parsePayload("secret-value", "payload") })
    }
}
