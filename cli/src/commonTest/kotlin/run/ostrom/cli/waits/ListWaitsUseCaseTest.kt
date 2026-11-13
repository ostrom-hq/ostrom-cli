// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val USE_LIST = "use the id that ostrom waits list prints."

class ListWaitsUseCaseTest {
    private val fake = FakeWaitsServer()
    private val list = ListWaitsUseCase(fake.server)
    private val open = ListWaits("open", null, null, null, 50)

    private fun items(count: Int, from: Int = 0) = (from until from + count).joinToString(",") {
        """{"id":"w$it"}"""
    }

    @Test
    fun theWaitsAskedOfTheCallerAreListedByDefault() = runTest {
        fake.answer("GET /api/v1/waits?status=open&mine=true&limit=50", 200, """{"items":[${items(2)}]}""")
        val listing = list(open)
        assertEquals(listOf("w0", "w1"), listing.items.map { it["id"].toString().trim('"') })
        assertEquals(null, listing.next)
        assertEquals("tok", fake.calls.single().token)
    }

    @Test
    fun theQueryNamesTheAssigneeTheSessionAndTheCursor() = runTest {
        fake.answer("GET /api/v1/waits?status=all&assignee=all&limit=10", 200, """{"items":[]}""")
        val filtered = "GET /api/v1/waits?status=resolved&assignee=lena&sessionId=ses1&before=c0&limit=5"
        fake.answer(filtered, 200, """{"items":[]}""")
        list(ListWaits("all", "all", null, null, 10))
        list(ListWaits("resolved", "@lena", "ses1", "c0", 5))
        assertEquals(2, fake.calls.size)
        assertEquals(listOf("GET", "GET"), fake.calls.map { it.method })
    }

    @Test
    fun morePagesAreReadUpToTheLimitAndTheCursorOfTheNextIsGiven() = runTest {
        val first = "GET /api/v1/waits?status=open&mine=true&limit=100"
        val second = "GET /api/v1/waits?status=open&mine=true&before=w99&limit=20"
        fake.answer(first, 200, """{"items":[${items(100)}],"next":"w99"}""")
        fake.answer(second, 200, """{"items":[${items(20, 100)}],"next":"w119"}""")
        val listing = list(open.copy(limit = 120))
        assertEquals(120, listing.items.size)
        assertEquals("w119", listing.next)
    }

    @Test
    fun aPageLongerThanTheLimitIsCut() = runTest {
        fake.answer("GET /api/v1/waits?status=open&mine=true&limit=2", 200, """{"items":[${items(3)}],"next":"w2"}""")
        val listing = list(open.copy(limit = 2))
        assertEquals(2, listing.items.size)
        assertEquals("w2", listing.next)
    }

    @Test
    fun aPageWithoutWaitsEndsTheListWhateverCursorItNames() = runTest {
        fake.answer("GET /api/v1/waits?status=open&mine=true&limit=50", 200, """{"items":[],"next":"x"}""")
        fake.answer("GET /api/v1/waits?status=open&mine=true&limit=40", 200, """{"items":[${items(10)}],"next":""}""")
        assertEquals(null, list(open).next)
        assertEquals(1, fake.calls.size)
        assertEquals(null, list(open.copy(limit = 40)).next, "an empty cursor is no cursor")
    }

    @Test
    fun whatTheServerWouldRefuseIsRefusedBeforeAnythingIsSent() = runTest {
        val refused = listOf(
            open.copy(limit = 0) to "--limit is 1 to 500.",
            open.copy(limit = 501) to "--limit is 1 to 500.",
            open.copy(assignee = "a b") to "\"a b\" is not a member id or a username.",
            open.copy(session = "ses 1") to "\"ses 1\" is not a valid session id: $USE_LIST",
            open.copy(before = "x/y") to "\"x/y\" is not a valid cursor: $USE_LIST",
        )
        for ((request, message) in refused) {
            assertEquals(message, assertFailsWith<WaitInputException> { list(request) }.message)
        }
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun aRefusalOfTheServerIsPassedOn() = runTest {
        val forbidden = """{"error":{"type":"forbidden","message":"nope"}}"""
        fake.answer("GET /api/v1/waits?status=open&mine=true&limit=50", 403, forbidden)
        val refusal = assertFailsWith<WaitsRefusal> { list(open) }
        assertEquals(403, refusal.status)
        assertEquals("forbidden", refusal.type)
        assertEquals("nope", refusal.message)
    }
}
