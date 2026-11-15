// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.launcher.text
import run.ostrom.cli.workflows.objects
import kotlinx.serialization.json.JsonObject

/** An organisation keeps at most 1000 workflows: twenty full pages of the server's 50, and one read to find the end. */
internal const val MAX_WORKFLOW_PAGES = 21

/**
 * Every workflow of the list [path] reads (`GET /api/v1/workflows`, with its filters), page by page: the first page
 * as asked, then `after` the previous page's `next` until a page has none, as TeamCode's `listAllWorkflows`.
 *
 * @param path The list, with its query (`?repo=` or none).
 * @param notFound What a 404 says.
 * @return The workflows, oldest first.
 */
internal fun OrgAccess.allWorkflows(path: String, notFound: String): List<JsonObject> {
    val items = mutableListOf<JsonObject>()
    val separator = if ('?' in path) "&" else "?"
    var after: String? = null
    var pages = 0
    do {
        val page = if (after == null) path else "$path${separator}after=${segment(after)}"
        val body = read(page, notFound)
        items += body.objects("items")
        after = body.text("next")
        pages++
    } while (after != null && pages < MAX_WORKFLOW_PAGES)
    return items
}
