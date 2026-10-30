// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * One HTTP/1.1 request to a server on this machine's loopback, without TLS: the hooks reach the launcher's relay this
 * way, in a few hundred microseconds and without an HTTP library in the hot path of `ostrom hook`.
 *
 * @property method `GET` or `POST`.
 * @property path The path and query, starting with `/`.
 * @property headers Extra headers (`Authorization`); `Host`, `Content-Length` and `Connection: close` are added.
 * @property body The UTF-8 body, or null.
 */
data class LoopbackRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** What the server answered: its status and its UTF-8 body. */
data class LoopbackResponse(val status: Int, val body: String)

/** Why an exchange gave no response. */
enum class LoopbackFailure(val message: String) {
    /** Nothing listens on the port, or the connection broke. */
    UNREACHABLE("the local relay is unreachable"),

    /** The whole exchange did not fit in its time. */
    TIMED_OUT("the local relay did not answer in time"),

    /** The response was larger than the CLI reads, or not HTTP. */
    UNREADABLE("the local relay's answer could not be read"),

    /** The request would not say what it means once written: a line break or a space in its line or headers. */
    UNWRITABLE("the request to the local relay could not be written"),
}

/** HTTP over the loopback. */
interface LoopbackHttp {
    /**
     * Sends [request] to `127.0.0.1:[port]` and reads the response, all within [timeoutMillis].
     *
     * @return The response, or why there is none.
     */
    fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse>
}

/** The failure of an exchange, carried by [Result]. */
class LoopbackException(val failure: LoopbackFailure) : Exception(failure.message)

/**
 * [LoopbackHttp] over a plain TCP socket.
 *
 * @param tcp One TCP exchange: port, request bytes, response capacity, timeout; the response bytes or a negative code
 * (-1 unreachable, -2 deadline, -3 too large). The native socket by default.
 */
class LoopbackHttpImpl(
    private val tcp: (Int, ByteArray, Int, Int) -> Pair<Int, ByteArray> = ::tcpExchange,
) : LoopbackHttp {
    override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
        if (!HttpMessages.isWritable(request)) return Result.failure(LoopbackException(LoopbackFailure.UNWRITABLE))
        val (count, bytes) = tcp(port, HttpMessages.encode(request, port), MAX_RESPONSE_BYTES, timeoutMillis)
        val failure = when (count) {
            TCP_UNREACHABLE -> LoopbackFailure.UNREACHABLE
            TCP_TIMED_OUT -> LoopbackFailure.TIMED_OUT
            else -> null
        }
        val response = if (failure != null || count < 0) null else HttpMessages.decode(bytes.copyOf(count))
        return response?.let { Result.success(it) }
            ?: Result.failure(LoopbackException(failure ?: LoopbackFailure.UNREADABLE))
    }

    private companion object {
        /** The relay answers JSON of a few kilobytes; a catalogue or a package listing stays far below. */
        const val MAX_RESPONSE_BYTES = 4 shl 20
    }
}

/** The bytes of HTTP/1.1 messages, written and read without a library. */
object HttpMessages {
    private const val CRLF = "\r\n"
    private val STATUS_LINE = Regex("^HTTP/1\\.[01] (\\d{3})")

    /**
     * Whether [request] can be written without changing what it says: a token for the method and each header name, a
     * path and values without a line break (which would add a header or a second request), no header the encoder sets.
     */
    fun isWritable(request: LoopbackRequest): Boolean = TOKEN.matches(request.method) &&
        PATH.matches(request.path) &&
        request.headers.all { (name, value) ->
            TOKEN.matches(name) && name.lowercase() !in SET_HERE && VALUE.matches(value)
        }

    /**
     * [request] as the bytes to send to `127.0.0.1:[port]` (check it with [isWritable] first). A body is JSON; a
     * request without one other than `GET` says `Content-Length: 0`.
     */
    fun encode(request: LoopbackRequest, port: Int): ByteArray {
        val body = request.body?.encodeToByteArray()
        val head = buildString {
            append("${request.method} ${request.path} HTTP/1.1$CRLF")
            append("Host: 127.0.0.1:$port$CRLF")
            for ((name, value) in request.headers) append("$name: $value$CRLF")
            if (body != null) append("Content-Type: application/json$CRLF")
            if (body != null || request.method != "GET") append("Content-Length: ${body?.size ?: 0}$CRLF")
            append("Connection: close$CRLF$CRLF")
        }
        return head.encodeToByteArray() + (body ?: ByteArray(0))
    }

