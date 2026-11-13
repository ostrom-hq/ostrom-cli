// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoLinkKind

/** A check that could not be read (`gh` down, a value not linked yet): reported unknown, never done. */
class UnknownCheck(message: String) : Exception(message)

/**
 * A pull request as `gh` shows it: [state] is OPEN, CLOSED or MERGED; [mergeSha] its merge commit once merged;
 * [author] its author's login (null for a deleted account).
 */
class GhPullRequest(
    val number: Int,
    val url: String,
    val state: String,
    val mergeSha: String?,
    val author: String?,
    val body: String?,
)

/** A workflow run of `gh run list`. */
class GhRun(val status: String?, val conclusion: String?, val workflowName: String?)

/**
 * GitHub as the agent's own `gh` sees it, with the agent's token: the checks run from the agent's machine (TeamCode's
 * `TodoChecks`, docs/todos.md "Where each check runs"), Ostrom has no GitHub reader of its own. Every call is bounded;
 * one that fails, times out or answers something else is an [UnknownCheck].
 */
class GitHub(private val capture: ProcessCapture) {
    private var me: String? = null

    /** `gh <arguments>`'s output, or null when it failed, timed out or could not start. */
    private fun gh(vararg arguments: String): String? {
        val result = capture.capture(listOf("gh") + arguments, GH_TIMEOUT_MILLIS)
        return (result as? CaptureResult.Exited)?.takeIf { it.code == 0 }?.output
    }

    /** The token's login, asked once: a PR found by its marker must be the agent's own. */
    private fun login(): String? =
        me ?: gh("api", "user", "--jq", ".login")?.let { answer -> answer.trim().also { me = it } }

    /**
     * The pull request of [item]: the one it is linked to, else the one of [repo] whose body carries its marker and
     * whose author is the token's login (the newest when several); null when none does.
     */
    fun pullRequest(list: Todo, item: TodoItem, repo: String): GhPullRequest? {
        // a linked PR counts only in the check's repository: another repository's PR proves nothing here
        val linked = item.links.firstOrNull { it.kind == TodoLinkKind.PR && it.ref.startsWith(pullsOf(repo)) }
        return if (linked != null) viewed(linked.ref) else searched(list, item, repo)
    }

    private fun viewed(ref: String): GhPullRequest {
        val answer = gh("pr", "view", ref, "--json", PR_FIELDS) ?: throw UnknownCheck("gh could not read $ref")
        return pullRequestOf(Json.parseToJsonElement(answer))
    }

    private fun searched(list: Todo, item: TodoItem, repo: String): GhPullRequest? {
        val marker = marker(list, item.id)
        // the search narrows the candidates; the exact marker in the body decides, so another list's PR is never taken
        val search = "${list.id.take(LIST_PREFIX)} ${item.id} in:body"
        val answer = gh("pr", "list", "--repo", repo, "--state", "all", "--search", search, "--json", "$PR_FIELDS,body",
            "--limit", SEARCH_LIMIT) ?: throw UnknownCheck("gh could not search the pull requests of $repo")
        val found = objects(answer).map(::pullRequestOf).filter { it.body.orEmpty().contains(marker) }
        if (found.isEmpty()) return null
        // only the agent's own PR: when the token's login cannot be read, nothing found can be told apart
        val login = login() ?: throw UnknownCheck("gh could not say whose token this is (gh api user)")
        return found.filter { it.author == login }.maxByOrNull { it.number }
    }

    /** The merge commit of the pull request of item [of]: from its link, else found by its marker. */
    fun mergeSha(list: Todo, of: String, repo: String): String {
        val target = list.items.firstOrNull { it.id == of } ?: throw UnknownCheck("item $of does not exist")
        val linked = target.links.firstOrNull { it.kind == TodoLinkKind.PR }?.sha
        if (linked != null) return linked
        val ending = target.doneWhen.checks.filterIsInstance<TodoCheck.PrMerged>().firstOrNull()
        val prRepo = if (ending == null) repo else ending.repo
        val merged = pullRequest(list, target, prRepo)?.takeIf { it.state == MERGED }?.mergeSha
        return merged ?: throw UnknownCheck("the pull request of item $of is not merged yet")
    }

