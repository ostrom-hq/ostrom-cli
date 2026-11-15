// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/cloud/
// CloudConversationCommands.kt.
@file:Suppress("TooManyFunctions") // TeamCode's file: the transcript lines and the four commands.

package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.AnswerCloudPermissionRequest
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventPage
import run.ostrom.core.api.models.CloudEventTypes
import run.ostrom.core.api.models.CloudInput
import run.ostrom.core.api.models.CloudInputStatuses
import run.ostrom.core.api.models.CloudQuestionAnswer
import run.ostrom.core.api.models.SendCloudMessageRequest
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

// `ostrom cloud logs|send|interrupt|answer`, as TeamCode's (`runners.cloud.conversation.v1`, `permissions.v1`).
// TeamCode's `cloud attach` holds an attachment lease (cloud.attach.v1), which Ostrom does not serve: not ported;
// `ostrom cloud attach` stays the terminal attach.
//
// The one adaptation: the CLI's HTTP layer reads JSON, not server-sent events, so `logs --follow` asks
// `GET .../events?after=` again every two seconds (as `ostrom sessions show --follow`) instead of holding the stream;
// it ends, as the stream does, once the session is terminated.

/** What a conversation command says to a server that runs no conversation session. */
internal const val NO_CLOUD_CONVERSATION =
    "This Ostrom server runs no conversation session: ask your admin to update it."

/** What `cloud answer` says to a server that relays no permission request. */
internal const val NO_CLOUD_PERMISSIONS =
    "This Ostrom server relays no permission request of a cloud agent: ask your admin to update it."

private fun CliDependencies.conversationAccess(server: String?, org: String?): OrgAccess =
    cloudAccess(server, org, ServerFeatures.RUNNERS_CLOUD_CONVERSATION, NO_CLOUD_CONVERSATION)

private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.isTrue() = (this as? JsonPrimitive)?.booleanOrNull == true
private fun String.preview(limit: Int) =
    replace('\n', ' ').let { if (it.length > limit) it.take(limit - 1) + "…" else it }

/**
 * A question request's questions and options in full, each with the index `ostrom cloud answer --answer` takes:
 * `? 0: "Red or blue?" (0: "Red" / 1: "Blue")`, marked `several` when more than one option may be chosen.
 */
private fun questions(input: JsonElement?): String =
    ((input as? JsonObject)?.get("questions") as? JsonArray).orEmpty().withIndex().joinToString("") { (index, item) ->
        val question = item as? JsonObject ?: JsonObject(emptyMap())
        val options = (question["options"] as? JsonArray).orEmpty().mapIndexed { option, label ->
            "$option: ${(((label as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull ?: "").quoted()}"
        }
        val several = question["multiSelect"].isTrue()
        val listed = if (options.isEmpty()) "" else " (${options.joinToString(" / ").printable()})"
        val asked = (question.str("question") ?: "").quoted().printable()
        "\n    ? $index: $asked$listed" + if (several) " several" else ""
    }

/** Micro-dollars as `$0.003253`; null for a negative amount, which no runner reports. */
internal fun dollars(micros: Long): String? =
    if (micros < 0) null else "$" + "${micros / MICROS}.${(micros % MICROS).toString().padStart(MICRO_DIGITS, '0')}"

/** `HH:MM:SS` of an ISO-8601 time, the time as given when it is shorter. */
private fun clockOf(at: String) = if (at.length >= TIME_END) at.substring(TIME_START, TIME_END) else at

/**
 * One readable line (or block) per event; unknown types are shown by name so newer servers stay readable. A subagent's
 * events (`parentToolUseId`) are indented under the tool call that started it.
 */
