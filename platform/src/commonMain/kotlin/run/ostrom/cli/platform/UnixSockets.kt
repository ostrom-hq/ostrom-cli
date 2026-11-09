// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/** Why an exchange on a Unix socket gave no answer. */
enum class UnixSocketFailure(val message: String) {
    /** Nothing listens at the path, or the connection broke. */
    UNREACHABLE("nothing answers on the socket"),

    /** The whole exchange did not fit in its time. */
    TIMED_OUT("no answer in time on the socket"),

    /** The answer was larger than the CLI reads. */
    TOO_LARGE("the answer on the socket is too large"),
}

/** The failure of an exchange, carried by [Result]. */
class UnixSocketException(val failure: UnixSocketFailure) : Exception(failure.message)

/** One request and its answer over a Unix socket of this machine: the server reads the request, answers, closes. */
fun interface UnixSockets {
    /**
     * Sends [request] to the server listening at [path] and reads its answer until it closes, all within
     * [timeoutMillis].
     *
     * @return The answer, as UTF-8, or why there is none.
     */
    fun exchange(path: String, request: String, timeoutMillis: Int): Result<String>
}

/**
 * [UnixSockets] over the native socket.
 *
 * @param unix One exchange: path, request bytes, answer capacity, timeout; the answer bytes or a negative code (-1
 * unreachable, -2 deadline, -3 too large). The native socket by default.
 * @param capacity The largest answer read.
 */
class UnixSocketsImpl(
    private val unix: (String, ByteArray, Int, Int) -> Pair<Int, ByteArray> = ::unixExchange,
    private val capacity: Int = MAX_ANSWER_BYTES,
) : UnixSockets {
    override fun exchange(path: String, request: String, timeoutMillis: Int): Result<String> {
        val (count, bytes) = unix(path, request.encodeToByteArray(), capacity, timeoutMillis)
        val failure = when {
            count == TCP_TIMED_OUT -> UnixSocketFailure.TIMED_OUT
            count == TCP_TOO_LARGE -> UnixSocketFailure.TOO_LARGE
            count < 0 -> UnixSocketFailure.UNREACHABLE
            else -> null
        }
        return if (failure == null) {
            Result.success(bytes.decodeToString(0, count))
        } else {
            Result.failure(UnixSocketException(failure))
        }
    }

    private companion object {
        /** A check's answer carries the tail of its output: a few kilobytes. */
        const val MAX_ANSWER_BYTES = 1 shl 18
    }
}

/**
 * Platform half of [UnixSocketsImpl]: one exchange with the server listening on the Unix socket at [path].
 *
 * @return The byte count (or -1 unreachable, -2 deadline, -3 too large) and the buffer holding the answer.
 */
internal expect fun unixExchange(
    path: String,
    request: ByteArray,
    capacity: Int,
    timeoutMillis: Int,
): Pair<Int, ByteArray>
