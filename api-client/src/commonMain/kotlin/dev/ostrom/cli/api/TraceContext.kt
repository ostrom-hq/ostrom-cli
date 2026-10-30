package dev.ostrom.cli.api

import kotlin.random.Random

/**
 * The W3C trace context of one CLI command, sent as `traceparent` on every call to the server: the server continues
 * the trace, so a slow or failing `ostrom` call is found in the traces by its id (`--debug` prints it). The ids are
 * random and say nothing about the user or the machine.
 *
 * @param random The source of the ids; tests inject a seeded one.
 */
class TraceContext(random: Random = Random.Default) {
    /** 32 lower-case hex digits. */
    val traceId: String = random.hex(TRACE_ID_BYTES)

    /** 16 lower-case hex digits, the id of the CLI's side of the call. */
    val spanId: String = random.hex(SPAN_ID_BYTES)

    /** The `traceparent` header, sampled. */
    val traceparent: String = "00-$traceId-$spanId-01"

    private companion object {
        const val TRACE_ID_BYTES = 16
        const val SPAN_ID_BYTES = 8
        const val LOW_BIT = 1

        /** Random bytes as hex; the last byte is made odd so that an id is never all zeros (invalid in W3C). */
        fun Random.hex(size: Int): String {
            val bytes = nextBytes(size)
            bytes[size - 1] = (bytes[size - 1].toInt() or LOW_BIT).toByte()
            return bytes.toHexString()
        }
    }
}

/** The header name. */
const val TRACEPARENT_HEADER: String = "traceparent"
