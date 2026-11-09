// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** The header Claude Code sends the capability in when it keeps its own claude.ai login in `Authorization`. */
internal const val LOCAL_TOKEN_HEADER = "x-ostrom-local-token"

private const val API_KEY_HEADER = "x-api-key"
private const val BEARER = "Bearer "
private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost")
private const val LOOPBACK_IPV6 = "::1"

/** True when the request carries [token] as `Authorization: Bearer`, `x-api-key` or [LOCAL_TOKEN_HEADER]. */
internal fun hasCapability(headers: Headers, token: String): Boolean =
    equalToken(headers[HttpHeaders.Authorization]?.removePrefix(BEARER), token) ||
        equalToken(headers[API_KEY_HEADER], token) ||
        equalToken(headers[LOCAL_TOKEN_HEADER], token)

/**
 * True for a request from a local program: no `Origin` (browsers send one with every cross-site request) and a `Host`
 * that names the loopback (a DNS-rebound page sends its own host name).
 */
internal fun isLocalRequest(headers: Headers): Boolean =
    headers[HttpHeaders.Origin] == null && isLoopbackHost(headers[HttpHeaders.Host])

/**
 * True when a `Host` header names the loopback, with or without a port. No header at all is HTTP/1.0 from a local
 * program: the relay only listens on `127.0.0.1`, and browsers always send one.
 */
internal fun isLoopbackHost(host: String?): Boolean = when {
    host == null -> true
    host.startsWith("[") -> host.substringAfter('[').substringBefore(']') == LOOPBACK_IPV6
    else -> host.substringBefore(':').lowercase() in LOOPBACK_NAMES
}

/** Compares in a time that does not depend on where [presented] first differs from [expected] (TeamCode). */
internal fun equalToken(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    var difference = presented.length xor expected.length
    for (index in expected.indices) {
        difference = difference or (expected[index].code xor (presented.getOrNull(index)?.code ?: 0))
    }
    return difference == 0
}
