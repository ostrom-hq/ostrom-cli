// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `ostrom automations`: list, show, create, edit, enable, disable, delete, rotate-secret, runs and run. */
class AutomationsCommandsTest : SetupKit() {
    private val cronId = "11111111-1111-1111-1111-111111111111"
    private val hookId = "22222222-2222-2222-2222-222222222222"
    private val list = "GET /api/v1/automations"
    private val create = "POST /api/v1/automations"
    private val showCron = "GET /api/v1/automations/$cronId"
    private val showHook = "GET /api/v1/automations/$hookId"
    private val workflows = "GET /api/v1/workflows"

    private val cron = """{"id":"$cronId","name":"Nightly","enabled":true,"version":3,""" +
        """"updatedAt":"1970-01-10T21:00:00Z",""" +
        """"trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris"},""" +
        """"action":{"prompt":"Triage","agent":"claude"},"overlap":"skip"}"""
    private val hook = """{"id":"$hookId","name":"Hook","enabled":false,"version":2,""" +
        """"updatedAt":"1970-01-10T21:00:00Z","trigger":{"kind":"webhook"},""" +
        """"action":{"workflowId":"wf-1"},"variables":[],""" +
        """"webhookUrl":"/hooks/abc/trigger"}"""
    private val items = """{"items":[$cron,$hook]}"""

