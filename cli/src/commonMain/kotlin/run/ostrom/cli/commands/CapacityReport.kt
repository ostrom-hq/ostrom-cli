// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.asJson
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.TypedAnswer
import run.ostrom.cli.launcher.UNREADABLE
import run.ostrom.cli.launcher.typedAnswer
import run.ostrom.core.client.OstromClient
import run.ostrom.cli.launcher.child
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The capacity sections of `ostrom status` (TeamCode's `teamcode status`, TC-CAP-21/24), each the server's JSON as it
 * answered: the organisation's admission queue (`GET /api/v1/inference/queue`), the caller's budget
 * (`GET /api/v1/budgets/me`), the capacity advice (`pacing.current`, `GET /api/v1/directives/current`) and the
 * shared accounts the caller
 * may be routed to (`GET /api/v1/accounts`: their home pool's, and the shared overflow pools' when they may borrow
 * them) and the caller's own pool policy (`GET /api/v1/members/{login}/policy`: `poolId`, absent for none, and
 * `allowSharedOverflow`, TeamCode's `capacity.poolId` and `capacity.allowSharedOverflow`, shown by `--json` only as
 * TeamCode does). A section that could not be shown is null, and [unavailable] says why by its name (`queue`,
 * `budget`, `pacing`, `accounts`, `policy`): `not_served` (403 or 404: an older server, or a token without the right),
 * `unexpected_response` (a body of another shape) or `read_failed` (any other error, no answer). [pacing] is also null
 * when no advice applies, without an [unavailable] entry. [nowMillis] is when it was read: delays are counted from it.
 */
@Suppress("LongParameterList") // one property per section of the status
internal class CapacityReport(
    val queue: JsonObject? = null,
    val budget: JsonObject? = null,
    val pacing: JsonObject? = null,
    val accounts: List<JsonObject>? = null,
    val policy: JsonObject? = null,
    val unavailable: Map<String, String> = emptyMap(),
    val nowMillis: Long = 0,
) {
    /** The sections as `--json` gives them: the same keys always, null for what is not shown. */
    fun json(): Map<String, JsonElement> = mapOf(
        "queue" to (queue ?: JsonNull),
        "budget" to (budget ?: JsonNull),
        "pacing" to (pacing ?: JsonNull),
        "accounts" to (accounts?.let(::JsonArray) ?: JsonNull),
        "policy" to (policy ?: JsonNull),
        "unavailable" to JsonObject(unavailable.mapValues { JsonPrimitive(it.value) }),
    )
}

/** One section read: its value, or why it is not shown. */
private class Section<T>(val value: T?, val reason: String?)

/**
 * Reads the five sections together: none of them fails `status`, whatever the server answers (TeamCode's: only the
 * lines it showed before are required).
 */
internal suspend fun readCapacity(
    server: ServerJson,
    client: OstromClient,
    token: String,
    login: String,
    now: Long,
): CapacityReport = coroutineScope {
    val queue = async { section(server.get(QUEUE, token)) { it.takeIf { body -> body.number("waiting") != null } } }
    val budget = async {
        section(server.get(BUDGET, token)) { it.takeIf { body -> body.number("spentMicros") != null } }
    }
    // `{}` or a null advice is none: a section shown as "none"
    val pacing = async { section(typedAnswer { client.pacing.current() }) { asJson(it) } }
    val accounts = async {
        section(server.get(ACCOUNTS, token)) { body ->
            (body["items"] as? JsonArray)?.takeIf { items -> items.all { it is JsonObject } }
                ?.filterIsInstance<JsonObject>()
        }
    }
    // the caller's own: a member reads only theirs, and sees no other member's pool
    val policy = async {
        section(server.get("$MEMBERS${Routes.encode(login)}/policy", token)) {
            it.takeIf { body -> body.flag("allowSharedOverflow") != null }
        }
    }
    val read = listOf("queue" to queue.await(), "budget" to budget.await(), "pacing" to pacing.await(),
        "accounts" to accounts.await(), "policy" to policy.await())
    CapacityReport(
        queue.await().value,
        budget.await().value,
        pacing.await().value?.child("directive"),
        accounts.await().value,
        policy.await().value,
        read.mapNotNull { (name, section) -> section.reason?.let { name to it } }.toMap(),
        now,
    )
}

private fun <V, T> section(answer: TypedAnswer<V>, shape: (V) -> T?): Section<T> = when (answer) {
    is TypedAnswer.Ok -> shape(answer.value)?.let { Section(it, null) } ?: Section(null, UNEXPECTED)
    is TypedAnswer.Failed -> Section(null, reasonOf(answer.failure))
}

/** Why a typed read failed: an answer this ostrom cannot read is another shape than the one expected. */
private fun reasonOf(failure: ServerAnswer.Failed) = when {
    failure.type == UNREADABLE -> UNEXPECTED
    failure.status in NOT_SERVED -> "not_served"
    else -> "read_failed"
}

private fun <T> section(answer: ServerAnswer, shape: (JsonObject) -> T?): Section<T> = when (answer) {
    is ServerAnswer.Ok -> shape(answer.body)?.let { Section(it, null) } ?: Section(null, UNEXPECTED)
    is ServerAnswer.Failed -> Section(null, if (answer.status in NOT_SERVED) "not_served" else "read_failed")
}

private const val QUEUE = "/api/v1/inference/queue"
private const val BUDGET = "/api/v1/budgets/me"
private const val ACCOUNTS = "/api/v1/accounts"
private const val MEMBERS = "/api/v1/members/"
internal const val UNEXPECTED = "unexpected_response"
private val NOT_SERVED = setOf(403, 404)
