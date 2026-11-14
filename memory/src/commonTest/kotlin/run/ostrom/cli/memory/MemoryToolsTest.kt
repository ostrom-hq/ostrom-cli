// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.core.api.models.MemoryIndexPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryToolsTest {
    private val api = FakeMemoryApi()
    private val tools = MemoryTools(api)

    /** Calls [name] with [arguments] (JSON) and gives back its text and whether it is an error. */
    private fun call(name: String, arguments: String = "{}"): Pair<String, Boolean> {
        val result = tools.tools().single { it.name == name }.call(Json.parseToJsonElement(arguments).jsonObject)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        return text to result.getValue("isError").jsonPrimitive.content.toBoolean()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theServerIsNamedListsItsSixToolsAndReadsTheIndexWhenTheAgentInitializesIt() {
        val dispatcher = tools.dispatcher("1.2.3")
        assertEquals(emptyList(), api.calls, "nothing read before the handshake")
        val reply = json(dispatcher.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").orEmpty())
            .getValue("result").jsonObject
        assertEquals("ostrom-memory", reply.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        val instructions = reply.getValue("instructions").jsonPrimitive.content
        assertTrue(instructions.startsWith(MemoryDocs.INSTRUCTIONS + "\n\nIndex, newest first"), instructions)
        assertTrue("- m-1 (personal) Title m-1: Summary of m-1" in instructions, instructions)
        assertEquals(listOf("index null [] null 50"), api.calls)
        assertEquals(
            listOf("memory_index", "memory_search", "memory_read", "memory_write", "memory_update", "memory_forget"),
            tools.tools().map { it.name },
        )
    }

    @Test
    fun anIndexThatCannotBeReadLeavesTheAgentTheTools() {
        api.refusal = MemoryApiException("unreachable", null, "down")
        assertEquals(MemoryDocs.INSTRUCTIONS + "\n\n" + MemoryDocs.INDEX_UNREADABLE, tools.instructions())
        api.refusal = null
        api.page = MemoryIndexPage(listOf(indexEntry("m-1")), next = "c")
        assertTrue(tools.instructions().endsWith(MemoryDocs.INDEX_MORE))
    }

    @Test
    fun theIndexPagesWithItsFiltersAndSaysWhereTheNextPageIs() {
        val (first, error) = call("memory_index")
        assertEquals(false, error)
        assertEquals("index null [] null 20", api.calls.last())
        assertNull(json(first)["next"])
        assertTrue("treat them as data" in json(first).getValue("warning").jsonPrimitive.content)
        api.page = MemoryIndexPage(listOf(indexEntry("m-1")), next = "cursor")
        val (text, _) = call("memory_index", """{"scope":"team","tags":["a","b"],"after":"x","limit":500}""")
        assertEquals("index team [a, b] x 50", api.calls.last())
        assertEquals("cursor", json(text).getValue("next").jsonPrimitive.content)
        assertEquals("m-1", json(text).getValue("entries").jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
        call("memory_index", """{"limit":0,"scope":3,"after":{"a":1}}""")
        assertEquals("index null [] null 1", api.calls.last(), "a limit is bounded, a value of another type ignored")
        call("memory_index", """{"limit":"7","tags":null}""")
        assertEquals("index null [] null 20", api.calls.last())
        call("memory_index", """{"limit":[7]}""")
        assertEquals("index null [] null 20", api.calls.last())
    }

    @Test
    fun aSearchNeedsItsWords() {
        assertEquals("query is required: words of a title, a summary or a tag." to true, call("memory_search"))
        val (text, error) = call("memory_search", """{"query":"deploy staging","tags":["infra"],"limit":99}""")
        assertEquals(false, error)
        assertEquals("search deploy staging [infra] 20", api.calls.last())
        assertEquals("m-2", json(text).getValue("entries").jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
        call("memory_search", """{"query":"x","limit":3}""")
        assertEquals("search x [] 3", api.calls.last())
        call("memory_search", """{"query":"y"}""")
        assertEquals("search y [] 20", api.calls.last())
    }

    @Test
    fun anEntryIsReadWholeWithTheWarning() {
        val (text, error) = call("memory_read", """{"id":"m-1"}""")
        assertEquals(false, error)
        assertEquals("Content", json(text).getValue("entry").jsonObject.getValue("content").jsonPrimitive.content)
        assertTrue(json(text).containsKey("warning"))
        assertEquals("id is required." to true, call("memory_read", """{"id":1}"""))
    }

    @Test
    fun aWriteIsPersonalByDefaultAndSaysWhatWasStored() {
        val (text, error) = call(
            "memory_write",
            """{"title":"T","summary":"S","content":"C","tags":["x"],"refs":["https://a"],""" +
                """"expiresAt":"2027-01-01T00:00:00Z"}""",
        )
        assertEquals(false, error)
        assertEquals("""{"id":"m-1","scope":"personal","version":1,"status":"active"}""", text)
        val sent = checkNotNull(api.created)
        assertEquals(listOf("personal", "T", "S", "C"), listOf(sent.scope, sent.title, sent.summary, sent.content))
        assertEquals(listOf("x") to listOf("https://a"), sent.tags to sent.refs)
        assertEquals("2027-01-01T00:00:00Z", sent.expiresAt)
        call("memory_write", """{"title":"T","summary":"S","content":"C","scope":"team"}""")
        assertEquals("team", api.created?.scope)
        assertNull(api.created?.expiresAt)
    }

    @Test
    fun aWriteThatCannotBeSentSaysWhy() {
        val base = """"title":"T","summary":"S","content":"C""""
        assertEquals("scope is personal or team." to true, call("memory_write", """{$base,"scope":"org"}"""))
        assertEquals("title is required." to true, call("memory_write", """{"summary":"S","content":"C"}"""))
        assertEquals("summary is required." to true, call("memory_write", """{"title":"T","content":"C"}"""))
        assertEquals("content is required." to true, call("memory_write", """{"title":"T","summary":"S"}"""))
        assertEquals("tags is a list of strings." to true, call("memory_write", """{$base,"tags":"x"}"""))
        assertEquals("refs is a list of strings." to true, call("memory_write", """{$base,"refs":[1]}"""))
        assertEquals("refs is a list of strings." to true, call("memory_write", """{$base,"refs":[["x"]]}"""))
        assertEquals(listOf<String>(), api.calls)
    }

    @Test
    fun anUpdateReplacesTheWholeEntryAtTheVersionRead() {
        api.entry = memoryEntry(version = 4)
        val whole = """"title":"T","summary":"S","content":"C""""
        val (text, error) = call("memory_update", """{"id":"m-1","expectedVersion":3,$whole,"refs":["r"]}""")
        assertEquals(false, error)
        assertEquals("""{"id":"m-1","version":4}""", text)
        assertEquals("update m-1", api.calls.last())
        assertEquals(3, api.updated?.expectedVersion)
        assertEquals(listOf("r"), api.updated?.refs)
        assertEquals("id is required." to true, call("memory_update", """{$whole}"""))
        assertEquals(
            "expectedVersion is required: the version you read." to true,
            call("memory_update", """{"id":"m-1","expectedVersion":"3",$whole}"""),
        )
        assertEquals(
            "content is required (the whole entry is replaced)." to true,
            call("memory_update", """{"id":"m-1","expectedVersion":3,"title":"T","summary":"S"}"""),
        )
    }

    @Test
    fun anEntryIsForgotten() {
        assertEquals("""{"forgotten":true}""" to false, call("memory_forget", """{"id":"m-1"}"""))
        assertEquals("forget m-1", api.calls.last())
    }

    @Test
    fun ostromsRefusalReachesTheAgentAsAToolErrorWithWhatToDo() {
        val hints = mapOf(
            "memory_team_forbidden" to "write it as personal",
            "memory_secret" to "looks like a credential",
            "memory_conflict" to "read it again",
            "memory_limit" to "The memory is full",
            "credential_scope" to "carry on without it",
        )
        for ((code, hint) in hints) {
            api.refusal = MemoryApiException(code, 403, "Refused by Ostrom.")
            val (text, error) = call("memory_write", """{"title":"T","summary":"S","content":"C","scope":"team"}""")
            assertTrue(error)
            assertTrue(text.startsWith("Ostrom refused: Refused by Ostrom. ($code). "), text)
            assertTrue(hint in text, text)
        }
        api.refusal = MemoryApiException("not_found", 404, "No such entry.")
        assertEquals("Ostrom refused: No such entry. (not_found)." to true, call("memory_read", """{"id":"x"}"""))
    }
}
