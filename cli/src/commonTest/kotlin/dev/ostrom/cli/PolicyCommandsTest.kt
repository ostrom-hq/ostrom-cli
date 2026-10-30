// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom policy show`, `set` and `check`. */
class PolicyCommandsTest : SetupKit() {
    private val models = "GET /api/v1/policy/models"
    private val putModels = "PUT /api/v1/policy/models"
    private val gates = "GET /api/v1/gates/policy"
    private val putGates = "PUT /api/v1/gates/policy"
    private val policy = """{"allowed":["sonnet","opus"],"default":"sonnet","byMember":{"tom":["sonnet"]}}"""

    @Test
    fun showReadsTheModelsAndTheSelfApproval() {
        val routes = mapOf(models to (200 to policy), gates to (200 to """{"selfApproval":"never"}"""))
        assertEquals(0, run(routes, "policy", "show"))
        assertOut("Policy of Acme\n  Models         sonnet, opus · default sonnet\n  Self-approval  never\n")
        assertOut("Change it: ostrom policy set models|default-model|self-approval <value>\n")
        assertEquals("o1", requests.last().second.org)
        console.out.clear()
        assertEquals(0, run(routes, "policy", "show", "--json"))
        assertEquals("""{"models":$policy,"gates":{"selfApproval":"never"}}""" + "\n", console.out.toString())
    }

    @Test
    fun showSaysWhatTheServerDoesNotHaveYet() {
        // no gate policy on this server (SRV-REGLES), an empty model policy
        val bare = mapOf(models to (200 to """{"allowed":[]}"""))
        assertEquals(0, run(bare, "policy", "show"))
        assertOut("  Models         none allowed · default none\n  Self-approval  not on this server yet\n")
        console.out.clear()
        assertEquals(0, run(bare + (gates to (200 to "{}")), "policy", "show"))
        assertOut("  Self-approval  not on this server yet\n")
        console.out.clear()
        assertEquals(0, run(bare, "policy", "show", "--json"))
        assertEquals("""{"models":{"allowed":[]}}""" + "\n", console.out.toString())
        assertEquals(69, run(bare + (gates to (500 to "{}")), "policy", "show"), "a server in trouble is not a 404")
        assertEquals(66, run(emptyMap(), "policy", "show"))
        assertErr("No model policy in acme.\n")
    }

    @Test
    fun setModelsKeepsTheDefaultAndTheMembersLists() {
        val routes = mapOf(models to (200 to policy), putModels to (200 to "{}"))
        assertEquals(0, run(routes, "policy", "set", "models", "opus, sonnet,haiku,"))
        assertEquals(
            """{"allowed":["opus","sonnet","haiku"],"default":"sonnet","byMember":{"tom":["sonnet"]}}""",
            bodyOf(putModels),
        )
        assertOut("✓ models = opus, sonnet,haiku, in Acme\n")
        // the default no longer allowed: the first model allowed takes its place
        assertEquals(0, run(routes, "policy", "set", "models", "haiku"))
        assertEquals("""{"allowed":["haiku"],"default":"haiku","byMember":{"tom":["sonnet"]}}""", bodyOf(putModels))
        val noDefault = mapOf(models to (200 to """{"allowed":["sonnet"]}"""), putModels to (200 to "{}"))
        assertEquals(0, run(noDefault, "policy", "set", "models", "opus"))
        assertEquals("""{"allowed":["opus"],"default":"opus"}""", bodyOf(putModels))
        assertEquals(64, run(routes, "policy", "set", "models", " , "))
        assertErr("Name at least one model.\n")
    }

    @Test
    fun setDefaultModelTakesAnAllowedOne() {
        val routes = mapOf(models to (200 to policy), putModels to (200 to "{}"))
        assertEquals(0, run(routes, "policy", "set", "default-model", "opus"))
        val opus = """{"allowed":["sonnet","opus"],"default":"opus","byMember":{"tom":["sonnet"]}}"""
        assertEquals(opus, bodyOf(putModels))
        assertEquals(64, run(routes, "policy", "set", "default-model", "gpt"))
        assertErr("gpt is not an allowed model (allowed: sonnet, opus).\n")
        val none = mapOf(models to (200 to "{}"), putModels to (200 to "{}"))
        assertEquals(64, run(none, "policy", "set", "default-model", "opus"))
        assertErr("Name at least one model.\n")
    }

    @Test
    fun setSelfApprovalTakesItsTwoValues() {
        val routes = mapOf(putGates to (200 to """{"selfApproval":"sole-approver"}"""))
        assertEquals(0, run(routes, "policy", "set", "self-approval", "sole-approver"))
        assertEquals("""{"selfApproval":"sole-approver"}""", bodyOf(putGates))
        assertOut("✓ self-approval = sole-approver in Acme\n")
        assertEquals(64, run(routes, "policy", "set", "self-approval", "always"))
        assertErr("self-approval must be sole-approver or never.\n")
        assertEquals(64, run(routes, "policy", "set", "fail-mode", "open"))
        assertErr("Unknown key \"fail-mode\". Keys: models, default-model, self-approval.\n")
        assertEquals(64, run(routes, "policy", "set", "models", "a", environment = inSession))
        assertEquals(1, sent().size, "only the first set reached the server")
    }

    @Test
    fun checkAsksWhatTheLauncherWouldBeTold() {
        val asked = "GET /api/v1/policy/agents?agent=claude-code&dev=maya"
        val routes = mapOf(asked to (200 to """{"allowed":true,"note":"logged"}"""))
        assertEquals(0, run(routes, "policy", "check", "claude"))
        assertOut("✓ @maya may start Claude Code in Acme.\n  logged\n")
        console.out.clear()
        val model = "GET /api/v1/policy/agents?agent=codex&dev=tom&model=gpt-5"
        assertEquals(0, run(mapOf(model to (200 to """{"allowed":true}""")), "policy", "check", "codex",
            "--dev", "@tom", "--model", "gpt-5"))
        assertEquals("✓ @tom may start Codex with gpt-5 in Acme.\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes, "policy", "check", "claude", "--json"))
        assertEquals("""{"allowed":true,"note":"logged"}""" + "\n", console.out.toString())
    }

    @Test
    fun aRefusalOrAnUnreadablePolicyIsRefused() {
        val asked = "GET /api/v1/policy/agents?agent=claude-code&dev=maya"
        val no = mapOf(asked to (200 to """{"allowed":false,"reason":"claude is not allowed for contractors"}"""))
        assertEquals(77, run(no, "policy", "check", "claude"))
        assertErr("✗ @maya may not start Claude Code: claude is not allowed for contractors\n")
        assertEquals(77, run(mapOf(asked to (200 to "{}")), "policy", "check", "claude", "--json"))
        assertEquals("{}\n", console.out.toString(), "--json prints what the server said")
        assertErr("✗ @maya may not start Claude Code: not allowed\n")
        assertEquals(64, run(emptyMap(), "policy", "check", "cursor"))
        assertErr("Unknown agent \"cursor\". Agents: claude, codex, opencode, gemini, grok, hermes, pi.\n")
        assertTrue(sent().none { it.contains("cursor") })
    }
}
