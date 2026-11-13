// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.json.JsonObject
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoCheckReport
import run.ostrom.core.api.models.TodoLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoChecksTest {
    private fun checks(capture: FakeCapture) =
        TodoChecks(GitHub(capture), CommandCheck(capture, OperatingSystem.LINUX))

    private fun observed(capture: FakeCapture, vararg items: run.ostrom.core.api.models.TodoItem) =
        checks(capture).run(todo(*items), items.first()).map { it.passed to it.observed }

    private val merged = TodoCheck.PrMerged("acme/web")

    @Test
    fun aPullRequestIsFoundByItsMarkerAmongTheTokensOwnAndLinked() {
        val capture = FakeCapture(
            "gh pr list" to printed(
                "[" + listOf(
                    ghPr(3, "MERGED", body = "x ${marker("fix")}", author = "someone-else", oid = "aaa"),
                    ghPr(4, "OPEN", body = "no marker here"),
                    ghPr(5, "MERGED", body = "fixes ${marker("fix")}", oid = "1234567890abc"),
                    ghPr(2, "CLOSED", body = marker("fix")),
                    ghPr(1, "OPEN", body = marker("fix"), author = null),
                ).joinToString(",") + "]",
            ),
            "gh api user" to printed("maya\n"),
        )
        val outcome = checks(capture).run(todo(item("fix", merged)), item("fix", merged)).single()
        assertEquals(true, outcome.passed)
        assertEquals("PR #5 MERGED", outcome.observed)
        assertEquals(listOf("https://github.com/acme/web/pull/5"), outcome.urls)
        val link = outcome.links.single()
        assertEquals(TodoLinkKind.PR to 5, link.kind to link.number)
        assertEquals("1234567890abc", link.sha, "a merged PR is bound with its merge commit")
        assertEquals(
            listOf(
                "gh", "pr", "list", "--repo", "acme/web", "--state", "all", "--search", "01234567 fix in:body",
                "--json", "number,state,url,mergeCommit,author,body", "--limit", "20",
            ),
            capture.commands.first(),
        )
        assertEquals(20_000, capture.timeouts.first(), "gh is bounded")
    }

    @Test
    fun aPullRequestNotMergedOrMissingIsNotDone() {
        val open = FakeCapture("gh pr list" to printed("[${ghPr(9, "OPEN", marker("fix"), author = null)}]"))
        val outcome = checks(open).run(todo(item("fix", merged)), item("fix", merged)).single()
        assertEquals(false to "PR #9 is OPEN", outcome.passed to outcome.observed)
        assertEquals(null, outcome.links.single().sha, "an open PR is bound too, without a merge commit")
        val none = FakeCapture("gh pr list" to printed("[]"), "gh api user" to printed("maya"))
        assertEquals(
            listOf(false to "No pull request of acme/web carries this item's marker: open it with the marker from " +
                "todo_start in its body."),
            observed(none, item("fix", merged)),
        )
    }

    @Test
    fun aLinkedPullRequestIsReadByItsAddress() {
        val capture = FakeCapture("gh pr view" to printed(ghPr(7, "MERGED", oid = "abc")))
        val linked = item("fix", merged, links = listOf(prLink()))
        assertEquals(listOf(true to "PR #7 MERGED"), observed(capture, linked))
        assertEquals(
            listOf(
                "gh", "pr", "view", "https://github.com/acme/web/pull/7", "--json",
                "number,state,url,mergeCommit,author",
            ),
            capture.commands.single(),
        )
        assertEquals(
            listOf(null to "unknown: gh could not read https://github.com/acme/web/pull/7"),
            observed(FakeCapture("gh pr view" to printed("", code = 1)), linked),
        )
    }

    @Test
    fun aClosedPullRequestEndsAPrClosedCheck() {
        val closed = TodoCheck.PrClosed("acme/web", "superseded")
        val answer = { state: String ->
            FakeCapture("gh pr list" to printed("[${ghPr(4, state, marker("old"))}]"), "gh api user" to printed("maya"))
        }
        val done = checks(answer("CLOSED")).run(todo(item("old", closed)), item("old", closed)).single()
        assertEquals(true to "PR #4 closed without merge", done.passed to done.observed)
        assertEquals(1, done.links.size)
        val notYet = checks(answer("MERGED")).run(todo(item("old", closed)), item("old", closed)).single()
        assertEquals(false to "PR #4 is MERGED", notYet.passed to notYet.observed)
        assertTrue(notYet.links.isEmpty(), "a PR that was merged is not bound to a pr_closed item")
        assertEquals(
            listOf(false to "No pull request of acme/web carries this item's marker."),
            observed(FakeCapture("gh pr list" to printed("[]")), item("old", closed)),
        )
    }

    @Test
    fun ghThatFailsTimesOutOrAnswersAnotherShapeIsUnknown() {
        val failing = listOf(
            printed("", code = 1) to "unknown: gh could not search the pull requests of acme/web",
            CaptureResult.TimedOut to "unknown: gh could not search the pull requests of acme/web",
            printed("{\"not\":\"a list\"}") to "unknown: the check could not be read (",
        )
        for ((answer, said) in failing) {
            val (passed, text) = observed(FakeCapture("gh pr list" to answer), item("fix", merged)).single()
            assertEquals(null, passed)
            assertTrue(text.startsWith(said), text)
        }
    }

    @Test
    fun theTokensLoginIsAskedOnce() {
        val capture = FakeCapture("gh pr list" to printed("[]"), "gh api user" to printed("maya"))
        val both = checks(capture)
        val list = todo(item("a", merged), item("b", merged))
        both.run(list, list.items[0])
        both.run(list, list.items[1])
        assertEquals(1, capture.commands.count { it.take(3) == listOf("gh", "api", "user") })
    }

    @Test
    fun aReleaseContainsTheMergeOfTheItemItNames() {
        val release = TodoCheck.ReleaseContains("acme/web", of = "fix")
        val capture = FakeCapture(
            "gh release list" to printed("""[{"tagName":"v3"},{"tagName":"web/v2"},{"tagName":"v1"}]"""),
            "gh api repos/acme/web/compare/abcdef1234567...v3" to printed("ahead\n"),
            "gh api repos/acme/web/compare/abcdef1234567...web%2Fv2" to printed("identical"),
            "gh api repos/acme/web/compare/abcdef1234567...v1" to printed("behind"),
        )
        val list = todo(item("fix", merged, links = listOf(prLink())), item("ship", release))
        val outcome = checks(capture).run(list, list.items[1]).single()
        assertEquals(true to "release web/v2 contains abcdef1", outcome.passed to outcome.observed)
        val bound = outcome.links.single()
        assertEquals(TodoLinkKind.RELEASE to "web/v2", bound.kind to bound.tag)
        assertEquals("https://github.com/acme/web/releases/tag/web/v2", bound.ref)
        assertEquals(4, capture.commands.size, "the comparisons stop at the first release without the merge")
    }

    @Test
    fun aReleaseOfACommitOrWithoutAnyIsJudgedAsSuch() {
        val ofCommit = TodoCheck.ReleaseContains("acme/web", commit = "fedcba9876")
        val none = FakeCapture(
            "gh release list" to printed("""[{"tagName":"v1"}]"""),
            "gh api repos/acme/web/compare/fedcba9876...v1" to printed("behind"),
        )
        assertEquals(
            listOf(false to "no published release of acme/web contains fedcba9"),
            observed(none, item("s", ofCommit)),
        )
        val cases = listOf(
            FakeCapture() to "unknown: gh could not list the releases of acme/web",
            FakeCapture("gh release list" to printed("""[{"tagName":"v1"}]""")) to "unknown: gh could not compare v1",
        )
        for ((capture, said) in cases) assertEquals(listOf(null to said), observed(capture, item("s", ofCommit)))
        val nothing = TodoCheck.ReleaseContains("acme/web")
        assertEquals(listOf(null to "unknown: no item or commit named"), observed(FakeCapture(), item("s", nothing)))
    }

    @Test
    fun theMergeOfAnItemIsItsLinkOrFoundByItsMarker() {
        val release = TodoCheck.ReleaseContains("acme/other", of = "fix")
        val routes = arrayOf(
            "gh release list" to printed("""[{"tagName":"v1"}]"""),
            "gh api repos/acme/other/compare/0ddba11...v1" to printed("ahead"),
            "gh api user" to printed("maya"),
        )
        // linked without its merge commit: read by its address
        val viewed = FakeCapture("gh pr view" to printed(ghPr(7, "MERGED", oid = "0ddba11")), *routes)
        val linked = todo(item("fix", merged, links = listOf(prLink(sha = null))), item("ship", release))
        assertEquals(true, checks(viewed).run(linked, linked.items[1]).single().passed)
        // not linked: searched in the repository of its pr_merged check
        val pr = printed("[${ghPr(8, "MERGED", marker("fix"), oid = "0ddba11")}]")
        val searched = FakeCapture("gh pr list --repo acme/web" to pr, *routes)
        val found = todo(item("fix", merged), item("ship", release))
        assertEquals(true, checks(searched).run(found, found.items[1]).single().passed)
        // an item without a pr_merged check: searched in the release's repository
        val other = FakeCapture("gh pr list --repo acme/other" to pr, *routes)
        val bare = todo(item("fix", TodoCheck.Attested("done")), item("ship", release))
        assertEquals(true, checks(other).run(bare, bare.items[1]).single().passed)
    }

    @Test
    fun aMergeThatIsNotThereYetIsUnknown() {
        val release = TodoCheck.ReleaseContains("acme/web", of = "fix")
        val notMerged = "unknown: the pull request of item fix is not merged yet"
        val cases = listOf(
            todo(item("ship", release)) to "unknown: item fix does not exist",
            todo(item("fix", merged), item("ship", release)) to notMerged,
            todo(item("fix", merged, links = listOf(prLink(sha = null))), item("ship", release)) to notMerged,
            todo(item("fix", merged, links = listOf(prLink(9, sha = null))), item("ship", release)) to notMerged,
        )
        val capture = FakeCapture(
            "gh pr view https://github.com/acme/web/pull/7" to printed(ghPr(7, "OPEN")),
            "gh pr view https://github.com/acme/web/pull/9" to printed(ghPr(9, "MERGED")),
            "gh pr list" to printed("[]"),
            "gh api user" to printed("maya"),
        )
        for ((list, said) in cases) {
            val outcome = checks(capture).run(list, list.items.last()).single()
            assertEquals(null to said, outcome.passed to outcome.observed)
        }
    }

    @Test
    fun ciIsGreenWhenEveryRunOnTheCommitCompletedGreen() {
        val ci = TodoCheck.CiGreen("acme/web", ref = "abcdef1234")
        val runs = { json: String -> FakeCapture("gh run list" to printed(json)) }
        val cases = listOf(
            "[]" to (false to "no CI run on abcdef1 yet"),
            """[{"status":"completed","conclusion":"failure"},{"status":"in_progress"}]""" to
                (false to "CI on abcdef1: 1 run(s) failed"),
            """[{"status":"completed","conclusion":"success"},{"status":"queued"}]""" to
                (false to "CI on abcdef1: 1 run(s) still running"),
            """[{"status":"completed","conclusion":"success"},{"status":"completed","conclusion":"skipped"},""" +
                """{"status":"completed","conclusion":"neutral"}]""" to (true to "CI on abcdef1: 3 run(s) green"),
        )
        for ((json, expected) in cases) assertEquals(listOf(expected), observed(runs(json), item("ci", ci)), json)
        val capture = runs("[]")
        checks(capture).run(todo(item("ci", ci)), item("ci", ci))
        assertEquals(
            listOf("gh", "run", "list", "--repo", "acme/web", "--commit", "abcdef1234", "--json",
                "status,conclusion,workflowName", "--limit", "50"),
            capture.commands.single(),
        )
    }

    @Test
    fun ciOfOneWorkflowOrOfAnItemsMerge() {
        val runs = """[{"status":"completed","conclusion":"success","workflowName":"Build"},""" +
            """{"status":"completed","conclusion":"failure","workflowName":"Nightly"}]"""
        val build = TodoCheck.CiGreen("acme/web", ref = "abcdef1234", workflow = "Build")
        assertEquals(
            listOf(true to "CI on abcdef1: 1 run(s) green"),
            observed(FakeCapture("gh run list" to printed(runs)), item("ci", build)),
        )
        val ofItem = TodoCheck.CiGreen("acme/web", of = "fix")
        val list = todo(item("fix", merged, links = listOf(prLink())), item("ci", ofItem))
        val capture = FakeCapture("gh run list" to printed(runs))
        assertEquals(false, checks(capture).run(list, list.items[1]).single().passed)
        assertEquals("abcdef1234567", capture.commands.single()[6], "the runs of the item's merge commit")
        assertEquals(
            listOf(null to "unknown: no item or commit named"),
            observed(FakeCapture(), item("ci", TodoCheck.CiGreen("acme/web"))),
        )
        assertEquals(
            listOf(null to "unknown: gh could not list the runs of acme/web"),
            observed(FakeCapture(), item("ci", build)),
        )
    }

    @Test
    fun anAttestedCheckIsTheAgentsWordMasked() {
        val attested = TodoCheck.Attested("the migration ran")
        val list = todo(item("m", attested))
        val token = "ghp_abcdefghijklmnopqrstuvwxyz"
        val said = checks(FakeCapture()).run(list, list.items[0], "ran it with $token", listOf("https://x"))
        assertEquals(true to "attested: ran it with [REDACTED]", said.single().passed to said.single().observed)
        assertEquals(listOf("https://x"), said.single().urls)
        for (text in listOf(null, "  ")) {
            val missing = checks(FakeCapture()).run(list, list.items[0], text).single()
            assertEquals(
                false to "This check is attested: give proof.text (and URLs) to todo_complete.",
                missing.passed to missing.observed,
            )
        }
    }

    @Test
    fun theServersChecksAndUnknownTypesAreNeverDoneHere() {
        val unknown = TodoCheck.Unknown("pr_reviewed", JsonObject(emptyMap()))
        assertEquals(
            listOf(
                null to "verified by the server",
                null to "verified by the server",
                null to "unknown: this ostrom does not know the check pr_reviewed",
            ),
            observed(FakeCapture(), item("x", TodoCheck.ArtifactPublished(), TodoCheck.WaitAnswered(), unknown)),
        )
    }

    @Test
    fun theCheapChecksAndTheReports() {
        assertTrue(TodoChecks.cheap(merged))
        assertTrue(TodoChecks.cheap(TodoCheck.ReleaseContains("a/b", of = "x")))
        assertTrue(TodoChecks.cheap(TodoCheck.ArtifactPublished()))
        assertTrue(TodoChecks.cheap(TodoCheck.WaitAnswered()))
        assertFalse(TodoChecks.cheap(TodoCheck.Command("true")))
        val long = "x".repeat(2_500)
        val urls = List(12) { "https://u/$it" }
        val reports = TodoChecks.reports(listOf(CheckOutcome(true, long, urls), CheckOutcome(null, "?")))
        assertEquals(TodoCheckReport(1, null, "?"), reports[1])
        assertEquals(2_000 to 10, reports[0].observed.length to reports[0].urls.size)
    }
}
