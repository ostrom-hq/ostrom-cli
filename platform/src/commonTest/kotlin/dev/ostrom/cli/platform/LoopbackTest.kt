// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackTest {
    private fun response(text: String) = text.encodeToByteArray()

    @Test
    fun aRequestIsWrittenWithItsLengthAndClosesTheConnection() {
        val post = LoopbackRequest("POST", "/api/x?a=1", mapOf("Authorization" to "Bearer t"), "{\"é\":1}")
        assertEquals(
            "POST /api/x?a=1 HTTP/1.1\r\nHost: 127.0.0.1:4100\r\nAuthorization: Bearer t\r\n" +
                "Content-Type: application/json\r\nContent-Length: 8\r\nConnection: close\r\n\r\n{\"é\":1}",
            HttpMessages.encode(post, 4100).decodeToString(),
        )
        assertEquals(
            "GET / HTTP/1.1\r\nHost: 127.0.0.1:1\r\nConnection: close\r\n\r\n",
            HttpMessages.encode(LoopbackRequest("GET", "/"), 1).decodeToString(),
        )
        assertEquals(
            "POST /x HTTP/1.1\r\nHost: 127.0.0.1:1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            HttpMessages.encode(LoopbackRequest("POST", "/x"), 1).decodeToString(),
            "a request without a body says so",
        )
    }

    @Test
    fun aRequestThatWouldSaySomethingElseOnceWrittenIsNeverSent() {
        val unsent = LoopbackHttpImpl { _, _, _, _ -> error("nothing is sent") }
        val requests = listOf(
            LoopbackRequest("GET", "/a\r\nX-Injected: 1"),
            LoopbackRequest("GET", "/a b"),
            LoopbackRequest("GET", "a"),
            LoopbackRequest("G T", "/"),
            LoopbackRequest("GET", "/", mapOf("X" to "1\r\nX-Injected: 2")),
            LoopbackRequest("GET", "/", mapOf("Bad Name" to "1")),
            LoopbackRequest("POST", "/", mapOf("Content-Type" to "text/plain"), "x"),
        )
        for (request in requests) {
            val failure = unsent.exchange(1, request, 1).exceptionOrNull() as LoopbackException
            assertEquals(LoopbackFailure.UNWRITABLE, failure.failure, request.toString())
        }
        assertTrue(HttpMessages.isWritable(LoopbackRequest("GET", "/x?a=%20", mapOf("Authorization" to "Bearer t"))))
    }

    @Test
    fun aResponseIsReadByItsLengthOrWhole() {
        val text = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX: y\r\nbad\r\n\r\n{}trailing"
        val sized = HttpMessages.decode(response(text))
        assertEquals(LoopbackResponse(200, "{}"), sized)
        val gone = HttpMessages.decode(response("HTTP/1.0 404 Not Found\r\n\r\ngone"))
        assertEquals(LoopbackResponse(404, "gone"), gone)
        val short = HttpMessages.decode(response("HTTP/1.1 500 x\r\nContent-Length: 9\r\n\r\nab"))
        assertNull(short, "a relay that died midway is no answer")
        assertNull(HttpMessages.decode(response("HTTP/1.1 200 x\r\nContent-Length: -1\r\n\r\nab")), "a negative length")
        val nan = HttpMessages.decode(response("HTTP/1.1 200 x\r\ncontent-length: x\r\n\r\nab"))
        assertEquals(LoopbackResponse(200, "ab"), nan)
        val identity = HttpMessages.decode(response("HTTP/1.1 200 x\r\nTransfer-Encoding: identity\r\n\r\nab"))
        assertEquals(LoopbackResponse(200, "ab"), identity)
    }

    @Test
    fun aChunkedResponseIsDecoded() {
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\n\r\n4;x=1\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n"
        assertEquals(LoopbackResponse(200, "{\"a\":1}"), HttpMessages.decode(response(chunked)))
        val head = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        assertNull(HttpMessages.decode(response(head + "4\r\nab")), "a chunk cut short")
        assertNull(HttpMessages.decode(response(head + "zz\r\nab\r\n")), "a size that is not hex")
        assertNull(HttpMessages.decode(response(head + "2\r\nab\r\n")), "no last chunk")
        assertNull(HttpMessages.decode(response(head + "-1\r\nab\r\n0\r\n\r\n")), "a negative size")
        assertNull(HttpMessages.decode(response(head + "7fffffff\r\nab\r\n")), "a size past the end")
        assertNull(HttpMessages.decode(response(head + "2\r\nabXX1\r\nc\r\n0\r\n\r\n")), "data without its CRLF")
        assertNull(HttpMessages.decode(response(head + "2\r\nab")), "data cut before its CRLF")
        assertNull(HttpMessages.decode(response(head + "2\r\nab\r")), "data cut inside its CRLF")
        assertNull(HttpMessages.decode(response(head + "2\r\nab\rX0\r\n\r\n")), "data followed by a lone CR")
        val gzip = "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n2\r\nab\r\n0\r\n\r\n"
        assertNull(HttpMessages.decode(response(gzip)), "a coding the reader does not undo")
        val listed = "HTTP/1.1 200 OK\r\nTransfer-Encoding: identity, chunked\r\n\r\n2\r\nab\r\n0\r\n\r\n"
        assertEquals(LoopbackResponse(200, "ab"), HttpMessages.decode(response(listed)), "identity is no coding")
        val many = (1..2_000).joinToString("") { "1\r\nx\r\n" } + "0\r\n\r\n"
        assertEquals(2_000, HttpMessages.decode(response(head + many))?.body?.length, "many small chunks")
    }

    @Test
    fun whatIsNotAnHttpResponseIsUnreadable() {
        assertNull(HttpMessages.decode(response("HTTP/1.1 200 OK\r\n")))
        assertNull(HttpMessages.decode(response("SSH-2.0\r\n\r\n")))
        assertNull(HttpMessages.decode(ByteArray(0)))
    }

    @Test
    fun theExchangeSaysWhyThereIsNoResponse() {
        fun http(count: Int, text: String = "") = LoopbackHttpImpl { _, _, _, _ -> count to text.encodeToByteArray() }
        val request = LoopbackRequest("GET", "/")
        val ok = "HTTP/1.1 200 OK\r\n\r\n{}junk"
        assertEquals(LoopbackResponse(200, "{}"), http(ok.length - 4, ok).exchange(1, request, 10).getOrThrow())
        for ((count, failure) in listOf(
            -1 to LoopbackFailure.UNREACHABLE,
            -2 to LoopbackFailure.TIMED_OUT,
            -3 to LoopbackFailure.UNREADABLE,
            0 to LoopbackFailure.UNREADABLE,
        )) {
            val error = assertFailsWith<LoopbackException> { http(count).exchange(1, request, 10).getOrThrow() }
            assertEquals(failure, error.failure)
            assertEquals(failure.message, error.message)
        }
    }

    @Test
    fun theNativeSocketReachesNothingOnAClosedPort() {
        val result = LoopbackHttpImpl().exchange(1, LoopbackRequest("GET", "/"), 500)
        assertEquals(LoopbackFailure.UNREACHABLE, (result.exceptionOrNull() as LoopbackException).failure)
    }
}
