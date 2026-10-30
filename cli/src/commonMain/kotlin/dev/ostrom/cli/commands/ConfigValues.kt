// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.platform.ConfigKey

/**
 * Why [value] cannot be kept as [key], or null when it can: `config set` refuses a value the CLI would later
 * misread (decision of 30/10, 05:15) rather than save it and fail at the next command.
 */
internal fun configValueProblem(key: ConfigKey, value: String): String? = when (key) {
    ConfigKey.SERVER -> if (isServerUrl(value)) {
        null
    } else {
        "server must be an http:// or https:// URL with a host, such as https://ostrom.example or " +
            "http://[::1]:8080 (a port up to 65535; no user, query or fragment)."
    }
    ConfigKey.DEFAULT_AGENT -> if (Agent.of(value) != null) {
        null
    } else {
        "default_agent must be one of ${Agent.entries.joinToString(", ") { it.command }}."
    }
    ConfigKey.COLOR -> if (value in COLOR_VALUES) null else "color must be auto, always or never."
    ConfigKey.EDITOR -> if (value.isNotBlank()) null else "editor cannot be empty."
}

/**
 * A scheme, a host (a name, an IPv4 address or a bracketed IPv6 one), an optional port from 1 to 65535 and an optional
 * path, with no space, user, query or fragment.
 */
private fun isServerUrl(value: String): Boolean {
    val match = SERVER_URL.matchEntire(value)
    val port = match?.groups?.get(PORT_GROUP)?.value?.toInt()
    return match != null && (port == null || port in 1..MAX_PORT)
}

private val SERVER_URL = Regex(
    "https?://(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9_]([A-Za-z0-9._-]*[A-Za-z0-9_])?)(:([0-9]{1,5}))?(/[^\\s?#@]*)?",
)
private const val PORT_GROUP = 4
private const val MAX_PORT = 65_535

private val COLOR_VALUES = setOf("auto", "always", "never")
