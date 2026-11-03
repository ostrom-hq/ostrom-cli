// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.FakeGit
import dev.ostrom.cli.hooks.gate.GateCatalogue
import dev.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateHookTest {
    private val logArgs = "log --no-color --no-ext-diff --no-textconv --format=%h %s (%an)"
    private val diffArgs = "diff --no-color --no-ext-diff --no-textconv --stat --patch"

    private val catalogue = """
        {"failMode":"closed","actions":[
          {"class":"lint","tools":["Bash"],"patterns":["\\blint\\b"]},
          {"class":"push-main","label":"Push to main","tools":["Bash"],"patterns":["\\bgit\\b.*\\bpush\\b.*\\bmain\\b"],
           "gate":{"enforce":true,"label":"Main","approvers":["omar"]}},
          {"class":"deploy","tools":["Bash"],"patterns":["\\bdeploy\\b"],"gate":{"label":"Prod"}},
          {"class":"push-pr","tools":["Bash"],"patterns":["\\bgh pr merge\\b"],"gate":{}},
          {"class":"merge","tools":["mcp"],"patterns":["^mcp__github__merge"],"gate":{"enforce":true}}
        ]}
    """.trimIndent()

    private val repository = mapOf(
        "/w|rev-parse --show-toplevel" to "/w",
        "/w|symbolic-ref --quiet --short HEAD" to "main",
        "/w|rev-parse --verify --quiet main^{commit}" to "M",
        "/w|rev-parse --verify --quiet HEAD^{commit}" to "M",
        "/w|remote get-url origin" to "git@github.com:acme/app.git",
        "/w|remote get-url up" to "git@github.com:acme/up.git",
        "/w|config branch.main.remote" to "origin",
        "/w|config branch.main.merge" to "refs/heads/main",
        "/w|rev-parse --verify --quiet refs/remotes/origin/main" to "B",
        "/w|$logArgs refs/remotes/origin/main..M" to "m1 fix (lena)",
        "/w|$diffArgs refs/remotes/origin/main..M" to "+fix",
        "/w|rev-parse --abbrev-ref @{u}" to "origin/main",
        "/w|$logArgs @{u}..HEAD" to "m1 fix (lena)",
        "/w|$diffArgs @{u}..HEAD" to "+fix",
        "/o|rev-parse --show-toplevel" to "/o",
        "/o|remote get-url origin" to "https://github.com/acme/other",
    )

    private class Checks(
        private val refusal: HookReply? = null,
        private val guarded: Boolean = false,
    ) : WorkflowChecks {
        val after = mutableListOf<String>()
        override val digest: String? = null
        override val context: String? = null

        override fun guards(subject: ToolSubject) = guarded

        override fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String) = refusal

        override fun afterTool(event: JsonObject, subject: ToolSubject) {
            after += subject.text
        }
    }

    private val files = FakeFiles()
    private var calls = FakeCalls()

    private fun hook(
        context: HookContext = contextOf(),
        checks: WorkflowChecks = Checks(),
        git: Map<String, String> = repository,
        catalogueText: String? = null,
        canonical: (String) -> String = { it.removeSuffix("/") },
    ): GateHook {
        // the verified policy (CatalogueStoreTest checks the signature); an unreadable one holds every action
        val parsed = GateCatalogue.parse(catalogueText ?: catalogue)
        val store = CatalogueStore {
            parsed?.let { CatalogueRead.Verified(it) } ?: CatalogueRead.Held("Ostrom's gate policy cannot be trusted")
        }
        val ids = SessionIds(context.sessionFile, files)
        return GateHook(context, calls, ids, files, store, checks, FakeGit(git), canonical, "/w")
    }

    private fun event(tool: String, input: String, after: Boolean = false, cwd: String? = null) = buildJsonObject {
        put("hook_event_name", if (after) "PostToolUse" else "PreToolUse")
        put("session_id", "a1")
        put("tool_name", tool)
        put("tool_input", Json.parseToJsonElement(input))
        if (cwd != null) put("cwd", cwd)
    }

    private fun bash(command: String, after: Boolean = false, cwd: String? = null) =
        event("Bash", JsonObject(mapOf("command" to JsonPrimitive(command))).toString(), after, cwd)

    private fun answer(json: String) {
        calls = FakeCalls().on("/api/v1/gates", ok(json))
    }

    @Test
    fun aToolWithNothingToMatchOrNoGateRuns() {
        assertEquals(HookReply(), hook().handle(event("Read", """{"file_path":"/w/a"}""")))
        assertEquals(HookReply(), hook().handle(bash("npm run lint")))
        assertEquals(HookReply(), hook().handle(bash("git status")))
        assertEquals(HookReply(), hook().handle(buildJsonObject { put("hook_event_name", "PreToolUse") }), "no tool")
        assertTrue(calls.calls.isEmpty())
        assertFalse("/s/session.refused.json" in files.texts)
    }

    @Test
    fun aGatedCommandRunOutsideAnyRepositoryIsAskedWithoutARealRepository() {
        answer("""{"id":"G2","status":"pending"}""")
        hook().handle(bash("deploy now", cwd = "/elsewhere"))
        val body = calls.bodyOf("/api/v1/gates")
        assertFalse("realRepo" in body.toString(), "no repository resolved: none is claimed")
        assertContains(body.toString(), """"class":"deploy","tool":"Bash"""")
    }

    @Test
    fun aGatedPushWaitsForAYesAndIsMarkedRefused() {
        answer("""{"id":"G1","status":"pending"}""")
        val reply = hook().handle(bash("git push origin main"))
        assertEquals(2, reply.exitCode)
        assertTrue(reply.stderr.startsWith("Ostrom: waiting for a yes. Run `ostrom wait G1` as its own command"))
        assertContains(reply.stderr, "needs a yes from omar (gate \"Main\")")
        val body = calls.bodyOf("/api/v1/gates")
        assertEquals(
            """{"sessionId":"a1","repo":"acme/app","class":"push-main","tool":"Bash",""" +
                """"command":"git push origin main\n# ostrom: git push to: main","dev":"lena","harnessVersion":"7",""" +
                """"realRepo":"acme/app","push":{"remote":"acme/app",""" +
                """"refs":[{"ref":"main","sha":"M"}],"force":false},"diff":"main ← M\nm1 fix (lena)\n\n+fix\n"}""",
            body.toString(),
        )
        assertEquals(3_000, calls.calls.single().timeoutMillis)
        // the agent runs nothing; its after-tool hook (Hermes) is ignored and clears the mark
        assertEquals(HookReply(), hook().handle(bash("git push origin main", after = true)))
        assertEquals(listOf("/api/v1/gates"), calls.paths())
        assertEquals("{}", files.texts["/s/session.refused.json"])
    }

    @Test
    fun aPushFromMainThatDoesNotNameItIsGatedAsMain() {
        answer("""{"id":"G2","status":"pending"}""")
        assertEquals(2, hook().handle(bash("git push origin HEAD")).exitCode)
        val command = calls.bodyOf("/api/v1/gates")["command"] as JsonPrimitive
        assertEquals("git push origin HEAD\n# ostrom: git push to: main", command.content)
        assertEquals(2, hook().handle(bash("git push")).exitCode, "a bare push to the upstream main")
    }

    @Test
    fun eachDecisionIsSaidInItsOwnWords() {
        answer("""{"id":"G1","status":"approved","decidedBy":"omar","receiptId":"R1"}""")
        val yes = hook().handle(bash("git push origin main"))
        assertEquals(0, yes.exitCode)
        assertContains(yes.stdout, "Ostrom: omar said yes to \\\"Push to main\\\" (gate \\\"Main\\\", receipt R1).")
        answer("""{"id":"G1","status":"approved"}""")
        val pending = hook().handle(bash("git push origin main")).stdout
        assertContains(pending, "omar said yes to \\\"Push to main\\\" (gate \\\"Main\\\", receipt pending)")
        answer("""{"id":"G1","status":"rejected","decidedBy":"omar","receiptId":"R2","gate":{"label":"Release"}}""")
        val no = hook().handle(bash("git push origin main"))
        assertEquals(2, no.exitCode)
        assertTrue(no.stderr.startsWith("Ostrom: omar said no to \"Push to main\" (gate \"Release\", receipt R2)."))
        answer("""{"id":"G1","status":"rejected"}""")
        assertContains(hook().handle(bash("git push origin main")).stderr, "receipt -")
        answer("""{"status":"pending","gate":{"approvers":["ana","bo",3,{}]}}""")
        assertContains(hook().handle(bash("git push origin main")).stderr, "`ostrom wait ?`")
        assertContains(hook().handle(bash("git push origin main")).stderr, "needs a yes from ana or bo (gate \"Main\")")
        answer("""{"status":"shadow"}""")
        val shadow = hook().handle(bash("./deploy.sh")).stdout
        assertContains(shadow, "falls under the proposed gate \\\"Prod\\\" (the gate owner)")
        answer("""{"status":"shadow","gate":{"approvers":[]}}""")
        assertContains(hook().handle(bash("./deploy.sh")).stdout, "(the gate owner)")
        answer("""{"status":"ungated"}""")
        assertEquals(HookReply(), hook().handle(bash("./deploy.sh")))
        // S2-29: a used yes no longer lets the action through
        answer("""{"id":"G9","status":"consumed"}""")
        assertContains(hook().handle(bash("./deploy.sh")).stderr, "Ostrom: waiting for a yes. Run `ostrom wait G9`")
    }

    @Test
    fun whenOstromCannotAnswerTheFailModeDecides() {
        calls = FakeCalls()
        val closed = hook().handle(bash("git push origin main"))
        assertEquals(2, closed.exitCode)
        assertContains(closed.stderr, "needs a yes from omar (Main), and Ostrom is unreachable (HTTP 404)")
        val open = catalogue.replace("\"closed\"", "\"open\"")
        assertEquals(2, hook(catalogueText = open).handle(bash("git push origin main")).exitCode, "a 404 is no outage")
        val outage = CallResult.Failed("Ostrom is unreachable (no connection)", CallResult.UNREACHABLE)
        calls = FakeCalls().on("/api/v1/gates", outage)
        val reply = hook(catalogueText = open).handle(bash("git push origin main"))
        assertEquals(0, reply.exitCode)
        assertContains(reply.stdout, "Ostrom unreachable (Ostrom is unreachable (no connection)): \\\"Push to main\\\"")
        val noLabel = hook(catalogueText = catalogue).handle(event("mcp__github__merge_pr", """{"pr":1}"""))
        assertContains(noLabel.stderr, "\"merge\" needs a yes from the gate owner (gate)")
    }

    @Test
    fun aClosedSessionIsReopenedOnceAndTheRequestSentThere() {
        val approved = ok("""{"status":"approved","decidedBy":"omar"}""")
        calls = FakeCalls()
            .on("/api/v1/gates", CallResult.Failed("session closed: a1"), approved)
            .on("/api/v1/sessions/a1/reopen", ok("""{"session":{"id":"a1~2","agentSessionId":"a1"}}"""))
        assertEquals(0, hook().handle(bash("git push origin main")).exitCode)
        assertEquals("a1~2", (calls.bodyOf("/api/v1/gates")["sessionId"] as JsonPrimitive).content)
        assertEquals("a1~2", files.texts["/s/session"])
        assertEquals("""{"launchId":"L1"}""", calls.calls[1].body.toString())
        calls = FakeCalls().on("/api/v1/gates", CallResult.Failed("closed", "session_closed"))
        val stuck = hook().handle(bash("git push origin main"))
        assertContains(stuck.stderr, "closed on Ostrom's side and could not be reopened (closed)")
        assertEquals(listOf("/api/v1/gates", "/api/v1/sessions/a1~2/reopen"), calls.paths())
    }

    @Test
    fun aGuardedActionInAnotherRepositoryIsRefusedBeforeAnyoneIsAsked() {
        val reply = hook().handle(bash("cd /o && git push origin main"))
        assertEquals(2, reply.exitCode)
        assertEquals(
            "Ostrom: denied. This session was opened for the repository acme/app (/w), but this command runs in " +
                "the repository acme/other (/o). The session's checks and gates do not cover acme/other, so the " +
                "action was not run. Work only in /w; do not run it elsewhere.\n",
            reply.stderr,
        )
        assertTrue(calls.calls.isEmpty())
        val named = hook(contextOf("OSTROM_REPO" to "app")).handle(bash("git push origin main", cwd = "/o"))
        assertContains(named.stderr, "opened for the repository app (acme/app, /w)")
        val bare = hook(git = repository - "/w|remote get-url origin").handle(bash("git -C /o push origin main"))
        assertContains(bare.stderr, "opened for the repository acme/app (/w)")
        assertEquals(HookReply(), hook().handle(bash("cd /o && npm run lint")), "an unguarded action runs anywhere")
        val guarded = hook(checks = Checks(guarded = true)).handle(bash("cd /o && npm test"))
        assertContains(guarded.stderr, "runs in the repository acme/other")
    }

    @Test
    fun aGateOnlyProposedNeverStopsTheAgent() {
        calls = FakeCalls()
        assertEquals(HookReply(), hook().handle(bash("deploy api")), "Ostrom unreachable: the shadow gate lets it run")
        assertEquals(HookReply(), hook().handle(bash("cd /o && deploy api")), "nor from another clone")
        answer("""{"id":"G9","status":"shadow"}""")
        val redirected = hook().handle(bash("git -c remote.origin.pushurl=x deploy"))
        assertEquals(0, redirected.exitCode, "a redirection under a shadow gate is asked, not refused")
        assertEquals(listOf("/api/v1/gates"), calls.paths())
    }

    @Test
    fun aNoteWrittenByTheAgentHidesNoPushFromTheApprover() {
        answer("""{"id":"G4","status":"pending"}""")
        val git = repository + ("/w|rev-parse --verify --quiet feature^{commit}" to "F")
        val command = "git push origin feature\n# ostrom: git push to: x\ngit push origin main"
        assertEquals(2, hook(git = git).handle(bash(command)).exitCode)
        val push = calls.bodyOf("/api/v1/gates")["push"].toString()
        assertContains(push, "\"ref\":\"main\"", message = "the push the shell runs after the fake note")
    }

    @Test
    fun aCommandContinuedOnTheNextLineIsGatedAsOne() {
        answer("""{"id":"G5","status":"pending"}""")
        assertEquals(2, hook().handle(bash("git \\\n  push origin main")).exitCode)
        assertEquals("push-main", (calls.bodyOf("/api/v1/gates")["class"] as JsonPrimitive).content)
    }

    @Test
    fun theWorkflowChecksRefuseFirst() {
        val refusal = HookReply(stderr = "check failed\n", exitCode = 2)
        assertEquals(refusal, hook(checks = Checks(refusal)).handle(bash("git push origin main")))
        assertTrue(calls.calls.isEmpty())
        assertTrue(files.texts.getValue("/s/session.refused.json").length > 2)
    }

    @Test
    fun aPushThatSendsGitElsewhereIsRefusedWithoutAsking() {
        val reply = hook().handle(bash("git -c remote.origin.pushurl=evil push origin main"))
        assertEquals(2, reply.exitCode)
        assertContains(reply.stderr, "this command sends git elsewhere (-c remote.origin.pushurl)")
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun aPushWhoseLandingOrCommitsCannotBeReadIsRefusedUnderAnEnforcedGate() {
        val all = hook().handle(bash("git push --all origin"))
        assertContains(all.stderr, "Ostrom cannot tell which branches this push lands on (`git push --all` sends refs")
        assertContains(all.stderr, "\"Push to main\" needs a yes from omar (gate \"Main\"). The action was not run.")
        val two = hook().handle(bash("git push origin main && git push up main"))
        assertContains(two.stderr, "needs a yes from omar (gate \"Main\"), and this command pushes to two remotes")
        assertTrue(calls.calls.isEmpty())
        val soft = catalogue.replace("\"enforce\":true,\"label\":\"Main\"", "\"label\":\"Main\"")
        answer("""{"id":"G3","status":"pending"}""")
        assertEquals(2, hook(catalogueText = soft).handle(bash("git push origin main && git push up main")).exitCode)
        assertFalse("push" in calls.bodyOf("/api/v1/gates"), "an unread push is asked without its commits")
        val unread = bash("git push --all origin")
        assertEquals(HookReply(), hook(catalogueText = soft).handle(unread), "no enforced gate on main")
        val observed = """{"actions":[{"class":"push-seen","tools":["Bash"],"patterns":["push"]}]}"""
        assertEquals(HookReply(), hook(catalogueText = observed).handle(bash("git push --all origin")))
        assertEquals(HookReply(), hook(catalogueText = "{}").handle(bash("git push --all origin")))
        val nameless =
            """{"actions":[{"class":"push-x","tools":["Bash"],""" + """"patterns":["main"],"gate":{"enforce":true}}]}"""
        val refusal = hook(catalogueText = nameless).handle(unread).stderr
        assertContains(refusal, "\"push-x\" needs a yes from the gate owner (gate \"gate\")")
    }

    @Test
    fun aGatedActionThatIsNotAPushShowsTheOutgoingCommitsOfAPushClass() {
        answer("""{"id":"G4","status":"pending"}""")
        hook().handle(bash("gh pr merge 3"))
        val body = calls.bodyOf("/api/v1/gates")
        assertEquals("m1 fix (lena)\n\n+fix", (body["diff"] as JsonPrimitive).content)
        hook().handle(bash("./deploy.sh"))
        assertFalse("diff" in calls.bodyOf("/api/v1/gates"))
    }

    @Test
    fun theAfterToolHookUsesUpNoYes() {
        // S2-25, S2-29: Ostrom uses the yes up when it sees the push land; the hook reports the action only
        val checks = Checks()
        assertEquals(HookReply(), hook(checks = checks).handle(bash("git push origin main", after = true)))
        assertEquals(listOf("git push origin main\n# ostrom: git push to: main"), checks.after)
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun quotesAndBackslashesDoNotHideAGatedCommand() {
        // S2-26: the patterns read the words as the shell reads them
        answer("""{"id":"G1","status":"pending"}""")
        for (command in listOf("git pu''sh origin main", "g\\it push origin main", "git push origin 'main'")) {
            assertEquals(2, hook().handle(bash(command)).exitCode, command)
        }
        assertEquals(3, calls.calls.size)
    }

    @Test
    fun withoutAPolicyToTrustEveryActionTheGateSeesIsRefused() {
        // S2-38: with the relay stopped, only four kinds of commands stayed gated; now nothing the gate sees runs
        val held = hook(catalogueText = """{"actions":"unreadable"}""")
        val events = listOf(
            bash("ls -la"),
            bash("kube''ctl apply -f prod.yaml"),
            event("Write", """{"file_path":"/w/a.txt"}"""),
            event("mcp__github__list_issues", "{}"),
        )
        for (tool in events) {
            val reply = held.handle(tool)
            assertEquals(2, reply.exitCode)
            assertContains(
                reply.stderr,
                "Ostrom: denied. Ostrom's gate policy cannot be trusted, so every action of this session is held",
            )
        }
        assertEquals(HookReply(), held.handle(event("Read", """{"file_path":"/w/a.txt"}""")), "not a gated tool")
        assertTrue(calls.calls.isEmpty(), "nobody is asked for a yes")
    }

    @Test
    fun aWriteIsGatedWhereverItsPathReallyLeads() {
        // a pattern on a path holds for every spelling of it, and through a folder linked to it (audit CLI n°2, M2)
        val gated = """{"failMode":"closed","actions":[{"class":"ci","label":"CI workflows","tools":["Write"],""" +
            """"patterns":["\\.github/workflows/"],"gate":{"enforce":true,"approvers":["omar"]}}]}"""
        calls = FakeCalls().on("/api/v1/gates", ok("""{"id":"G1","status":"pending"}"""))
        val links = mapOf("/w/wf" to "/w/.github/workflows")
        val canonical = { path: String -> links[path] ?: path }
        val paths = listOf(
            "/w/.github/workflows/x.yml",
            "/w/.github/./workflows/x.yml",
            "/w/.github//workflows/x.yml",
            "/w/src/../.github/workflows/x.yml",
            "/w/wf/x.yml",
            "wf/x.yml",
        )
        for (path in paths) {
            val reply = hook(catalogueText = gated, canonical = canonical)
                .handle(event("Write", """{"file_path":"$path"}"""))
            assertEquals(2, reply.exitCode, path)
        }
        assertEquals(paths.size, calls.paths().count { it.startsWith("/api/v1/gates") }, "each one is asked for a yes")
        val elsewhere = hook(catalogueText = gated, canonical = canonical)
        assertEquals(HookReply(), elsewhere.handle(event("Write", """{"file_path":"/w/src/x.kt"}""")))
        assertEquals(HookReply(), elsewhere.handle(event("Edit", """{"file_path":"/w/src/./x.kt"}""")))
    }

    @Test
    fun aProgramNamedByAVariableIsRefusedWhileAGateIsEnforced() {
        val reply = hook().handle(bash("G=git; \$G push origin main"))
        assertEquals(2, reply.exitCode)
        assertContains(reply.stderr, "\"Push to main\" needs a yes from omar (gate \"Main\"), and Ostrom cannot read")
        assertContains(reply.stderr, "which program `\$G` runs")
        assertEquals(HookReply(), hook().handle(bash("echo \$HOME")), "a variable in an argument is read as text")
        val soft = catalogue.replace("\"enforce\":true,\"label\":\"Main\"", "\"label\":\"Main\"")
        assertEquals(HookReply(), hook(catalogueText = soft).handle(bash("\$G status")), "no gate on Bash enforced")
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun aPushWithAVariableOrAShellAliasCannotBeReadAndIsRefused() {
        val variable = hook().handle(bash("git push origin \$B"))
        assertContains(variable.stderr, "`\$B` is a shell variable or substitution")
        val git = repository + ("/w|config --get alias.yolo" to "!git push origin main")
        assertContains(hook(git = git).handle(bash("git yolo")).stderr, "runs the shell alias `!git push origin main`")
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun aGitAliasThatPushesIsReadAsThePushItStandsFor() {
        answer("""{"id":"G1","status":"pending"}""")
        val git = repository + ("/w|config --get alias.ship" to "push origin") + ("/w|config --get alias.lg" to "log")
        assertEquals(2, hook(git = git).handle(bash("git ship main")).exitCode)
        val push = calls.bodyOf("/api/v1/gates")["push"].toString()
        assertEquals("""{"remote":"acme/app","refs":[{"ref":"main","sha":"M"}],"force":false}""", push)
        assertEquals(HookReply(), hook(git = git).handle(bash("git lg")), "an alias that does not push")
        val inline = hook(git = git).handle(bash("git -c alias.x=push x origin main"))
        assertContains(inline.stderr, "this command sends git elsewhere (-c alias.x)")
    }

    @Test
    fun aChangeToWherePushesGoIsRefusedWhileAGateOnMainIsEnforced() {
        val writes = listOf("git config alias.ship push", "git remote set-url origin x", "git config set url.x.y z")
        for (command in writes) {
            val reply = hook().handle(bash(command))
            assertEquals(2, reply.exitCode, command)
            assertContains(reply.stderr, "needs a yes from omar (gate \"Main\"), and this command changes git")
        }
        assertContains(hook().handle(bash("git remote add up x")).stderr, "changes git remote add, which decides")
        assertEquals(HookReply(), hook().handle(bash("git config --get remote.origin.url")))
        assertEquals(HookReply(), hook().handle(bash("git config user.name lena")))
        val soft = catalogue.replace("\"enforce\":true,\"label\":\"Main\"", "\"label\":\"Main\"")
        assertEquals(HookReply(), hook(catalogueText = soft).handle(bash("git config alias.ship push")))
    }

    @Test
    fun aScriptIsReadWithTheCommandThatRunsIt() {
        val script = "#!/bin/sh\nnpm test\ngit push origin main\n"
        files.texts["/w/ship.sh"] = script
        files.texts["/w/./ship.sh"] = script // `./ship.sh`, joined as the shell reads it
        for (command in listOf("sh ship.sh", "./ship.sh", "bash -e ship.sh")) {
            val reply = hook().handle(bash(command))
            assertEquals(2, reply.exitCode, command)
            assertContains(reply.stderr, "run the push as its own command")
        }
        assertTrue(calls.calls.isEmpty())
        assertEquals(HookReply(), hook().handle(bash("sh missing.sh")))
        assertEquals(HookReply(), hook().handle(bash("bash -c 'echo hi'")))
    }

    @Test
    fun theContractsAnswerAndThePrototypesAreBothRead() {
        answer("""{"gate":{"id":"G7","status":"pending","gate":{"label":"Release","approvers":["ana"]}}}""")
        val reply = hook().handle(bash("git push origin main")).stderr
        assertContains(reply, "`ostrom wait G7`")
        assertContains(reply, "needs a yes from ana (gate \"Release\")")
        answer("""{"gate":{"label":"Release"},"id":"G8","status":"pending"}""")
        assertContains(hook().handle(bash("git push origin main")).stderr, "`ostrom wait G8`")
        answer("{}")
        assertContains(hook().handle(bash("git push origin main")).stderr, "`ostrom wait ?`")
        answer("""{"status":"pending"}""")
        val merge = hook().handle(event("mcp__github__merge_pr", """{"pr":1}""")).stderr
        assertContains(merge, "\"merge\" needs a yes from the gate owner (gate \"gate\")")
    }

    @Test
    fun theSkillsAndSubagentsOfThePackageAreReportedWhenOpened() {
        calls = FakeCalls().on("/api/v1/sessions/a1/seen", ok())
        hook().handle(event("Skill", """{"skill":"ostrom-review"}""", after = true))
        assertEquals(
            """{"kind":"skill","name":"ostrom-review","via":"hook"}""",
            calls.bodyOf("/api/v1/sessions/a1/seen").toString(),
        )
        hook().handle(event("Task", """{"subagent_type":"ostrom-tester"}""", after = true))
        assertEquals("subagent", (calls.bodyOf("/api/v1/sessions/a1/seen")["kind"] as JsonPrimitive).content)
        hook().handle(event("Read", """{"file_path":"/w/.claude/skills/ostrom-ship/SKILL.md"}""", after = true))
        assertEquals("/ostrom-ship/", (calls.bodyOf("/api/v1/sessions/a1/seen")["name"] as JsonPrimitive).content)
        hook().handle(bash("cat skills/ostrom-x/SKILL.md", after = true))
        assertEquals("/ostrom-x/", (calls.bodyOf("/api/v1/sessions/a1/seen")["name"] as JsonPrimitive).content)
        val before = calls.calls.size
        hook().handle(event("Skill", """{"skill":"review"}""", after = true))
        hook().handle(event("Agent", """{}""", after = true))
        hook().handle(event("Skill", """{"skill":"ostrom-review"}"""))
        assertEquals(before, calls.calls.size, "not the package's, or before the call")
    }

    @Test
    fun theSeenFieldsOfEachAgent() {
        fun seen(tool: String, input: String) =
            Seen.of(
                buildJsonObject {
                    put("tool_name", tool)
                    put("tool_input", Json.parseToJsonElement(input))
                },
                "",
            )
        assertEquals("skill" to "ostrom-a", seen("Skill", """{"command":"ostrom-a"}"""))
        assertEquals("skill" to "ostrom-b", seen("Skill", """{"name":"ostrom-b"}"""))
        assertEquals("subagent" to "ostrom-c", seen("spawn_agent", """{"agent_type":"ostrom-c"}"""))
        assertEquals("subagent" to "ostrom-d", seen("Agent", """{"subagent_type":"ostrom-d"}"""))
        assertEquals(null, seen("Skill", "[]"))
        assertEquals(null, Seen.of(JsonObject(emptyMap()), ""))
    }

    @Test
    fun withoutACwdTheHookWorksWhereItWasStarted() {
        answer("""{"id":"G5","status":"pending"}""")
        val event = JsonObject(bash("git push origin main").filterKeys { it != "hook_event_name" })
        assertEquals(2, hook().handle(event).exitCode, "an event without a name is a before-tool event")
    }
}
