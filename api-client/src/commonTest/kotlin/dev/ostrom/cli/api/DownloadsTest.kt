// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DownloadsTest {
    private fun downloads(engine: MockEngine) =
        DownloadsImpl("ostrom/0.1.0") {
            HttpClient(engine) {
                expectSuccess = false
                install(HttpTimeout)
            }
        }

    @Test
    fun aFileComesWholeWhenItFits() = runTest {
        val downloads = downloads(
            MockEngine { request ->
                assertEquals("ostrom/0.1.0", request.headers[HttpHeaders.UserAgent])
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            },
        )
        val file = assertIs<Download.Bytes>(downloads.fetch("https://releases.example/a.tar.gz", 3))
        assertContentEquals(byteArrayOf(1, 2, 3), file.bytes)
        assertEquals(Download.TooLarge(2), downloads.fetch("https://releases.example/a.tar.gz", 2))
    }

    @Test
    fun aFileSaidToBeTooLargeIsNotRead() = runTest {
        val declared = headersOf(HttpHeaders.ContentLength, "4294967296")
        val downloads = downloads(MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, declared) })
        assertEquals(Download.TooLarge(16), downloads.fetch("https://releases.example/latest.json", 16))
    }

    @Test
    fun anAnswerThatIsNotTheFileSaysItsStatus() = runTest {
        val downloads = downloads(MockEngine { respond("no", HttpStatusCode.NotFound) })
        assertEquals(Download.Missing(404), downloads.fetch("https://releases.example/latest.json", 10))
    }

    @Test
    fun noAnswerIsUnreachableAndACancellationStaysOne() = runTest {
        for (failure in listOf(IOException("refused"), IllegalStateException("tls"))) {
            val downloads = downloads(MockEngine { throw failure })
            val error = assertFailsWith<ServerUnreachableException> { downloads.fetch("https://r.example/x", 1) }
            assertEquals("https://r.example/x", error.url)
        }
        val cancelled = downloads(MockEngine { throw CancellationException("stop") })
        assertFailsWith<CancellationException> { cancelled.fetch("https://r.example/x", 1) }
        DownloadsImpl("ostrom/0.1.0") // the default client, built at the first download only
    }

    @Test
    fun publicFilesFollowTheRedirectOfAGitHubRelease() {
        // the API's client never follows one (a token would go along); the downloads' one must, without a token
        val downloads = downloadHttpClient()
        assertNotNull(downloads.pluginOrNull(HttpRedirect))
        downloads.close()
        val api = defaultHttpClient()
        assertNull(api.pluginOrNull(HttpRedirect))
        api.close()
    }
}