    /**
     * Reads a whole response (the server closed the connection after it). The body is what follows the headers, cut
     * to `Content-Length` when given; a chunked body is decoded.
     *
     * @return The response, or null when the bytes are not an HTTP/1.x response, or are cut short of their length.
     */
    fun decode(bytes: ByteArray): LoopbackResponse? {
        val end = indexOf(bytes, HEAD_END)
        val head = if (end < 0) emptyList() else bytes.decodeToString(0, end).split(CRLF)
        val statusLine = head.firstOrNull()?.let { STATUS_LINE.find(it) }
        return statusLine?.let {
            response(it.groupValues[1].toInt(), head.drop(1), bytes.copyOfRange(end + HEAD_END.size, bytes.size))
        }
    }

    private fun response(status: Int, lines: List<String>, raw: ByteArray): LoopbackResponse? {
        val headers = lines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull()
        val body = when {
            headers["transfer-encoding"]?.lowercase() == "chunked" -> dechunk(raw)
            length == null -> raw
            // a negative length is no length, a body shorter than its own is a relay that died midway
            length < 0 || length > raw.size -> null
            else -> raw.copyOf(length)
        }
        return body?.let { LoopbackResponse(status, it.decodeToString()) }
    }

    /** The chunks' data, copied once into one array (a body in many small chunks stays linear). */
    private fun dechunk(raw: ByteArray): ByteArray? {
        val ranges = mutableListOf<IntRange>()
        var chunk = chunkAt(raw, 0)
        while (chunk != null && !chunk.first.isEmpty()) {
            ranges += chunk.first
            chunk = chunkAt(raw, chunk.second)
        }
        if (chunk == null) return null
        val out = ByteArray(ranges.sumOf { it.last - it.first + 1 })
        var at = 0
        for (range in ranges) {
            raw.copyInto(out, at, range.first, range.last + 1)
            at += range.last - range.first + 1
        }
        return out
    }

    /**
     * Where the data of the chunk at [at] is and where the next one starts, or null when the bytes are not a chunk
     * (a size that is not hex, negative, or past the end).
     */
    private fun chunkAt(raw: ByteArray, at: Int): Pair<IntRange, Int>? {
        val lineEnd = indexOf(raw, CRLF_BYTES, at)
        val line = if (lineEnd < 0) null else raw.decodeToString(at, lineEnd)
        val size = line?.let { it.substringBefore(';').trim().toIntOrNull(HEX) }
        val start = lineEnd + CRLF_BYTES.size
        return if (size == null || size < 0 || size > raw.size - start) {
            null
        } else {
            start until start + size to start + size + CRLF_BYTES.size
        }
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int = 0): Int {
        for (i in from..bytes.size - needle.size) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) return i
        }
        return -1
    }

    private const val HEX = 16
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val PATH = Regex("/[^\\s]*")
    private val VALUE = Regex("[^\\r\\n]*")
    private val SET_HERE = setOf("host", "content-length", "content-type", "connection", "transfer-encoding")
    private val CRLF_BYTES = CRLF.encodeToByteArray()
    private val HEAD_END = "$CRLF$CRLF".encodeToByteArray()
}

/** The native socket's "unreachable". */
internal const val TCP_UNREACHABLE = -1

/** The native socket's "deadline". */
internal const val TCP_TIMED_OUT = -2

/** The native socket's "response too large". */
internal const val TCP_TOO_LARGE = -3

/**
 * Platform half of [LoopbackHttpImpl]: one TCP exchange with 127.0.0.1:[port].
 *
 * @return The byte count (or -1 unreachable, -2 deadline, -3 too large) and the buffer holding the response.
 */
internal expect fun tcpExchange(
    port: Int,
    request: ByteArray,
    capacity: Int,
    timeoutMillis: Int,
): Pair<Int, ByteArray>
