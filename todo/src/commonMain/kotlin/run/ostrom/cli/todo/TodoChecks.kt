// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoCheckReport
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoLinkKind

/**
 * What a check observed, ready to report: [passed] null is "unknown", which never counts as done. [links] are what it
 * found (a PR by its marker, a release), to bind to the item so that Ostrom and the next check can cite them.
 */
class CheckOutcome(
    val passed: Boolean?,
    val observed: String,
    val urls: List<String> = emptyList(),
    val links: List<LinkTodoItemRequest> = emptyList(),
)

/**
 * Runs the typed checks of a todo item from the agent's environment, which holds the GitHub token (TeamCode's
 * `TodoChecks`, docs/todos.md "Where each check runs"): `gh` for pull requests, releases and CI, a shell for `command`.
 * The condition is the item Ostrom stored, never one the agent gives, so the agent chooses neither the command nor
 * the moment. Ostrom verifies `artifact_published` and `wait_answered` itself; `attested` is the agent's word.
 */
class TodoChecks(private val github: GitHub, private val commands: CommandCheck) {
    /**
     * Every check of [item], in order. [attested] and [urls] are the agent's proof, for an `attested` check only.
     */
    fun run(
        list: Todo,
        item: TodoItem,
        attested: String? = null,
        urls: List<String> = emptyList(),
    ): List<CheckOutcome> =
        item.doneWhen.checks.map { check ->
            try {
                evaluate(list, item, check, attested, urls)
            } catch (e: UnknownCheck) {
                CheckOutcome(null, "unknown: ${e.message}")
            } catch (e: IllegalArgumentException) {
                // a `gh` answer of another shape (SerializationException is one)
                CheckOutcome(null, "unknown: the check could not be read (${e::class.simpleName})")
            }
        }

    private fun evaluate(list: Todo, item: TodoItem, check: TodoCheck, attested: String?, urls: List<String>) =
        when (check) {
            is TodoCheck.PrMerged -> prMerged(list, item, check.repo)
            is TodoCheck.PrClosed -> prClosed(list, item, check.repo)
            is TodoCheck.ReleaseContains -> releaseContains(list, check)
            is TodoCheck.CiGreen -> ciGreen(list, check)
            is TodoCheck.Command -> commands.run(list, check)
            is TodoCheck.Attested -> attested(attested, urls)
            // the server verifies these through the item's links: what is reported here is ignored
            is TodoCheck.ArtifactPublished, is TodoCheck.WaitAnswered -> CheckOutcome(null, "verified by the server")
            is TodoCheck.Unknown -> CheckOutcome(null, "unknown: this ostrom does not know the check ${check.type}")
        }

    private fun prMerged(list: Todo, item: TodoItem, repo: String): CheckOutcome {
        val pr = github.pullRequest(list, item, repo) ?: return CheckOutcome(
            false,
            "No pull request of $repo carries this item's marker: open it with the marker from todo_start in its body.",
        )
        val found = listOf(link(pr))
        return if (pr.state == GitHub.MERGED) {
            CheckOutcome(true, "PR #${pr.number} MERGED", listOf(pr.url), found)
        } else {
            CheckOutcome(false, "PR #${pr.number} is ${pr.state}", listOf(pr.url), found)
        }
    }

    private fun prClosed(list: Todo, item: TodoItem, repo: String): CheckOutcome {
        val pr = github.pullRequest(list, item, repo)
            ?: return CheckOutcome(false, "No pull request of $repo carries this item's marker.")
        return if (pr.state == GitHub.CLOSED) {
            CheckOutcome(true, "PR #${pr.number} closed without merge", listOf(pr.url), listOf(link(pr)))
        } else {
            CheckOutcome(false, "PR #${pr.number} is ${pr.state}", listOf(pr.url))
        }
    }

    private fun link(pr: GhPullRequest) =
        LinkTodoItemRequest(kind = TodoLinkKind.PR, ref = pr.url, number = pr.number, sha = pr.mergeCommit?.oid)

    private fun releaseContains(list: Todo, check: TodoCheck.ReleaseContains): CheckOutcome {
        val sha = check.commit ?: github.mergeSha(list, check.of ?: throw UnknownCheck(NO_ITEM), check.repo)
        val tag = github.earliestRelease(check.repo, sha)
            ?: return CheckOutcome(false, "no published release of ${check.repo} contains ${sha.take(SHORT_SHA)}")
        val url = "https://github.com/${check.repo}/releases/tag/$tag"
        val bound = LinkTodoItemRequest(kind = TodoLinkKind.RELEASE, ref = url, tag = tag)
        return CheckOutcome(true, "release $tag contains ${sha.take(SHORT_SHA)}", listOf(url), listOf(bound))
    }

    private fun ciGreen(list: Todo, check: TodoCheck.CiGreen): CheckOutcome {
        val sha = check.ref ?: github.mergeSha(list, check.of ?: throw UnknownCheck(NO_ITEM), check.repo)
        val runs = github.runs(check.repo, sha, check.workflow)
        val short = sha.take(SHORT_SHA)
        val pending = runs.count { it.status != COMPLETED }
        val failed = runs.count { it.status == COMPLETED && it.conclusion !in GREEN }
        return when {
            runs.isEmpty() -> CheckOutcome(false, "no CI run on $short yet")
            failed > 0 -> CheckOutcome(false, "CI on $short: $failed run(s) failed")
            pending > 0 -> CheckOutcome(false, "CI on $short: $pending run(s) still running")
            else -> CheckOutcome(true, "CI on $short: ${runs.size} run(s) green")
        }
    }

    private fun attested(text: String?, urls: List<String>): CheckOutcome = if (text.isNullOrBlank()) {
        CheckOutcome(false, "This check is attested: give proof.text (and URLs) to todo_complete.")
    } else {
        CheckOutcome(true, "attested: ${Secrets.mask(text).take(MAX_ATTESTED)}", urls)
    }

    /** Limits and words of the checks. */
    companion object {
        /** The cheap checks, one `gh` call or none: the Stop hook runs them for every open item. */
        fun cheap(check: TodoCheck): Boolean = check is TodoCheck.PrMerged || check is TodoCheck.ReleaseContains ||
            check is TodoCheck.ArtifactPublished || check is TodoCheck.WaitAnswered

        /** The reports Ostrom wants, by position in the stored condition, each bounded as the contract says. */
        fun reports(outcomes: List<CheckOutcome>): List<TodoCheckReport> = outcomes.mapIndexed { index, outcome ->
            TodoCheckReport(index, outcome.passed, outcome.observed.take(MAX_OBSERVED), outcome.urls.take(MAX_URLS))
        }

        private const val NO_ITEM = "no item or commit named"
        private const val COMPLETED = "completed"
        private const val SHORT_SHA = 7
        private const val MAX_ATTESTED = 500
        private const val MAX_OBSERVED = 2_000
        private const val MAX_URLS = 10
        private val GREEN = setOf("success", "skipped", "neutral")
    }
}
