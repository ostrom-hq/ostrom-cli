// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom budgets list`, `me` and `set` (PARCOURS 3.5): the organisation's, a member's and a workflow's limits. */
class BudgetCommandsTest : SetupKit() {
    private val list = "GET /api/v1/budgets"
    private val put = "PUT /api/v1/budgets"
    private val me = "GET /api/v1/budgets/me"
    private val page = """{"items":[""" +
        """{"scope":"organization","usdPerMonth":500,"hardStop":true},""" +
        """{"scope":"member","usdPerMonth":50,"usdPerDay":5,"hardStop":true},""" +
        """{"scope":"member","subject":"m_7","usdPerDay":20,"hardStop":false},""" +
        """{"scope":"workflow","subject":"wf_1","usdPerMonth":100.5,"hardStop":true}""" +
        """],"orgSpentUsd":12.345,"periodStart":"2026-11-01T00:00:00Z","resetsAt":"2026-12-01T00:00:00Z"}"""

    @Test
    fun listShowsEveryLimitWithItsSubjectPeriodAndAction() {
        assertEquals(0, run(mapOf(list to (200 to page)), "budgets", "list"))
        assertOut("Budgets of Acme · \$12.35 spent this month, resets 2026-12-01T00:00:00Z\n")
        assertOut("  organization    \$500.00/month · stops\n")
        assertOut("  every member    \$50.00/month, \$5.00/day · stops\n")
        assertOut("  member m_7      \$20.00/day · warns\n")
        assertOut("  workflow wf_1   \$100.50/month · stops\n")
        assertOut("Change one: ostrom budgets set --scope organization|member|workflow\n")
        assertEquals("o1", requests.last().second.org)
        console.out.clear()
        assertEquals(0, run(mapOf(list to (200 to page)), "budgets"), "the list by default")
        assertOut("  workflow wf_1   \$100.50/month · stops\n")
        console.out.clear()
        assertEquals(0, run(mapOf(list to (200 to page)), "budgets", "list", "--json"))
        assertEquals("$page\n", console.out.toString())
    }

    @Test
    fun anEmptyListSaysNothingIsLimited() {
        val none = """{"items":[],"orgSpentUsd":0,"periodStart":"x","resetsAt":"2026-12-01T00:00:00Z"}"""
        assertEquals(0, run(mapOf(list to (200 to none)), "budgets", "list"))
        assertOut("Budgets of Acme · \$0.00 spent this month, resets 2026-12-01T00:00:00Z\n")
        assertOut("  No budget: model calls are not limited.\n")
        // the old name of the organisation's scope, and a limit with no amount left
        val old = """{"items":[{"scope":"org","hardStop":false}],"orgSpentUsd":0.004,"resetsAt":"r"}"""
        console.out.clear()
        assertEquals(0, run(mapOf(list to (200 to old)), "budgets", "list"))
        assertOut("Budgets of Acme · \$0.00 spent this month, resets r\n  organization    no limit · warns\n")
    }

    @Test
    fun aListTheTokenMayNotReadSaysWhichTokenReadsIt() {
        val refused = """{"error":{"type":"forbidden","message":"This credential does not allow budgets:read."}}"""
        assertEquals(77, run(mapOf(list to (403 to refused)), "budgets", "list"))
        assertErr("This credential does not allow budgets:read.\n")
        assertEquals(66, run(emptyMap(), "budgets", "list"))
        assertErr("No budgets in acme.\n")
    }

