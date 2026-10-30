// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.doctor

import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The verdict on the answer of the health route ([HEALTH_ROUTE]). Reachable means an Ostrom server: a 2xx with the
 * contract's body, a `status` of `ok` or `degraded` and a `version` that reads as one. A 5xx is a server in trouble
 * (with the checks that failed when Ostrom says which); a redirect, a 4xx or another body is not an Ostrom server of
 * this version, and the URL is the first thing to check. The server's version is compared with [contract], the
 * version of the API contract this build speaks: another MAJOR.MINOR is a warning that says which side is behind,
 * since the server and the contract are versioned apart; a blank [contract] compares nothing.
 *
 * @param host The server's host, as the doctor names it.
 * @param url The server URL the doctor read (config or `OSTROM_URL`).
 */
internal fun healthCheck(host: String, url: String, response: ServerResponse, contract: String): DoctorCheck {
    val status = response.status
    val body = parseObject(response.body)
    // shown as is: only the characters of a version, never an escape sequence or a line of its own
    val version = body?.text("version")?.takeIf { PRINTABLE.matches(it) }
    val health = body?.text("status")
    return when {
        status >= SERVER_ERROR -> DoctorCheck(
            CheckStatus.PROBLEM,
            "$host answers $status: the server is in trouble${failing(body)}, try again later.",
        )
        status !in SUCCESS -> DoctorCheck(
            CheckStatus.PROBLEM,
            "$host answers $status on $HEALTH_ROUTE: not an Ostrom server of this version. " +
                "Check the server URL ($url).",
        )
        version == null || health !in HEALTH_STATUSES -> DoctorCheck(
            CheckStatus.PROBLEM,
            "$host answers, but not as an Ostrom server: check the server URL ($url).",
        )
        else -> versionCheck("$host reachable (${response.millis} ms) · Ostrom $version", version, contract)
    }
}

/** ` (database: down)`: the checks of an Ostrom health body that are not `ok`, or nothing. */
private fun failing(body: JsonObject?): String {
    val checks = body?.child("checks") ?: return ""
    val failed = checks.mapNotNull { (name, state) ->
        val shown = (state as? JsonPrimitive)?.content?.takeIf { PRINTABLE.matches(it) } ?: "?"
        "$name: $shown".takeIf { shown != "ok" && PRINTABLE.matches(name) }
    }
    return if (failed.isEmpty()) "" else " (${failed.joinToString(", ")})"
}

private fun versionCheck(reachable: String, server: String, contract: String): DoctorCheck {
    val spoken = majorMinor(contract)
    val served = majorMinor(server)
    val advice = when {
        spoken == null || served == spoken -> null
        served == null -> "compare them with your admin."
        compareValuesBy(served, spoken, { it.first }, { it.second }) < 0 ->
            "the server is behind: ask your admin to update it."
        else -> "this ostrom is behind: ostrom update."
    }
    return if (advice == null) {
        DoctorCheck(CheckStatus.OK, reachable)
    } else {
        DoctorCheck(CheckStatus.WARNING, "$reachable, but this ostrom speaks the API $contract: $advice")
    }
}

/** MAJOR and MINOR of a version (`v` prefix and suffixes allowed), or null when it has no numbers. */
private fun majorMinor(version: String): Pair<Int, Int>? =
    NUMBERED.find(version)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

private val NUMBERED = Regex("""^v?(\d{1,9})\.(\d{1,9})""")

/** What a version, or a check's name or state, may hold to be printed: never an escape sequence nor a new line. */
private val PRINTABLE = Regex("""[0-9A-Za-z._+-]{1,64}""")

/** The `status` values of the contract's health body. */
private val HEALTH_STATUSES = setOf("ok", "degraded")

/** The statuses of an answer that went well. */
private val SUCCESS = 200..299

/** The first HTTP status that means the server itself failed. */
private const val SERVER_ERROR = 500