@Suppress("CyclomaticComplexMethod") // one branch per event type, as TeamCode's
internal fun CloudEvent.render(): String {
    val body = when (type) {
        CloudEventTypes.USER_MESSAGE -> "> " + (data.str("text") ?: "")
        CloudEventTypes.AGENT_MESSAGE -> data.str("text") ?: ""
        CloudEventTypes.TOOL_CALL ->
            "[tool] ${data.str("name") ?: "tool"} ${data["input"]?.toString()?.preview(TOOL_INPUT) ?: ""}".trimEnd()
        CloudEventTypes.TOOL_RESULT -> "[result${if (data["isError"].isTrue()) ", error" else ""}] " +
            (data.str("output") ?: "").preview(TOOL_OUTPUT)
        CloudEventTypes.TURN -> {
            val cost = (data["costMicros"] as? JsonPrimitive)?.longOrNull?.let(::dollars)?.let { ", $it" } ?: ""
            "-- turn ${data.str("phase") ?: "update"}$cost --"
        }
        CloudEventTypes.STATUS -> "[status] " + data.entries.joinToString(", ") { (key, value) ->
            "$key=${(value as? JsonPrimitive)?.contentOrNull ?: value}"
        }
        CloudEventTypes.ERROR ->
            "[error] ${data.str("code") ?: "error"}: ${data.str("message") ?: ""}".trimEnd(' ', ':')
        CloudEventTypes.INPUT -> inputLine()
        CloudEventTypes.PERMISSION_REQUEST -> permissionLine()
        CloudEventTypes.PERMISSION_EXPIRED ->
            "[expired ${data.str("requestId") ?: ""}] ${data.str("message") ?: EXPIRED}".printable()
        CloudEventTypes.TASKS -> (data["tasks"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("description")?.preview(TASK_DESCRIPTION) }
            .let { if (it.isEmpty()) "[tasks] none running" else "[tasks] ${it.size} running: ${it.joinToString()}" }
        else -> "[$type] $data"
    }
    val nested = if (data.str("parentToolUseId") != null) "  ↳ " else ""
    // Every line is agent text: escape sequences never reach the terminal, so none can rewrite a request shown above.
    return "${clockOf(at)} #$seq $nested${body.lines().joinToString("\n") { it.printable() }}"
}

private fun CloudEvent.inputLine(): String {
    val reason = data.str("reason")?.let { ": $it" } ?: ""
    val id = data.str("inputId")?.take(INPUT_ID) ?: ""
    val head = "[${data.str("kind") ?: "message"} $id ${data.str("status") ?: ""}$reason]"
    // A permission answer says which request it answered, how, and who decided.
    val answered = data.str("requestId")?.let { request ->
        val by = data.str("authorId")?.printable() ?: "?"
        if (data.str("status") == CloudInputStatuses.CONSUMED) {
            " ${data.str("behavior") ?: "answer"} ${request.printable()} by $by"
        } else {
            " (an answer to ${request.printable()} by $by, not applied)"
        }
    } ?: ""
    return head + answered
}

private fun CloudEvent.permissionLine(): String {
    val question = data["question"].isTrue()
    // The whole input (bounded by the runner): whoever decides must see everything the tool would do. A question is
    // shown by the indexed lines that follow, not as raw JSON. An input left out of the event shows its preview
    // instead, and can only be denied (the server refuses allow).
    val input = if (question && data["input"] != null) {
        ""
    } else {
        data["input"]?.toString() ?: data.str("inputPreview")?.let { "$it… $CUT" } ?: ""
    }
    val head = "[${if (question) "question" else "permission"} ${data.str("requestId")?.printable() ?: ""}] "
    return head + "${data.str("toolName") ?: "tool"} $input".printable().trimEnd() +
        if (question) questions(data["input"]) else ""
}

/** What `logs` prints for an event: nothing for live text deltas, whose final `message.agent` follows. */
internal fun CloudEvent.printed(): String? = if (shown()) render() else null
internal fun CloudEvent.shown() = type != CloudEventTypes.AGENT_MESSAGE_DELTA

/**
 * The sequence to start after so that the last [tail] events [shown] are printed: deltas are stored as events but
 * not printed, so `last - tail` could print none. Walks back from [last] with `before` pages, at most
 * `tail / 200 + 10` of them, then starts after the oldest event read.
 */
@Suppress("ReturnCount") // the event found, the oldest reached, the pages spent
internal suspend fun tailStart(
    tail: Int,
    last: Long,
    shown: (CloudEvent) -> Boolean,
    before: suspend (Long) -> CloudEventPage,
): Long {
    if (tail == 0) return last
    var cursor = last + 1
    var counted = 0
    repeat(tail / PAGE_LIMIT + EXTRA_PAGES) {
        val page = before(cursor)
        for (event in page.events.asReversed()) if (shown(event) && ++counted == tail) return event.seq - 1
        cursor = page.nextBefore ?: return 0
    }
    return cursor - 1
}

private fun CloudInput.line() =
    listOfNotNull(id, kind, status, reason, if (woke) "woke the session" else null).joinToString("\t")

private fun eventsPath(id: String, query: String) = "${cloudSessionPath(id)}/events?$query"

/** A page of events read from [answer]; a page this CLI cannot read stops with what to do. */
@Suppress("SwallowedException") // its message is said
private fun pageOf(answer: JsonObject): CloudEventPage = try {
    OstromJson.decodeFromJsonElement(CloudEventPage.serializer(), answer)
} catch (error: SerializationException) {
    throw stopWith(ExitCode.FAILED, "ostrom: a transcript page cannot be read (${error.message}): update ostrom.")
}

private suspend fun OrgAccess.page(id: String, query: String): CloudEventPage =
    pageOf(server.get(eventsPath(id, query), signed.token).orStop(signed.serverUrl, "No cloud session $id."))

/**
 * `ostrom cloud logs SESSION [--after N] [--tail N] [--follow] [--json]`: a conversation session's transcript; with
 * `--follow`, new events until the session ends, reconnecting after network failures.
 */
internal class CloudLogsCommand(private val dependencies: CliDependencies) : CliktCommand(name = "logs") {
    override fun help(context: Context) = "Print a session transcript. With --follow, keep printing new events and " +
        "reconnect after network failures."

    private val session by argument("session", help = "The cloud session's id")
    private val after by option("--after", help = "Start after this event sequence (default: the last --tail events)")
        .long()
    private val tail by option("--tail", help = "Events to show before following (default 50)").int()
        .default(DEFAULT_TAIL)
    private val follow by option("-f", "--follow", help = "Keep printing new events until the session ends").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by option("--json", help = "Print events as JSON lines").flag()

    override fun run() {
        if (tail !in 0..MAX_TAIL) throw UsageError("Use --tail between 0 and $MAX_TAIL.")
        val access = dependencies.conversationAccess(server, org)
        val console = dependencies.console
        val print: (CloudEvent) -> Unit = { event ->
            if (json) console.data(OstromJson.encodeToString(CloudEvent.serializer(), event) + "\n")
            else event.printed()?.let { console.out(it + "\n") }
        }
        runBlocking {
            val last = if (after == null) access.page(session, "after=0&limit=1").lastSeq ?: 0L else 0L
            var cursor = after ?: tailStart(tail, last, { json || it.shown() }) {
                access.page(session, "before=$it&limit=$PAGE_LIMIT")
            }
            while (true) {
                val page = access.page(session, "after=$cursor&limit=$PAGE_LIMIT")
                page.events.forEach(print)
                cursor = page.events.lastOrNull()?.seq ?: cursor
                if (page.nextAfter == null) break
            }
            if (follow && !dependencies.followEvents(access, session, cursor, print)) {
                throw ProgramResult(ExitCode.FAILED.code)
            }
        }
    }
}

/**
 * Follows the transcript from [after], asking again every two seconds and with backoff after failures, from the last
 * printed sequence, so no event is printed twice or skipped. Returns `true` when the session ended and `false` when an
 * event cannot be read (asking again would read it again); stops when access was refused.
 */
internal suspend fun CliDependencies.followEvents(
    access: OrgAccess,
    id: String,
    after: Long,
    print: (CloudEvent) -> Unit,
): Boolean {
    val notice = { text: String -> console.err("$text\n") }
    var cursor = after
    var wait = FOLLOW_EVERY
    // Events written just before the session ended may follow the empty page: one more page is read after the end.
    var endSeen = false
    while (true) {
        val answer = access.server.get(eventsPath(id, "after=$cursor&limit=$PAGE_LIMIT"), access.signed.token)
        var more = false
        when (answer) {
            is ServerAnswer.Ok -> {
                val page = try {
                    OstromJson.decodeFromJsonElement(CloudEventPage.serializer(), answer.body)
                } catch (error: SerializationException) {
                    notice(
                        "The event after $cursor cannot be read (${error.message}). Update ostrom, or follow from a " +
                            "later event with `ostrom cloud logs $id --follow --after <sequence>`.",
                    )
                    return false
                }
                for (event in page.events.filter { it.seq > cursor }) {
                    print(event)
                    cursor = event.seq
                }
                wait = FOLLOW_EVERY
                more = page.nextAfter != null
                if (page.events.isEmpty() && (endSeen || ended(access, id))) {
                    if (endSeen) {
                        notice("The session has ended.")
                        return true
                    }
                    endSeen = true
                    more = true
                }
            }
            is ServerAnswer.Failed -> {
                refusedTranscript(answer)?.let { throw stopWith(ExitCode.REFUSED, it) }
                notice("Disconnected; reconnecting from event $cursor.")
            }
        }
        if (!more) {
            sleep(wait)
            if (answer is ServerAnswer.Failed) wait = (wait * 2).coerceAtMost(MAX_BACKOFF)
        }
    }
}

/** What a refusal of the transcript says (401, 403, 404: asking again would not help), or null to ask again. */
private fun refusedTranscript(answer: ServerAnswer.Failed): String? =
    if (answer.status in REFUSALS) {
        "The server refused the transcript (HTTP ${answer.status}${answer.type?.let { ", $it" } ?: ""})."
    } else {
        null
    }

/** True once the session is terminated (or failed): its stream would end there. A failed read is not an end. */
private suspend fun ended(access: OrgAccess, id: String): Boolean {
    val answer = access.server.get(cloudSessionPath(id), access.signed.token)
    if (answer is ServerAnswer.Failed) refusedTranscript(answer)?.let { throw stopWith(ExitCode.REFUSED, it) }
    return (answer as? ServerAnswer.Ok)?.body?.text("status") in ENDED
}

/** Decodes an input the server answered. */
private fun inputOf(body: JsonObject): CloudInput = OstromJson.decodeFromJsonElement(CloudInput.serializer(), body)

/**
 * Sends with one idempotency key and retries network failures, so a retry never delivers the message twice; a
 * refusal (4xx other than 408 and 429) stops at once.
 */
internal suspend fun CliDependencies.sendWithRetry(
    access: OrgAccess,
    id: String,
    request: SendCloudMessageRequest,
    key: String,
): CloudInput {
    val path = "${cloudSessionPath(id)}/messages"
    val body = OstromJson.encodeToJsonElement(SendCloudMessageRequest.serializer(), request).jsonObject
    var wait = FIRST_RETRY
    repeat(RETRIES) {
        val answer = access.server.call("POST", path, body, access.signed.token, WRITE_MILLIS, key)
        if (answer is ServerAnswer.Ok) return inputOf(answer.body)
        val status = (answer as ServerAnswer.Failed).status
        if (status in REFUSED_SENDS) {
            throw answer.refusal(access.signed.serverUrl, "No cloud session $id.")
        }
        console.err("Sending failed; retrying.\n")
        sleep(wait)
        wait *= 2
    }
    val last = access.server.call("POST", path, body, access.signed.token, WRITE_MILLIS, key)
    return inputOf(last.orStop(access.signed.serverUrl, "No cloud session $id."))
}

/**
 * The message `cloud send` sends.
 *
 * `send --file` (`runners.cloud.files.v1`) comes with the files step: it uploads each file first and passes their ids
 * as [fileIds] (at most five), with which the text may be empty.
 */
internal fun messageRequest(text: String, wake: Boolean, fileIds: List<String> = emptyList()) =
    SendCloudMessageRequest(text, wake = wake, fileIds = fileIds)

/**
 * `ostrom cloud send SESSION TEXT|- [--no-wake] [--idempotency-key KEY] [--wait] [--json]`: a message to the agent; a
 * paused session wakes up unless `--no-wake`.
 */
internal class CloudSendCommand(private val dependencies: CliDependencies) : CliktCommand(name = "send") {
    override fun help(context: Context) = "Send a message to a session. A paused session wakes up unless --no-wake " +
        "is given; the message waits in its queue."

    private val session by argument("session", help = "The cloud session's id")
    private val text by argument("text", help = "The message; use - to read it from stdin")
    private val noWake by option(
        "--no-wake",
        help = "Leave a paused session paused; the message is delivered on the next resume",
    ).flag()
    private val idempotencyKey by option(
        "--idempotency-key",
        help = "Retrying with the same key never sends twice. Defaults to a random key.",
    )
    private val wait by option("--wait", help = "Wait until the agent consumed the message (or it was rejected)").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val message = if (text == "-") generateSequence(dependencies.readLine).joinToString("\n") else text
        val access = dependencies.conversationAccess(server, org)
        val request = messageRequest(message, wake = !noWake)
        val input = runBlocking {
            var input = dependencies.sendWithRetry(access, session, request, idempotencyKey ?: Uuid.random().toString())
            val path = "${cloudSessionPath(session)}/messages/${segment(input.id)}"
            while (wait && input.status in PENDING) {
                dependencies.sleep(WAIT_EVERY)
                val read = access.server.get(path, access.signed.token)
                input = inputOf(read.orStop(access.signed.serverUrl, "No message ${input.id} in $session."))
            }
            input
        }
        val console = dependencies.console
        if (json) console.data(OstromJson.encodeToString(CloudInput.serializer(), input) + "\n")
        else console.out(input.line() + "\n")
    }
}

