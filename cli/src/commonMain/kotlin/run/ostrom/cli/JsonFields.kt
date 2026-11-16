// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The string field [key] of a typed model (one the server always sends): its text. */
internal fun JsonObject.required(key: String): String = getValue(key).jsonPrimitive.content
