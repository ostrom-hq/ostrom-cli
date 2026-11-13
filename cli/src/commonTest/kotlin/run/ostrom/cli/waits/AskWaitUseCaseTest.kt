// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AskWaitUseCaseTest {
    private val fake = FakeWaitsServer()
    private var keys = 0
    private val ask = AskWaitUseCase(fake.server) { "key-${++keys}" }
    private val minimal = AskWait(null, "Deploy?", null, null, emptyList(), emptyList(), emptyList(), emptyList())

    private suspend fun refusal(request: AskWait): String =
        assertFailsWith<WaitInputException> { ask(request) }.message.orEmpty()

    @Test
    fun aWaitIsAskedWithAFreshIdempotencyKeyEachTime() = runTest {
        fake.answer("POST /api/v1/waits", 201, """{"id":"w1"}""")
        assertEquals("w1", ask(minimal)["id"].toString().trim('"'))
        ask(minimal)
        assertEquals(listOf("key-1", "key-2"), fake.calls.map { it.idempotencyKey })
        assertEquals("""{"question":{"title":"Deploy?","fields":[],"actions":[],"refs":[]}}""", fake.calls[0].body)
    }

    @Test
    fun everythingTypedMakesTheQuestion() = runTest {
        fake.answer("POST /api/v1/waits", 201, """{"id":"w1"}""")
        val full = AskWait(
            session = "ses1",
            title = "Which region?",
            body = "Pick **one**",
            assignee = "@lena",
            refs = listOf("artifact:a1", "session:s2", "url:https://x.example/p?q=1"),
            fields = listOf("region:choice:Region: EU or US:required", "note:text:Note", "ok:confirm:Sure?:required"),
            options = listOf("region" to "eu", "region" to "us"),
            actions = listOf("go:Go: now", "stop:Stop"),
        )
        ask(full)
        val body = fake.bodyOf(0)
        assertEquals("ses1", body["sessionId"].toString().trim('"'))
        assertEquals("lena", body["assignee"].toString().trim('"'))
        val expected = """{"title":"Which region?","body":"Pick **one**","fields":[""" +
            """{"id":"region","type":"choice","label":"Region: EU or US","required":true,"options":["eu","us"]},""" +
            """{"id":"note","type":"text","label":"Note","required":false,"options":[]},""" +
            """{"id":"ok","type":"confirm","label":"Sure?","required":true,"options":[]}],""" +
            """"actions":[{"id":"go","label":"Go: now"},{"id":"stop","label":"Stop"}],""" +
            """"refs":[{"kind":"artifact","id":"a1"},{"kind":"session","id":"s2"},""" +
            """{"kind":"url","url":"https://x.example/p?q=1"}]}"""
        assertEquals(expected, body["question"].toString())
    }

    @Test
    fun whatTheServerWouldRefuseIsRefusedBeforeAnythingIsSent() = runTest {
        val long = "x".repeat(201)
        val refused = listOf(
            minimal.copy(session = "a b") to "\"a b\" is not a valid session id: $USE_LIST",
            minimal.copy(assignee = "a b") to "\"a b\" is not a member id or a username.",
            minimal.copy(title = " ") to "--title is 1 to 200 characters.",
            minimal.copy(title = long) to "--title is 1 to 200 characters.",
            minimal.copy(body = "x".repeat(32_001)) to "--body is 32000 characters at most.",
            minimal.copy(options = listOf("ghost" to "a")) to
                "--option ghost=… names a field that no --field declares.",
            minimal.copy(fields = listOf("note:text")) to FIELD,
            minimal.copy(fields = listOf("note:text:")) to FIELD,
            minimal.copy(fields = listOf("note:text::required")) to FIELD,
            minimal.copy(fields = listOf("1x:text:Note")) to
                "\"1x\" is not a field id: a letter, then letters, digits or _ (64 at most).",
            minimal.copy(fields = listOf("note:secret:Note")) to
                "\"secret\" is not a field type: use text, choice, confirm, number, date, info.",
            minimal.copy(fields = listOf("r:choice:Region")) to
                "The choice field r needs its choices: --option r=VALUE, repeated.",
            minimal.copy(fields = listOf("note:text:Note"), options = listOf("note" to "a")) to
                "--option note=… is for a choice field, and note is text.",
            minimal.copy(actions = listOf("go")) to ACTION,
            minimal.copy(actions = listOf("1:Go")) to ACTION,
            minimal.copy(actions = listOf("go: ")) to ACTION,
            minimal.copy(refs = listOf("file:x")) to REF,
            minimal.copy(refs = listOf("artifact:")) to REF,
            minimal.copy(refs = listOf("artifact:a/b")) to "\"a/b\" is not a valid artifact id: $USE_LIST",
            minimal.copy(refs = listOf("url:http://x.example")) to "--ref url: takes an https:// address.",
        )
        for ((request, message) in refused) assertEquals(message, refusal(request))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun aRefusalOfTheServerIsPassedOn() = runTest {
        fake.answer("POST /api/v1/waits", 403, """{"error":{"type":"wait_session_owner_only","message":"not yours"}}""")
        val refusal = assertFailsWith<WaitsRefusal> { ask(minimal) }
        assertEquals("wait_session_owner_only", refusal.type)
    }

    private companion object {
        const val FIELD = "--field is ID:TYPE:LABEL[:required], such as notes:text:Notes:required."
        const val ACTION = "--action is ID:LABEL, such as approve:Approve."
        const val REF = "--ref is artifact:ID, session:ID or url:https://… ."
        const val USE_LIST = "use the id that ostrom waits list prints."
    }
}
