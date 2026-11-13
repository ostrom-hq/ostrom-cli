// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import run.ostrom.cli.workflows.age
import run.ostrom.cli.workflows.failureWords
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom workflows`: list, show, create, update, delete, run (--wait), runs, run-show and cancel. */
class WorkflowsCommandsTest : SetupKit() {
    private val list = "GET /api/v1/workflows"
    private val show = "GET /api/v1/workflows/deploy"
    private val create = "POST /api/v1/workflows"
    private val save = "PUT /api/v1/workflows/deploy"
    private val delete = "DELETE /api/v1/workflows/deploy"
    private val start = "POST /api/v1/workflows/deploy/runs"
    private val runs = "GET /api/v1/runs?limit=50"
    private val runShow = "GET /api/v1/runs/r1"
    private val cancel = "POST /api/v1/runs/r1/cancel"

    private val definition = """{"id":"deploy","name":"Deploy","domain":"Backend","status":"accepted",""" +
        """"foundAt":"1970-01-10T21:00:00Z","start":"build","inputs":[{"id":"env","label":"Environment",""" +
        """"required":true,"maxLength":8},{"id":"note","label":"Note","required":true,"default":"none"},""" +
        """{"id":"tag","label":"Tag"}],"nodes":[""" +
        """{"id":"build","kind":"agent","label":"Build","run":{"instructions":"Build it for {{inputs.env}}"}},""" +
        """{"id":"ask","kind":"wait","label":"Ship it?","wait":{"title":"Ship {{nodes.build.output}}?",""" +
        """"actions":[{"id":"go","label":"Go"}]}},{"id":"call","kind":"workflow","workflowId":"notify",""" +
        """"label":"Tell everyone","arguments":{"text":"done {{inputs.note}}"},"detail":"Calls notify"}],""" +
        """"edges":[{"from":"build","to":"ask","on":"delivered"},{"from":"ask","to":"call","on":"go"},""" +
        """{"from":"call","to":null}]}"""
    private val stored = definition.dropLast(1) +
        ""","version":3,"updatedAt":"1970-01-10T21:00:00Z","updatedBy":"lena","repos":["acme/*"],""" +
        """"description":"Ships it"}"""
    private val proposed = """{"id":"b","name":"Bare","domain":"Ops","status":"proposed","foundAt":"x","start":"s",""" +
        """"nodes":[{"id":"s","kind":"step","label":"S"}],"edges":[{"from":"s","to":null}]}"""
    private val bare = """{"id":"b","name":"Bare","domain":"Ops","status":"proposed","foundAt":"x","nodes":[],""" +
        """"edges":[],"updatedAt":"1970-01-10T21:00:00Z"}"""
    private val unnamed = """{"id":"deploy"}"""
    private val odd = """{"id":"odd","kind":"notify","label":"Odd","run":{"message":"hi"},"wait":{},""" +
        """"detail":"${"long ".repeat(30)}"}"""
    private val shown = stored.replace("""}],"edges":""", """},$odd],"edges":""")

    private fun runJson(
        id: String = "r1",
        status: String = "running",
        extra: String = "",
        visits: String = "",
    ) = """{"id":"$id","workflowId":"deploy","workflowName":"Deploy","workflowVersion":3,"status":"$status",""" +
        """"startedBy":"m1","source":"manual","startedAt":"1970-01-10T21:00:00Z"$extra,"visits":[$visits]}"""

    private fun visit(seq: Int, status: String, extra: String = "", node: String = "build", kind: String = "agent") =
        """{"seq":$seq,"nodeId":"$node","kind":"$kind","status":"$status","startedAt":"1970-01-10T21:00:00Z"$extra}"""

    private val served = mapOf(capabilities("workflows.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more
    private fun error(type: String, message: String) = """{"error":{"type":"$type","message":"$message"}}"""
    private fun calls() = sent().filter { it != "GET /api/v1/capabilities" }

    private fun file(text: String, path: String = "/work/acme/w.json") {
        fileSystem.createDirectories(path.toPath().parent!!)
        fileSystem.write(path.toPath()) { writeUtf8(text) }
    }

    /** Runs `ostrom args` against a server that answers each route with its answers in turn, the last one again. */
    private fun sequence(sequences: Map<String, List<Pair<Int, String>>>, vararg args: String): Int {
        signIn("/home/maya/.config/ostrom")
        val queues = (mapOf(orgs(acme).let { it.first to listOf(it.second) }) + sequences)
            .mapValues { it.value.toMutableList() }
        val api: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    requests += url to request
                    val queue = queues["${request.method} ${request.path}"] ?: return ServerResponse(404, "{}", 1)
                    val (status, body) = if (queue.size > 1) queue.removeFirst() else queue.first()
                    if (status == 0) throw ServerUnreachableException(url, Exception("reset"))
                    return ServerResponse(status, body, 1)
                }
            }
        }
        return cli(environment = withServer, api = api).run(arrayOf(*args))
    }

    private fun following(
        vararg polls: Pair<Int, String>,
        started: String = runJson(),
        args: List<String> = emptyList(),
    ) =
        sequence(
            mapOf(
                capabilities("workflows.v1").let { it.first to listOf(it.second) },
                show to listOf(200 to definition),
                start to listOf(201 to started),
                runShow to polls.toList(),
            ),
            "workflows", "run", "deploy", "--input", "env=prod", "--wait", *args.toTypedArray(),
        )

    @Test
    fun theWorkflowsAreListedWithTheirVersionStatusSizeAndAge() {
        val items = routes(list to (200 to """{"items":[$stored,$bare,$proposed]}"""))
        assertEquals(0, run(items, "workflows", "list"))
        assertEquals(
            "deploy  Deploy  v3  accepted  3 nodes  3 inputs  3h\nb  Bare  proposed  0 nodes  0 inputs  3h\n" +
                "b  Bare  proposed  1 nodes  0 inputs  ?\n",
            console.out.toString(),
        )
        assertEquals(listOf(list), calls())
        console.out.clear()
        assertEquals(0, run(routes(list to (200 to """{"items":[$bare]}""")), "workflows", "list", "--json"))
        assertEquals("[$bare]\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(list to (200 to """{"items":[]}""")), "workflows", "list"))
        assertEquals("", console.out.toString())
        assertErr("No workflow is saved yet. Save one: ostrom workflows create --file FILE.json\n")
    }

    @Test
    fun everyCommandSaysSoWhenTheServerDoesNotServeWorkflows() {
        val none = mapOf(capabilities("waits.v1"))
        val lines = listOf(
            listOf("list"), listOf("show", "deploy"), listOf("delete", "deploy"), listOf("run", "deploy"),
            listOf("runs"), listOf("run-show", "r1"), listOf("cancel", "r1"),
        )
        lines.forEach { line ->
            console.err.clear()
            assertEquals(69, run(none, "workflows", *line.toTypedArray()), line.toString())
            assertErr("This Ostrom server does not serve workflows (workflows.v1): ask your admin to update it.")
        }
        file(definition)
        assertEquals(69, run(none, "workflows", "create", "--file", "w.json"))
        assertEquals(69, run(none, "workflows", "update", "deploy", "--file", "w.json"))
        assertEquals(emptyList(), calls())
    }

    @Test
    fun aWorkflowIsShownWithItsInputsNodesAndEdges() {
        assertEquals(0, run(routes(show to (200 to shown)), "workflows", "show", "deploy"))
        val text = console.out.toString()
        val head = "Deploy  [deploy]  v3  accepted\nShips it\nDomain: Backend; repositories: acme/*\n"
        assertTrue(text.startsWith(head), text)
        assertTrue(text.contains("Start: build\nUpdated 1970-01-10T21:00:00Z by lena\n"), text)
        val inputs = "Inputs:\n  env  Environment (required)\n  note  Note (required, default \"none\")\n" +
            "  tag  Tag (optional)\n"
        assertTrue(text.contains(inputs), text)
        assertTrue(text.contains("  build  agent  Build\n    instructions: Build it for {{inputs.env}}\n"), text)
        assertTrue(text.contains("  ask  wait  Ship it?\n    asks: Ship {{nodes.build.output}}?\n"), text)
        assertTrue(text.contains("  call  workflow  Tell everyone\n    runs workflow notify\n    Calls notify\n"), text)
        val cut = "  odd  notify  Odd\n    ${"long ".repeat(17)}long…\n"
        assertTrue(text.contains(cut), text)
        assertTrue(text.endsWith("Edges:\n  build -delivered-> ask\n  ask -go-> call\n  call -next-> end\n"), text)
        console.out.clear()
        assertEquals(0, run(routes(show to (200 to bare)), "workflows", "show", "deploy"))
        val empty = "Bare  [b]  proposed\nDomain: Ops\nUpdated 1970-01-10T21:00:00Z\n\nInputs:\n  none\n\nNodes:\n\nEdges:\n  none\n"
        assertEquals(empty, console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(show to (200 to stored)), "workflows", "show", "deploy", "--json"))
        assertEquals("$stored\n", console.out.toString())
        assertEquals(66, run(routes(), "workflows", "show", "deploy"))
        assertErr("No workflow deploy in acme.\n")
    }

    @Test
    fun aDefinitionIsCheckedHereAndSavedAsItIs() {
        file(definition)
        val saved = routes(create to (201 to stored))
        assertEquals(0, run(saved, "workflows", "create", "--file", "w.json"))
        assertEquals("Saved Deploy [deploy] as v3.\n", console.out.toString())
        assertEquals(definition, bodyOf(create))
        console.out.clear()
        assertEquals(0, run(saved, "workflows", "create", "--file", "w.json", "--json"))
        assertEquals("$stored\n", console.out.toString())
        console.out.clear()
        // the output of show --json, or a wrapper: what the server adds is left out
        file("""{"workflow":$stored}""")
        assertEquals(0, run(saved, "workflows", "create", "--file", "w.json"))
        assertFalse(bodyOf(create)!!.contains("updatedBy"), bodyOf(create))
        assertFalse(bodyOf(create)!!.contains(""""version""""), bodyOf(create))
        assertTrue(bodyOf(create)!!.contains(""""repos":["acme/*"]"""))
        // a proposed workflow is on trial; the server answers without an id or a version
        file(proposed)
        console.out.clear()
        assertEquals(0, run(routes(create to (201 to "{}")), "workflows", "create", "--file", "w.json"))
        assertEquals("Saved the workflow [?].\n", console.out.toString())
    }

    @Test
    fun aDefinitionComesFromStandardInputToo() {
        console.standardInput = StandardInput.Read(definition.encodeToByteArray())
        assertEquals(0, run(routes(create to (201 to stored)), "workflows", "create", "--file", "-"))
        assertOut("Saved Deploy [deploy] as v3.")
        val reasons = mapOf(
            StandardInputFailure.TERMINAL to "Nothing is piped in: pipe the definition, or give --file FILE.",
            StandardInputFailure.TOO_LARGE to "The definition is larger than 1 MiB.",
            StandardInputFailure.ERROR to "The definition could not be read from standard input.",
        )
        reasons.forEach { (reason, message) ->
            console.err.clear()
            console.standardInput = StandardInput.Unavailable(reason)
            assertEquals(64, run(routes(), "workflows", "create", "--file", "-"))
            assertErr("ostrom: $message\n")
        }
    }

    @Test
    fun aDefinitionThatCannotBeReadIsRefusedBeforeAnythingIsSent() {
        assertEquals(66, run(routes(), "workflows", "create", "--file", "nope.json"))
        assertErr("No file /work/acme/nope.json.\n")
        file("[1]")
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        assertErr("ostrom: The definition is not a JSON object.\n")
        file("""{"name":"x"}""")
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        assertErr("ostrom: The definition is not a workflow: ")
        assertErr("A workflow has an id, name, domain, status, foundAt, nodes (id, kind, label) and edges (from, to).")
        file(" ".repeat(1024 * 1024 + 1))
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        assertErr("ostrom: The definition is larger than 1 MiB.\n")
        assertEquals(emptyList(), calls())
    }

    @Test
    fun everyObviousMistakeIsListedWithItsNode() {
        val bad = """{"id":"x","name":"X","domain":"d","status":"accepted","foundAt":"x","start":"nowhere",""" +
            """"inputs":[{"id":"env","label":"E"}],"nodes":[{"id":"a","kind":"agent","label":"A",""" +
            """"run":{"instructions":"  "}},{"id":"norun","kind":"agent","label":"N"},""" +
            """{"id":"a","kind":"wait","label":"B","detail":"{{inputs.zzz}} {{foo.bar}} {{x.y.z}} {{nodes.a.input}} """ +
            """{{nodes.ghost.output}} {{nodes.a.output}} {{inputs.env}} {{other}} {{nodes.a.output.x}}"},""" +
            """{"id":"lost","kind":"step","label":"L","run":{"message":"{{nodes.a.output}}"}}],""" +
            """"edges":[{"from":"a","to":"gone","on":"delivered"},{"from":"ghost","to":"a"},{"from":"lost","to":"void"}]}"""
        file(bad)
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        val message = console.err.toString()
        listOf(
            "The definition cannot be saved (checked here, nothing was sent):",
            "  - The node id \"a\" is used by more than one node.",
            "  - The start node \"nowhere\" does not exist.",
            "  - An edge leaves \"ghost\", which does not exist.",
            "  - Node \"a\" goes on \"delivered\" to \"gone\", which does not exist.",
            "  - Node \"lost\" goes on \"next\" to \"void\", which does not exist.",
            "  - Agent node \"norun\" has no instructions (run.instructions).",
            "  - Agent node \"a\" has no instructions (run.instructions).",
            "  - Node \"a\" uses {{inputs.zzz}}, but the workflow declares no input \"zzz\".",
            "  - Node \"a\" uses {{nodes.ghost.output}}, but there is no node \"ghost\".",
            "  - Node \"a\" reads its own output ({{nodes.a.output}}).",
            "  - No edge ends the workflow: give one edge \"to\": null.",
        ).forEach { assertTrue(message.contains(it), message) }
        // an unreachable node, a node of another one's output, and the start of the first trigger
        val loose = """{"id":"x","name":"X","domain":"d","status":"accepted","foundAt":"x","nodes":[""" +
            """{"id":"t","kind":"trigger","label":"T"},""" +
            """{"id":"s","kind":"step","label":"S","detail":"{{nodes.t.output}}"},""" +
            """{"id":"island","kind":"step","label":"I"}],""" +
            """"edges":[{"from":"t","to":"s"},{"from":"s","to":"t"},{"from":"s","to":null}]}"""
        file(loose)
        console.err.clear()
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        assertErr("  - Node \"island\" cannot be reached from the start node.")
        assertFalse(console.err.contains("The start node"), console.err.toString())
        file("""{"id":"x","name":"X","domain":"d","status":"accepted","foundAt":"x","nodes":[""" +
            """{"id":"s","kind":"step","label":"S"}],"edges":[{"from":"s","to":null}]}""")
        console.err.clear()
        assertEquals(64, run(routes(), "workflows", "create", "--file", "w.json"))
        assertErr("  - No start: set \"start\" to a node id, or add a trigger node.")
        assertEquals(emptyList(), calls())
    }

    @Test
    fun aDecisionIsHeldForTheConsoleAndNothingIsSavedInsideAnAgentsSession() {
        file(definition)
        val held = """{"confirmationId":"c1","confirmUrl":"https://app.ostrom.example/confirm/c1"}"""
        val poll = "GET /api/v1/confirmations/c1?wait=30"
        val routes = routes(create to (202 to held), poll to (200 to """{"status":"confirmed","result":$stored}"""))
        assertEquals(0, run(routes, "workflows", "create", "--file", "w.json"))
        assertErr("Confirm it on the console: https://app.ostrom.example/confirm/c1")
        assertOut("Saved Deploy [deploy] as v3.")
        for (line in listOf(listOf("create", "--file", "w.json"), listOf("update", "deploy", "--file", "w.json"),
            listOf("delete", "deploy"))) {
            console.err.clear()
            assertEquals(64, run(routes(), "workflows", *line.toTypedArray(), environment = inSession))
            assertErr("ostrom workflows ${line.first()} does not run inside an agent's session")
        }
    }

    @Test
    fun anUpdateNamesTheWorkflowAndSendsTheVersionItWasBasedOn() {
        file(stored)
        val saved = routes(save to (200 to stored))
        assertEquals(0, run(saved, "workflows", "update", "deploy", "--file", "w.json", "--expected-version", "3"))
        assertEquals("Saved Deploy [deploy] as v3.\n", console.out.toString())
        val body = bodyOf(save)!!
        assertTrue(body.startsWith("""{"workflow":{"id":"deploy","name":"Deploy","""), body)
        assertTrue(body.endsWith(""","expectedVersion":3}"""), body)
        // the id of the command wins over the file's; without a version none is sent
        file(definition.replace(""""id":"deploy","name":"Deploy"""", """"id":"other","name":"Deploy""""))
        console.out.clear()
        assertEquals(0, run(saved, "workflows", "update", "deploy", "--file", "w.json", "--json"))
        assertEquals("$stored\n", console.out.toString())
        assertTrue(bodyOf(save)!!.contains(""""id":"deploy""""), bodyOf(save))
        assertFalse(bodyOf(save)!!.contains("expectedVersion"), bodyOf(save))
        assertEquals(64, run(saved, "workflows", "update", "deploy", "--file", "w.json", "--expected-version", "0"))
        assertErr("ostrom: --expected-version is a version number, 1 or more.\n")
        assertEquals(66, run(routes(), "workflows", "update", "deploy", "--file", "w.json"))
        assertErr("No workflow deploy in acme.\n")
        val stale = routes(save to (409 to error("workflow_conflict", "someone saved this workflow in between")))
        assertEquals(64, run(stale, "workflows", "update", "deploy", "--file", "w.json", "--expected-version", "2"))
        assertErr("someone saved this workflow in between")
    }

    @Test
    fun aWorkflowIsDeletedUnlessSomethingUsesIt() {
        assertEquals(0, run(routes(delete to (204 to "")), "workflows", "delete", "deploy"))
        assertEquals("Deleted deploy.\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(delete to (204 to "")), "workflows", "delete", "deploy", "--json"))
        assertEquals("""{"id":"deploy","deleted":true}""" + "\n", console.out.toString())
        val used = routes(delete to (409 to error("workflow_in_use", "A pinned package compiles it.")))
        assertEquals(64, run(used, "workflows", "delete", "deploy"))
        assertErr("A pinned package compiles it.")
    }

    @Test
    fun aRunIsStartedWithItsInputsAndAFreshKeyEachTime() {
        val started = routes(show to (200 to definition), start to (201 to runJson()))
        val args = arrayOf("workflows", "run", "deploy", "--input", "env=prod", "--repository", "acme/api")
        assertEquals(0, run(started, *args))
        assertEquals(
            "Started run r1 of Deploy (v3).\nFollow it: ostrom workflows run-show r1   " +
                "Stop it: ostrom workflows cancel r1\n",
            console.out.toString(),
        )
        assertEquals("""{"inputs":{"env":"prod"},"repository":"acme/api"}""", bodyOf(start))
        val first = requests.last { it.second.path == "/api/v1/workflows/deploy/runs" }.second
        assertNotNull(first.idempotencyKey)
        console.out.clear()
        assertEquals(0, run(started, "workflows", "run", "deploy", "--input", "env=prod", "--json"))
        assertEquals(runJson() + "\n", console.out.toString())
        assertEquals("""{"inputs":{"env":"prod"}}""", bodyOf(start))
        val second = requests.last { it.second.path == "/api/v1/workflows/deploy/runs" }.second
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertNull(requests.first { it.second.path == "/api/v1/capabilities" }.second.idempotencyKey)
        // an answer without a version or an id still says what it can
        console.out.clear()
        val bareRun = routes(show to (200 to unnamed), start to (201 to "{}"))
        assertEquals(0, run(bareRun, "workflows", "run", "deploy"))
        assertEquals(
            "Started run ? of deploy.\nFollow it: ostrom workflows run-show ?   Stop it: ostrom workflows cancel ?\n",
            console.out.toString(),
        )
    }

    @Test
    fun anInputThatDoesNotFitIsNamedBeforeAnythingIsSent() {
        val read = routes(show to (200 to definition))
        fun refused(message: String, vararg args: String) {
            console.err.clear()
            assertEquals(64, run(read, "workflows", "run", "deploy", *args))
            assertErr("ostrom: $message\n")
        }
        refused("\"Deploy\" has no input \"zone\". Its inputs: env, note, tag.", "--input", "env=a", "--input", "zone=b")
        refused("Input env is given twice: pass --input env=... once.", "--input", "env=a", "--input", "env=b")
        refused("Input env is 9 characters long; the most it takes is 8.", "--input", "env=123456789")
        refused("\"Deploy\" needs env (Environment): pass --input env=....")
        assertEquals(64, run(read, "workflows", "run", "deploy", "--input", "novalue"))
        assertErr("\"novalue\" needs the form KEY=VALUE.")
        refused("\"x y\" is not a repository: write OWNER/NAME.", "--input", "env=a", "--repository", "x y")
        refused("--interval is 2 seconds or more.", "--interval", "1")
        refused("--timeout is 1 to 1440 minutes.", "--timeout", "0")
        refused("--timeout is 1 to 1440 minutes.", "--timeout", "1441")
        val none = routes(show to (200 to bare.replace("\"id\":\"b\"", "\"id\":\"deploy\"")))
        assertEquals(64, run(none, "workflows", "run", "deploy", "--input", "a=b"))
        assertErr("\"Bare\" has no input \"a\". It takes no inputs.")
        assertEquals(64, run(routes(show to (200 to unnamed)), "workflows", "run", "deploy", "--input", "a=b"))
        assertErr("\"The workflow\" has no input \"a\". It takes no inputs.")
        assertTrue(calls().none { it.startsWith("POST") }, "nothing was started")
        assertEquals(66, run(routes(), "workflows", "run", "deploy"))
        assertErr("No workflow deploy in acme.\n")
    }

    @Test
    fun aRunFollowedToItsEndSaysEachStepAndExitsWithItsOutcome() {
        val waiting = visit(2, "waiting", ""","waitId":"w9"""", "ask", "wait")
        val polls = arrayOf(
            200 to runJson(visits = visit(1, "running", ""","sessionId":"s1"""")),
            200 to runJson(status = "waiting", visits = visit(1, "done", ""","outcome":"delivered"""") + "," + waiting),
            200 to runJson(
                status = "succeeded", extra = ""","output":"shipped"""",
                visits = visit(1, "done", ""","outcome":"delivered"""") + "," +
                    visit(2, "done", ""","outcome":"go","waitId":"w9"""", "ask", "wait") + "," +
                    visit(3, "done", ""","childRunId":"r2"""", "call", "workflow"),
            ),
        )
        assertEquals(0, following(*polls))
        assertEquals(
            "step 1: build (agent) started in session s1\n" +
                "step 1: build done (delivered)\n" +
                "step 2: ask (wait) started\n" +
                "step 2: ask waits for a person: ostrom waits show w9\n" +
                "step 2: ask done (go)\n" +
                "step 3: call (workflow) started sub-run r2\n" +
                "step 3: call done\n" +
                "Run r1 succeeded.\nOutput:\nshipped\n",
            console.out.toString(),
        )
        assertErr("Started run r1 of Deploy. Following it; Ctrl-C stops following and leaves the run running")
        assertEquals(listOf(3_000L, 3_000L, 3_000L), waits, "every 3 seconds by default")
    }

    @Test
    fun withJsonTheStepsGoToStandardErrorAndTheFinalRunToStandardOutput() {
        val done = runJson(status = "succeeded", visits = visit(1, "done"))
        assertEquals(0, following(200 to done, args = listOf("--json", "--interval", "5")))
        assertEquals("$done\n", console.out.toString())
        assertErr("step 1: build (agent) started\nstep 1: build done\nRun r1 succeeded.\n")
        assertEquals(listOf(5_000L), waits)
    }

    @Test
    fun aFailedOrCancelledRunExitsWithItsCodeAndSaysWhy() {
        val code = ""","failureCode":"ended_without_delivery""""
        val failed = runJson(status = "failed", extra = code, visits = visit(1, "failed"))
        assertEquals(1, following(200 to failed))
        assertOut("step 1: build failed\nRun r1 failed: the agent ended its turn without delivering")
        console.out.clear()
        val cancelled = runJson(status = "cancelled", visits = visit(1, "gone"))
        assertEquals(2, following(200 to cancelled))
        assertOut("step 1: build gone\nRun r1 cancelled.\n")
        console.out.clear()
        val text = runJson(status = "failed", extra = ""","failure":"No cloud runner is free.","output":"partial"""")
        assertEquals(1, following(200 to text, args = listOf("--json")))
        assertErr("Run r1 failed: No cloud runner is free.\n")
        assertEquals("$text\n", console.out.toString())
    }

    @Test
    fun aRunStillUnfinishedAtTheTimeoutExits75AndKeepsGoing() {
        val running = 200 to runJson(status = "waiting", visits = visit(1, "waiting", ""","gateId":"g1"""") + "," +
            visit(2, "waiting", "", "call", "workflow") + "," + visit(3, "running", "", "x"))
        assertEquals(75, following(running, args = listOf("--timeout", "1", "--interval", "30")))
        assertErr("Still waiting after 1 minutes; stopped following. The run keeps going: ostrom workflows run-show r1")
        assertOut("step 1: build waits for an approval: ostrom inbox\n")
        assertEquals(listOf(30_000L, 30_000L), waits)
        console.out.clear()
        assertEquals(75, following(running, args = listOf("--timeout", "1", "--interval", "30", "--json")))
        assertEquals(running.second + "\n", console.out.toString())
    }

    @Test
    fun aReadThatFailsForAPassingReasonIsTriedAgainThreeTimes() {
        val ok = 200 to runJson(status = "succeeded")
        val going = 200 to runJson()
        // each failure is followed by a read that works: the count starts again
        assertEquals(0, following(503 to "{}", going, 0 to "", 429 to "{}", going, 408 to "{}", ok))
        assertTrue(console.out.contains("Run r1 succeeded."))
        // three failures in a row are tried again, the fourth stops
        console.out.clear()
        assertEquals(0, following(503 to "{}", 503 to "{}", 503 to "{}", ok))
        assertTrue(console.out.contains("Run r1 succeeded."))
        console.err.clear()
        assertEquals(69, following(503 to "{}", 503 to "{}", 503 to "{}", 503 to error("down", "Down for now.")))
        assertErr("Ostrom could not answer: Down for now.\nThe run keeps going: ostrom workflows run-show r1\n")
        console.err.clear()
        assertEquals(69, following(0 to ""))
        assertErr("The run keeps going")
        // a refusal is not tried again
        console.err.clear()
        assertEquals(77, following(403 to error("forbidden", "Not yours.")))
        assertErr("Not yours.\nThe run keeps going")
        assertEquals(66, following(404 to "{}"))
    }

    @Test
    fun theRunsAreListedNewestFirstWithTheirCurrentStepAndFailure() {
        val failed = runJson("r2", "failed", ""","failureCode":"max_visits:build"""")
        val going = runJson("r3", "running", ""","currentNodeId":"ask","currentNodeKind":"wait"""")
        val noKind = runJson("r4", "waiting", ""","currentNodeId":"call"""")
        val odd = runJson("r5", "failed", ""","failure":"Quota used up."""")
        val page = routes(runs to (200 to """{"items":[$failed,$going,$noKind,$odd]}"""))
        assertEquals(0, run(page, "workflows", "runs"))
        assertEquals(
            "r2  failed  Deploy v3  -  3h  failed: the loop came back to step \"build\" more often than its limit " +
                "allows\n" +
                "r3  running  Deploy v3  ask (wait)  3h\nr4  waiting  Deploy v3  call  3h\n" +
                "r5  failed  Deploy v3  -  3h  failed: Quota used up.\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, run(page, "workflows", "runs", "--json"))
        assertEquals("[$failed,$going,$noKind,$odd]\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(runs to (200 to """{"items":[]}""")), "workflows", "runs"))
        assertEquals("", console.out.toString())
        assertErr("No run matches.\n")
    }

    @Test
    fun theFiltersGoToTheServerAndDoneMeansSucceeded() {
        val some = routes("GET /api/v1/runs?status=succeeded&all=true&before=r9&limit=5" to (200 to """{"items":[]}"""))
        val args = arrayOf("workflows", "runs", "--status", "done", "--all", "--before", "r9", "--limit", "5")
        assertEquals(0, run(some, *args))
        val path = "GET /api/v1/workflows/deploy/runs?status=failed&limit=50"
        val byWorkflow = routes(path to (200 to """{"items":[]}"""))
        assertEquals(0, run(byWorkflow, "workflows", "runs", "--workflow", "deploy", "--status", "failed"))
        assertEquals(64, run(routes(), "workflows", "runs", "--limit", "0"))
        assertErr("ostrom: --limit is 1 to 500.\n")
        assertEquals(64, run(routes(), "workflows", "runs", "--limit", "501"))
        assertEquals(66, run(routes(), "workflows", "runs"))
        assertErr("No runs in acme.\n")
    }

    @Test
    fun theListFollowsTheCursorUpToTheLimitAndSaysWhereTheNextPageIs() {
        val first = runJson("r1")
        val second = runJson("r2")
        val paged = sequence(
            mapOf(
                capabilities("workflows.v1").let { it.first to listOf(it.second) },
                "GET /api/v1/runs?limit=2" to listOf(200 to """{"items":[$first],"next":"r1"}"""),
                "GET /api/v1/runs?before=r1&limit=1" to listOf(200 to """{"items":[$second],"next":"r2"}"""),
            ),
            "workflows", "runs", "--limit", "2",
        )
        assertEquals(0, paged)
        assertEquals(2, console.out.toString().lines().count { it.startsWith("r") })
        assertErr("More exist: ostrom workflows runs --before r2\n")
        // a page without a run is the end, whatever cursor it names
        console.err.clear()
        assertEquals(0, run(routes(runs to (200 to """{"items":[],"next":"x"}""")), "workflows", "runs"))
        assertFalse(console.err.contains("More exist"), console.err.toString())
        console.err.clear()
        val empty = routes("GET /api/v1/runs?limit=1" to (200 to """{"items":[$first],"next":""}"""))
        assertEquals(0, run(empty, "workflows", "runs", "--limit", "1"))
        assertFalse(console.err.contains("More exist"), console.err.toString())
        console.err.clear()
        val more = routes("GET /api/v1/runs?limit=1" to (200 to """{"items":[$first],"next":"r1"}"""))
        assertEquals(0, run(more, "workflows", "runs", "--limit", "1"))
        assertErr("More exist: ostrom workflows runs --before r1\n")
    }

    @Test
    fun aRunIsShownWithItsStepWhatItWaitsForItsFailureAndItsVisits() {
        val visits = visit(1, "done", ""","outcome":"delivered","sessionId":"s1","output":"built  it\nwell"""") + "," +
            visit(2, "waiting", ""","waitId":"w9","nodeTitle":"Ship it?"""", "ask", "wait")
        val body = runJson(
            status = "waiting", extra = ""","currentNodeId":"ask","sessionId":"s1","parentRunId":"r0",""" +
                """"inputs":{"env":"prod"}""",
            visits = visits,
        )
        assertEquals(0, run(routes(runShow to (200 to body)), "workflows", "run-show", "r1"))
        assertEquals(
            "Deploy v3  [r1]  waiting\nStarted 1970-01-10T21:00:00Z by m1; 3h ago\nSession: s1\nSub-run of: r0\n" +
                "Inputs:\n  env = prod\nCurrent step: ask \"Ship it?\"\n" +
                "Blocked on a person's answer: ostrom waits show w9\n\nVisits:\n" +
                "  1  build  agent  done (delivered, session s1)\n      built it well\n" +
                "  2  ask \"Ship it?\"  wait  waiting (wait w9)\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, run(routes(runShow to (200 to body)), "workflows", "run-show", "r1", "--json"))
        assertEquals("$body\n", console.out.toString())
        assertEquals(66, run(routes(), "workflows", "run-show", "r1"))
        assertErr("No run r1 in acme.\n")
    }

    @Test
    fun aRunBlockedOnASubRunOrAnApprovalSaysSo() {
        val child = runJson(visits = visit(1, "waiting", ""","childRunId":"r2"""", "call", "workflow"))
        assertEquals(0, run(routes(runShow to (200 to child)), "workflows", "run-show", "r1"))
        assertOut("Current step: call\nBlocked on a sub-run: ostrom workflows run-show r2\n")
        assertOut("  1  call  workflow  waiting (sub-run r2)\n")
        console.out.clear()
        val gate = runJson(visits = visit(1, "running", ""","gateId":"g1""""))
        assertEquals(0, run(routes(runShow to (200 to gate)), "workflows", "run-show", "r1"))
        assertOut("Current step: build\nBlocked on an approval: ostrom inbox\n")
        console.out.clear()
        assertEquals(0, run(routes(runShow to (200 to runJson())), "workflows", "run-show", "r1"))
        assertOut("Current step: -\n\nVisits:\n  none yet\n")
    }

    @Test
    fun aFinishedRunShowsItsFailureAndOutputInsteadOfAStep() {
        val failed = runJson(
            status = "failed",
            extra = ""","failureCode":"no_transition","endedAt":"1970-01-10T22:00:00Z","output":"half"""",
            visits = visit(1, "failed"),
        )
        assertEquals(0, run(routes(runShow to (200 to failed)), "workflows", "run-show", "r1"))
        val text = console.out.toString()
        assertTrue(text.contains("; ended 1970-01-10T22:00:00Z\n"), text)
        assertFalse(text.contains("Current step"), text)
        val failure = "Failure: a step ended with an outcome that has no edge\n\nOutput:\nhalf\n\nVisits:\n"
        assertTrue(text.contains(failure), text)
    }

    @Test
    fun aRunIsCancelled() {
        val cancelled = runJson(status = "cancelled")
        assertEquals(0, run(routes(cancel to (200 to cancelled)), "workflows", "cancel", "r1"))
        assertEquals("Run r1 of Deploy is cancelled.\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(cancel to (200 to cancelled)), "workflows", "cancel", "r1", "--json"))
        assertEquals("$cancelled\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(cancel to (200 to """{"status":"cancelling"}""")), "workflows", "cancel", "r1"))
        assertEquals("Run r1 is cancelling.\n", console.out.toString())
        assertEquals(66, run(routes(), "workflows", "cancel", "r1"))
        assertErr("No run r1 in acme.\n")
        val ended = routes(cancel to (409 to error("run_ended", "The run already ended.")))
        assertEquals(64, run(ended, "workflows", "cancel", "r1"))
    }

    @Test
    fun everyFailureCodeIsSaidInWordsAndAnUnknownOneAsItIs() {
        listOf(
            "session_failed", "session_deleted", "session_missing", "ended_without_delivery", "profile_missing",
            "starter_unavailable", "wait_cancelled", "wait_missing", "max_total_visits", "max_depth",
            "workflow_missing", "invalid_arguments", "sub_workflow_failed", "sub_workflow_cancelled", "expired",
            "no_transition",
        ).forEach { assertNotEquals(it, failureWords(it), it) }
        assertEquals("something new", failureWords("something new"))
        assertEquals("?", age("not a date", 0))
        assertEquals("?", age(null, 0))
        assertEquals("0s", age("1970-01-02T00:00:00Z", 0))
        val at = "1970-01-01T00:00:00Z"
        assertEquals("59s", age(at, 59_000L))
        assertEquals("2m", age(at, 120_000L))
        assertEquals("1h", age(at, 3_600_000L))
        assertEquals("4d", age(at, 4 * 86_400_000L))
    }
}
