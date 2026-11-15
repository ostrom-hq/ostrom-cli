// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HeadersBuilder
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
    fun noClientFollowsARedirectItself() {
        // the downloads follow them by hand, under rules (HTTPS, hosts), and carry no token anyway
        val downloads = downloadHttpClient()
        assertNull(downloads.pluginOrNull(HttpRedirect))
        assertNotNull(downloads.pluginOrNull(HttpTimeout))
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

    private fun redirect(location: String?) =
        if (location == null) headersOf() else headersOf(HttpHeaders.Location, location)

    private fun releaseServer(
        asset: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) ->
        io.ktor.client.request.HttpResponseData,
    ) = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/tags/1.2.3")) respond(release, HttpStatusCode.OK) else asset(request)
    }

    private suspend fun refused(code: String, block: suspend () -> Unit) =
        assertEquals(code, assertFailsWith<ReleaseDownloadException> { block() }.code)

    @Test
    fun everyRequestAsksForTheBytesAsTheyAre() = runTest {
        val downloads = downloads(
            MockEngine { request ->
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertNull(request.headers[HttpHeaders.Authorization])
                respond(byteArrayOf(1), HttpStatusCode.OK)
            },
        )
        assertIs<Download.Bytes>(downloads.fetch("https://releases.example/a", 8))
    }

    @Test
    fun anAssetRedirectsOnlyToTheReleaseCdnWithoutGitHubHeaders() = runTest {
        var requests = 0
        val downloads = downloads(
            releaseServer { request ->
                requests++
                if (request.url.host == "api.github.com") {
                    respond("", HttpStatusCode.Found, redirect("$CDN/a?sig=SECRET"))
                } else {
                    assertNull(request.headers[HttpHeaders.Accept])
                    assertNull(request.headers["X-GitHub-Api-Version"])
                    respond(byteArrayOf(5), HttpStatusCode.OK)
                }
            },
        )
        assertContentEquals(byteArrayOf(5), assertIs<Download.Bytes>(downloads.fetch("$base/latest.json", 8)).bytes)
        assertEquals(3, requests)
    }

    @Test
    fun aRedirectLoopStopsAfterFiveRedirects() = runTest {
        var requests = 0
        val downloads = downloads(
            releaseServer {
                requests++
                respond("", HttpStatusCode.Found, redirect("https://objects.githubusercontent.com/loop"))
            },
        )
        refused("release_redirect_invalid") { downloads.fetch("$base/latest.json", 8) }
        assertEquals(1 + 6, requests)
    }

    @Test
    fun aRedirectCannotDowngradeOrLeaveTheReleaseCdn() = runTest {
        val addresses = listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://attacker.example/",
            "https://release-assets.githubusercontent.com.attacker.example/",
            "https://name:password@release-assets.githubusercontent.com/file",
            "https://:password@release-assets.githubusercontent.com/file",
            "https://github.com/elsewhere",
            "https://release-assets.githubusercontent.com:444/file",
            "//objects.githubusercontent.com/file",
            "https://objects.githubusercontent.com/file#fragment",
            "https://objects.githubusercontent.com/file\r\nsecret",
            "https://objects.githubusercontent.com/" + "a".repeat(9000),
            "https://[",
            "",
        )
        for (address in addresses) {
            var requests = 0
            val downloads = downloads(
                releaseServer {
                    requests++
                    respond("", HttpStatusCode.TemporaryRedirect, redirect(address))
                },
            )
            refused("release_redirect_invalid") { downloads.fetch("$base/latest.json", 8) }
            assertEquals(2, requests, address)
        }
    }

    @Test
    fun aRedirectWithoutExactlyOneLocationIsRefused() = runTest {
        val none = downloads(MockEngine { respond("", HttpStatusCode.Found) })
        refused("release_redirect_invalid") { none.fetch("https://releases.example/a", 8) }
        val two = downloads(
            MockEngine {
                val both = headersOf(HttpHeaders.Location, listOf("https://a.example/", "https://b.example/"))
                respond("", HttpStatusCode.Found, both)
            },
        )
        refused("release_redirect_invalid") { two.fetch("https://releases.example/a", 8) }
    }

    @Test
    fun aReleaseLookupIsNeverRedirected() = runTest {
        val downloads = downloads(
            MockEngine { respond("", HttpStatusCode.Found, redirect("$CDN/f")) },
        )
        refused("release_redirect_invalid") { downloads.fetch("$base/latest.json", 8) }
    }

    @Test
    fun aMirrorMayRedirectToAnyHttpsHostButNeverToHttp() = runTest {
        val asked = mutableListOf<String>()
        val mirror = downloads(
            MockEngine { request ->
                asked += request.url.toString()
                if (request.url.host == "mirror.example") {
                    respond("", HttpStatusCode.Found, redirect("https://storage.example/a"))
                } else {
                    respond(byteArrayOf(9), HttpStatusCode.OK)
                }
            },
        )
        assertIs<Download.Bytes>(mirror.fetch("https://mirror.example/a", 8))
        assertEquals(listOf("https://mirror.example/a", "https://storage.example/a"), asked)
        val downgrade = downloads(
            MockEngine { respond("", HttpStatusCode.Found, redirect("http://storage.example/a")) },
        )
        refused("release_redirect_invalid") { downgrade.fetch("https://mirror.example/a", 8) }
    }

    @Test
    fun theRateLimitSaysWhenItResets() = runTest {
        fun limited(status: HttpStatusCode, reset: String?) = DownloadsImpl(
            "ostrom/0.1.0",
            epochSeconds = { NOW },
            factory = {
                HttpClient(
                    MockEngine {
                        val headers = mutableMapOf("x-ratelimit-remaining" to listOf("0"))
                        if (reset != null) headers["x-ratelimit-reset"] = listOf(reset)
                        val answer = HeadersBuilder().apply { headers.forEach { (k, v) -> appendAll(k, v) } }.build()
                        respond("", status, answer)
                    },
                )
            },
        )
        val expected = mapOf(
            "${NOW + 600}" to "GitHub API rate limit reached, it resets in about 10 minutes",
            "${NOW + 30}" to "GitHub API rate limit reached, it resets in about a minute",
            "${NOW - 30}" to "GitHub API rate limit reached, it resets in about a minute",
            "${NOW + 999_999}" to "GitHub API rate limit reached, it resets in about 60 minutes",
            "soon" to "GitHub API rate limit reached",
        )
        for ((reset, detail) in expected) {
            for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
                val error = assertFailsWith<ReleaseDownloadException> {
                    limited(status, reset).fetch("https://releases.example/a", 8)
                }
                assertEquals("release_rate_limited", error.code)
                assertEquals(detail, error.detail)
            }
        }
        val noReset = assertFailsWith<ReleaseDownloadException> {
            limited(HttpStatusCode.Forbidden, null).fetch("https://releases.example/a", 8)
        }
        assertEquals("GitHub API rate limit reached", noReset.detail)
        DownloadsImpl("ostrom/0.1.0") // the real clock, only read when a rate limit answers
    }

    @Test
    fun aForbiddenAnswerWithRequestsLeftOrAnotherStatusIsJustMissing() = runTest {
        val left = downloads(
            MockEngine { respond("", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining", "3")) },
        )
        assertEquals(Download.Missing(403), left.fetch("https://releases.example/a", 8))
        val server = downloads(
            MockEngine { respond("", HttpStatusCode.BadGateway, headersOf("x-ratelimit-remaining", "0")) },
        )
        assertEquals(Download.Missing(502), server.fetch("https://releases.example/a", 8))
    }

    @Test
    fun aCompressedAnswerIsRefusedAndIdentityIsFine() = runTest {
        for (encoding in listOf("gzip", "br")) {
            val downloads = downloads(
                MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, encoded(encoding)) },
            )
            refused("release_encoding_invalid") { downloads.fetch("https://releases.example/a", 8) }
        }
        val identity = downloads(
            MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, encoded("Identity")) },
        )
        assertIs<Download.Bytes>(identity.fetch("https://releases.example/a", 8))
    }

    @Test
    fun aBodyThatIsNotTheAnnouncedSizeIsRefused() = runTest {
        val short = downloads(
            MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5")) },
        )
        refused("release_size_invalid") { short.fetch("https://releases.example/a", 8) }
        val exact = downloads(
            MockEngine { respond(byteArrayOf(1, 2), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "2")) },
        )
        assertIs<Download.Bytes>(exact.fetch("https://releases.example/a", 8))
    }

    @Test
    fun anAssetThatAnswers404ThroughACachedIdIsLookedUpAgainOnce() = runTest {
        var lookups = 0
        val downloads = downloads(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "$repo/tags/1.2.3" -> {
                        lookups++
                        respond(if (lookups == 1) release else release.replace("12", "22"), HttpStatusCode.OK)
                    }
                    "$repo/assets/11" -> respond(byteArrayOf(7), HttpStatusCode.OK)
                    // the release's assets were uploaded again: id 12 is gone, 22 is the signature now
                    "$repo/assets/22" -> respond(byteArrayOf(8), HttpStatusCode.OK)
                    else -> respond("gone", HttpStatusCode.NotFound)
                }
            },
        )
        assertContentEquals(byteArrayOf(7), assertIs<Download.Bytes>(downloads.fetch("$base/latest.json", 8)).bytes)
        assertContentEquals(byteArrayOf(8), assertIs<Download.Bytes>(downloads.fetch("$base/latest.json.sig", 8)).bytes)
        assertEquals(2, lookups)
    }

    @Test
    fun aMissingAssetIsNotLookedUpAgainWhenTheReleaseWasJustRead() = runTest {
        var lookups = 0
        val downloads = downloads(
            MockEngine {
                lookups++
                respond("""{"assets":[]}""", HttpStatusCode.OK)
            },
        )
        assertEquals(Download.Missing(404), downloads.fetch("$base/latest.json", 8))
        assertEquals(1, lookups)
        // now cached: a missing file is looked up once more, in case it was published since
        assertEquals(Download.Missing(404), downloads.fetch("$base/latest.json", 8))
        assertEquals(2, lookups)
    }

    @Test
    fun aDownloadThatOutlastsItsBudgetIsUnreachable() = runTest {
        val downloads = downloads(MockEngine { kotlinx.coroutines.awaitCancellation() })
        val error = assertFailsWith<ServerUnreachableException> { downloads.fetch("https://releases.example/a", 8) }
        assertEquals("https://releases.example/a", error.url)
        val big = 2L * MAX_RELEASE_JSON_BYTES
        val large = assertFailsWith<ServerUnreachableException> { downloads.fetch("https://releases.example/a", big) }
        assertEquals("https://releases.example/a", large.url)
    }

    private fun encoded(encoding: String) = headersOf(HttpHeaders.ContentEncoding, encoding)

    private companion object {
        const val CDN = "https://release-assets.githubusercontent.com"
        const val NOW = 1_700_000_000L
    }
}
