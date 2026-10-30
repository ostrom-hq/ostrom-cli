package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GateCatalogue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogueStoreTest {
    private val copy = """{"failMode":"closed","actions":[{"class":"old"}]}"""
    private val served = ok("""{"actions":[{"class":"new"}]}""")
    private var started = 0

    private fun store(files: FakeFiles, calls: FakeCalls, context: HookContext = contextOf(), starts: Boolean = true) =
        CatalogueStore(context, files, calls) { started++; starts }

    @Test
    fun aFreshCopyIsServedWithoutACall() {
        val files = FakeFiles(mutableMapOf("/s/gates.json" to copy), mutableMapOf("/s/gates.json" to 1_000))
        val calls = FakeCalls()
        assertEquals("old", store(files, calls).current().actions.single().actionClass)
        assertTrue(calls.calls.isEmpty())
        assertEquals(0, started)
    }

    @Test
    fun pastASixthOfTheTtlOneRefreshStartsInTheBackground() {
        val files = FakeFiles(mutableMapOf("/s/gates.json" to copy), mutableMapOf("/s/gates.json" to 6_000))
        val calls = FakeCalls()
        val store = store(files, calls)
        assertEquals("old", store.current().actions.single().actionClass)
        assertEquals(1, started)
        assertEquals("refreshing", files.texts["/s/gates.json.refreshing"])
        files.ages["/s/gates.json.refreshing"] = 9_000
        store.current()
        assertEquals(1, started, "one refresh at a time")
        files.ages["/s/gates.json.refreshing"] = 10_000
        store.current()
        assertEquals(2, started, "a stale marker is taken over")
    }

    @Test
    fun aRefreshThatCannotStartOrMarkLeavesNoMarker() {
        val files = FakeFiles(mutableMapOf("/s/gates.json" to copy), mutableMapOf("/s/gates.json" to 6_000))
        store(files, FakeCalls(), starts = false).current()
        assertFalse("/s/gates.json.refreshing" in files.texts)
        assertEquals(1, started)
        files.readOnly = true
        store(files, FakeCalls()).current()
        assertEquals(1, started, "no marker, no refresh")
    }

    @Test
    fun pastTheTtlTheCatalogueIsRefreshedInLineAndKept() {
        val files = FakeFiles(mutableMapOf("/s/gates.json" to copy), mutableMapOf("/s/gates.json" to 30_000))
        val calls = FakeCalls().on("/api/bridge/gates/catalog", served)
        assertEquals("new", store(files, calls).current().actions.single().actionClass)
        assertEquals("/api/bridge/gates/catalog?repo=acme%2Fapp", calls.calls.single().path)
        assertEquals(800, calls.calls.single().timeoutMillis)
        val kept = files.texts.getValue("/s/gates.json")
        assertContains(kept, "\"failMode\":\"closed\"", message = "the rest of the copy is kept")
        assertContains(kept, "new")
    }

    @Test
    fun whenOstromCannotServeItTheCopyElseNothingIsMatched() {
        val stale = FakeFiles(mutableMapOf("/s/gates.json" to copy), mutableMapOf("/s/gates.json" to 60_000))
        assertEquals("old", store(stale, FakeCalls()).current().actions.single().actionClass)
        val bad = FakeCalls().on("/api/bridge/gates/catalog", ok("""{"actions":3}"""))
        assertEquals("old", store(stale, bad).refresh(1).first.actions.single().actionClass)
        assertEquals(GateCatalogue(), store(FakeFiles(), FakeCalls()).current())
        val noActions = FakeCalls().on("/api/bridge/gates/catalog", ok())
        assertEquals(GateCatalogue() to false, store(FakeFiles(), noActions).refresh(1))
    }

    @Test
    fun aDamagedCopyIsReplacedWhole() {
        val files = FakeFiles(mutableMapOf("/s/gates.json" to "{"))
        val calls = FakeCalls().on("/api/bridge/gates/catalog", served)
        assertEquals("new", store(files, calls).current().actions.single().actionClass)
        assertEquals("""{"actions":[{"class":"new"}]}""", files.texts["/s/gates.json"])
    }

    @Test
    fun outsideASessionTheCatalogueIsAskedEveryTime() {
        val context = contextOf("OSTROM_GATES" to "")
        val calls = FakeCalls().on("/api/bridge/gates/catalog", served)
        val store = store(FakeFiles(), calls, context)
        assertEquals("new", store.current().actions.single().actionClass)
        store.refreshNow()
        assertEquals(1, calls.calls.size, "no file to refresh")
    }

    @Test
    fun theBackgroundRefreshClearsItsMarkerOnlyOnSuccess() {
        val files = FakeFiles(mutableMapOf("/s/gates.json.refreshing" to "refreshing"))
        store(files, FakeCalls()).refreshNow()
        assertTrue("/s/gates.json.refreshing" in files.texts)
        val calls = FakeCalls().on("/api/bridge/gates/catalog", served)
        store(files, calls).refreshNow()
        assertFalse("/s/gates.json.refreshing" in files.texts)
        assertEquals(5_000, calls.calls.single().timeoutMillis)
    }

    @Test
    fun queryValuesArePercentEncoded() {
        assertEquals("a-z_0.9~%2F%20%C3%A9%3F", Urls.encode("a-z_0.9~/ é?"))
    }
}
