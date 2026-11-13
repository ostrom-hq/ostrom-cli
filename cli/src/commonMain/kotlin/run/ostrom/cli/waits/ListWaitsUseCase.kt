// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject

/** The most `ostrom waits list` fetches in one run; older waits are reached with `--before`. */
internal const val MAX_LISTED = 500

/** The most one request of the server returns (`limit` of the contract). */
private const val PAGE = 100

/**
 * What `ostrom waits list` asks.
 *
 * @property status `open`, `resolved`, `cancelled` or `all`.
 * @property assignee `all`, or a member id: the waits of the team or of that member (administrators); null: those
 *   asked of the caller.
 * @property session Only the waits of this session (its person only).
 * @property before The `next` of the previous page.
 * @property limit At most this many waits, 1 to [MAX_LISTED].
 */
internal data class ListWaits(
    val status: String,
    val assignee: String?,
    val session: String?,
    val before: String?,
    val limit: Int,
)

/** Waits, newest first, and the cursor of the page that follows them when more exist. */
internal class WaitsListing(val items: List<JsonObject>, val next: String?)

/** Lists the waits, page after page of the server, up to the limit asked. */
internal class ListWaitsUseCase(private val server: WaitsServer) {
    suspend operator fun invoke(request: ListWaits): WaitsListing {
        if (request.limit !in 1..MAX_LISTED) refuse("--limit is 1 to $MAX_LISTED.")
        val assignee = request.assignee?.let { if (it == "all") it else requireMember(it) }
        request.session?.let { requireId(it, "session id") }
        request.before?.let { requireId(it, "cursor") }
        val items = mutableListOf<JsonObject>()
        var cursor = request.before
        do {
            val page = server.page(query(request, assignee, cursor, minOf(PAGE, request.limit - items.size)))
            val read = page.list("items").filterIsInstance<JsonObject>()
            items += read
            // a page without a wait is the end, whatever cursor it names: never asked again forever
            cursor = page.text("next")?.takeIf { it.isNotEmpty() && read.isNotEmpty() }
        } while (cursor != null && items.size < request.limit)
        return WaitsListing(items.take(request.limit), cursor)
    }

    private fun query(request: ListWaits, assignee: String?, cursor: String?, size: Int): String {
        val parts = listOfNotNull(
            "status=${request.status}",
            if (assignee == null) "mine=true" else "assignee=${Routes.encode(assignee)}",
            request.session?.let { "sessionId=${Routes.encode(it)}" },
            cursor?.let { "before=${Routes.encode(it)}" },
            "limit=$size",
        )
        return "?" + parts.joinToString("&")
    }
}

private val MEMBER = Regex("[a-zA-Z0-9._-]{1,64}")

/** [value] without a leading `@` when it can be a member id or a username, else a usage message. */
internal fun requireMember(value: String): String {
    val name = value.removePrefix("@")
    if (!MEMBER.matches(name)) refuse("\"$value\" is not a member id or a username.")
    return name
}
