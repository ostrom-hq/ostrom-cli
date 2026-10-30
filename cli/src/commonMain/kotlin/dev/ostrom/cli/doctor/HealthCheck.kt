// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.doctor

import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text

/**
 * The verdict on the answer of the health route ([HEALTH_ROUTE]). Reachable means an Ostrom server: a 2xx with the
 * contract's body, `status` and `version`. A 5xx is a server in trouble; a redirect, a 4xx or another body is not an
 * Ostrom server of this version, and the URL is the first thing to check. The server's version is compared with
 * [contract], the version of the API contract this build speaks: another MAJOR.MINOR is a warning, since the server
 * and the contract are versioned apart; a blank [contract] compares nothing.
 *
 * @param host The server's host, as the doctor names it.
 * @param url The server URL the doctor read (config or `OSTROM_URL`).
 */
internal fun healthCheck(host: String, url: String, response: ServerResponse, contract: String): DoctorCheck {
    val status = response.status
    val body = parseObject(response.body)
    val version = body?.text("version")
    val health = body?.text("status")
    return when {
        status >= SERVER_ERROR ->
            DoctorCheck(CheckStatus.PROBLEM, "$host answers $status: the server is in trouble, try again later.")
        status !in SUCCESS -> DoctorCheck(
            CheckStatus.PROBLEM,
            "$host answers $status on $HEALTH_ROUTE: not an Ostrom server of this version. " +
                "Check the server URL ($url).",
        )
        version == null || health == null -> DoctorCheck(
            CheckStatus.PROBLEM,
            "$host answers, but not as an Ostrom server: check the server URL ($url).",
        )
        else -> versionCheck("$host reachable (${response.millis} ms) · Ostrom $version", version, contract)
    }
}

private fun versionCheck(reachable: String, server: String, contract: String): DoctorCheck {
    val spoken = majorMinor(contract)
    return if (spoken == null || majorMinor(server) == spoken) {
        DoctorCheck(CheckStatus.OK, reachable)
    } else {
        DoctorCheck(
            CheckStatus.WARNING,
            "$reachable, but this ostrom speaks the API $contract: update the one that is behind " +
                "(ostrom update, or ask your admin to update the server).",
        )
    }
}

/** `MAJOR.MINOR` of a version (`v` prefix and suffixes allowed), or null when it has no numbers. */
private fun majorMinor(version: String): String? =
    VERSION.find(version)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }

private val VERSION = Regex("""^v?(\d+)\.(\d+)""")

/** The statuses of an answer that went well. */
private val SUCCESS = 200..299

/** The first HTTP status that means the server itself failed. */
private const val SERVER_ERROR = 500