    private val served = mapOf(capabilities("automations.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more
    private fun calls() = sent().filter { it != "GET /api/v1/capabilities" }

    private fun file(text: String, path: String) {
        fileSystem.createDirectories(("/work/acme/$path").toPath().parent!!)
        fileSystem.write(("/work/acme/$path").toPath()) { writeUtf8(text) }
    }

    private fun automations(
        routes: Map<String, Pair<Int, String>>,
        vararg args: String,
        env: Map<String, String> = withServer,
    ) = run(routes, "automations", *args, environment = env)

    private val enableCron = "POST /api/v1/automations/$cronId/enable"
    private val disableCron = "POST /api/v1/automations/$cronId/disable"
    private val runHook = "POST /api/v1/automations/$hookId/run"

    private fun fresh() {
        console.out.clear()
        console.err.clear()
        requests.clear()
    }

    @Test
    fun theAutomationsAreListedWithWhenAndWhatTheyStart() {
        assertEquals(0, automations(routes(list to (200 to items)), "list"))
        assertEquals(
            "$cronId  Nightly  cron \"0 3 * * *\" Europe/Paris  enabled  claude  v3  3h\n" +
                "$hookId  Hook  webhook  paused  workflow wf-1  v2  3h\n",
            console.out.toString(),
        )
        assertEquals(listOf(list), calls())
        fresh()
        assertEquals(0, automations(routes(list to (200 to """{"items":[$hook]}""")), "list", "--json"))
        assertEquals("[$hook]\n", console.out.toString())
        fresh()
        assertEquals(0, automations(routes(list to (200 to """{"items":[],"next":"t"}""")), "list"))
        assertEquals("", console.out.toString())
        assertErr("No automation is saved yet. Create one: ostrom automations create --help\n")
        assertErr("More automations exist than this first page lists.\n")
    }

    @Test
    fun everyCommandSaysSoWhenTheServerDoesNotServeAutomations() {
        val none = mapOf(capabilities("waits.v1"))
        val lines = listOf(
            listOf("list"), listOf("show", "x"), listOf("create"), listOf("edit", "x"), listOf("enable", "x"),
            listOf("disable", "x"), listOf("delete", "x", "--yes"), listOf("rotate-secret", "x"), listOf("runs", "x"),
            listOf("run", "x"), listOf("cancel-run", "x", "r1"),
        )
        lines.forEach { line ->
            console.err.clear()
            assertEquals(69, automations(none, *line.toTypedArray()), line.toString())
            assertErr("This Ostrom server does not serve automations (automations.v1): ask your admin to update it.")
        }
    }

    @Test
    fun anAutomationIsShownByIdOrByUniqueNameWithItsWebhook() {
        val served = routes(showHook to (200 to hook), list to (200 to items))
        assertEquals(0, automations(served, "show", hookId))
        assertOut("Hook  [$hookId]  v2  paused")
        assertOut("Webhook (signed): POST https://ostrom.example/hooks/abc/trigger")
        assertEquals(listOf(showHook), calls())
        fresh()
        assertEquals(0, automations(served, "show", "Hook", "--url-version", "current"))
        assertOut("POST https://ostrom.example/hooks/abc/v/2/trigger")
        assertEquals(listOf(list, showHook), calls())
        fresh()
        assertEquals(0, automations(served, "show", hookId, "--json"))
        assertEquals("$hook\n", console.out.toString())
        fresh()
        assertEquals(64, automations(served, "show", hookId, "--url-version", "newest"))
        assertErr("ostrom: --url-version is latest, current or vN, such as v3.\n")
        assertEquals(64, automations(served, "show", hookId, "--url-mode", "secret"))
    }

    @Test
    fun anAutomationNamedByNothingOrByTwoIsRefusedAndAMissingOneIsNotFound() {
        val twice = """{"items":[$cron,{"id":"$hookId","name":"Nightly"}]}"""
        assertEquals(66, automations(routes(list to (200 to items)), "show", "Ghost"))
        assertErr("No automation named \"Ghost\" in acme.\n")
        assertEquals(64, automations(routes(list to (200 to twice)), "show", "Nightly"))
        assertErr("ostrom: several automations are named \"Nightly\": give the id.\n")
        console.err.clear()
        val odd = "GET /api/v1/automations/odd"
        val noId = routes(list to (200 to """{"items":[{"name":"odd"}]}"""), odd to (404 to "{}"))
        assertEquals(66, automations(noId, "show", "odd"))
        assertErr("No automation odd in acme.\n")
    }

    @Test
    fun aCronAutomationIsCreatedPausedFromItsOptions() {
        val created = """{"id":"$cronId","name":"Nightly","enabled":false,"version":1}"""
        val line = arrayOf(
            "create", "--name", "Nightly", "--cron", "0 3 * * *", "--timezone", "Europe/Paris", "--prompt", "Triage",
        )
        assertEquals(0, automations(routes(create to (201 to created)), *line))
        assertEquals(
            """{"name":"Nightly","trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris"},""" +
                """"action":{"prompt":"Triage"}}""",
            bodyOf(create),
        )
        assertEquals(
            "Created Nightly [$cronId] as v1, paused.\n" +
                "Test it: ostrom automations run $cronId --dry-run   Then: ostrom automations enable $cronId\n",
            console.out.toString(),
        )
        fresh()
        assertEquals(0, automations(routes(create to (201 to created)), *line, "--json"))
        assertEquals("$created\n", console.out.toString())
    }

    @Test
    fun aWebhookAutomationStartsAWorkflowAndPrintsItsSecretOnce() {
        file("""{"id":1}""", "sample.json")
        val found = """{"items":[{"id":"wf-1","name":"Review"}]}"""
        val made = hook.dropLast(1) + ""","secret":"whsec_1"}"""
        val served = routes(create to (201 to made), workflows to (200 to found))
        val args = listOf(
            "create", "--name", "Hook", "--webhook", "--workflow", "Review", "--input", "env=prod",
            "--var", "ticket=\$.id", "--sample-payload", "sample.json", "--repository", "acme/web",
        )
        assertEquals(0, automations(served, *args.toTypedArray()))
        assertEquals(
            """{"name":"Hook","trigger":{"kind":"webhook"},"action":{"workflowId":"wf-1","inputs":{"env":"prod"},""" +
                """"repository":"acme/web"},"variables":[{"name":"ticket","path":"${'$'}.id"}],""" +
                """"samplePayload":{"id":1}}""",
            bodyOf(create),
        )
        assertOut("Webhook (signed): POST https://ostrom.example/hooks/abc/trigger")
        assertOut("Secret (shown once, keep it): whsec_1")
        fresh()
        val byId = automations(served, "create", "--name", "Hook", "--webhook", "--workflow", "wf-1")
        assertEquals(0, byId)
        assertTrue("\"workflowId\":\"wf-1\"" in bodyOf(create).orEmpty())
    }

    @Test
    fun aWorkflowIsLookedUpByIdOrUniqueNameAndTheRestIsRefused() {
        val found = """{"items":[{"id":"w1","name":"Dup"},{"id":"w2","name":"Dup"},{"name":"Anon"}]}"""
        val served = routes(workflows to (200 to found), create to (201 to hook))
        for ((ref, message) in mapOf(
            "Dup" to "ostrom: Several workflows are named \"Dup\": give the id.\n",
            "Nope" to "ostrom: No workflow \"Nope\" in acme: see ostrom workflows list.\n",
            "Anon" to "ostrom: No workflow \"Anon\" in acme: see ostrom workflows list.\n",
        )) {
            console.err.clear()
            val line = arrayOf("create", "--name", "n", "--webhook", "--workflow", ref)
            assertEquals(64, automations(served, *line), ref)
            assertErr(message)
        }
        assertEquals(0, automations(served, "create", "--name", "n", "--webhook", "--workflow", "w2"))
    }

    @Test
    fun theOptionsThatCannotBeUsedAreRefusedBeforeAnythingIsSent() {
        file("x", "p.txt")
        file("[]", "v.json")
        val head = listOf("create", "--name", "n", "--webhook")
        val refused = listOf(
            Triple(listOf("--prompt", "a", "--prompt-file", "p.txt"), 64, "Give --prompt or --prompt-file, not both."),
            Triple(
                listOf("--prompt", "a", "--var", "a=\$.a", "--variables-file", "v.json"),
                64,
                "Give --var or --variables-file, not both.",
            ),
            Triple(listOf("--prompt", "a", "--repository", "x y"), 64, "\"x y\" is not a repository"),
            Triple(listOf("--prompt", "a", "--var", "bad"), 64, "--var needs the form NAME="),
            Triple(listOf("--prompt", "a", "--sample-payload", "nope.json"), 66, "No file /work/acme/nope.json."),
            Triple(listOf("--prompt", "a", "--sample-payload", "v.json"), 64, "The sample payload is not a JSON"),
        )
        for ((extra, code, message) in refused) {
            console.err.clear()
            assertEquals(code, automations(routes(), *(head + extra).toTypedArray()), extra.toString())
            assertErr(message)
        }
        assertEquals(emptyList(), calls().filter { it.startsWith("POST") })
    }

    @Test
    fun aPromptComesFromAFileOrStandardInput() {
        file("From file", "p.txt")
        file("""[{"name":"a","path":"${'$'}.a"}]""", "v.json")
        val served = routes(create to (201 to hook))
        val head = arrayOf("create", "--name", "Hook", "--webhook")
        assertEquals(0, automations(served, *head, "--prompt-file", "p.txt", "--variables-file", "v.json"))
        assertEquals(
            """{"name":"Hook","trigger":{"kind":"webhook"},"action":{"prompt":"From file"},""" +
                """"variables":[{"name":"a","path":"${'$'}.a"}]}""",
            bodyOf(create),
        )
        console.standardInput = StandardInput.Read("Piped".encodeToByteArray())
        assertEquals(0, automations(served, *head, "--prompt", "-"))
        assertTrue("\"prompt\":\"Piped\"" in bodyOf(create).orEmpty())
        val reasons = mapOf(
            StandardInputFailure.TERMINAL to "Nothing is piped in: pipe the prompt.",
            StandardInputFailure.TOO_LARGE to "The prompt is larger than 1 MiB.",
            StandardInputFailure.ERROR to "The prompt could not be read from standard input.",
        )
        reasons.forEach { (reason, message) ->
            console.err.clear()
            console.standardInput = StandardInput.Unavailable(reason)
            assertEquals(64, automations(served, *head, "--prompt", "-"))
            assertErr("ostrom: $message\n")
        }
        console.err.clear()
        assertEquals(66, automations(served, *head, "--prompt-file", "nope.txt"))
        assertErr("No file /work/acme/nope.txt.\n")
        assertEquals(64, automations(served, *head, "--prompt", "p", "--input", "novalue"))
        assertErr("\"novalue\" needs the form KEY=TEXT.")
    }

    @Test
    fun theWritingCommandsDoNotRunInsideAnAgentsSession() {
        val lines = listOf(
            listOf("create"), listOf("edit", "x"), listOf("enable", "x"), listOf("disable", "x"),
            listOf("delete", "x"), listOf("rotate-secret", "x"), listOf("cancel-run", "x", "r1"),
        )
        for (line in lines) {
            console.err.clear()
            assertEquals(64, automations(routes(), *line.toTypedArray(), env = inSession), line.toString())
            assertErr("ostrom automations ${line.first()} does not run inside an agent's session")
        }
    }

    @Test
    fun anEditSendsBackWhatItReadWithTheVersionAndOnlyTheChanges() {
        val saved = """{"id":"$cronId","name":"Day","enabled":true,"version":4}"""
        val put = "PUT /api/v1/automations/$cronId"
        val served = routes(showCron to (200 to cron), put to (200 to saved), list to (200 to items))
        assertEquals(0, automations(served, "edit", "Nightly", "--name", "Day"))
        assertEquals(
            """{"name":"Day","trigger":{"kind":"cron","cron":"0 3 * * *","timezone":"Europe/Paris"},""" +
                """"action":{"prompt":"Triage","agent":"claude"},"overlap":"skip","version":3}""",
            bodyOf(put),
        )
        assertEquals("Saved Day [$cronId] as v4, enabled.\n", console.out.toString())
        fresh()
        assertEquals(0, automations(served, "edit", cronId, "--json", "--prompt", "New"))
        assertEquals("$saved\n", console.out.toString())
        fresh()
        assertEquals(64, automations(served, "edit", cronId, "--cron", "* * * * *", "--webhook"))
        assertErr("ostrom: --cron and --webhook are two triggers: give one.\n")
        fresh()
        assertEquals(66, automations(routes(showCron to (404 to "{}")), "edit", cronId, "--name", "x"))
        assertErr("No automation $cronId in acme.\n")
    }

    @Test
    fun anEditCanClearWhatIsOptionalAndPinTheWebhookAddress() {
        val put = "PUT /api/v1/automations/$hookId"
        val saved = hook.replace("\"version\":2", "\"version\":3")
        val served = routes(showHook to (200 to hook), put to (200 to saved), workflows to (200 to """{"items":[]}"""))
        val clears = arrayOf("--clear-variables", "--clear-model", "--clear-effort", "--clear-sample-payload")
        assertEquals(0, automations(served, "edit", hookId, *clears, "--url-version", "current"))
        assertTrue("\"variables\":[]" in bodyOf(put).orEmpty(), bodyOf(put))
        assertOut("POST https://ostrom.example/hooks/abc/v/3/trigger")
        fresh()
        assertEquals(64, automations(served, "edit", hookId, "--workflow", "Ghost"))
        assertErr("No workflow \"Ghost\" in acme")
    }

    @Test
    fun anAutomationIsEnabledAndDisabled() {
        val on = """{"id":"$cronId","name":"Nightly","enabled":true,"nextRunAt":"2026-11-14T03:00:00Z"}"""
        val off = """{"id":"$cronId","name":"Nightly","enabled":false}"""
        assertEquals(0, automations(routes(enableCron to (200 to on)), "enable", cronId))
        assertEquals("Nightly [$cronId] is enabled. Next run: 2026-11-14T03:00:00Z.\n", console.out.toString())
        fresh()
        assertEquals(0, automations(routes(disableCron to (200 to off)), "disable", cronId))
        assertEquals("Nightly [$cronId] is paused.\n", console.out.toString())
        fresh()
        assertEquals(0, automations(routes(disableCron to (200 to off)), "disable", cronId, "--json"))
        assertEquals("$off\n", console.out.toString())
        fresh()
        assertEquals(66, automations(routes(enableCron to (404 to "{}")), "enable", cronId))
        assertErr("No automation $cronId in acme.\n")
    }

    @Test
    fun aDeletionNeedsYesOrAnAnswerAndAnythingButYesIsNo() {
        val delete = "DELETE /api/v1/automations/$cronId"
        val served = routes(delete to (204 to ""))
        assertEquals(0, automations(served, "delete", cronId, "--yes"))
        assertEquals("Deleted $cronId.\n", console.out.toString())
        assertEquals(listOf(delete), calls())
        fresh()
        assertEquals(0, automations(served, "delete", cronId, "--yes", "--json"))
        assertEquals("""{"id":"$cronId","deleted":true}""" + "\n", console.out.toString())
        fresh()
        assertEquals(64, automations(served, "delete", cronId))
        assertErr("no terminal to ask")
        assertEquals(emptyList(), calls())
        console.isInteractive = true
        typed.addLast("n")
        assertEquals(2, automations(served, "delete", cronId))
        assertEquals(emptyList(), calls())
        typed.addLast("y")
        assertEquals(0, automations(served, "delete", cronId))
        assertEquals(listOf(delete), calls().filter { it.startsWith("DELETE") })
    }

    @Test
    fun aNewSecretIsPrintedOnceWithTheTimeTheFormerOneLasts() {
        val rotate = "POST /api/v1/automations/$hookId/webhook/rotate-secret"
        val rotated = """{"secret":"whsec_2","previousSecretValidUntil":"2026-11-13T12:10:00Z"}"""
        val served = routes(showHook to (200 to hook), rotate to (200 to rotated), showCron to (200 to cron))
        assertEquals(0, automations(served, "rotate-secret", hookId))
        assertOut("Webhook (signed): POST https://ostrom.example/hooks/abc/trigger")
        assertOut("Secret (shown once, keep it): whsec_2")
        assertOut("The former secret is valid until 2026-11-13T12:10:00Z.")
        fresh()
        val plain = routes(showHook to (200 to hook), rotate to (200 to """{"secret":"s"}"""))
        assertEquals(0, automations(plain, "rotate-secret", hookId))
        assertTrue("valid until" !in console.out.toString())
        fresh()
        assertEquals(0, automations(served, "rotate-secret", hookId, "--json"))
        assertEquals("$rotated\n", console.out.toString())
        fresh()
        assertEquals(64, automations(served, "rotate-secret", cronId))
        assertErr("ostrom: Nightly is a cron automation: it has no secret.\n")
        assertEquals(emptyList(), calls().filter { it.startsWith("POST") })
    }

    private fun runJson(id: String, status: String, source: String) =
        """{"id":"$id","status":"$status","source":"$source","createdAt":"1970-01-10T21:00:00Z"}"""

    @Test
    fun theRunsAreReadInPagesAndFilteredByWhereTheyCameFromAndTheirStatus() {
        val first = "GET /api/v1/automations/$cronId/runs?limit=100"
        val second = "$first&before=t1"
        val newer = runJson("r1", "started", "cron")
        val older = listOf(runJson("r2", "failed", "webhook"), runJson("r3", "failed", "test")).joinToString(",")
        val served = routes(
            first to (200 to """{"items":[$newer],"next":"t1"}"""),
            second to (200 to """{"items":[$older]}"""),
        )
        assertEquals(0, automations(served, "runs", cronId))
        assertEquals("r1  started  cron  3h\nr2  failed  webhook  3h\nr3  failed  test  3h\n", console.out.toString())
        assertEquals(listOf(first, second), calls())
        fresh()
        val filters = arrayOf("--source", "webhook", "--source", "test", "--status", "failed")
        assertEquals(0, automations(served, "runs", cronId, *filters))
        assertEquals("r2  failed  webhook  3h\nr3  failed  test  3h\n", console.out.toString())
        fresh()
        assertEquals(0, automations(served, "runs", cronId, "--limit", "1", "--status", "failed"))
        assertEquals("r2  failed  webhook  3h\n", console.out.toString())
        fresh()
        assertEquals(0, automations(served, "runs", cronId, "--json", "--status", "started"))
        assertEquals("[${runJson("r1", "started", "cron")}]\n", console.out.toString())
        fresh()
        assertEquals(0, automations(served, "runs", cronId, "--status", "nothing"))
        assertErr("No run matches.\n")
    }

    @Test
    fun theRunsReadStopAtFiveHundredAndTheLimitIsChecked() {
        fun page(n: Int) = (1..100).joinToString(",", "[", "]") { runJson("p$n-$it", "started", "cron") }
        val base = "GET /api/v1/automations/$cronId/runs?limit=100"
        val served = routes(
            base to (200 to """{"items":${page(1)},"next":"t1"}"""),
            "$base&before=t1" to (200 to """{"items":${page(2)},"next":"t2"}"""),
            "$base&before=t2" to (200 to """{"items":${page(3)},"next":"t3"}"""),
            "$base&before=t3" to (200 to """{"items":${page(4)},"next":"t4"}"""),
            "$base&before=t4" to (200 to """{"items":${page(5)},"next":"t5"}"""),
        )
        assertEquals(0, automations(served, "runs", cronId, "--status", "failed"))
        assertEquals(5, calls().size)
        assertErr("No run matches.\n")
        fresh()
        assertEquals(64, automations(served, "runs", cronId, "--limit", "0"))
        assertErr("ostrom: --limit is 1 to 500.\n")
        assertEquals(64, automations(served, "runs", cronId, "--limit", "501"))
    }

    @Test
    fun aDryRunShowsWhatWouldHappenAndExitsOneOnAProblem() {
        file("""{"a":1}""", "payload.json")
        val clean = """{"variables":[{"name":"a","value":"1"}],"renderedPrompt":"Hi 1"}"""
        val withHook = routes(showHook to (200 to hook), runHook to (200 to clean))
        assertEquals(0, automations(withHook, "run", hookId, "--dry-run", "--payload", "payload.json"))
        assertEquals("""{"payload":{"a":1},"dryRun":true}""", bodyOf(runHook))
        assertEquals("{{a}} = 1\nPrompt:\nHi 1\n", console.out.toString())
        fresh()
        val bad = """{"problems":[{"path":"${'$'}.a","rule":"required"}]}"""
        val failing = routes(showHook to (200 to hook), runHook to (200 to bad))
        assertEquals(1, automations(failing, "run", hookId, "--dry-run"))
        assertEquals("""{"dryRun":true}""", bodyOf(runHook))
        assertOut("Problem: \$.a (required)")
        fresh()
        assertEquals(1, automations(failing, "run", hookId, "--dry-run", "--json"))
        assertEquals("$bad\n", console.out.toString())
        fresh()
        val gone = routes(showHook to (200 to hook), runHook to (404 to "{}"))
        assertEquals(66, automations(gone, "run", hookId, "--dry-run"))
    }

    @Test
    fun aRealRunSendsItsIdempotencyKeyAndSaysWhyNoSessionStarted() {
        val started = """{"id":"r1","status":"started","source":"test","version":2,""" +
            """"createdAt":"1970-01-10T21:00:00Z","sessionId":"s1"}"""
        val served = routes(showHook to (200 to hook), runHook to (202 to started))
        assertEquals(0, automations(served, "run", hookId, "--idempotency-key", "k-1"))
        assertEquals("r1  started  test  v2  3h  session s1\n", console.out.toString())
        assertEquals("{}", bodyOf(runHook))
        assertEquals("k-1", requests.last { it.second.method == "POST" }.second.idempotencyKey)
        fresh()
        assertEquals(0, automations(served, "run", hookId, "--json"))
        assertEquals("$started\n", console.out.toString())
        val key = requests.last { it.second.method == "POST" }.second.idempotencyKey
        assertNotNull(key)
        assertTrue(Regex("[A-Za-z0-9._:-]{1,128}").matches(key), key)
        for ((status, why) in mapOf(
            "skipped_overlap" to "a run of it still works",
            "skipped_budget" to "it used its runs of the day",
        )) {
            fresh()
            val skipped = """{"id":"r2","status":"$status"}"""
            assertEquals(0, automations(routes(showHook to (200 to hook), runHook to (202 to skipped)), "run", hookId))
            assertErr("No session was started: this run was recorded as $status ($why).\n")
        }
    }

    @Test
    fun aRunThatFailsOrHasABadPayloadExitsOneWithTheProblems() {
        val invalid = """{"id":"r3","status":"invalid_payload","problems":[{"path":"${'$'}.a","rule":"type"}]}"""
        assertEquals(1, automations(routes(showHook to (200 to hook), runHook to (202 to invalid)), "run", hookId))
        assertErr("Problem: \$.a (type)\n")
        fresh()
        val failed = """{"id":"r4","status":"failed"}"""
        assertEquals(1, automations(routes(showHook to (200 to hook), runHook to (202 to failed)), "run", hookId))
        assertEquals("r4  failed  ?\n", console.out.toString())
        fresh()
        assertEquals(66, automations(routes(showHook to (200 to hook), runHook to (404 to "{}")), "run", hookId))
        assertErr("No automation $hookId in acme.\n")
    }

    @Test
    fun aPayloadIsCheckedBeforeAnythingIsSent() {
        file("""{"a":1}""", "payload.json")
        val served = routes(showHook to (200 to hook), showCron to (200 to cron))
        assertEquals(64, automations(served, "run", cronId, "--payload", "payload.json"))
        assertErr("ostrom: Nightly is a cron automation: it takes no payload.\n")
        fresh()
        assertEquals(64, automations(served, "run", hookId, "--payload", "payload.json", "--payload-stdin"))
        assertErr("ostrom: Give --payload FILE or --payload-stdin, not both.\n")
        fresh()
        assertEquals(64, automations(served, "run", hookId, "--idempotency-key", "bad key!"))
        assertErr("ostrom: --idempotency-key is 1 to 128 of A-Z a-z 0-9 . _ : -\n")
        fresh()
        console.standardInput = StandardInput.Read("""["secret"]""".encodeToByteArray())
        assertEquals(64, automations(served, "run", hookId, "--payload-stdin"))
        assertErr("ostrom: The payload is not a JSON object.\n")
        assertEquals(emptyList(), calls().filter { it.startsWith("POST") })
        fresh()
        console.standardInput = StandardInput.Read("""{"b":2}""".encodeToByteArray())
        val dry = routes(showHook to (200 to hook), runHook to (200 to "{}"))
        assertEquals(0, automations(dry, "run", hookId, "--payload-stdin", "--dry-run"))
        assertEquals("""{"payload":{"b":2},"dryRun":true}""", bodyOf(runHook))
    }

    @Test
    fun aRunIsCancelledOrReportedAsNotFoundOrAlreadyEnded() {
        val cancel = "POST /api/v1/automations/$cronId/runs/r1/cancel"
        val ended = """{"id":"r1","status":"cancelled","source":"cron","createdAt":"1970-01-10T21:00:00Z"}"""
        assertEquals(0, automations(routes(cancel to (200 to ended)), "cancel-run", cronId, "r1"))
        assertEquals("Cancelled: r1  cancelled  cron  3h\n", console.out.toString())
        assertEquals(listOf(cancel), calls())
        fresh()
        assertEquals(0, automations(routes(cancel to (200 to ended)), "cancel-run", cronId, "r1", "--json"))
        assertEquals("$ended\n", console.out.toString())
        fresh()
        assertEquals(66, automations(routes(), "cancel-run", cronId, "r1"))
        assertErr("No run r1 of automation $cronId in acme.\n")
        val done = """{"error":{"type":"conflict","message":"The run already ended."}}"""
        assertEquals(64, automations(routes(cancel to (409 to done)), "cancel-run", cronId, "r1"))
        assertErr("The run already ended.")
    }
}
