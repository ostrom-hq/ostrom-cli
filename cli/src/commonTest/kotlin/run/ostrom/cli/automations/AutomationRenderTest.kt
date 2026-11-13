// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How automations, their runs and their webhook are written for a person. */
class AutomationRenderTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    /** 1970-01-10T21:00:00Z, three hours after the fixtures below. */
    private val now = 853_200_000L

    private val cron = json(
        """{"id":"a1","name":"Nightly","owner":"u","enabled":true,"version":4,"updatedAt":"1970-01-10T18:00:00Z",
        "nextRunAt":"2026-11-14T03:00:00Z",
        "trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris"},
        "action":{"prompt":"Triage\nnow","agent":"claude","model":"opus","effort":"high","maxRuntimeMinutes":45,
        "repository":"acme/web"},"overlap":"allow","maxRunsPerDay":10}""",
    )

    private val webhook = json(
        """{"id":"h1","name":"Hook","enabled":false,"trigger":{"kind":"webhook"},
        "action":{"workflowId":"w1","inputs":{"k":"a  b"}},
        "variables":[{"name":"t","path":"${'$'}.t","type":"enum","values":["x","y"],"required":false,"sensitive":true},
        {"name":"u","path":"${'$'}.u"}],
        "webhookUrl":"/hooks/abc/trigger","version":4}""",
    )

    @Test
    fun aListRowIsOneLineWithWhenAndWhatItStarts() {
        val row = "a1  Nightly  cron \"0 3 * * *\" Europe/Paris  enabled  claude opus  v4  3h"
        assertEquals(row, cron.automationLine(now))
        assertEquals("h1  Hook  webhook  paused  workflow w1  v4  ?", webhook.automationLine(now))
        assertEquals("x  webhook  paused  claude  ?", json("""{"name":"x"}""").automationLine(now))
    }

    @Test
    fun theDetailsTellTheDefinitionInWords() {
        assertEquals(
            listOf(
                "Nightly  [a1]  v4  enabled",
                "Owner: u",
                "Runs: cron \"0 3 * * *\" Europe/Paris; next 2026-11-14T03:00:00Z",
                "Starts: claude opus",
                "Repository: acme/web",
                "Prompt: Triage now",
                "Effort: high",
                "Runs for at most 45 minutes",
                "Overlap: allow; at most 10 runs a day",
                "Updated 1970-01-10T18:00:00Z",
            ).joinToString("\n"),
            cron.automationDetails(emptyList()),
        )
        assertEquals(
            listOf(
                "Hook  [h1]  v4  paused",
                "Runs: webhook",
                "Starts: workflow w1",
                "Inputs:",
                "  k = a b",
                "Variables:",
                "  {{t}}  \$.t  enum(x,y), optional, sensitive",
                "  {{u}}  \$.u  string",
                "Overlap: skip; at most 24 runs a day",
                "",
                "A",
                "B",
            ).joinToString("\n"),
            webhook.automationDetails(listOf("A", "B")),
        )
        val bare = json("""{"name":"Hook","id":"h1"}""").automationDetails(emptyList())
        assertEquals("Hook  [h1]  paused", bare.lines().first())
    }

    @Test
    fun anActionWithoutAnAgentOrAPromptIsStillWrittenInWords() {
        val bare = json("""{"id":"b","name":"Bare","action":{"prompt":"p"}}""")
        assertEquals("b  Bare  webhook  paused  claude  ?", bare.automationLine(now))
        val blank = json("""{"id":"b","name":"Bare","action":{"prompt":"  ","inputs":{}}}""")
        val lines = blank.automationDetails(emptyList()).lines()
        assertEquals(listOf("Bare  [b]  paused", "Runs: webhook", "Starts: claude"), lines.take(3))
        assertTrue("Prompt" !in blank.automationDetails(emptyList()))
    }

    @Test
    fun theWebhookIsCalledSignedAtTheLatestOrAPinnedVersion() {
        val latest = webhook.webhookAddress("latest", null, "https://o.example/")
        assertEquals("Webhook (signed): POST https://o.example/hooks/abc/trigger", latest.first())
        assertEquals(5, latest.size)
        assertTrue(latest.any { "HMAC-SHA256" in it })
        assertTrue(latest.any { "Idempotency-Key" in it && "Ostrom-Delivery" in it })
        val current = webhook.webhookAddress("current", "s3cret", "https://o.example")
        assertEquals("Webhook (signed): POST https://o.example/hooks/abc/v/4/trigger", current.first())
        assertEquals("This address runs only while the automation is at version 4.", current[5])
        assertEquals("Secret (shown once, keep it): s3cret", current.last())
        assertEquals(7, current.size)
        val pinned = webhook.webhookAddress("v7", null, "https://o.example")
        assertEquals("Webhook (signed): POST https://o.example/hooks/abc/v/7/trigger", pinned.first())
        assertTrue("version 7" in pinned.last())
    }

    @Test
    fun anAbsoluteAddressIsKeptAndAnAutomationWithoutOneHasNoSection() {
        val absolute = json("""{"webhookUrl":"https://h.example/hooks/z/trigger"}""")
        val first = "Webhook (signed): POST https://h.example/hooks/z/trigger"
        assertEquals(first, absolute.webhookAddress("latest", null, "x").first())
        assertEquals(emptyList(), cron.webhookAddress("latest", null, "https://o.example"))
        val unversioned = absolute.webhookAddress("current", null, "x")
        assertEquals(first, unversioned.first())
        assertEquals(5, unversioned.size)
    }

    @Test
    fun aUrlVersionIsLatestCurrentOrVAndANumber() {
        for (ok in listOf("latest", "current", "v1", "v123456789")) assertEquals(ok, urlVersionOf(ok))
        for (bad in listOf("v0", "v", "7", "v1234567890", "newest", "")) assertNull(urlVersionOf(bad), bad)
    }

    @Test
    fun aRunRowSaysWhyItWasSkippedAndWhichSessionItStarted() {
        val full = json(
            """{"id":"r1","status":"skipped","reason":"overlap","source":"webhook","version":3,
            "createdAt":"1970-01-10T18:00:00Z","sessionId":"s1"}""",
        )
        assertEquals("r1  skipped (overlap)  webhook  v3  3h  session s1", full.automationRunLine(now))
        assertEquals("r2  done  ?", json("""{"id":"r2","status":"done"}""").automationRunLine(now))
    }

    @Test
    fun aDryRunListsWhatWouldHappenWithoutRepeatingMissingValues() {
        val dry = json(
            """{"wouldSkip":"disabled","problems":[{"path":"${'$'}.a","rule":"required"}],
            "variables":[{"name":"t","value":"x"},{"name":"u"}],"nextRuns":["a","b"],"renderedPrompt":"Hi x"}""",
        )
        assertEquals(
            listOf(
                "Would be skipped: the automation is paused (enable it first)",
                "Problem: \$.a (required)",
                "{{t}} = x",
                "{{u}} = (missing)",
                "Next runs: a, b",
                "Prompt:",
                "Hi x",
            ),
            dry.dryRunLines(),
        )
        assertTrue(dry.hasProblems())
        assertEquals(emptyList(), json("{}").dryRunLines())
        assertFalse(json("""{"problems":[]}""").hasProblems())
        val skips = mapOf(
            "overlap" to "a run of it still works (overlap skip)",
            "budget" to "it used its runs of the day",
            "other" to "other",
        )
        for ((reason, words) in skips) {
            assertEquals(listOf("Would be skipped: $words"), json("""{"wouldSkip":"$reason"}""").dryRunLines())
        }
    }
}
