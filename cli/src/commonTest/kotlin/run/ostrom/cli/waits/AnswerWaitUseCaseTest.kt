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
        fake.answer("POST /api/v1/waits/w1/answer", status, waitJson(status = "resolved").toString())
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
    fun everyWordOfAConfirmationIsRead() = runTest {
        val words = mapOf("true" to true, "yes" to true, "y" to true, "false" to false, "no" to false, "n" to false)
        serve(wait("[${field("ok", "confirm")}]"))
        for ((word, value) in words) {
            answer(ask("ok=$word"))
            assertEquals("""{"action":"done","values":{"ok":$value}}""", fake.calls.last().body, word)
        }
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
        serve(wait())
        answer(ask())
        assertEquals("""{"action":"done"}""", fake.calls.last().body)
    }

    @Test
    fun aWaitNoLongerOpenIsNotAnswered() = runTest {
        serve(wait(status = "resolved"))
        assertEquals("This wait is already resolved.", refusal(ask()))
        serve(wait(status = "cancelled"))
        assertEquals("This wait is already cancelled.", refusal(ask()))
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
        serve(wait("""[${field("note", "text")}]"""))
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
        serve(wait(actions = """[{"id":"yes","label":"Yes"},{"id":"no","label":"No"}]"""))
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

    @Test
    fun aSecretComesFromTheReaderOnlyAfterEverythingElseIsChecked() = runTest {
        val fields = listOf(field("pw", "secret"), field("note", "text")).joinToString(",", "[", "]")
        serve(wait(fields, """[{"id":"go","label":"Go"}]"""))
        var reads = 0
        val use = AnswerWaitUseCase(fake.server) { names -> reads++; names.associateWith { "p@ss" } }
        val request = AnswerWait("w1", null, listOf("note" to "n"), emptyList(), listOf("pw"))
        use(request)
        assertEquals(1, reads)
        assertEquals("""{"action":"go","values":{"note":"n","pw":"p@ss"}}""", fake.calls.last().body)
        val nope = AnswerWait("w1", null, emptyList(), emptyList(), listOf("nope"))
        val typo = assertFailsWith<WaitInputException> { use(nope) }
        assertEquals(1, reads, "nothing was read for a field the wait does not have")
        assertEquals(true, typo.message.orEmpty().startsWith("This wait has no field"))
        val twice = AnswerWait("w1", "stop", emptyList(), emptyList(), listOf("pw"))
        assertEquals(true, assertFailsWith<WaitInputException> { use(twice) }.message.orEmpty().contains("no action"))
        assertEquals(1, reads, "nothing was read for an action the wait does not have")
        val note = AnswerWait("w1", null, emptyList(), emptyList(), listOf("note"))
        val plain = assertFailsWith<WaitInputException> { use(note) }
        assertEquals("Field note is not a secret: give it with --set note=VALUE.", plain.message)
        assertEquals(
            true,
            refusal(ask("pw=x")).startsWith("Field pw is a secret: give it with --set-secret pw"),
        )
        assertEquals(
            "Field pw is a secret: give it with --set-secret pw, never as an argument.",
            refusal(ask(json = listOf("pw=\"x\""))),
        )
    }

    @Test
    fun aSecretIsNeitherEmptyNorLongerThanTheServerTakes() = runTest {
        serve(wait("[" + field("pw", "secret") + "]"))
        val empty = AnswerWaitUseCase(fake.server) { mapOf("pw" to "") }
        assertEquals("The value of pw is empty.", assertFailsWith<WaitInputException> {
            empty(AnswerWait("w1", null, emptyList(), emptyList(), listOf("pw")))
        }.message)
        val long = AnswerWaitUseCase(fake.server) { mapOf("pw" to "x".repeat(MAX_SECRET_BYTES + 1)) }
        assertEquals(true, assertFailsWith<WaitInputException> {
            long(AnswerWait("w1", null, emptyList(), emptyList(), listOf("pw")))
        }.message.orEmpty().contains("longer than $MAX_SECRET_BYTES bytes"))
    }

    @Test
    fun theSecretReaderTakesTheTerminalOrStandardInput() {
        val prompts = mutableListOf<String>()
        val typed = ArrayDeque(listOf("a", "b", "c\r"))
        assertEquals(emptyMap(), readSecrets(emptyList(), false, prompts::add, { error("no") }, { error("no") }))
        val asked = readSecrets(listOf("x", "y"), false, prompts::add, { typed.removeFirstOrNull() }, { error("no") })
        assertEquals(mapOf("x" to "a", "y" to "b"), asked)
        assertEquals(listOf("Secret for x (hidden): ", "Secret for y (hidden): "), prompts)
        val missing = readSecrets(listOf("z"), false, prompts::add, { null }, { error("no") })
        assertEquals(mapOf("z" to ""), missing)
        val lines = ArrayDeque(listOf("one", "two", "three\r"))
        val piped = readSecrets(listOf("p", "q"), true, prompts::add, { error("no") }, { lines.removeFirstOrNull() })
        assertEquals(mapOf("p" to "one", "q" to "two"), piped)
        val rest = readSecrets(listOf("k"), true, prompts::add, { error("no") }, { lines.removeFirstOrNull() })
        assertEquals(mapOf("k" to "three"), rest)
        val multi = ArrayDeque(listOf("-----BEGIN", "body", "-----END", ""))
        val key = readSecrets(listOf("k"), true, prompts::add, { error("no") }, { multi.removeFirstOrNull() })
        assertEquals(mapOf("k" to "-----BEGIN\nbody\n-----END"), key)
    }
}
