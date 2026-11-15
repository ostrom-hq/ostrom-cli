// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Network failures by family, for the messages of a server or a release that cannot be reached. [HEADERS] is an answer
 * whose headers the engine's parser refused (a header line over 8 192 bytes for ktor-http-cio, used by the Curl and
 * WinHttp engines); [PROXY] a proxy that refused or failed the connection. Port of TeamCode's `NetworkFailures.kt`.
 */
enum class NetworkFailure(val cause: String) {
    DNS("the name could not be resolved (DNS)"),
    REFUSED("the connection was refused or could not be established"),
    TIMEOUT("the connection timed out"),
    TLS("the TLS handshake or the certificate failed"),
    CLOSED("the connection was closed before the answer was complete"),
    PROXY("a proxy refused or failed the connection"),
    HEADERS("the answer's headers could not be read"),
    OTHER("the network failed"),
}

private const val CHAIN_LENGTH = 8
private const val DETAIL_LENGTH = 200
private const val DETAIL_CAUSES = 3
private val TIMEOUTS = listOf("Timeout")
private val TLS_NAMES = listOf("SSL", "Tls", "TLS", "Certificate")
private val DNS_NAMES = listOf("UnknownHost", "UnresolvedAddress")
private val REFUSED_NAMES = listOf("ConnectException", "NoRouteToHost", "PortUnreachable")
private val CLOSED_NAMES = listOf("EOFException", "ClosedReceiveChannel", "ClosedByteChannel")

/**
 * The family of [error], detected from ktor's common types and each engine's error names and messages (inspected, never
 * printed); null when the error was not raised by the HTTP stack. A local disk or permission failure (also an
 * IOException on the JVM) must not read as an unreachable server.
 */
fun networkFailure(error: Throwable): NetworkFailure? {
    val chain = generateSequence(error) { it.cause }.take(CHAIN_LENGTH).toList()
    val text = chain.joinToString("\n") { it.message.orEmpty() }
    val names = chain.map { it::class.simpleName.orEmpty() }
    val packages = chain.map { it::class.qualifiedName.orEmpty() }
    val timedOut = chain.any {
        it is ConnectTimeoutException || it is SocketTimeoutException || it is HttpRequestTimeoutException
    }
    val fromHttpStack = packages.any { name -> HTTP_PACKAGES.any(name::startsWith) } ||
        names.matchesAny(ENGINE_NAMES) || text.mentions(CURL_TEXT) || timedOut
    return when {
        text.mentions(HEADER_TEXT) -> NetworkFailure.HEADERS
        !fromHttpStack -> null
        else -> byName(names, timedOut) ?: byText(text, names)
    }
}

private fun List<String>.matchesAny(candidates: List<String>) = any { name -> candidates.any(name::contains) }

private fun String.mentions(fragments: List<String>) = fragments.any { contains(it) }

private fun byName(names: List<String>, timedOut: Boolean): NetworkFailure? = when {
    names.matchesAny(TLS_NAMES) -> NetworkFailure.TLS
    names.matchesAny(DNS_NAMES) -> NetworkFailure.DNS
    timedOut || names.matchesAny(TIMEOUTS) -> NetworkFailure.TIMEOUT
    names.matchesAny(REFUSED_NAMES) -> NetworkFailure.REFUSED
    names.matchesAny(CLOSED_NAMES) -> NetworkFailure.CLOSED
    else -> null
}

private fun String.hasCode(code: Int) =
    contains("Code=$code ") || contains("Code=$code\"") || endsWith("Code=$code")

/** Whether [text] carries an engine's numeric code: they only count for their own engine, not for a port number. */
private fun codeOf(text: String, names: List<String>, darwin: List<Int>, winHttp: List<Int>): Boolean =
    (names.any { "Darwin" in it } && darwin.any { text.hasCode(it) }) ||
        (names.any { "WinHttp" in it } && winHttp.any { Regex("(^|\\D)$it(\\D|$)").containsMatchIn(text) })

