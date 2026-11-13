// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnswerWaitUseCaseTest {
    private val fake = FakeWaitsServer()
    private val answer = AnswerWaitUseCase(fake.server)

    private fun serve(wait: String, status: Int = 200) {
        fake.answer("GET /api/v1/waits/w1", 200, wait)
        fake.answer("POST /api/v1/waits/w1/answer", status, """{"id":"w1","status":"resolved"}""")
    }

    private fun wait(fields: String = "[]", actions: String = "[]", status: String? = "open") =
        waitJson(status = status, fields = fields, actions = actions).toString()

    private suspend fun refusal(request: AnswerWait): String =
        assertFailsWith<WaitInputException> { answer(request) }.message.orEmpty()

    private fun ask(
        vararg sets: String,
        json: List<String> = emptyList(),
        action: String? = null,
    ) = AnswerWait("w1", action, sets.map(::pairOf), json.map(::pairOf))

    private fun field(id: String, type: String, extra: String = "") =
        """{"id":"$id","type":"$type","label":"L"$extra}"""

    @Test
    fun aValueIsConvertedByTheTypeItsFieldDeclaresAndSentWithTheOnlyAction() = runTest {
        val fields = listOf(
            field("note", "text"),
            field("day", "date"),
            field("n", "number"),
            field("ratio", "number"),
            field("ok", "confirm"),
            field("no", "confirm"),
            field("region", "choice", ""","options":["eu","us"]"""),
            field("misc", "unknown"),
        ).joinToString(",", "[", "]")
        serve(wait(fields, """[{"id":"approve","label":"Approve"}]"""))
        val done = answer(
            ask(
                "note=a=b", "day=2026-11-13", "n= 42 ", "ratio=1.5", "ok=YES", "no=n", "region=eu", "misc=x",
            ),
        )
        assertEquals("resolved", done["status"].toString().trim('"'))
        assertEquals(
            """{"action":"approve","values":{"note":"a=b","day":"2026-11-13","n":42,"ratio":1.5,"ok":true,""" +
                """"no":false,"region":"eu","misc":"x"}}""",
            fake.calls.last().body,
        )
    }

    @Test
    fun aMultipleChoiceRepeatsItsOptionAndAFreeAnswerNeedsAllowOther() = runTest {
        val tags = field("tags", "choice", ""","options":["a","b"],"multiple":true,"allowOther":true""")
        serve(wait("[$tags]"))
        answer(ask("tags=a", "tags=free"))
        assertEquals("""{"action":"done","values":{"tags":["a","free"]}}""", fake.calls.last().body)
    }

    @Test
    fun aJsonValueIsUsedAsGiven() = runTest {
        serve(wait("[${field("tags", "text")}]", """[{"id":"a","label":"A"},{"id":"b","label":"B"}]"""))
        answer(ask(json = listOf("tags=[\"x\",2]"), action = "b"))
        assertEquals("""{"action":"b","values":{"tags":["x",2]}}""", fake.calls.last().body)
    }

    @Test
    fun aWaitWithoutFieldsAcceptsAnAnswerWithNoValues() = runTest {
        serve("""{"id":"w1","status":"open"}""")
        answer(ask())
        assertEquals("""{"action":"done","values":{}}""", fake.calls.last().body)
    }

    @Test
    fun aWaitNoLongerOpenIsNotAnswered() = runTest {
        serve(wait(status = "resolved"))
        assertEquals("This wait is already resolved.", refusal(ask()))
        serve(wait(status = null))
        assertEquals("This wait is already closed.", refusal(ask()))
        assertEquals(2, fake.calls.size, "read twice, answered never")
    }

    @Test
    fun anIdThatCannotBeOneIsRefusedAtOnce() = runTest {
        assertEquals(
            "\"a/b\" is not a valid wait id: use the id that ostrom waits list prints.",
            refusal(AnswerWait("a/b", null, emptyList(), emptyList())),
        )
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun anUnknownFieldIsRefusedNamingTheFieldsTheWaitHas() = runTest {
        serve(wait("""[${field("note", "text")},{"type":"text"}]"""))
        assertEquals("This wait has no field \"x\". Its fields: note.", refusal(ask("x=1")))
        assertEquals("This wait has no field \"x\". Its fields: note.", refusal(ask(json = listOf("x=1"))))
        serve(wait())
        assertEquals("This wait has no field \"x\". It takes no values.", refusal(ask("x=1")))
    }

    @Test
    fun aValueThatDoesNotConvertIsRefusedBeforeAnythingIsSent() = runTest {
        val fields = listOf(
            field("day", "date"),
            field("n", "number"),
            field("inf", "number"),
            field("ok", "confirm"),
            field("region", "choice", ""","options":["eu","us"]"""),
            field("hint", "info"),
            field("one", "text"),
            field("fake", "text", ""","multiple":true"""),
        ).joinToString(",", "[", "]")
        serve(wait(fields))
        val refused = mapOf(
            ask("day=tomorrow") to "Field day is a date: write it as YYYY-MM-DD, not \"tomorrow\".",
            ask("n=ten") to "Field n is a number: \"ten\" is not one.",
            ask("inf=Infinity") to "Field inf is a number: \"Infinity\" is not one.",
            ask("ok=maybe") to "Field ok is a confirmation: use true or false, not \"maybe\".",
            ask("region=asia") to "Field region takes one of: eu, us. \"asia\" is not one.",
            ask("hint=x") to "Field hint only shows information and takes no value.",
            ask("one=a", "one=b") to "Field one takes one value, not 2: repeat --set only for a multiple choice.",
            ask("fake=a", "fake=b") to "Field fake takes one value, not 2: repeat --set only for a multiple choice.",
            ask(json = listOf("one={")) to "The value of one is not valid JSON (--set-json): write strings in quotes.",
            ask("one=a", json = listOf("one=\"b\"")) to
                "Field one is given twice: use --set or --set-json for it, not both.",
        )
        for ((request, message) in refused) assertEquals(message, refusal(request))
        assertEquals(refused.size, fake.calls.size, "each wait was read, none answered")
    }

    @Test
    fun anActionTheWaitDoesNotHaveIsRefusedAndSeveralNeedOneToBeChosen() = runTest {
        serve(wait(actions = """[{"id":"yes","label":"Yes"},{"id":"no","label":"No"},{"label":"none"}]"""))
        assertEquals("This wait has no action \"maybe\". Its actions: yes, no.", refusal(ask(action = "maybe")))
        assertEquals("This wait has several actions: choose one with --action (yes, no).", refusal(ask()))
        answer(ask(action = "no"))
        assertEquals("no", fake.bodyOf(fake.calls.lastIndex)["action"].toString().trim('"'))
    }

    @Test
    fun aRefusalOfTheServerIsPassedOn() = runTest {
        serve(wait(), status = 409)
        assertEquals(409, assertFailsWith<WaitsRefusal> { answer(ask()) }.status)
    }
}
