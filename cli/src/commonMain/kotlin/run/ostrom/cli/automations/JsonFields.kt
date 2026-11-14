// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.text
import run.ostrom.cli.workflows.number
import kotlinx.serialization.json.JsonObject

// Reads of a JSON object that may be absent: one safe call each, so a caller chains none of its own.

/** The string field [key], or null when this object or the field is absent. */
internal fun JsonObject?.str(key: String): String? = this?.text(key)

/** The object field [key], or null when this object or the field is absent. */
internal fun JsonObject?.sub(key: String): JsonObject? = this?.child(key)

/** The whole-number field [key], or null when this object or the field is absent or not a whole number. */
internal fun JsonObject?.int(key: String): Int? = this?.number(key)?.toIntOrNull()

