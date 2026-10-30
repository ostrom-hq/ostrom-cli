// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerUnreachableException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom status`. */
class StatusCommandTest : CliKit() {
    private val me = "GET /api/v1/auth/me" to
        (200 to """{"dev":{"login":"maya-k"},"organization":{"name":"acme"},"member":{"role":"member"}}""")
    private val inbox = "GET /api/v1/approvals?mine=true&status=pending"

    @Test
    fun statusSaysWhoTheRepositorysHarnessTheYesesAndTheDevice() {
        signIn("/home/maya/.config/ostrom")
        pinKey()
        fileSystem.createDirectories("/work/acme/.git".toPath())
        val routes = mapOf(
            me,
            "GET /api/v1/packages/current?repo=acme" to (200 to """{"version":"p2026-3"}"""),
            inbox to (200 to """{"items":[{"id":"g1"},{"id":"g2"}]}"""),
        )
        assertEquals(0, serving(routes).run(arrayOf("status")))
        assertEquals(
            "Signed in   @maya-k · acme (ostrom.example) · member\n" +
                "Repository  acme · harness p2026-3\n" +
                "Waiting     2 requests need your yes (ostrom inbox)\n" +
                "This device ostrom ${BuildInfo.VERSION} · key a1b2c3d4 pinned\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, serving(routes).run(arrayOf("status", "--json")))
        assertEquals(
            """{"login":"maya-k","organization":"acme","role":"member","server":"https://ostrom.example",""" +
                """"repository":"acme","harness":"p2026-3","waiting":2,"version":"${BuildInfo.VERSION}",""" +
                """"pinnedKey":"a1b2c3d4"}""" + "\n",
            console.out.toString(),
        )
    }

    @Test
    fun statusSaysWhatItCouldNotLearn() {
        signIn("/home/maya/.config/ostrom")
        val bare = mapOf("GET /api/v1/auth/me" to (200 to "{}"), inbox to (500 to "{}"))
        assertEquals(0, serving(bare).run(arrayOf("status")))
        assertEquals(
            "Signed in   @maya · ostrom.example\nRepository  not in a git repository\nWaiting     unknown\n" +
                "This device ostrom ${BuildInfo.VERSION} · no key pinned yet\n",
            console.out.toString(),
        )
        fileSystem.createDirectories("/work/.git".toPath())
        for ((items, line) in listOf("[]" to "nothing waits for your yes", "[{}]" to "1 request needs your yes")) {
            console.out.clear()
            assertEquals(0, serving(mapOf(me, inbox to (200 to """{"items":$items}"""))).run(arrayOf("status")))
            assertOut("Repository  work · no harness yet\n")
            assertOut("Waiting     $line")
        }
    }

    @Test
    fun statusOfflineShowsTheDeviceOnlyAndSignedOutIs65() {
        signIn("/home/maya/.config/ostrom")
        pinKey()
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = throw ServerUnreachableException(url, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        assertEquals(69, cli(environment = withServer, api = down).run(arrayOf("status")))
        assertEquals("This device ostrom ${BuildInfo.VERSION} · key a1b2c3d4 pinned\n", console.out.toString())
        assertTrue(console.err.endsWith("! Ostrom is unreachable: server lines not shown.\n"))
        assertEquals(65, serving(mapOf("GET /api/v1/auth/me" to (401 to "{}"))).run(arrayOf("status")))
        assertEquals(65, cli(environment = withServer).run(arrayOf("status", "--server", "https://other.example")))
    }
}