private fun byText(text: String, names: List<String>): NetworkFailure = when {
    // curl's own proxy errors first: "Could not resolve proxy" would otherwise read as DNS.
    text.mentions(CURL_PROXY_TEXT) -> NetworkFailure.PROXY
    text.mentions(TLS_TEXT) || codeOf(text, names, DARWIN_TLS, WINHTTP_TLS) -> NetworkFailure.TLS
    text.mentions(DNS_TEXT) || codeOf(text, names, DARWIN_DNS, WINHTTP_DNS) -> NetworkFailure.DNS
    text.mentions(TIMEOUT_TEXT) || codeOf(text, names, DARWIN_TIMEOUT, WINHTTP_TIMEOUT) -> NetworkFailure.TIMEOUT
    text.mentions(PROXY_TEXT) -> NetworkFailure.PROXY
    text.mentions(REFUSED_TEXT) || codeOf(text, names, DARWIN_REFUSED, WINHTTP_REFUSED) -> NetworkFailure.REFUSED
    else -> NetworkFailure.OTHER
}

/**
 * A short, printable technical cause: the class and message of [error] and its first causes, URLs reduced to their
 * origin (a signed CDN query never shows), Darwin's `UserInfo` dictionary dropped, control characters removed, at most
 * 200 characters.
 */
fun releaseFailureDetail(error: Throwable): String {
    val parts = generateSequence(error) { it.cause }.take(DETAIL_CAUSES).map { cause ->
        val message = cause.message.orEmpty().substringBefore("UserInfo=")
            .replace(URL) { match -> match.groupValues[1] + match.groupValues[2] + "/…" }
            .replace(USERINFO, "")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().trimEnd(',', ';', ' ')
        val name = cause::class.simpleName ?: "Exception"
        if (message.isEmpty()) name else "$name: $message"
    }.distinct().toList()
    val text = parts.joinToString(" < ")
    return if (text.length <= DETAIL_LENGTH) text else text.take(DETAIL_LENGTH - 1) + "…"
}

// The origin without any user:password@ part, then the rest of the URL dropped.
private val URL = Regex("((?:https?|wss?|socks[45]?h?)://)(?:[^/\\s\"'<>?#@]*@)?([^/\\s\"'<>?#@]+)[^\\s\"'<>]*")

// Any remaining `name:secret@host` (a proxy written without a scheme).
private val USERINFO = Regex("[^\\s/\"'<>@:]+:[^\\s/\"'<>@]+@")

// Engines: OkHttp (JVM tests), Curl (Linux), Darwin/NSURLSession (macOS) and WinHTTP (Windows). Case-sensitive on
// purpose: a lowercase host name such as `tls.example.com` in an engine message must not look like a TLS failure.
private val HTTP_PACKAGES =
    listOf("io.ktor.", "java.net.", "javax.net.", "okhttp3.", "java.nio.channels.UnresolvedAddress")
private val ENGINE_NAMES = listOf("Curl", "Darwin", "WinHttp")
private val CURL_TEXT =
    listOf("CurlRequestData(", "Connection failed for request", "TLS verification failed for request")

// ktor-http-cio's own wording (HttpParser, and the Curl and WinHttp engines that use it), not generic parser errors.
private val HEADER_TEXT = listOf("Header line length limit exceeded", "Failed to parse response header")
private val CURL_PROXY_TEXT =
    listOf("resolve proxy", "from proxy after CONNECT", "CONNECT tunnel failed", "Proxy CONNECT aborted")
private val PROXY_TEXT = listOf("proxy server", "web proxy", "Proxy Authentication")
private val TLS_TEXT = listOf("SSL", "TLS", "certificate", "Certificate", "security error")
private val DNS_TEXT = listOf(
    "resolve host",
    "Could not resolve",
    "Couldn't resolve",
    "could not be resolved",
    "Unable to resolve",
    "nodename nor servname",
    "Name or service not known",
)
private val TIMEOUT_TEXT = listOf("timed out", "Timeout was reached")
private val REFUSED_TEXT =
    listOf(
        "Couldn't connect",
        "Could not connect",
        "Connection refused",
        "Failed to connect",
        "could not be established",
    )
private val DARWIN_TLS = listOf(-1200, -1201, -1202, -1203, -1204, -1205, -1206, -2000)
private val WINHTTP_TLS = listOf(12175, 12037, 12038, 12044, 12045, 12057, 12169)
private val DARWIN_DNS = listOf(-1003, -1006)
private val WINHTTP_DNS = listOf(12007)
private val DARWIN_TIMEOUT = listOf(-1001)
private val WINHTTP_TIMEOUT = listOf(12002)
private val DARWIN_REFUSED = listOf(-1004, -1009, -1005)
private val WINHTTP_REFUSED = listOf(12029, 12030)
