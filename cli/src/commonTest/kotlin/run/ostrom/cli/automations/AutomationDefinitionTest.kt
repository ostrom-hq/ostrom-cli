// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The body `create` and `edit` send, and every combination of options that is refused before sending. */
class AutomationDefinitionTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val cronCloud = json(
        """{"id":"a1","name":"Nightly","owner":"u","enabled":true,"version":4,
        "trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris","catchup":"one","catchupGraceMinutes":30},
        "action":{"prompt":"Triage","agent":"claude","model":"opus","effort":"high","maxRuntimeMinutes":45,
        "repository":"acme/web"},
        "overlap":"skip","maxRunsPerDay":10}""",
    )

    private val webhookWorkflow = json(
        """{"name":"Hook","version":2,"trigger":{"kind":"webhook"},
        "action":{"workflowId":"w1","inputs":{"k":"v"},"repository":"acme/web","agent":"codex","maxRuntimeMinutes":20},
        "variables":[{"name":"t","path":"${'$'}.t"}],"samplePayload":{"t":"1"},"overlap":"allow"}""",
    )

    private fun refused(saved: JsonObject?, given: AutomationOptions, workflow: String? = null): String =
        assertFailsWith<AutomationInputException> { definitionOf(saved, given, workflow) }.message.orEmpty()

    @Test
    fun aCronCloudAutomationIsCreatedFromItsOptions() {
        val body = definitionOf(
            null,
            AutomationOptions(
                name = "Nightly", cron = "0 3 * * *", timezone = "Europe/Paris", catchup = "one", graceMinutes = 30,
                prompt = "Triage", agent = "claude", model = "opus", effort = "high", maxRuntimeMinutes = 45,
                repository = "acme/web", overlap = "skip", maxRunsPerDay = 10,
            ),
            null,
        )
        assertEquals(
            json(
                """{"name":"Nightly","trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris",
                "catchup":"one","catchupGraceMinutes":30},
                "action":{"prompt":"Triage","agent":"claude","model":"opus","effort":"high","maxRuntimeMinutes":45,
                "repository":"acme/web"},"overlap":"skip","maxRunsPerDay":10}""",
            ),
            body,
        )
    }

    @Test
    fun aWebhookWorkflowAutomationCarriesItsVariablesAndSample() {
        val variable = json("""{"name":"t","path":"${'$'}.t"}""")
        val body = definitionOf(
            null,
            AutomationOptions(
                name = "Hook", webhook = true, workflow = "Review", inputs = listOf("k" to "v"),
                variables = listOf(variable), samplePayload = json("""{"t":"1"}"""),
            ),
            "w1",
        )
        assertEquals(
            json(
                """{"name":"Hook","trigger":{"kind":"webhook"},"action":{"workflowId":"w1","inputs":{"k":"v"}},
                "variables":[{"name":"t","path":"${'$'}.t"}],"samplePayload":{"t":"1"}}""",
            ),
            body,
        )
        val bare = definitionOf(null, AutomationOptions(name = "H", webhook = true, prompt = "p"), null)
        assertEquals(json("""{"name":"H","trigger":{"kind":"webhook"},"action":{"prompt":"p"},"variables":[]}"""), bare)
    }

    @Test
    fun anEditKeepsWhatItWasNotAskedToChangeAndSendsTheVersion() {
        val same = definitionOf(cronCloud, AutomationOptions(), null)
        assertEquals(
            json(
                """{"name":"Nightly","trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris",
                "catchup":"one","catchupGraceMinutes":30},
                "action":{"prompt":"Triage","agent":"claude","model":"opus","effort":"high","maxRuntimeMinutes":45,
                "repository":"acme/web"},"overlap":"skip","maxRunsPerDay":10,"version":4}""",
            ),
            same,
        )
        val renamed = definitionOf(
            cronCloud,
            AutomationOptions(
                name = "Day", cron = "0 4 * * *", timezone = "UTC", catchup = "none", graceMinutes = 5,
                prompt = "New", agent = "codex", model = "m", effort = "low", maxRuntimeMinutes = 5,
                repository = "acme/api", overlap = "allow", maxRunsPerDay = 2,
            ),
            null,
        )
        assertEquals("Day", renamed["name"].toString().trim('"'))
        assertTrue("\"cron\":\"0 4 * * *\"" in renamed.toString() && "\"version\":4" in renamed.toString())
        val cleared = definitionOf(cronCloud, AutomationOptions(clears = setOf(Clear.MODEL, Clear.EFFORT)), null)
        assertTrue("model" !in cleared["action"].toString() && "effort" !in cleared["action"].toString())
        val keptHook = definitionOf(webhookWorkflow, AutomationOptions(), null)
        assertEquals(
            json(
                """{"name":"Hook","trigger":{"kind":"webhook"},
                "action":{"workflowId":"w1","inputs":{"k":"v"},"repository":"acme/web","agent":"codex",
                "maxRuntimeMinutes":20},"variables":[{"name":"t","path":"${'$'}.t"}],"samplePayload":{"t":"1"},
                "overlap":"allow","version":2}""",
            ),
            keptHook,
        )
    }

    @Test
    fun clearingVariablesAndTheSampleEmptiesThem() {
        val body = definitionOf(
            webhookWorkflow,
            AutomationOptions(clears = setOf(Clear.VARIABLES, Clear.SAMPLE)),
            null,
        )
        assertEquals("[]", body["variables"].toString())
        assertTrue("samplePayload" !in body)
    }

    @Test
    fun changingTheKindOfTriggerLeavesTheOldOnesBehind() {
        val toHook = definitionOf(cronCloud, AutomationOptions(webhook = true), null)
        assertEquals("""{"kind":"webhook"}""", toHook["trigger"].toString())
        assertEquals("[]", toHook["variables"].toString())
        val toCron = definitionOf(webhookWorkflow, AutomationOptions(cron = "* * * * *", timezone = "UTC"), null)
        assertEquals("""{"kind":"cron","cron":"* * * * *","timezone":"UTC"}""", toCron["trigger"].toString())
        assertTrue("variables" !in toCron && "samplePayload" !in toCron)
        val noZone = refused(webhookWorkflow, AutomationOptions(cron = "* * * * *"))
        assertTrue("needs --cron EXPR and --timezone" in noZone)
    }

    @Test
    fun theTriggerOptionsThatContradictEachOtherAreNamed() {
        val none = AutomationOptions(name = "n", prompt = "p")
        assertTrue("An automation needs a trigger" in refused(null, none))
        assertTrue("two triggers" in refused(null, AutomationOptions(cron = "* * * * *", webhook = true)))
        assertTrue("needs --cron EXPR and --timezone" in refused(null, AutomationOptions(cron = "* * * * *")))
        val noCron = json("""{"name":"n","trigger":{"kind":"cron","timezone":"UTC"}}""")
        assertTrue("needs --cron EXPR and --timezone" in refused(noCron, AutomationOptions(prompt = "p")))
        for (opts in listOf(
            AutomationOptions(webhook = true, timezone = "UTC"),
            AutomationOptions(webhook = true, catchup = "one"),
            AutomationOptions(webhook = true, graceMinutes = 3),
        )) {
            assertTrue("are for a --cron trigger" in refused(null, opts))
        }
        assertTrue("needs a name" in refused(null, AutomationOptions(webhook = true, prompt = "p")))
    }

    @Test
    fun variablesAndSamplesBelongToWebhooksAndNeverContradictTheirClear() {
        val hook = AutomationOptions(name = "n", webhook = true, prompt = "p")
        val variable = json("""{"name":"t","path":"${'$'}.t"}""")
        val sample = json("""{"a":1}""")
        val cron = AutomationOptions(name = "n", cron = "* * * * *", timezone = "UTC", prompt = "p")
        val noVars = hook.changed(variables = listOf(variable), clears = setOf(Clear.VARIABLES))
        assertTrue("opposite" in refused(null, noVars))
        assertTrue("opposite" in refused(null, hook.changed(samplePayload = sample, clears = setOf(Clear.SAMPLE))))
        assertTrue("a cron has no payload" in refused(null, cron.changed(variables = listOf(variable))))
        assertTrue("a cron has no payload" in refused(null, cron.changed(samplePayload = sample)))
    }

    @Test
    fun theActionsThatContradictEachOtherAreNamed() {
        val base = AutomationOptions(name = "n", webhook = true)
        assertTrue("no remote action" in refused(null, base.changed(action = "remote", prompt = "p")))
        assertTrue("two actions" in refused(null, base.changed(action = "cloud", workflow = "w")))
        assertTrue("needs --workflow" in refused(null, base.changed(action = "workflow")))
        assertTrue("cloud action needs its prompt" in refused(null, base))
        assertTrue("cloud action needs its prompt" in refused(null, base.changed(prompt = " ")))
        assertTrue("cloud action needs its prompt" in refused(webhookWorkflow, base.changed(action = "cloud")))
        val orphan = base.changed(prompt = "p", inputs = listOf())
        assertTrue("for a workflow action: add --workflow" in refused(null, orphan))
        val noModel = AutomationOptions(model = "m", clears = setOf(Clear.MODEL))
        assertTrue("--model and --clear-model" in refused(cronCloud, noModel))
        val noEffort = AutomationOptions(effort = "e", clears = setOf(Clear.EFFORT))
        assertTrue("--effort and --clear-effort" in refused(cronCloud, noEffort))
        for (opts in listOf(base.changed(prompt = "p"), base.changed(model = "m"), base.changed(effort = "e"))) {
            assertTrue("for a cloud action" in refused(null, opts.changed(workflow = "w"), "w1"))
        }
        val twice = base.changed(workflow = "w", inputs = listOf("k" to "1", "k" to "2"))
        assertTrue("given twice" in refused(null, twice, "w1"))
    }

    @Test
    fun aWorkflowActionKeepsItsSettingsOnlyWhileItIsTheSameWorkflow() {
        val edit = definitionOf(webhookWorkflow, AutomationOptions(inputs = listOf("x" to "y"), repository = "r"), null)
        assertEquals(
            json("""{"workflowId":"w1","inputs":{"x":"y"},"repository":"r","agent":"codex","maxRuntimeMinutes":20}"""),
            edit["action"],
        )
        val other = definitionOf(
            webhookWorkflow,
            AutomationOptions(workflow = "Other", agent = "claude", maxRuntimeMinutes = 9),
            "w2",
        )
        assertEquals(
            json("""{"workflowId":"w2","repository":"acme/web","agent":"claude","maxRuntimeMinutes":9}"""),
            other["action"],
        )
        val dropped = definitionOf(webhookWorkflow, AutomationOptions(workflow = "Other"), "w2")
        assertEquals(json("""{"workflowId":"w2","repository":"acme/web"}"""), dropped["action"])
        val toCloud = definitionOf(webhookWorkflow, AutomationOptions(action = "cloud", prompt = "p"), null)
        assertEquals(json("""{"prompt":"p","repository":"acme/web"}"""), toCloud["action"])
    }

    @Suppress("LongParameterList") // one parameter per option a test changes
    private fun AutomationOptions.changed(
        action: String? = this.action,
        workflow: String? = this.workflow,
        inputs: List<Pair<String, String>>? = this.inputs,
        prompt: String? = this.prompt,
        model: String? = this.model,
        effort: String? = this.effort,
        variables: List<JsonObject>? = this.variables,
        samplePayload: JsonObject? = this.samplePayload,
        clears: Set<String> = this.clears,
    ) = AutomationOptions(
        name = name, cron = cron, timezone = timezone, webhook = webhook, action = action, workflow = workflow,
        inputs = inputs, prompt = prompt, model = model, effort = effort, variables = variables,
        samplePayload = samplePayload, clears = clears,
    )
}
