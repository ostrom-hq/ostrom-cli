// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.platform.ConfigKey

/**
 * Why [value] cannot be kept as [key], or null when it can: `config set` refuses a value the CLI would later
 * misread (decision of 30/10, 05:15) rather than save it and fail at the next command.
 */
internal fun configValueProblem(key: ConfigKey, value: String): String? = when (key) {
    ConfigKey.SERVER -> if (SERVER_URL.matches(value)) {
        null
    } else {
        "server must be an http:// or https:// URL, such as https://ostrom.example."
    }
    ConfigKey.DEFAULT_AGENT -> if (Agent.of(value) != null) {
        null
    } else {
        "default_agent must be one of ${Agent.entries.joinToString(", ") { it.command }}."
    }
    ConfigKey.COLOR -> if (value in COLOR_VALUES) null else "color must be auto, always or never."
    ConfigKey.EDITOR -> if (value.isNotBlank()) null else "editor cannot be empty."
}

/** A scheme, a host (with an optional port) and an optional path, with no space, query or fragment. */
private val SERVER_URL = Regex("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\\s?#]*)?$")

private val COLOR_VALUES = setOf("auto", "always", "never")
