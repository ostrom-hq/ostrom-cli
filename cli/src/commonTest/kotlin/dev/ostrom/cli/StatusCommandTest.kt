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

    private fun serving(routes: Map<String, Pair<Int, String>>) = cli(environment = withServer, api = serverOf(routes))

    private fun pinKey(kid: String = "a1b2c3d4") {
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write("/home/maya/.config/ostrom/server-keys.json".toPath()) {
            writeUtf8("""{"https://ostrom.example":{"kid":"$kid","publicKeyPem":"x"}}""")
        }
    }

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
        console.out.clear()
        assertEquals(0, serving(bare).run(arrayOf("status", "--json")))
        assertEquals(
            """{"login":"maya","organization":null,"role":null,"server":"https://ostrom.example",""" +
                """"repository":null,"harness":null,"waiting":null,"version":"${BuildInfo.VERSION}",""" +
                """"pinnedKey":null}""" + "\n",
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
    fun theWaitingCountReadsEveryPageOfTheInbox() {
        signIn("/home/maya/.config/ostrom")
        val paged = mapOf(
            me,
            inbox to (200 to """{"items":[{}],"next":"n 1"}"""),
            "$inbox&before=n%201" to (200 to """{"items":[{},{}],"next":""}"""),
        )
        assertEquals(0, serving(paged).run(arrayOf("status")))
        assertOut("Waiting     3 requests need your yes (ostrom inbox)\n")
        console.out.clear()
        val endless = mapOf(
            me,
            inbox to (200 to """{"items":[{}],"next":"c"}"""),
            "$inbox&before=c" to (200 to """{"items":[{}],"next":"c"}"""),
        )
        assertEquals(0, serving(endless).run(arrayOf("status")))
        assertOut("Waiting     20 requests need your yes (ostrom inbox)\n")
        console.out.clear()
        val broken = mapOf(me, inbox to (200 to """{"items":[{}],"next":"c"}"""), "$inbox&before=c" to (500 to "{}"))
        assertEquals(0, serving(broken).run(arrayOf("status")))
        assertOut("Waiting     unknown\n")
    }

    @Test
    fun theRepositoryIsTheOneTheLaunchWouldName() {
        signIn("/home/maya/.config/ostrom")
        fileSystem.createDirectories("/work/acme/.git".toPath())
        val routes = mapOf(
            me,
            "GET /api/v1/packages/current?repo=acme-payments" to (200 to """{"version":"p2026-4"}"""),
        )
        val named = withServer + ("OSTROM_REPO" to "acme-payments")
        assertEquals(0, cli(environment = named, api = serverOf(routes)).run(arrayOf("status")))
        assertOut("Repository  acme-payments · harness p2026-4\n")
        console.out.clear()
        val revoked = mapOf(
            me,
            "GET /api/v1/packages/current?repo=acme-payments" to (200 to """{"version":"p2026-4","revoked":true}"""),
        )
        assertEquals(0, cli(environment = named, api = serverOf(revoked)).run(arrayOf("status")))
        assertOut("Repository  acme-payments · no harness yet\n")
        console.out.clear()
        val empty = withServer + ("OSTROM_REPO" to "")
        assertEquals(0, cli(environment = empty, api = serverOf(routes)).run(arrayOf("status")))
        assertOut("Repository  acme · no harness yet\n")
    }

    @Test
    fun aFailingServerStillShowsTheDeviceAndJsonStaysJson() {
        signIn("/home/maya/.config/ostrom")
        pinKey()
        val failing = mapOf("GET /api/v1/auth/me" to (503 to "{}"))
        assertEquals(69, serving(failing).run(arrayOf("status")))
        assertEquals("This device ostrom ${BuildInfo.VERSION} · key a1b2c3d4 pinned\n", console.out.toString())
        assertTrue(console.err.endsWith("! Ostrom answers HTTP 503: server lines not shown.\n"), console.err.toString())
        console.out.clear()
        assertEquals(69, serving(failing).run(arrayOf("status", "--json")))
        assertEquals(
            """{"login":"maya","organization":null,"role":null,"server":"https://ostrom.example",""" +
                """"repository":null,"harness":null,"waiting":null,"version":"${BuildInfo.VERSION}",""" +
                """"pinnedKey":"a1b2c3d4"}""" + "\n",
            console.out.toString(),
        )
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

    @Test
    fun theServersProfileNamesTheLoginWithoutAnOrganization() {
        signIn("/home/maya/.config/ostrom")
        val profile = """{"id":"u1","login":"maya-k","displayName":"Maya","emails":[],"providers":["github"]}"""
        val routes = mapOf("GET /api/v1/auth/me" to (200 to profile), inbox to (200 to """{"items":[]}"""))
        assertEquals(0, serving(routes).run(arrayOf("status")))
        assertOut("Signed in   @maya-k · ostrom.example\n")
    }

    @Test
    fun theHomeScreenSaysWhoIsSignedIn() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(0, cli(environment = withServer).run(emptyArray()))
        assertOut("Ostrom ${BuildInfo.VERSION}\nSigned in as @maya\n")
        console.out.clear()
        assertEquals(0, cli(environment = emptyMap()).run(emptyArray()))
        assertOut("Not signed in. Next: ostrom login\n")
    }
}
