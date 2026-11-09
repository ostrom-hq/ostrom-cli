// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The keys of an agent's settings known to be harmless in a repository's or working folder's file: anything else
 * could run a program of the repository's, change the shell, the hooks, the MCP servers, the tools or the trust
 * (security audit of the CLI n°2, H3 and H4). A key is a dotted path, with the values it may take: `ui` lets
 * everything under `ui` through, `ui.theme` only that one.
 *
 * @param inert The dotted paths let through, each with the test its value must pass.
 */
class InertKeys(private val inert: Map<String, (JsonElement) -> Boolean>) {
    /** Lets [paths] through with any value. */
    constructor(paths: Set<String>) : this(paths.associateWith { { _: JsonElement -> true } })

    /** The dotted path of the first key of [settings] that is not let through, or null when all are. */
    fun firstOutside(settings: JsonObject, prefix: String = ""): String? =
        settings.entries.firstNotNullOfOrNull { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val accepts = inert[path]
            when {
                accepts != null -> path.takeUnless { accepts(value) }
                // a key some of whose children are let through is looked into
                value is JsonObject && inert.keys.any { it.startsWith("$path.") } -> firstOutside(value, path)
                else -> path
            }
        }
}
