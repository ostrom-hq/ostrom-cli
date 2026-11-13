// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WaitRenderTest {
    private val created = "2026-11-13T10:00:00Z"
    private val at = 1_794_564_000_000L // 2026-11-13T10:00:00Z

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun anAgeIsShownInTheLargestUnit() {
        assertEquals("0s", age(created, at))
        assertEquals("59s", age(created, at + 59_000))
        assertEquals("5m", age(created, at + 300_000))
        assertEquals("3h", age(created, at + 3 * 3_600_000))
        assertEquals("2d", age(created, at + 2 * 86_400_000))
        assertEquals("0s", age(created, at - 5_000), "a clock behind the server")
        assertEquals("?", age("yesterday", at))
        assertEquals("?", age(null, at))
    }

    @Test
    fun aRowHasIdStatusKindTitleSessionAndAge() {
        assertEquals("w1  open  question  Which region?  -  5m", waitJson().line(at + 300_000))
        val bare = json("""{"sessionId":"ses1"}""")
        assertEquals("ses1  ?", bare.line(at))
    }

    @Test
    fun aWaitIsShownWithEverythingAPersonNeedsToAnswer() {
        val wait = json(
            """{"id":"w1","status":"resolved","kind":"question","assignee":"m1","sessionId":"ses1",
            |"createdAt":"$created","resolvedAt":"2026-11-13T11:00:00Z",
            |"question":{"title":"Which region?","body":"Pick one",
            |"refs":[{"kind":"artifact","id":"a1"},{"kind":"url","url":"https://x.example"}],
            |"fields":[
            |{"id":"region","type":"choice","label":"Region","required":true,"multiple":true,"allowOther":true,
            |"help":"Where to run","options":["eu","us"],"default":"eu"},
            |{"id":"note","type":"text","label":"Note","default":null},
            |{"id":"free","type":"text","label":"Free"}],
            |"actions":[{"id":"go","label":"Go","requires":["region","note"]},{"id":"stop","label":"Stop"}]},
            |"answer":{"action":"go","values":{"region":["eu"],"note":"ok","n":3},"author":"lena","answeredAt":"12:00"}}
            """.trimMargin().replace("\n", ""),
        )
        val expected = listOf(
            "Which region?  [w1]",
            "Status: resolved at 2026-11-13T11:00:00Z; kind: question",
            "Asked of: m1; session: ses1; created $created",
            "",
            "Pick one",
            "",
            "References:",
            "  artifact  a1",
            "  url  https://x.example",
            "",
            "Fields (id, type, label):",
            "  region  choice  Region (required, multiple, other allowed)",
            "    Where to run",
            "    options: eu, us",
            "    default: \"eu\"",
            "  note  text  Note",
            "  free  text  Free",
            "",
            "Actions:",
            "  go  Go (requires region, note)",
            "  stop  Stop",
            "",
            "Answer: go by lena at 12:00",
            "  region = [\"eu\"]",
            "  note = ok",
            "  n = 3",
        ).joinToString("\n")
        assertEquals(expected, wait.details())
    }

    @Test
    fun aBareWaitIsShownWithoutSections() {
        assertEquals(
            "null  [w2]\nStatus: open; kind: null\nAsked of: null; session: -; created null",
            json("""{"id":"w2","status":"open"}""").details(),
        )
        val noValues = json("""{"id":"w3","answer":{"action":"a","author":"x","answeredAt":"t"}}""")
        assertEquals(true, noValues.details().endsWith("\nAnswer: a by x at t"))
    }

    @Test
    fun aNameAndAValueAreSplitAtTheFirstEquals() {
        assertEquals("a" to "b=c", pairOf("a=b=c"))
        assertEquals("a" to "", pairOf("a="))
        val nameless = assertFailsWith<WaitInputException> { pairOf("=x") }
        assertEquals("\"\" needs the form NAME=VALUE.", nameless.message)
        val bare = assertFailsWith<WaitInputException> { pairOf("abc") }
        assertEquals("\"abc\" needs the form NAME=VALUE.", bare.message)
    }
}
