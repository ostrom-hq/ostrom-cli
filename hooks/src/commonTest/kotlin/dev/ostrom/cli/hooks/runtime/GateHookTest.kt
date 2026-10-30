// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.FakeGit
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
        "/w|log --no-color --format=%h %s (%an) refs/remotes/origin/main..M" to "m1 fix (lena)",
        "/w|diff --no-color --stat --patch refs/remotes/origin/main..M" to "+fix",
        "/w|rev-parse --abbrev-ref @{u}" to "origin/main",
        "/w|log --no-color --format=%h %s (%an) @{u}..HEAD" to "m1 fix (lena)",
        "/w|diff --no-color --stat --patch @{u}..HEAD" to "+fix",
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

    private val files = FakeFiles(mutableMapOf("/s/gates.json" to catalogue))
    private var calls = FakeCalls()

    private fun hook(
        context: HookContext = contextOf(),
        checks: WorkflowChecks = Checks(),
        git: Map<String, String> = repository,
        catalogueText: String? = null,
    ): GateHook {
        if (catalogueText != null) files.texts["/s/gates.json"] = catalogueText
        val ids = SessionIds(context.sessionFile, files)
        val store = CatalogueStore(context, files, calls) { true }
        return GateHook(context, calls, ids, store, checks, FakeGit(git), { it.removeSuffix("/") }, "/w")
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
        assertTrue(calls.calls.isEmpty())
        assertFalse("/s/session.refused.json" in files.texts)
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
        answer("""{"status":"pending","gate":{"approvers":["ana","bo",3]}}""")
        assertContains(hook().handle(bash("git push origin main")).stderr, "`ostrom wait ?`")
        assertContains(hook().handle(bash("git push origin main")).stderr, "needs a yes from ana or bo (gate \"Main\")")
        answer("""{"status":"shadow"}""")
        val shadow = hook().handle(bash("./deploy.sh")).stdout
        assertContains(shadow, "falls under the proposed gate \\\"Prod\\\" (the gate owner)")
        answer("""{"status":"shadow","gate":{"approvers":[]}}""")
        assertContains(hook().handle(bash("./deploy.sh")).stdout, "(the gate owner)")
        for (status in listOf("ungated", "consumed")) {
            answer("""{"status":"$status"}""")
            assertEquals(HookReply(), hook().handle(bash("./deploy.sh")))
        }
    }

    @Test
    fun whenOstromCannotAnswerTheFailModeDecides() {
        calls = FakeCalls()
        val closed = hook().handle(bash("git push origin main"))
        assertEquals(2, closed.exitCode)
        assertContains(closed.stderr, "needs a yes from omar (Main), and Ostrom is unreachable (HTTP 404)")
        val open = catalogue.replace("\"closed\"", "\"open\"")
        val reply = hook(catalogueText = open).handle(bash("git push origin main"))
        assertEquals(0, reply.exitCode)
        assertContains(reply.stdout, "Ostrom unreachable (HTTP 404): \\\"Push to main\\\" let through")
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
    fun anActionThatRanUsesUpItsYes() {
        answer("""{"gate":{"id":"G1","status":"approved","decidedBy":"omar"}}""")
        assertEquals(0, hook().handle(bash("git push origin main")).exitCode)
        calls = FakeCalls().on("/api/v1/gates/G1/consume", ok())
        val checks = Checks()
        assertEquals(HookReply(), hook(checks = checks).handle(bash("git push origin main", after = true)))
        assertEquals(listOf("git push origin main\n# ostrom: git push to: main"), checks.after)
        assertEquals(listOf("/api/v1/gates/G1/consume"), calls.paths())
        assertEquals("{}", calls.calls.single().body.toString())
        assertEquals(2_000, calls.calls.single().timeoutMillis)
        assertEquals(HookReply(), hook().handle(bash("git push origin main", after = true)))
        assertEquals(1, calls.calls.size, "one yes, one action")
        assertEquals(HookReply(), hook().handle(bash("npm run lint", after = true)))
        assertEquals(1, calls.calls.size, "an ungated action has no yes to use up")
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
            """{"kind":"skill","name":"ostrom-review","via":"Skill"}""",
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