/** `ostrom cloud interrupt SESSION`: stops the agent's current turn; queued messages stay queued. */
internal class CloudInterruptCommand(private val dependencies: CliDependencies) : CliktCommand(name = "interrupt") {
    override fun help(context: Context) = "Stop the agent's current turn. Queued messages stay queued."

    private val session by argument("session", help = "The cloud session's id")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.conversationAccess(server, org)
        // Ostrom's interrupt takes no Idempotency-Key (TeamCode's does): one interrupt is one request.
        val input = access.write("POST", "${cloudSessionPath(session)}/interrupt", null, "No cloud session $session.")
        dependencies.console.out(inputOf(input).line() + "\n")
    }
}

/**
 * `ostrom cloud answer SESSION REQUEST_ID allow|deny [--message M] [--answer Q=O,...] [--answer-text Q=TEXT]`: answers
 * a permission request or a question of the agent.
 */
internal class CloudAnswerCommand(private val dependencies: CliDependencies) : CliktCommand(name = "answer") {
    override fun help(context: Context) = "Answer a permission request of the agent (a [permission] or [question] " +
        "line of the transcript): allow or deny it, or answer its questions by the indexes printed under it. A tool " +
        "use is answered by the session owner or an administrator only."

    private val session by argument("session", help = "The cloud session's id")
    private val request by argument("request_id", help = "The request's id, from its line in the transcript")
    private val behavior by argument("allow|deny").choice("allow", "deny")
    private val message by option("--message", help = "Why it was denied; the agent reads it")
    private val answers by option(
        "--answer",
        help = "QUESTION=OPTION[,OPTION...] by index, from 0, for a question request (repeatable); needs allow",
    ).multiple()
    private val texts by option(
        "--answer-text",
        help = "QUESTION=TEXT: a free answer to the question at that index (repeatable); needs allow",
    ).multiple()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val parsed = answers.map { value ->
            val (question, options) = indexed(value, "--answer QUESTION=OPTION[,OPTION...]")
            val indexes = options.split(',').map {
                it.trim().toIntOrNull()?.takeIf { index -> index >= 0 }
                    ?: throw UsageError("Use --answer QUESTION=OPTION[,OPTION...] with option indexes from 0.")
            }
            CloudQuestionAnswer(question, options = indexes)
        } + texts.map { value ->
            val (question, text) = indexed(value, "--answer-text QUESTION=TEXT")
            CloudQuestionAnswer(question, text = text)
        }
        val feature = ServerFeatures.RUNNERS_CLOUD_PERMISSIONS
        val access = dependencies.cloudAccess(server, org, feature, NO_CLOUD_PERMISSIONS)
        val answer = AnswerCloudPermissionRequest(
            request,
            behavior,
            message,
            parsed.sortedBy { it.question }.takeIf { it.isNotEmpty() },
        )
        val body = OstromJson.encodeToJsonElement(AnswerCloudPermissionRequest.serializer(), answer).jsonObject
        val path = "${cloudSessionPath(session)}/permissions"
        val key = Uuid.random().toString()
        val input = access.write("POST", path, body, "No cloud session $session.", idempotencyKey = key)
        dependencies.console.out(inputOf(input).line() + "\n")
    }

    private fun indexed(value: String, usage: String): Pair<Int, String> {
        val split = value.indexOf('=')
        val question = value.take(split.coerceAtLeast(0)).trim().toIntOrNull()?.takeIf { split > 0 && it >= 0 }
            ?: throw UsageError("Use $usage with a question index from 0.")
        return question to value.drop(split + 1)
    }
}

private const val MICROS = 1_000_000
private const val MICRO_DIGITS = 6
private const val TIME_START = 11
private const val TIME_END = 19
private const val TOOL_INPUT = 160
private const val TOOL_OUTPUT = 300
private const val TASK_DESCRIPTION = 60
private const val INPUT_ID = 8
private const val PAGE_LIMIT = 200
private const val EXTRA_PAGES = 10
private const val DEFAULT_TAIL = 50
private const val MAX_TAIL = 10_000
private const val FOLLOW_EVERY = 2_000L
private const val MAX_BACKOFF = 15_000L
private const val WAIT_EVERY = 1_000L
private const val FIRST_RETRY = 1_000L
private const val RETRIES = 5
private const val WRITE_MILLIS = 15_000L
/** The refusals a retry would not change: every 4xx but 408 (timeout) and 429 (too many requests). */
private val REFUSED_SENDS = (400..499).toSet() - setOf(408, 429)
private val REFUSALS = setOf(401, 403, 404)
private val ENDED = setOf("terminated", "failed")
private val PENDING = setOf(CloudInputStatuses.QUEUED, CloudInputStatuses.DELIVERED)
private const val EXPIRED = "No answer in time: the request was denied."
private const val CUT = "(cut; too large to approve, deny only)"