    /**
     * The earliest published, non-draft release of [repo] whose tag is at or ahead of [sha]: the 30 newest are listed,
     * at most 15 compared, newest first, until one does not contain it. Null when none does.
     */
    fun earliestRelease(repo: String, sha: String): String? {
        val listed = gh("release", "list", "--repo", repo, "--exclude-drafts", "--limit", RELEASES, "--json", "tagName")
            ?: throw UnknownCheck("gh could not list the releases of $repo")
        var earliest: String? = null
        for (release in objects(listed).take(MAX_COMPARES)) {
            val tag = release.required("tagName")
            val status = gh("api", "repos/$repo/compare/$sha...${tag.replace("/", "%2F")}", "--jq", ".status")
                ?: throw UnknownCheck("gh could not compare $tag")
            if (status.trim() !in CONTAINS) break
            earliest = tag
        }
        return earliest
    }

    /** The workflow runs of [repo] on [sha] (of [workflow] only, when given). */
    fun runs(repo: String, sha: String, workflow: String?): List<GhRun> {
        val answer = gh("run", "list", "--repo", repo, "--commit", sha, "--json", "status,conclusion,workflowName",
            "--limit", RUNS) ?: throw UnknownCheck("gh could not list the runs of $repo")
        return objects(answer)
            .map { GhRun(it.text("status"), it.text("conclusion"), it.text("workflowName")) }
            .filter { workflow == null || it.workflowName == workflow }
    }

    /** What `gh` is asked and how long it may take. */
    companion object {
        /** A pull request merged. */
        const val MERGED = "MERGED"

        /** A pull request closed without merge. */
        const val CLOSED = "CLOSED"

        /** The marker of an item's pull request, as `startTodoItem` returns it. */
        fun marker(list: Todo, itemId: String) = "<!-- ostrom-todo: ${list.id.take(LIST_PREFIX)}/$itemId -->"

        /** The address every pull request of [repo] starts with. */
        private fun pullsOf(repo: String) = "https://github.com/$repo/pull/"

        private const val GH_TIMEOUT_MILLIS = 20_000
        private const val LIST_PREFIX = 8
        private const val MAX_COMPARES = 15
        private const val PR_FIELDS = "number,state,url,mergeCommit,author"
        private const val SEARCH_LIMIT = "20"
        private const val RELEASES = "30"
        private const val RUNS = "50"
        private val CONTAINS = setOf("ahead", "identical")
    }
}

/** A text field of `gh`'s JSON, or null when absent or null. */
private fun JsonObject.text(key: String): String? = this[key]?.let { it.jsonPrimitive.contentOrNull }

/** A text field `gh` always gives: an answer without it is another shape, so the check is unknown. */
private fun JsonObject.required(key: String): String = text(key) ?: throw UnknownCheck("gh answered without $key")

/** [field] of the object under [key] (`mergeCommit.oid`, `author.login`), or null. */
private fun JsonObject.inner(key: String, field: String): String? = (this[key] as? JsonObject)?.text(field)

/**
 * `gh`'s JSON read by hand, its unknown fields ignored. Another shape throws an [IllegalArgumentException] (a JSON
 * that is no list of objects, a number that is not one) or an [UnknownCheck] (a field missing): unknown either way.
 */
private fun objects(answer: String): List<JsonObject> =
    Json.parseToJsonElement(answer).jsonArray.map { it.jsonObject }

private fun pullRequestOf(element: JsonElement): GhPullRequest {
    val pr = element.jsonObject
    return GhPullRequest(
        number = pr.required("number").toInt(),
        url = pr.required("url"),
        state = pr.required("state"),
        mergeSha = pr.inner("mergeCommit", "oid"),
        author = pr.inner("author", "login"),
        body = pr.text("body"),
    )
}
