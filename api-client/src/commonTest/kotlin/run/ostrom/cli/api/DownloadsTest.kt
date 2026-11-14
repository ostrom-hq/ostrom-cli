// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

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

    private val release =
        """{"tag_name":"1.2.3","assets":[{"name":"latest.json","id":11},{"name":"latest.json.sig","id":12}]}"""
    private val base = "https://github.com/ostrom-hq/ostrom-cli/releases/download/1.2.3"
    private val repo = "/repos/ostrom-hq/ostrom-cli/releases"

    @Test
    fun aGitHubReleaseAssetComesThroughTheRestEndpointNotThePublicRedirect() = runTest {
        val asked = mutableListOf<String>()
        val downloads = downloads(
            MockEngine { request ->
                asked += request.url.toString()
                when (request.url.encodedPath) {
                    "$repo/tags/1.2.3" -> {
                        assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
                        respond(release, HttpStatusCode.OK)
                    }
                    "$repo/assets/11" -> {
                        assertEquals("application/octet-stream", request.headers[HttpHeaders.Accept])
                        respond(byteArrayOf(7), HttpStatusCode.OK)
                    }
                    "$repo/assets/12" -> respond(byteArrayOf(8), HttpStatusCode.OK)
                    else -> error("never the public address: ${request.url}")
                }
            },
        )
        assertContentEquals(byteArrayOf(7), assertIs<Download.Bytes>(downloads.fetch("$base/latest.json", 8)).bytes)
        val signature = assertIs<Download.Bytes>(downloads.fetch("$base/latest.json.sig", 8))
        assertContentEquals(byteArrayOf(8), signature.bytes)
        // one lookup of the release's JSON for the two assets
        assertEquals(1, asked.count { it.endsWith("/releases/tags/1.2.3") })
    }

    @Test
    fun theLatestReleaseIsLookedUpAsLatest() = runTest {
        val downloads = downloads(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "$repo/latest" -> respond(release, HttpStatusCode.OK)
                    "$repo/assets/11" -> respond(byteArrayOf(7), HttpStatusCode.OK)
                    else -> error(request.url.toString())
                }
            },
        )
        val latest = "https://github.com/ostrom-hq/ostrom-cli/releases/latest/download/latest.json"
        assertIs<Download.Bytes>(downloads.fetch(latest, 8))
    }

    @Test
    fun aReleaseThatCannotBeReadOrLacksTheAssetIsMissing() = runTest {
        val url = "$base/latest.json"
        val notFound = downloads(MockEngine { respond("no", HttpStatusCode.NotFound) })
        assertEquals(Download.Missing(404), notFound.fetch(url, 8))
        val limited = downloads(MockEngine { respond("rate", HttpStatusCode.Forbidden) })
        assertEquals(Download.Missing(403), limited.fetch(url, 8))
        val declared = headersOf(HttpHeaders.ContentLength, "4294967296")
        val huge = downloads(MockEngine { respond("x", HttpStatusCode.OK, declared) })
        assertIs<Download.TooLarge>(huge.fetch(url, 8))
        val lacking = downloads(MockEngine { respond("""{"assets":[]}""", HttpStatusCode.OK) })
        assertEquals(Download.Missing(404), lacking.fetch(url, 8))
        val bad = listOf(
            "nope",
            """{"assets":""",
            "[]",
            """{"assets":{}}""",
            """{"assets":[1]}""",
            """{"assets":[{"id":1}]}""",
            """{"assets":[{"name":"latest.json","id":"1"}]}""",
            """{"assets":[{"name":"latest.json","id":0}]}""",
            """{"assets":[{"name":"latest.json","id":1.5}]}""",
            "{}",
            """{"assets":[{"name":"latest.json"}]}""",
            """{"assets":[{"id":1,"name":7}]}""",
            """{"assets":[{"id":{},"name":"latest.json"}]}""",
            """{"assets":[{"id":1,"name":{}}]}""",
        )
        for (json in bad) {
            val unreadable = downloads(MockEngine { respond(json, HttpStatusCode.OK) })
            assertEquals(Download.Missing(502), unreadable.fetch(url, 8), json)
        }
    }

    @Test
    fun otherHostsAreFetchedAsTheyAre() = runTest {
        val asked = mutableListOf<String>()
        val downloads = downloads(
            MockEngine { request ->
                asked += request.url.toString()
                respond(byteArrayOf(1), HttpStatusCode.OK)
            },
        )
        val enterprise = "https://git.acme.example/ostrom-hq/ostrom-cli/releases/download/1.2.3/latest.json"
        val urls = listOf(enterprise, "$base/a/b")
        for (url in urls) assertIs<Download.Bytes>(downloads.fetch(url, 8))
        assertEquals(urls, asked)
    }
}
