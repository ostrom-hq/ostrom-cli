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
        val (count, bytes) = tcp(port, HttpMessages.encode(request, port), MAX_RESPONSE_BYTES, timeoutMillis)
        val failure = when (count) {
            TCP_UNREACHABLE -> LoopbackFailure.UNREACHABLE
            TCP_TIMED_OUT -> LoopbackFailure.TIMED_OUT
            else -> null
        }
        if (failure != null) return Result.failure(LoopbackException(failure))
        val response = if (count < 0) null else HttpMessages.decode(bytes.copyOf(count))
        return response?.let { Result.success(it) } ?: Result.failure(LoopbackException(LoopbackFailure.UNREADABLE))
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

    /** [request] as the bytes to send to `127.0.0.1:[port]`. */
    fun encode(request: LoopbackRequest, port: Int): ByteArray {
        val body = request.body?.encodeToByteArray()
        val head = buildString {
            append("${request.method} ${request.path} HTTP/1.1$CRLF")
            append("Host: 127.0.0.1:$port$CRLF")
            for ((name, value) in request.headers) append("$name: $value$CRLF")
            if (body != null) {
                append("Content-Type: application/json$CRLF")
                append("Content-Length: ${body.size}$CRLF")
            }
            append("Connection: close$CRLF$CRLF")
        }
        return head.encodeToByteArray() + (body ?: ByteArray(0))
    }

    /**
     * Reads a whole response (the server closed the connection after it). The body is what follows the headers, cut
     * to `Content-Length` when given; a chunked body is decoded.
     *
     * @return The response, or null when the bytes are not an HTTP/1.x response.
     */
    fun decode(bytes: ByteArray): LoopbackResponse? {
        val end = indexOf(bytes, "\r\n\r\n".encodeToByteArray())
        if (end < 0) return null
        val head = bytes.decodeToString(0, end).split(CRLF)
        val statusLine = STATUS_LINE.find(head.first()) ?: return null
        val status = statusLine.groupValues[1].toInt()
        val headers = head.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val raw = bytes.copyOfRange(end + 4, bytes.size)
        val body = when {
            headers["transfer-encoding"]?.lowercase() == "chunked" -> dechunk(raw) ?: return null
            else -> headers["content-length"]?.toIntOrNull()?.let { raw.copyOf(minOf(it, raw.size)) } ?: raw
        }
        return LoopbackResponse(status, body.decodeToString())
    }

    private fun dechunk(raw: ByteArray): ByteArray? {
        var out = ByteArray(0)
        var at = 0
        while (true) {
            val lineEnd = indexOf(raw, CRLF.encodeToByteArray(), at)
            if (lineEnd < 0) return null
            val size = raw.decodeToString(at, lineEnd).substringBefore(';').trim().toIntOrNull(HEX) ?: return null
            if (size == 0) return out
            val start = lineEnd + 2
            if (start + size > raw.size) return null
            out += raw.copyOfRange(start, start + size)
            at = start + size + 2
        }
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int = 0): Int {
        for (i in from..bytes.size - needle.size) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) return i
        }
        return -1
    }

    private const val HEX = 16
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
