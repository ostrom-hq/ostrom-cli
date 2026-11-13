// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.list
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** What the lines of the workflows commands are made of. */
private const val SHORT = 90
private const val SECOND = 1_000L
private const val MINUTE = 60
private const val HOUR = 3_600
private const val DAY = 86_400

/** [text] on one line, cut to [max] characters. */
internal fun oneLine(text: String, max: Int = SHORT): String =
    text.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

/** The objects of the array field [key]. */
internal fun JsonObject.objects(key: String): List<JsonObject> = list(key).filterIsInstance<JsonObject>()

/** The field [key] as written (a number is a number); null when it is absent. */
internal fun JsonObject.number(key: String): String? = this[key]?.toString()

/** The strings of the array field [key]. */
internal fun JsonObject.items(key: String): List<String> =
    list(key).filterIsInstance<JsonPrimitive>().map { it.content }

/** `5m`, `3h`, `2d`: how long ago [at] (an ISO instant) was at [now] (epoch milliseconds); `?` if unreadable. */
internal fun age(at: String?, now: Long): String {
    val then = try {
        Instant.parse(at.orEmpty()).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        return "?"
    }
    val seconds = ((now - then) / SECOND).coerceAtLeast(0)
    return when {
        seconds < MINUTE -> "${seconds}s"
        seconds < HOUR -> "${seconds / MINUTE}m"
        seconds < DAY -> "${seconds / HOUR}h"
        else -> "${seconds / DAY}d"
    }
}
