// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoCheckReport
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoProof
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoToolsTest {
    private val merged = TodoCheck.PrMerged("acme/web")
    private val list = todo(item("fix", merged), next = "fix")

    private fun tools(api: FakeTodoApi, capture: FakeCapture = FakeCapture()) =
        TodoTools(api, TodoChecks(GitHub(capture), CommandCheck(capture, OperatingSystem.LINUX)))

    /** Calls [name] with [arguments] (JSON) and gives back its text and whether it is an error. */
    private fun TodoTools.call(name: String, arguments: String = "{}"): Pair<String, Boolean> {
        val result = tools().single { it.name == name }.call(Json.parseToJsonElement(arguments).jsonObject)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        return text to result.getValue("isError").jsonPrimitive.content.toBoolean()
    }

    private val itemJson = """{"id":"fix","name":"Fix","description":"d","doneWhen":{"text":"t","checks":""" +
        """[{"type":"pr_merged","repo":"acme/web"}]}}"""

    @Test
    fun theServerIsNamedAndInstructedAndListsItsNineTools() {
        val dispatcher = tools(FakeTodoApi()).dispatcher("1.2.3")
        val reply = Json.parseToJsonElement(
            dispatcher.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").orEmpty(),
        ).jsonObject.getValue("result").jsonObject
        assertEquals("ostrom-todo", reply.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(TodoDocs.INSTRUCTIONS, reply.getValue("instructions").jsonPrimitive.content)
        assertEquals(
            listOf(
                "todo_help", "todo_create", "todo_show", "todo_start", "todo_complete", "todo_link", "todo_add",
                "todo_note", "todo_drop",
            ),
            tools(FakeTodoApi()).tools().map { it.name },
        )
        val rule = "An\nitem that delivers a PR ends on pr_merged, even when a person merges it"
        assertTrue(TodoDocs.INSTRUCTIONS.contains(rule))
        assertEquals(TodoDocs.HELP to false, tools(FakeTodoApi()).call("todo_help"))
    }

    @Test
    fun showIsTheListOrHowToStartOne() {
        assertEquals(
            "This session has no todo yet. Call todo_create (todo_help shows the format)." to false,
            tools(FakeTodoApi(null)).call("todo_show"),
        )
        assertEquals(TodoRender.show(list) to false, tools(FakeTodoApi(list)).call("todo_show"))
    }

    @Test
    fun createSendsTheReferenceTheRefsAndTheItems() {
        val api = FakeTodoApi(list)
        val refs = """[{"kind":"artifact","id":"a-1","title":"Plan"}]"""
        val arguments = """{"reference":"The plan","refs":$refs,"items":[$itemJson]}"""
        val (text, error) = tools(api).call("todo_create", arguments)
        assertEquals(false, error)
        assertEquals("Todo created with 1 items.\n\n" + TodoRender.show(list), text)
        val sent = api.calls.single().second as CreateTodoRequest
        assertEquals("The plan" to "a-1", sent.reference to sent.refs.single().id)
        assertEquals(merged, sent.items.single().doneWhen.checks.single())
        for (noRefs in listOf("", ""","refs":null""")) {
            val again = FakeTodoApi(list)
            tools(again).call("todo_create", """{"reference":"r","items":[]$noRefs}""")
            assertEquals(emptyList(), (again.calls.single().second as CreateTodoRequest).refs)
        }
    }

    @Test
    fun argumentsThatCannotBeUsedAreSaidToTheAgent() {
        val tools = tools(FakeTodoApi(list))
        assertEquals("reference is required." to true, tools.call("todo_create", """{"items":[]}"""))
        assertEquals("reference is required." to true, tools.call("todo_create", """{"reference":7,"items":[]}"""))
        assertEquals("reference is required." to true, tools.call("todo_create", """{"reference":{},"items":[]}"""))
        assertEquals("items is required." to true, tools.call("todo_create", """{"reference":"r"}"""))
        val (text, error) = tools.call("todo_create", """{"reference":"r","items":[{"id":"x"}]}""")
        assertTrue(error && text.startsWith("items is not in the expected form: "), text)
        assertTrue(text.endsWith(". Call todo_help for the format."), text)
        assertEquals("item is required." to true, tools.call("todo_add", """{"reason":"r"}"""))
        assertEquals("id is required." to true, tools.call("todo_note"))
    }

    @Test
    fun ostromsRefusalsComeWithWhatToDo() {
        val hints = mapOf(
            "todo_exists" to "Use todo_add to add an item to the existing todo.",
            "todo_not_found" to "Create the todo first with todo_create (todo_help shows the format).",
            "already_linked" to "A link does not change. If the PR was closed without merge, pass replaceClosed: true.",
            "forbidden" to "This session's credential cannot keep a todo; carry on without it.",
            "dependency_open" to "Finish the item it depends on first (todo_show lists them).",
            "not_done" to "Fix what is missing (merge the PR, wait for CI, publish the release), then call " +
                "todo_complete again.",
        )
        for ((code, hint) in hints) {
            val api = FakeTodoApi(list).also { it.refuse("note fix", code, "No.") }
            assertEquals(
                "Ostrom refused: No. ($code). $hint" to true,
                tools(api).call("todo_note", """{"id":"fix","text":"t"}"""),
            )
        }
        val api = FakeTodoApi(list).also { it.refuse("add", "too_many_items", "500 at most.") }
        assertEquals(
            "Ostrom refused: 500 at most. (too_many_items)." to true,
            tools(api).call("todo_add", """{"item":$itemJson,"reason":"found"}"""),
        )
    }

    @Test
    fun startGivesTheMarkerOnlyForAPullRequestItem() {
        val withPr = FakeTodoApi(list)
        val (text, _) = tools(withPr).call("todo_start", """{"id":"fix","background":true,"agent":"helper"}""")
        assertEquals(
            "Item fix is in progress.\nWhat fix is about\nDone when: Done when fix holds\nPut this marker, as is, in " +
                "the body of the pull request you open for it (the PR is found and linked by it):\n" +
                "<!-- ostrom-todo: 01234567/x -->",
            text,
        )
        val sent = withPr.calls.single().second as StartTodoItemRequest
        assertEquals(true to "helper", sent.background to sent.agent)
        val closing = FakeTodoApi(todo(item("old", TodoCheck.PrClosed("acme/web", "moot"))))
        assertTrue(tools(closing).call("todo_start", """{"id":"old","background":"yes"}""").first.contains("marker"))
        assertEquals(false, (closing.calls.single().second as StartTodoItemRequest).background)
        val research = FakeTodoApi(todo(item("dig", TodoCheck.ArtifactPublished())))
        assertEquals(
            "Item dig is in progress.\nWhat dig is about\nDone when: Done when dig holds" to false,
            tools(research).call("todo_start", """{"id":"dig"}"""),
        )
        assertEquals(
            "Ostrom refused: No item 'gone' in this todo. (unknown_item)." to true,
            tools(FakeTodoApi(list)).call("todo_start", """{"id":"gone"}"""),
        )
    }

    @Test
    fun completeRunsTheStoredChecksBindsWhatTheyFoundAndReports() {
        val done = todo(item("fix", merged, status = TodoItemStatus.DONE))
        val api = FakeTodoApi(list, after = done).also { it.refuse("link fix", "already_linked") }
        val capture = FakeCapture(
            "gh pr list" to printed("[${ghPr(5, "MERGED", marker("fix"), oid = "abc")}]"),
            "gh api user" to printed("maya"),
        )
        assertEquals(
            "Item fix is done.\n\n" + TodoRender.show(done) to false,
            tools(api, capture).call("todo_complete", """{"id":"fix"}"""),
        )
        assertEquals(listOf("read", "link fix", "complete fix"), api.calls.map { it.first })
        val link = api.calls[1].second as LinkTodoItemRequest
        assertEquals(TodoLinkKind.PR to 5, link.kind to link.number)
        assertEquals(
            listOf(TodoCheckReport(0, true, "PR #5 MERGED", listOf("https://github.com/acme/web/pull/5"))),
            (api.calls[2].second as CompleteTodoItemRequest).checks,
        )
    }

    @Test
    fun anAttestedItemTakesTheAgentsProofAndItsHttpsAddressesOnly() {
        val attested = todo(item("m", TodoCheck.Attested("ran")))
        val api = FakeTodoApi(attested)
        val proof = """{"text":"I ran it","urls":["https://ci/1","http://plain",7,{"a":1}]}"""
        tools(api).call("todo_complete", """{"id":"m","proof":$proof}""")
        val report = (api.calls.last().second as CompleteTodoItemRequest).checks.single()
        assertEquals(TodoCheckReport(0, true, "attested: I ran it", listOf("https://ci/1")), report)
        val bare = FakeTodoApi(attested)
        tools(bare).call("todo_complete", """{"id":"m","proof":{"text":"I ran it"}}""")
        assertEquals(emptyList(), (bare.calls.last().second as CompleteTodoItemRequest).checks.single().urls)
    }

    @Test
    fun aRefusedCompletionSaysWhatOstromSaw() {
        val kept = todo(item("fix", merged, proof = TodoProof(AT, false, "PR #5 is OPEN")))
        val capture = FakeCapture("gh pr list" to printed("[]"), "gh api user" to printed("maya"))
        val hint = "Fix what is missing (merge the PR, wait for CI, publish the release), then call " +
            "todo_complete again."
        val cases = listOf(
            kept to "Item fix is not done: PR #5 is OPEN. $hint",
            null to "Item fix is not done: No pull request of acme/web carries this item's marker: open it with the " +
                "marker from todo_start in its body.. $hint",
            todo(item("other")) to "Item fix is not done: No pull request of acme/web carries this item's marker: " +
                "open it with the marker from todo_start in its body.. $hint",
            todo(item("fix", merged)) to "Item fix is not done: No pull request of acme/web carries this item's " +
                "marker: open it with the marker from todo_start in its body.. $hint",
        )
        for ((afterRefusal, said) in cases) {
            val api = FakeTodoApi(list, afterRefusal).also { it.refuse("complete fix", "not_done") }
            assertEquals(said to true, tools(api, capture).call("todo_complete", """{"id":"fix"}"""))
        }
        val other = FakeTodoApi(list).also { it.refuse("complete fix", "item_closed", "Closed.") }
        val fix = """{"id":"fix"}"""
        assertEquals("Ostrom refused: Closed. (item_closed)." to true, tools(other, capture).call("todo_complete", fix))
        val linkFails = FakeTodoApi(list).also { it.refuse("link fix", "invalid_link", "Bad.") }
        val found = FakeCapture("gh pr list" to printed("[${ghPr(5, "OPEN", marker("fix"))}]"))
        assertEquals("Ostrom refused: Bad. (invalid_link)." to true, tools(linkFails, found).call("todo_complete", fix))
    }

    @Test
    fun completeNeedsAListAndOneOfItsItems() {
        assertEquals(
            "Ostrom refused: This session has no todo yet. (todo_not_found). Create the todo first with todo_create " +
                "(todo_help shows the format)." to true,
            tools(FakeTodoApi(null)).call("todo_complete", """{"id":"fix"}"""),
        )
        assertEquals(
            "No item 'gone' in this todo." to true,
            tools(FakeTodoApi(list)).call("todo_complete", """{"id":"gone"}"""),
        )
    }

    @Test
    fun linkDeducesTheKindOfAGitHubAddress() {
        val cases = listOf(
            """{"id":"fix","ref":"https://github.com/acme/web/pull/42","replaceClosed":true}""" to
                LinkTodoItemRequest(
                    kind = TodoLinkKind.PR,
                    ref = "https://github.com/acme/web/pull/42",
                    number = 42,
                    replaceClosed = true,
                ),
            """{"id":"fix","ref":"https://github.com/acme/web/releases/tag/v1.2"}""" to
                LinkTodoItemRequest(
                    kind = TodoLinkKind.RELEASE,
                    ref = "https://github.com/acme/web/releases/tag/v1.2",
                    tag = "v1.2",
                ),
            """{"id":"fix","ref":"a-1","kind":"artifact"}""" to
                LinkTodoItemRequest(kind = TodoLinkKind.ARTIFACT, ref = "a-1"),
            """{"id":"fix","ref":"w-1","kind":"wait"}""" to LinkTodoItemRequest(kind = TodoLinkKind.WAIT, ref = "w-1"),
            """{"id":"fix","ref":"p-1","kind":"pr"}""" to LinkTodoItemRequest(kind = TodoLinkKind.PR, ref = "p-1"),
            """{"id":"fix","ref":"r-1","kind":"release"}""" to
                LinkTodoItemRequest(kind = TodoLinkKind.RELEASE, ref = "r-1"),
        )
        for ((arguments, expected) in cases) {
            val api = FakeTodoApi(list)
            val (text, error) = tools(api).call("todo_link", arguments)
            assertEquals(false, error, text)
            assertTrue(text.startsWith("Item fix is linked to ${expected.ref}.\n\n"), text)
            assertEquals(expected, api.calls.single().second)
        }
        val api = FakeTodoApi(list)
        val wrongKind = "kind is pr, release, artifact or wait." to true
        assertEquals(wrongKind, tools(api).call("todo_link", """{"id":"fix","ref":"x","kind":"issue"}"""))
        assertEquals(wrongKind, tools(api).call("todo_link", """{"id":"fix","ref":"x","kind":""}"""))
        assertEquals(
            "Name the kind of link: pr, release, artifact or wait." to true,
            tools(api).call("todo_link", """{"id":"fix","ref":"https://example.com/x"}"""),
        )
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun addNoteAndDropSayWhatHappened() {
        val api = FakeTodoApi(list)
        val tools = tools(api)
        assertEquals(
            "Item fix added.\n\n" + TodoRender.show(list) to false,
            tools.call("todo_add", """{"item":$itemJson,"reason":"found"}"""),
        )
        assertEquals("found", (api.calls.last().second as AddTodoItemRequest).reason)
        assertEquals("Noted on fix." to false, tools.call("todo_note", """{"id":"fix","text":"half done"}"""))
        assertEquals("half done", (api.calls.last().second as NoteTodoItemRequest).text)
        val (dropped, _) = tools.call("todo_drop", """{"id":"fix","reason":"moot"}""")
        assertTrue(dropped.startsWith("The session's owner is asked whether to drop fix (a wait is open)."), dropped)
        assertEquals("moot", (api.calls.last().second as DropTodoItemRequest).reason)
        assertEquals("reason is required." to true, tools.call("todo_drop", """{"id":"fix"}"""))
    }

    @Test
    fun theSchemasNameTheirRequiredArguments() {
        val schema = tools(FakeTodoApi()).tools().single { it.name == "todo_link" }.definition
            .getValue("inputSchema").jsonObject
        assertEquals(listOf("id", "ref"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            setOf("id", "ref", "kind", "replaceClosed"),
            schema.getValue("properties").jsonObject.keys,
        )
        val items = tools(FakeTodoApi()).tools().single { it.name == "todo_create" }.definition
            .getValue("inputSchema").jsonObject.getValue("properties").jsonObject.getValue("items").jsonObject
        assertEquals(JsonPrimitive("object"), items.getValue("items").jsonObject["type"])
        assertEquals(null, (items as JsonObject)["missing"])
    }
}
