// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import kotlinx.serialization.json.JsonObject

/**
 * The keys of an agent's settings known to be harmless in a repository's or working folder's file: anything else
 * could run a program of the repository's, change the shell, the hooks, the MCP servers, the tools or the trust
 * (security audit of the CLI n°2, H3 and H4). A key is a dotted path: `ui` lets everything under `ui` through,
 * `ui.theme` only that one.
 *
 * @param inert The dotted paths let through.
 */
class InertKeys(private val inert: Set<String>) {
    /** The dotted path of the first key of [settings] that is not let through, or null when all are. */
    fun firstOutside(settings: JsonObject, prefix: String = ""): String? =
        settings.entries.firstNotNullOfOrNull { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                path in inert -> null
                // a key some of whose children are let through is looked into
                value is JsonObject && inert.any { it.startsWith("$path.") } -> firstOutside(value, path)
                else -> path
            }
        }
}
