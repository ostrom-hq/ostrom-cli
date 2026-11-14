// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The log of what the relay sent is read by the test while the relay's own threads still write it
 * (RelayActivityTest.aReportOstromSaysSupersededIsSentAgainByTheNextHook threw ConcurrentModificationException on
 * linuxX64).
 */
class FakeOstromTest {
    @Test
    fun theRequestsCanBeReadWhileOtherThreadsRecordThem() = runBlocking {
        val ostrom = FakeOstrom { respond("{}") }
        val client = ostrom.client()
        val writers = 8
        val each = 400
        val reader = launch(Dispatchers.Default) {
            while (ostrom.requests.size < writers * each) ostrom.requests.count { it.url.encodedPath == "/x" }
        }
        withContext(Dispatchers.Default) {
            (1..writers).map { async { repeat(each) { client.get("https://ostrom.example/x") } } }.awaitAll()
        }
        reader.join()
        assertEquals(writers * each, ostrom.requests.size, "no request is lost")
    }
}
