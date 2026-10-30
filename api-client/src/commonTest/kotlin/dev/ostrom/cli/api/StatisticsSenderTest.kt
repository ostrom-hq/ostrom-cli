// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatisticsSenderTest {
    @Test
    fun postsTheJsonBody() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://eu.i.posthog.com/batch/", request.url.toString())
            val content = request.body as TextContent
            assertEquals(ContentType.Application.Json, content.contentType.withoutParameters())
            seen += content.text
            respond("")
        }
        StatisticsSender(HttpClient(engine)).send("https://eu.i.posthog.com/batch/", """{"a":1}""")

        assertEquals(listOf("""{"a":1}"""), seen)
    }

    @Test
    fun aFailureIsDroppedButCancellationIsNot() = runTest {
        StatisticsSender(HttpClient(MockEngine { throw IOException("offline") })).send("https://x/batch/", "{}")

        assertFailsWith<CancellationException> {
            StatisticsSender(HttpClient(MockEngine { throw CancellationException("stop") })).send("https://x/", "{}")
        }
    }

    @Test
    fun theDefaultClientGivesUpQuickly() {
        StatisticsSender()
        assertEquals(400L, STATISTICS_TIMEOUT_MILLIS)
    }
}
