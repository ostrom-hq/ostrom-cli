// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.join
import kotlin.test.Test
import kotlin.test.assertEquals

class HookApprovalsTest {
    private val files = mutableMapOf<String, String>()
    private val approvals = HookApprovals("/config", files::get) { path, text -> files[path] = text }
    private val approvalsFile = join("/config", "hooks", "approvals.json")
    private val shownFile = join("/config", "hooks", "shown.json")

    private fun hook(digest: String) = EffectiveHook("guard", "1", digest, Hooks.definition)

    @Test
    fun aDecisionBindsOneDigest() {
        assertEquals(HookState.AWAITING, approvals.stateOf(hook("d1")))
        approvals.decide("guard", HookDecision("d1", approved = true))
        assertEquals(HookState.APPROVED, approvals.stateOf(hook("d1")))
        assertEquals(HookState.NEW_VERSION, approvals.stateOf(hook("d2")))
        approvals.decide("guard", HookDecision("d2", approved = false))
        approvals.decide("audit", HookDecision("d9", approved = true))
        assertEquals(HookState.REFUSED, approvals.stateOf(hook("d2")))
        assertEquals(
            """{"guard":{"digest":"d2","approved":false},"audit":{"digest":"d9","approved":true}}""",
            files[approvalsFile],
        )
    }

    @Test
    fun aFileThatIsNotDecisionsApprovesNothing() {
        files[approvalsFile] = "not json"
        assertEquals(emptyMap(), approvals.decisions())
        files[approvalsFile] = """{"a":{"digest":"d"},"b":{"approved":true},"c":"x",""" +
            """"d":{"digest":"d","approved":true}}"""
        assertEquals(mapOf("d" to HookDecision("d", true)), approvals.decisions())
    }

    @Test
    fun theVersionShownLastIsKeptByName() {
        assertEquals(emptyMap(), approvals.shown())
        approvals.show("guard", "d1")
        approvals.show("audit", "d2")
        approvals.show("guard", "d3")
        assertEquals(mapOf("guard" to "d3", "audit" to "d2"), approvals.shown())
        files[shownFile] = """{"guard":1,"audit":"d2","x":{}}"""
        assertEquals(mapOf("audit" to "d2"), approvals.shown())
    }
}
