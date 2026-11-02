// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UnixSocketsTest {
    @Test
    fun theRequestIsSentAndTheAnswerReadWhole() {
        val sent = mutableListOf<String>()
        val sockets = UnixSocketsImpl({ path, request, capacity, timeout ->
            sent += "$path ${request.decodeToString()} $capacity $timeout"
            val answer = "{\"exit\":0}\n…".encodeToByteArray()
            answer.size to answer + ByteArray(8)
        }, capacity = 64)
        assertEquals("{\"exit\":0}\n…", sockets.exchange("/run/x.sock", "{}\n", 900).getOrThrow())
        assertEquals(listOf("/run/x.sock {}\n 64 900"), sent)
    }

    @Test
    fun theExchangeSaysWhyThereIsNoAnswer() {
        fun failure(count: Int) = (
            UnixSocketsImpl({ _, _, _, _ -> count to ByteArray(0) })
                .exchange("/s", "{}", 1).exceptionOrNull() as UnixSocketException
            ).failure
        assertEquals(UnixSocketFailure.UNREACHABLE, failure(-1))
        assertEquals(UnixSocketFailure.TIMED_OUT, failure(-2))
        assertEquals(UnixSocketFailure.TOO_LARGE, failure(-3))
        assertEquals(UnixSocketFailure.UNREACHABLE, failure(-7), "any other failure of the socket")
        assertEquals("nothing answers on the socket", UnixSocketFailure.UNREACHABLE.message)
    }

    @Test
    fun theNativeSocketReachesNothingWhereNothingListens() {
        val path = "/nonexistent/ostrom-${Random.nextLong()}.sock"
        val result = UnixSocketsImpl().exchange(path, "{}\n", 500)
        assertEquals(UnixSocketFailure.UNREACHABLE, (result.exceptionOrNull() as UnixSocketException).failure)
    }
}