    @Test
    fun meShowsTheCallersSpendLimitAndWhatIsLeft() {
        val mine = """{"limitMicros":20000000,"spentMicros":3204999,"remainingMicros":16795001,""" +
            """"resetsAt":"2026-12-01T00:00:00Z","exhausted":false}"""
        assertEquals(0, run(mapOf(me to (200 to mine)), "budgets", "me"))
        assertOut("Your budget in Acme this month\n")
        assertOut("  Spent    \$3.20 of \$20.00 · \$16.80 left\n  Resets   2026-12-01T00:00:00Z\n")
        assertTrue("Refused" !in console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf(me to (200 to mine)), "budgets", "me", "--json"))
        assertEquals("$mine\n", console.out.toString())
    }

    @Test
    fun meSaysWhenNoLimitAppliesAndWhichBudgetRefusesTheCalls() {
        val free = """{"spentMicros":0,"resetsAt":"r","exhausted":false}"""
        assertEquals(0, run(mapOf(me to (200 to free)), "budgets", "me"))
        assertOut("  Spent    \$0.00 · no limit\n")
        val scopes = mapOf(
            "org" to "the organization's budget",
            "member" to "your budget",
            "workflow" to "the workflow's budget",
        )
        for ((scope, said) in scopes) {
            console.out.clear()
            val spent = """{"limitMicros":10000,"spentMicros":10000,"remainingMicros":0,"resetsAt":"r",""" +
                """"exhausted":true,"exhaustedScope":"$scope"}"""
            assertEquals(0, run(mapOf(me to (200 to spent)), "budgets", "me"), scope)
            assertOut("  Spent    \$0.01 of \$0.01 · \$0.00 left\n")
            assertOut("  Refused  model calls are refused until it resets: $said is reached\n")
        }
        console.out.clear()
        val unnamed = """{"spentMicros":1,"resetsAt":"r","exhausted":true}"""
        assertEquals(0, run(mapOf(me to (200 to unnamed)), "budgets", "me"))
        assertOut("  Refused  model calls are refused until it resets: a budget is reached\n")
    }

    @Test
    fun setSendsTheWholeBudgetOfAWorkflow() {
        val routes = mapOf(put to (200 to "{}"))
        val set = arrayOf("budgets", "set", "--scope", "workflow", "--subject", "wf_1", "--monthly", "100.5")
        assertEquals(0, run(routes, *set))
        assertEquals("""{"scope":"workflow","subject":"wf_1","usdPerMonth":100.5,"hardStop":true}""", bodyOf(put))
        assertOut("✓ Budget of workflow wf_1 in Acme: \$100.50/month · stops\n")
        console.out.clear()
        val member = arrayOf("budgets", "set", "--scope", "member", "--subject", "m_7", "--daily", "20", "--warn")
        assertEquals(0, run(routes, *member))
        assertEquals("""{"scope":"member","subject":"m_7","usdPerDay":20.0,"hardStop":false}""", bodyOf(put))
        assertOut("✓ Budget of member m_7 in Acme: \$20.00/day · warns\n")
        console.out.clear()
        assertEquals(0, run(routes, "budgets", "set", "--scope", "member", "--monthly", "50", "--daily", "5"))
        assertEquals("""{"scope":"member","usdPerMonth":50.0,"usdPerDay":5.0,"hardStop":true}""", bodyOf(put))
        assertOut("✓ Budget of every member in Acme: \$50.00/month, \$5.00/day · stops\n")
        console.out.clear()
        assertEquals(0, run(routes, "budgets", "set", "--scope", "organization"))
        assertEquals("""{"scope":"organization","hardStop":true}""", bodyOf(put))
        assertOut("✓ Budget of organization in Acme removed: model calls are not limited by it.\n")
    }

    @Test
    fun setRefusesWhatTheContractDoesNotTakeBeforeAskingTheServer() {
        val routes = mapOf(put to (200 to "{}"))
        assertEquals(64, run(routes, "budgets", "set", "--scope", "team"))
        assertErr("Unknown scope \"team\". Scopes: organization, member, workflow.\n")
        assertEquals(64, run(routes, "budgets", "set", "--scope", "workflow", "--monthly", "5"))
        assertErr("A workflow budget needs --subject <workflow id>.\n")
        assertEquals(64, run(routes, "budgets", "set", "--scope", "organization", "--subject", "o1"))
        assertErr("An organization budget has no --subject.\n")
        for (amount in listOf("ten", "-1", "NaN", "Infinity")) {
            assertEquals(64, run(routes, "budgets", "set", "--scope", "organization", "--monthly", amount), amount)
        }
        assertErr("--monthly must be an amount in US dollars, such as 250 or 12.50.\n")
        assertEquals(64, run(routes, "budgets", "set", "--scope", "organization", "--daily", "-0.5"))
        assertErr("--daily must be an amount in US dollars, such as 250 or 12.50.\n")
        assertEquals(64, run(routes, "budgets", "set", "--scope", "organization", environment = inSession))
        assertTrue(sent().isEmpty(), sent().toString())
    }

    @Test
    fun aBudgetTheServerRefusesIsSaidWithItsReason() {
        val notServed = """{"error":{"type":"invalid_request","message":"A workflow budget is not served."}}"""
        val set = arrayOf("budgets", "set", "--scope", "workflow", "--subject", "wf_1", "--monthly", "1")
        assertEquals(1, run(mapOf(put to (400 to notServed)), *set))
        assertErr("Ostrom refused: A workflow budget is not served.\n")
        // without an admin token, the governance write says how to get one
        val lacks = """{"error":{"type":"forbidden","message":"A browser session is required."}}"""
        assertEquals(77, run(mapOf(put to (403 to lacks)), *set))
        assertErr("A browser session is required.\nChanging the policy or the harness of acme takes an admin token.")
    }

    @Test
    fun eachBudgetCommandHasItsHelp() {
        val helps = mapOf(
            listOf("budgets") to "Spending limits of your organisation",
            listOf("budgets", "list") to "The budgets of your organisation",
            listOf("budgets", "me") to "Your own budget this month",
            listOf("budgets", "set") to "Set the budget of the organisation, every member, a member or a workflow",
        )
        for ((command, help) in helps) {
            console.out.clear()
            assertEquals(0, run(emptyMap(), *command.toTypedArray(), "--help"), "$command")
            assertTrue(console.out.contains(help), console.out.toString())
        }
        assertTrue(sent().isEmpty(), "help asks the server nothing")
    }
}
