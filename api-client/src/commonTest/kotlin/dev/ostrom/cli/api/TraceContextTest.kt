// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraceContextTest {
    @Test
    fun theHeaderFollowsTheW3cFormat() {
        val trace = TraceContext(Random(42))

        assertEquals(32, trace.traceId.length)
        assertTrue(Regex("^00-${trace.traceId}-[0-9a-f]{16}-01$").matches(trace.traceparent()))
        assertEquals(TRACEPARENT_HEADER, "traceparent")
    }

    @Test
    fun idsAreNeverAllZeros() {
        val zeros = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        val trace = TraceContext(zeros)

        assertEquals("0".repeat(31) + "1", trace.traceId)
        assertEquals("00-${"0".repeat(31)}1-${"0".repeat(15)}1-01", trace.traceparent())
    }

    @Test
    fun everyCallOfACommandSharesItsTraceButNotItsParentId() {
        val trace = TraceContext()
        val first = trace.traceparent()
        val second = trace.traceparent()

        assertEquals(first.split("-")[1], second.split("-")[1])
        assertNotEquals(first.split("-")[2], second.split("-")[2])
        assertNotEquals(TraceContext().traceId, trace.traceId)
    }
}
