// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GateCatalogue
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogueStoreTest {
    private val served = ok("""{"gates":[],"actions":[{"class":"new","tools":["Bash"],"patterns":["x"]}]}""")

    private fun store(files: FakeFiles, calls: FakeCalls, context: HookContext = contextOf()) =
        CatalogueStore(context, files, calls)

    @Test
    fun ostromsCatalogueIsAskedThroughTheRelayAtEveryCall() {
        val calls = FakeCalls().on("/api/v1/gates/catalog", served)
        val store = store(FakeFiles(), calls)
        assertEquals("new", store.current().actions.single().actionClass)
        store.current()
        assertEquals(List(2) { "/api/v1/gates/catalog?repo=acme%2Fapp" }, calls.paths())
        assertEquals(null, calls.calls.first().body, "a GET")
    }

    @Test
    fun aCopyOnDiskNeverRemovesWhatOstromGates() {
        // S2-20: an emptied copy dated in the future used to be served forever
        val emptied = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[]}"""))
        val calls = FakeCalls().on("/api/v1/gates/catalog", served)
        assertEquals(listOf("new"), store(emptied, calls).current().actions.map { it.actionClass })
    }

    @Test
    fun thePackagesCatalogueAddsActionsAndClosesTheFailModeNeverOpensIt() {
        val open = ok("""{"failMode":"open","actions":[{"class":"new"}]}""")
        val added = FakeFiles(mutableMapOf("/s/gates.json" to """{"failMode":"closed","actions":[{"class":"pkg"}]}"""))
        val joined = store(added, FakeCalls().on("/api/v1/gates/catalog", open)).current()
        assertEquals(listOf("new", "pkg"), joined.actions.map { it.actionClass })
        assertEquals("closed", joined.failMode)
        val opening = FakeFiles(mutableMapOf("/s/gates.json" to """{"failMode":"open","actions":[]}"""))
        val served = store(opening, FakeCalls().on("/api/v1/gates/catalog", ok("""{"actions":[]}"""))).current()
        assertEquals(null, served.failMode, "the copy cannot open what Ostrom left closed")
    }

    @Test
    fun withoutOstromsCatalogueTheBuiltInOneFailsClosed() {
        val unreachable = store(FakeFiles(), FakeCalls()).current()
        assertEquals(GateCatalogue.CLOSED, unreachable)
        assertEquals("closed", unreachable.failMode)
        val broken = FakeCalls().on("/api/v1/gates/catalog", ok("""{"actions":"none"}"""))
        assertEquals(GateCatalogue.CLOSED, store(FakeFiles(), broken).current())
        val unparsable = FakeFiles(mutableMapOf("/s/gates.json" to "not json"))
        assertEquals(GateCatalogue.CLOSED, store(unparsable, FakeCalls()).current(), "a broken copy adds nothing")
        val withCopy = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[{"class":"pkg"}]}"""))
        val joined = store(withCopy, FakeCalls()).current()
        assertEquals(GateCatalogue.CLOSED.actions.size + 1, joined.actions.size)
        assertEquals("closed", joined.failMode)
    }

    @Test
    fun outsideASessionThereIsNoCopyToRead() {
        val noCopy = contextOf("OSTROM_GATES" to "")
        val files = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[{"class":"pkg"}]}"""))
        val calls = FakeCalls().on("/api/v1/gates/catalog", served)
        assertEquals(listOf("new"), store(files, calls, noCopy).current().actions.map { it.actionClass })
    }
}
