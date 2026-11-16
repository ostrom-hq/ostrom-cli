// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.commands.CapacityReport
import run.ostrom.cli.commands.UNEXPECTED
import run.ostrom.cli.commands.bar
import run.ostrom.cli.commands.duration
import run.ostrom.cli.launcher.typedClient
import run.ostrom.core.client.OstromClient
import run.ostrom.cli.commands.text
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.launcher.ServerTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The capacity sections of `ostrom status` (TeamCode's `teamcode status`, TC-CAP-21/24). */
class StatusCapacityTest {
    private val now = Instant.parse("2026-11-15T10:00:00Z").toEpochMilliseconds()

    private class Fake(val server: ServerJson, val client: OstromClient)

    private fun server(vararg routes: Pair<String, Pair<Int?, String>>): Fake {
        val transport = ServerTransport { call ->
            val (status, body) = routes.toMap()[call.path] ?: (404 to "{}")
            ServerReply(status, body)
        }
        return Fake(ServerJson(transport), typedClient(transport, "ost_1"))
    }

    private suspend fun readCapacity(fake: Fake, token: String, now: Long) =
        run.ostrom.cli.commands.readCapacity(fake.server, fake.client, token, "maya k", now)

    private val policy = "/api/v1/members/maya%20k/policy"

    private val accounts = """{"items":[""" +
        """{"label":"Account 1","provider":"claude","status":"ready","paused":false,"windows":[""" +
        """{"window":"five_hour","utilization":0.42,"resetsAt":"2026-11-15T12:30:00Z"},""" +
        """{"window":"seven_day","utilization":0.95},{"window":"model:opus","utilization":0.1}]},""" +
        """{"label":"Account 2","provider":"codex","status":"ready","paused":true},""" +
        """{"label":"Account 3","provider":"claude","status":"ready","cooldownUntil":"2026-11-15T10:05:00Z"},""" +
        """{"label":"Account 4","provider":"claude","status":"ready","cooldownUntil":"2026-11-15T09:00:00Z"},""" +
        """{"provider":"claude","status":"reconnect_required"}]}"""

    @Test
    fun everySectionIsShownFromTheServersAnswers() = runTest {
        val report = readCapacity(
            server(
                "/api/v1/inference/queue" to (200 to """{"waiting":3,"waitingMine":1}"""),
                "/api/v1/budgets/me" to (200 to """{"limitMicros":5000000,"spentMicros":1200000,""" +
                    """"resetsAt":"2026-12-01T00:00:00Z","exhausted":false}"""),
                "/api/v1/directives/current" to (200 to """{"directive":{"id":"a","pace":"slow_down",""" +
                    """"maxConcurrent":2,"minIntervalMillis":5000,"message":"m","createdAt":"x",""" +
                    """"expiresAt":"2026-11-15T10:03:00Z"}}"""),
                "/api/v1/accounts" to (200 to accounts),
                policy to (200 to """{"member":"maya k","poolId":"team-a","allowSharedOverflow":true}"""),
            ),
            "ost_1",
            now,
        )
        assertEquals(emptyMap(), report.unavailable)
        val text = report.text()
        assertEquals(
            "Queue       3 calls waiting, 1 of yours\n" +
                "Budget      $1.20 used of $5.00, $3.80 left  ███████░░░ · resets 2026-12-01 00:00 UTC, " +
                "in 15d 14h\n" +
                "Pacing      slow down: at most 2 at once, 5000 ms between calls, until 2026-11-15 10:03 UTC, in 3m\n" +
                "Accounts\n",
            text.lines().take(4).joinToString("\n") + "\n",
        )
        val header = text.lines()[4].split(Regex(" {2,}")).filter { it.isNotEmpty() }
        assertEquals(listOf("ACCOUNT", "PROVIDER", "STATE", "5H", "WEEKLY", "OTHER", "USABLE NOW"), header)
        val first = text.lines()[5].split(Regex(" {2,}")).filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "Account 1", "claude", "ready", "████░░░░░░ 42% (reset in 2h 30m)", "█████████░ 95%", "opus 10%", "yes",
            ),
            first,
        )
        val rows = report.text().lines()
        assertTrue(rows.any { it.startsWith("  Account 2") && it.endsWith("no, paused") }, rows.joinToString("\n"))
        assertTrue(rows.any { it.endsWith("no, cooling down until 2026-11-15 10:05 UTC, in 5m") })
        assertTrue(rows.any { it.startsWith("  Account 4") && it.endsWith("yes") }, "its rest is over")
        assertTrue(rows.any { it.startsWith("  -") && it.endsWith("no, reconnect required") })
        assertTrue(report.json().keys == setOf("queue", "budget", "pacing", "accounts", "policy", "unavailable"))
    }

    @Test
    fun theBudgetSaysWhatLimitsItAndWhenCallsAreRefused() = runTest {
        fun line(budget: String): String = CapacityReport(budget = parse(budget), nowMillis = now).text().lines()[1]
        assertEquals("Budget      $0.50 used, no limit · resets x", line("""{"spentMicros":500000,"resetsAt":"x"}"""))
        assertEquals(
            "Budget      $2.00 used of $0.00, $0.00 left  ░░░░░░░░░░ (temporary budget) · " +
                "calls refused now (the organisation's budget)",
            line("""{"limitMicros":0,"spentMicros":2000000,"override":{},"exhausted":true,"exhaustedScope":"org"}"""),
        )
        assertTrue(line("""{"spentMicros":1,"exhausted":true,"exhaustedScope":"member"}""").endsWith("(your budget)"))
    }

    @Test
    fun aSectionThatCannotBeShownSaysWhyAndNoAdviceIsNone() = runTest {
        val report = readCapacity(
            server(
                "/api/v1/inference/queue" to (200 to """{"waiting":"many"}"""),
                "/api/v1/budgets/me" to (null to "refused"),
                "/api/v1/directives/current" to (200 to """{"directive":null}"""),
                "/api/v1/accounts" to (200 to """{"items":[]}"""),
            ),
            "ost_1",
            now,
        )
        assertEquals(
            mapOf("queue" to "unexpected_response", "budget" to "read_failed", "policy" to "not_served"),
            report.unavailable,
        )
        assertEquals(
            "Queue       unavailable (unexpected response from the server)\n" +
                "Budget      unavailable (the read failed; try again)\n" +
                "Pacing      none\n" +
                "Accounts    none in your pools\n",
            report.text(),
        )
        val odd = readCapacity(
            server(
                "/api/v1/inference/queue" to (200 to """{"waiting":1}"""),
                "/api/v1/directives/current" to (200 to """{"directive":[1]}"""),
                "/api/v1/accounts" to (200 to """{"items":[1]}"""),
                "/api/v1/budgets/me" to (500 to "{}"),
            ),
            "ost_1",
            now,
        )
        assertEquals(
            mapOf(
                "pacing" to "unexpected_response", "accounts" to "unexpected_response", "budget" to "read_failed",
                "policy" to "not_served",
            ),
            odd.unavailable,
        )
        val text = odd.text()
        assertTrue("Queue       1 call waiting, 0 of yours\n" in text, text)
        assertTrue("Pacing      unavailable (unexpected response from the server)\n" in text, text)
        val bare = CapacityReport(pacing = parse("""{"pace":"normal"}"""), nowMillis = now).text()
        assertTrue("Pacing      normal: no concurrency cap, 0 ms between calls\n" in bare, bare)
    }

    @Test
    fun aPacingTheServerDoesNotServeOrFailsToGiveSaysWhy() = runTest {
        val directive = "/api/v1/directives/current"
        val notServed = readCapacity(server(directive to (403 to "{}")), "ost_1", now)
        assertEquals("not_served", notServed.unavailable["pacing"])
        val failed = readCapacity(server(directive to (500 to "{}")), "ost_1", now)
        assertEquals("read_failed", failed.unavailable["pacing"])
    }

    @Test
    fun sparseAnswersAreShownWithTheirDefaults() = runTest {
        val accounts = listOf(
            parse("""{"windows":[{"utilization":0.5},{"window":"five_hour"}]}"""),
            parse("""{"label":"a\u0007b","status":"ready","cooldownUntil":"later"}"""),
            parse("""{"label":"A much longer label","provider":"claude","status":"ready"}"""),
            parse("""{"label":"x","windows":[{"window":"seven_day","resetsAt":"soon"}]}"""),
        )
        val report = CapacityReport(parse("{}"), parse("{}"), parse("{}"), accounts, nowMillis = now)
        val text = report.text()
        assertTrue("Queue       0 calls waiting, 0 of yours\n" in text, text)
        assertTrue("Budget      $0.00 used, no limit\n" in text, text)
        assertTrue("Pacing      unknown: no concurrency cap, 0 ms between calls\n" in text, text)
        val rows = text.lines().drop(4).map { row -> row.split(Regex(" {2,}")).filter { it.isNotEmpty() } }
        assertEquals(listOf("-", "-", "-", "░░░░░░░░░░ -", "-", "50%", "no, "), rows[1])
        assertEquals(listOf("a b", "-", "ready", "-", "-", "-", "yes"), rows[2])
        val none = CapacityReport().text()
        assertEquals(3, none.lines().count { "unavailable from this server" in it }, none)
        assertTrue("Pacing      none\n" in none, none)
        val absent = readCapacity(
            server(
                "/api/v1/budgets/me" to (200 to """{"spentMicros":"1"}"""),
                "/api/v1/directives/current" to (200 to "{}"),
                "/api/v1/accounts" to (200 to "{}"),
            ),
            "ost_1",
            now,
        )
        assertEquals(
            mapOf(
                "queue" to "not_served", "budget" to "unexpected_response", "accounts" to "unexpected_response",
                "policy" to "not_served",
            ),
            absent.unavailable,
        )
    }

    @Test
    fun theCallersPoolAndOverflowAreTheServersAnswerInTheJsonOnly() = runTest {
        val answer = """{"member":"maya k","poolId":"team-a","allowSharedOverflow":true}"""
        val report = readCapacity(server(policy to (200 to answer)), "ost_1", now)
        assertEquals(parse(answer), report.json()["policy"])
        assertTrue(report.text().lines().none { "team-a" in it }, "TeamCode's status text shows no pool")
        val alone = """{"member":"maya k","allowSharedOverflow":false}"""
        val none = readCapacity(server(policy to (200 to alone)), "ost_1", now)
        assertEquals(null, none.unavailable["policy"], "a member in no pool")
        assertEquals(parse(alone), none.policy)
    }

    @Test
    fun aPoolPolicyTheServerDoesNotServeOrGivesInAnotherShapeSaysWhy() = runTest {
        assertEquals("not_served", readCapacity(server(), "ost_1", now).unavailable["policy"], "an older server")
        assertEquals("not_served", readCapacity(server(policy to (403 to "{}")), "ost_1", now).unavailable["policy"])
        assertEquals("read_failed", readCapacity(server(policy to (500 to "{}")), "ost_1", now).unavailable["policy"])
        val odd = readCapacity(server(policy to (200 to """{"poolId":"team-a"}""")), "ost_1", now)
        assertEquals(UNEXPECTED, odd.unavailable["policy"])
        assertEquals(kotlinx.serialization.json.JsonNull, odd.json()["policy"])
    }

    @Test
    fun barsAndDelaysAreReadableWithoutColour() {
        assertEquals("░░░░░░░░░░", bar(0.0))
        assertEquals("█░░░░░░░░░", bar(0.01))
        assertEquals("██████████", bar(1.5))
        assertEquals(listOf("0m", "1m", "2h 0m", "1d 1h"), listOf(0L, 1L, 7_200_000L, 90_000_000L).map(::duration))
    }

    private fun parse(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject
}
