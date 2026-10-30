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
            "http://[::1]:8080 (a port from 1 to 65535; no user, query or fragment)."
    }
    ConfigKey.DEFAULT_AGENT -> if (Agent.of(value) != null) {
        null
    } else {
        "default_agent must be one of ${Agent.entries.joinToString(", ") { it.command }}."
    }
    ConfigKey.COLOR -> if (value in COLOR_VALUES) null else "color must be auto, always or never."
    ConfigKey.EDITOR -> if (value.isNotBlank()) null else "editor cannot be empty."
    // Written by ostrom telemetry, never by config set (editableKey refuses them first).
    ConfigKey.TELEMETRY, ConfigKey.TELEMETRY_ID -> null
}

/**
 * An `http` or `https` URL (scheme in any case) with a host (a name, a trailing dot allowed; an IPv4 address; a
 * bracketed IPv6 one), an optional port from 1 to 65535 and an optional path, with no user, space, query or fragment.
 */
internal fun isServerUrl(value: String): Boolean {
    val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
    val rest = value.substringAfter("://")
    val authority = rest.substringBefore('/')
    val path = rest.substring(authority.length)
    return scheme in SCHEMES && path.none { it.isWhitespace() || it in "?#" } && isAuthority(authority)
}

private fun isAuthority(authority: String): Boolean {
    val end = when {
        // An unclosed bracket ends the host at 0: no host.
        authority.startsWith("[") -> authority.indexOf(']') + 1
        ':' in authority -> authority.lastIndexOf(':')
        else -> authority.length
    }
    val port = authority.substring(end)
    return isHost(authority.substring(0, end)) && (port.isEmpty() || isPort(port))
}

private fun isPort(port: String): Boolean {
    val digits = port.removePrefix(":")
    val written = port.startsWith(":") && digits.length in 1..MAX_PORT_DIGITS && digits.all { it in DIGITS }
    return (if (written) digits.toInt() else 0) in 1..MAX_PORT
}

private fun isHost(host: String): Boolean =
    if (host.startsWith("[")) isIpv6(host.removeSurrounding("[", "]")) else isNameOrIpv4(host.removeSuffix("."))

private fun isNameOrIpv4(host: String): Boolean {
    val labels = host.split('.')
    val numeric = labels.all { label -> label.isNotEmpty() && label.all { it in DIGITS } }
    return if (numeric) isIpv4(host) else labels.all(::isLabel)
}

private fun isLabel(label: String): Boolean =
    label.length in 1..MAX_LABEL && label.first() != '-' && label.last() != '-' && label.all { it in LABEL_CHARACTERS }

private fun isIpv4(address: String): Boolean {
    val octets = address.split('.')
    return octets.size == IPV4_OCTETS &&
        octets.all { it.length in 1..MAX_OCTET_DIGITS && it.all { c -> c in DIGITS } && it.toInt() <= MAX_OCTET }
}

/** Eight groups of 1 to 4 hex digits, or fewer around one `::`; the last two may be an IPv4 address. */
private fun isIpv6(address: String): Boolean {
    val halves = address.split("::")
    val parts = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
    val ipv4 = parts.lastOrNull()?.contains('.') == true
    val groups = parts.dropLast(if (ipv4) 1 else 0)
    val shaped = groups.all { it.length in 1..MAX_GROUP_DIGITS && it.all { c -> c in HEX_DIGITS } } &&
        (!ipv4 || isIpv4(parts.last()))
    val count = groups.size + if (ipv4) 2 else 0
    return halves.size <= 2 && shaped && if (halves.size == 2) count < IPV6_GROUPS else count == IPV6_GROUPS
}

private val COLOR_VALUES = setOf("auto", "always", "never")
private val SCHEMES = setOf("http", "https")
private const val DIGITS = "0123456789"
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
private const val LABEL_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
private const val MAX_PORT = 65_535
private const val MAX_PORT_DIGITS = 5
private const val MAX_LABEL = 63
private const val IPV4_OCTETS = 4
private const val MAX_OCTET_DIGITS = 3
private const val MAX_OCTET = 255
private const val MAX_GROUP_DIGITS = 4
private const val IPV6_GROUPS = 8
