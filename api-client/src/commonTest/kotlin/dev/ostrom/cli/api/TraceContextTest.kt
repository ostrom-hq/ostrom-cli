// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TraceContextTest {
    @Test
    fun theHeaderFollowsTheW3cFormat() {
        val trace = TraceContext(Random(42))

        assertEquals(32, trace.traceId.length)
        assertEquals(16, trace.spanId.length)
        assertEquals("00-${trace.traceId}-${trace.spanId}-01", trace.traceparent)
        assertEquals(TRACEPARENT_HEADER, "traceparent")
    }

    @Test
    fun idsAreNeverAllZeros() {
        val zeros = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        val trace = TraceContext(zeros)

        assertEquals("0".repeat(31) + "1", trace.traceId)
        assertEquals("0".repeat(15) + "1", trace.spanId)
    }

    @Test
    fun everyCommandGetsItsOwnTrace() {
        assertNotEquals(TraceContext().traceId, TraceContext().traceId)
    }
}
