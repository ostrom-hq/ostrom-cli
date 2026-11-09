// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.runtime.PolicyVectors.NOW
import run.ostrom.cli.hooks.runtime.PolicyVectors.envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CatalogueStoreTest {
    private val signed = CallResult.Ok(envelope())
    private val policy = SignedGatePolicy(SignedGatePolicy.keysOf(PolicyVectors.KEYS))
    private val intact = PackageSeal(null, null, FakeFiles())

    private fun store(
        files: FakeFiles,
        calls: FakeCalls,
        context: HookContext = contextOf(),
        seal: PackageSeal = intact,
        now: Long = NOW,
    ) = CatalogueStoreImpl(context, files, calls, policy, seal, { now })

    private fun classes(read: CatalogueRead) = assertIs<CatalogueRead.Verified>(read).catalogue.actions.map {
        it.actionClass
    }

    private fun reason(read: CatalogueRead) = assertIs<CatalogueRead.Held>(read).reason

    @Test
    fun theSignedPolicyIsAskedThroughTheRelayAndCheckedAtEveryCall() {
        val calls = FakeCalls().on("/api/v1/gates/catalog/signed", signed)
        val store = store(FakeFiles(), calls)
        assertEquals(listOf("push-main"), classes(store.current()))
        store.current()
        assertEquals(List(2) { "/api/v1/gates/catalog/signed?repo=acme%2Fapp" }, calls.paths())
        assertEquals(null, calls.calls.first().body, "a GET")
    }

    @Test
    fun aCopyOnDiskNeverRemovesWhatOstromGates() {
        // S2-20: an emptied copy dated in the future used to be served forever
        val emptied = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[]}"""))
        val calls = FakeCalls().on("/api/v1/gates/catalog/signed", signed)
        assertEquals(listOf("push-main"), classes(store(emptied, calls).current()))
    }

    @Test
    fun thePackagesCatalogueAddsActionsAndClosesTheFailModeNeverOpensIt() {
        val added = FakeFiles(mutableMapOf("/s/gates.json" to """{"failMode":"open","actions":[{"class":"pkg"}]}"""))
        val joined = store(added, FakeCalls().on("/api/v1/gates/catalog/signed", signed)).current()
        assertEquals(listOf("push-main", "pkg"), classes(joined))
        assertEquals("closed", assertIs<CatalogueRead.Verified>(joined).catalogue.failMode, "never opened by a copy")
        val unparsable = FakeFiles(mutableMapOf("/s/gates.json" to "not json"))
        val alone = store(unparsable, FakeCalls().on("/api/v1/gates/catalog/signed", signed)).current()
        assertEquals(listOf("push-main"), classes(alone), "a broken copy adds nothing")
    }

    @Test
    fun withoutAPolicyToTrustEveryActionIsHeld() {
        // S2-38: with the relay stopped and the copy on disk rewritten, only four kinds of commands stayed gated
        val copy = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[{"class":"pkg"}]}"""))
        val stopped = store(copy, FakeCalls())
        assertEquals("Ostrom's gate policy could not be read (HTTP 404)", reason(stopped.current()))
        val unsigned = FakeCalls().on("/api/v1/gates/catalog/signed", ok("""{"actions":[]}"""))
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it is not an ostrom.gates/1 envelope",
            reason(store(FakeFiles(), unsigned).current()),
        )
        val served = FakeCalls().on("/api/v1/gates/catalog/signed", signed)
        val late = store(FakeFiles(), served, now = PolicyVectors.EXPIRY)
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it expired at 2026-10-30T05:10:00Z",
            reason(late.current()),
        )
    }

    @Test
    fun aPackageChangedSinceTheLaunchHoldsEveryActionBeforeOstromIsAsked() {
        val calls = FakeCalls().on("/api/v1/gates/catalog/signed", signed)
        val broken = PackageSeal("abc", "/s/package.sha256", FakeFiles())
        assertEquals(
            "The session's package is not the one Ostrom signed and laid down: its seal is incomplete or its " +
                "manifest cannot be read",
            reason(store(FakeFiles(), calls, seal = broken).current()),
        )
        assertEquals(emptyList(), calls.paths())
    }

    @Test
    fun outsideASessionThereIsNoCopyToRead() {
        val noCopy = contextOf("OSTROM_GATES" to "")
        val files = FakeFiles(mutableMapOf("/s/gates.json" to """{"actions":[{"class":"pkg"}]}"""))
        val calls = FakeCalls().on("/api/v1/gates/catalog/signed", signed)
        assertEquals(listOf("push-main"), classes(store(files, calls, noCopy).current()))
    }
}
