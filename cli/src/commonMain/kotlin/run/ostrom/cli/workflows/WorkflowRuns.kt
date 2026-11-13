// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowInput
import kotlinx.serialization.json.JsonObject

/** The statuses after which a run no longer moves. */
internal val TERMINAL = setOf(RunStatus.SUCCEEDED.value, RunStatus.FAILED.value, RunStatus.CANCELLED.value)

/** The most `workflows runs` fetches in one call; older runs are reached with `--before`. */
internal const val MAX_LISTED_RUNS = 500

/** The most one request of the server returns (`limit` of the contract). */
private const val PAGE = 100

/** Seconds between two reads of `--wait`: the default and the least. */
internal const val DEFAULT_INTERVAL = 3
internal const val MIN_INTERVAL = 2

/** Minutes `--wait` follows a run: the default, the least and the most. */
internal const val DEFAULT_TIMEOUT = 60
internal val TIMEOUT_MINUTES = 1..1440

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND

/** A read that failed this many times in a row is not tried again. */
private const val MAX_RETRIES = 3

private val TRANSIENT = setOf(408, 429)
private const val SERVER_ERRORS = 500

/** The workflow's declared inputs, as the contract types them. */
internal fun inputsOf(workflow: JsonObject): List<WorkflowInput> =
    workflow.list("inputs").map { OstromJson.decodeFromJsonElement(WorkflowInput.serializer(), it) }

/**
 * The inputs to send, checked against the declared ones: an unknown, repeated, too long or missing required input is
 * named here, so that nothing reaches the server. An input with a default is left to the server.
 */
internal fun resolveInputs(workflow: JsonObject, given: List<Pair<String, String>>): Map<String, String> {
    val name = workflow.text("name") ?: "The workflow"
    val declared = inputsOf(workflow).associateBy { it.id }
    val values = linkedMapOf<String, String>()
    given.forEach { (key, value) ->
        val input = declared[key] ?: refuse(
            "\"$name\" has no input \"$key\"." +
                if (declared.isEmpty()) " It takes no inputs." else " Its inputs: ${declared.keys.joinToString(", ")}.",
        )
        if (key in values) refuse("Input $key is given twice: pass --input $key=... once.")
        if (value.length > input.maxLength) {
            refuse("Input $key is ${value.length} characters long; the most it takes is ${input.maxLength}.")
        }
        values[key] = value
    }
    val missing = declared.values.filter { it.required && it.default == null && it.id !in values }
    if (missing.isNotEmpty()) {
        refuse(
            "\"$name\" needs ${missing.joinToString(", ") { "${it.id} (${it.label})" }}: " +
                "pass ${missing.joinToString(" ") { "--input ${it.id}=..." }}.",
        )
    }
    return values
}

private val REPOSITORY = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")

/** [value] when it names a repository as `OWNER/NAME`, else a usage message. */
internal fun requireRepository(value: String): String {
    if (!REPOSITORY.matches(value)) refuse("\"$value\" is not a repository: write OWNER/NAME.")
    return value
}

/** What `workflows runs` asks: the filters of the list and how many runs. */
internal class RunsQuery(
    val all: Boolean,
    val workflow: String?,
    val status: String?,
    val before: String?,
    val limit: Int,
)

/** The runs of the page, then the cursor of the next one when more exist. */
internal class RunsPage(val runs: List<JsonObject>, val next: String?)

/** Reads the runs page after page, up to the limit asked. [page] reads one: its query (`?...`), then the body. */
internal fun listRuns(query: RunsQuery, page: (String) -> JsonObject): RunsPage {
    if (query.limit !in 1..MAX_LISTED_RUNS) refuse("--limit is 1 to $MAX_LISTED_RUNS.")
    val runs = mutableListOf<JsonObject>()
    var cursor = query.before
    do {
        val read = page(queryString(query, cursor, minOf(PAGE, query.limit - runs.size)))
        val items = read.list("items").filterIsInstance<JsonObject>()
        runs += items
        // a page without a run is the end, whatever cursor it names: never asked again forever
        cursor = read.text("next")?.takeIf { it.isNotEmpty() && items.isNotEmpty() }
    } while (cursor != null && runs.size < query.limit)
    return RunsPage(runs.take(query.limit), cursor)
}

private fun queryString(query: RunsQuery, cursor: String?, size: Int): String = "?" + listOfNotNull(
    query.status?.let { "status=$it" },
    if (query.all) "all=true" else null,
    cursor?.let { "before=${Routes.encode(it)}" },
    "limit=$size",
).joinToString("&")

/** The status to ask for: `done` is `succeeded`. */
internal fun statusFor(choice: String): String = if (choice == "done") RunStatus.SUCCEEDED.value else choice

/** A read of a run failed for a reason that does not pass, or went on failing: [answer] says why. */
internal class RunReadFailed(val answer: ServerAnswer.Failed) : Exception(answer.message)

/** What `--wait` followed: the run as last read, and whether the timeout came first. */
internal class Followed(val run: JsonObject, val timedOut: Boolean)

/**
 * Follows a run with `--wait`: reads it every interval until it ends or the timeout passes, saying each step that
 * starts, ends or begins to wait for a person. A read that fails for a reason that may pass (no answer, a 5xx, 408,
 * 429) is tried again up to three times in a row; a refusal is not.
 *
 * @param read Reads the run.
 * @param pause Waits the given milliseconds (virtual in tests).
 * @param emit Says a line.
 */
internal class RunFollower(
    private val read: suspend () -> ServerAnswer,
    private val pause: suspend (Long) -> Unit,
    private val emit: (String) -> Unit,
) {
    suspend fun follow(first: JsonObject, intervalSeconds: Int, timeoutMinutes: Int): Followed {
        val seen = mutableMapOf<Int, String>()
        var run = first
        var waited = 0L
        var failures = 0
        while (true) {
            changes(seen, run).forEach(emit)
            if (run.text("status") in TERMINAL) return Followed(run, false)
            if (waited >= timeoutMinutes * MINUTE) return Followed(run, true)
            pause(intervalSeconds * SECOND)
            waited += intervalSeconds * SECOND
            when (val answer = read()) {
                is ServerAnswer.Ok -> run = answer.body.also { failures = 0 }
                is ServerAnswer.Failed -> {
                    failures += 1
                    if (!transient(answer) || failures > MAX_RETRIES) throw RunReadFailed(answer)
                }
            }
        }
    }

    private fun transient(answer: ServerAnswer.Failed): Boolean =
        answer.status.let { it == null || it >= SERVER_ERRORS || it in TRANSIENT }
}
