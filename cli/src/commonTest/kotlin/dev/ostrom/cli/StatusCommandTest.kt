// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.platform.CaptureResult
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom status`, and the home screen that says who is signed in. */
class StatusCommandTest : SetupKit() {
    private val me = "GET /api/v1/auth/me" to (200 to """{"id":"u1","login":"maya-k","displayName":"Maya"}""")
    private val inbox = "GET /api/v1/approvals?mine=true&status=pending"
    private val pack = "GET /api/v1/packages/current?repo=acme"
    private val version = BuildInfo.VERSION

    /** `ostrom status args` against [routes], in the git repository whose top is [top] (null: outside one). */
    private fun status(
        routes: Map<String, Pair<Int, String>>,
        vararg args: String,
        environment: Map<String, String> = withServer,
        top: String? = "/work/acme",
    ): Int {
        val git = if (top == null) FakeMysql(CaptureResult.Exited(128, "")) else gitTop(top)
        return cli(environment = environment, api = serverOf(routes), capture = git).run(arrayOf("status", *args))
    }

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
        val routes = mapOf(
            me,
            orgs(acme),
            pack to (200 to """{"version":"p2026-3"}"""),
            inbox to (200 to """{"items":[{"id":"g1"},{"id":"g2"}]}"""),
        )
        assertEquals(0, status(routes))
        assertEquals(
            "Signed in   @maya-k · Acme (ostrom.example) · admin\n" +
                "Repository  acme · harness p2026-3\n" +
                "Waiting     2 requests need your yes (ostrom inbox)\n" +
                "This device ostrom $version · key a1b2c3d4 pinned\n",
            console.out.toString(),
        )
        val sent = requests.map { it.second }
        val scoped = sent.filter { it.path.startsWith("/api/v1/approvals") || it.path == pack.drop(4) }
        assertTrue(scoped.size == 2 && scoped.all { it.org == "o1" }, "read in the organisation: $scoped")
        console.out.clear()
        assertEquals(0, status(routes, "--json"))
        assertEquals(
            """{"login":"maya-k","organization":"Acme","role":"admin","server":"https://ostrom.example",""" +
                """"repository":"acme","harness":"p2026-3","waiting":2,"waitingComplete":true,""" +
                """"version":"$version","pinnedKey":"a1b2c3d4"}""" + "\n",
            console.out.toString(),
        )
    }

    @Test
    fun statusSaysWhatItCouldNotLearn() {
        signIn("/home/maya/.config/ostrom")
        val bare = mapOf("GET /api/v1/auth/me" to (200 to "{}"), inbox to (403 to "{}"))
        assertEquals(0, status(bare, top = null))
        assertEquals(
            "Signed in   @maya · ostrom.example\nRepository  not in a git repository\nWaiting     unknown\n" +
                "This device ostrom $version · no key pinned yet\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, status(bare, "--json", top = null))
        assertEquals(
            """{"login":"maya","organization":null,"role":null,"server":"https://ostrom.example",""" +
                """"repository":null,"harness":null,"waiting":null,"waitingComplete":null,""" +
                """"version":"$version","pinnedKey":null}""" + "\n",
            console.out.toString(),
        )
        for ((body, login) in listOf("""{"dev":{"login":"maya-k"}}""" to "maya-k", """{"dev":{}}""" to "maya")) {
            console.out.clear()
            assertEquals(0, status(mapOf("GET /api/v1/auth/me" to (200 to body)), top = null))
            assertOut("Signed in   @$login · ostrom.example\n")
        }
    }

    @Test
    fun theWaitingCountReadsEveryPageOfTheInboxAndSaysWhenItStopped() {
        signIn("/home/maya/.config/ostrom")
        for ((items, line) in listOf("[]" to "nothing waits for your yes", "[{}]" to "1 request needs your yes")) {
            console.out.clear()
            assertEquals(0, status(mapOf(me, inbox to (200 to """{"items":$items}""")), top = null))
            assertOut("Waiting     $line")
        }
        console.out.clear()
        val paged = mapOf(
            me,
            inbox to (200 to """{"items":[{}],"next":"n 1"}"""),
            "$inbox&before=n%201" to (200 to """{"items":[{},{}],"next":""}"""),
        )
        assertEquals(0, status(paged, top = null))
        assertOut("Waiting     3 requests need your yes (ostrom inbox)\n")
        console.out.clear()
        val endless = mapOf(
            me,
            inbox to (200 to """{"items":[{}],"next":"c"}"""),
            "$inbox&before=c" to (200 to """{"items":[{}],"next":"c"}"""),
        )
        assertEquals(0, status(endless, top = null))
        assertOut("Waiting     20+ requests need your yes (ostrom inbox)\n")
        console.out.clear()
        assertEquals(0, status(endless, "--json", top = null))
        assertOut(""""waiting":20,"waitingComplete":false""")
        console.out.clear()
        val broken = mapOf(me, inbox to (200 to """{"items":[{}],"next":"c"}"""), "$inbox&before=c" to (503 to "{}"))
        assertEquals(69, status(broken, top = null), "a server that fails is not a healthy status")
        assertOut("Waiting     unknown\n")
        assertTrue(console.err.endsWith("! Ostrom answers HTTP 503: some lines are unknown.\n"), console.err.toString())
    }

    @Test
    fun theRepositoryIsTheOneTheLaunchWouldNameAndItsHarnessIsKnownOrSaidUnknown() {
        signIn("/home/maya/.config/ostrom")
        val named = withServer + ("OSTROM_REPO" to "acme-payments")
        val payments = "GET /api/v1/packages/current?repo=acme-payments"
        val cases = listOf(
            (200 to """{"version":"p2026-4"}""") to "acme-payments · harness p2026-4",
            (200 to """{"version":"p2026-4","revoked":true}""") to "acme-payments · no harness yet",
            (404 to "{}") to "acme-payments · no harness yet",
            (403 to "{}") to "acme-payments · harness unknown",
        )
        for ((answer, line) in cases) {
            console.out.clear()
            assertEquals(0, status(mapOf(me, payments to answer), environment = named, top = null))
            assertOut("Repository  $line\n")
        }
        console.out.clear()
        assertEquals(69, status(mapOf(me, payments to (503 to "{}")), environment = named, top = null))
        assertOut("Repository  acme-payments · harness unknown\n")
        console.out.clear()
        assertEquals(0, status(mapOf(me), environment = withServer + ("OSTROM_REPO" to "")))
        assertOut("Repository  acme · no harness yet\n")
    }

    @Test
    fun theOrganisationIsTheOneTheCliActsIn() {
        signIn("/home/maya/.config/ostrom")
        val both = mapOf(me, orgs(acme, beta, """{"slug":"no-id"}"""))
        assertEquals(0, status(both, top = null))
        assertOut("Signed in   @maya-k · ostrom.example\n")
        val kept = listOf("beta" to "Beta (ostrom.example) · member", "o1" to "Acme (ostrom.example) · admin")
        for ((org, line) in kept) {
            console.out.clear()
            assertEquals(0, status(both, environment = withServer + ("OSTROM_ORG" to org), top = null))
            assertOut("Signed in   @maya-k · $line\n")
        }
        console.out.clear()
        assertEquals(0, status(mapOf(me, orgs("""{"id":"o9"}""")), top = null))
        assertOut("Signed in   @maya-k · o9 (ostrom.example) · member\n")
        console.out.clear()
        assertEquals(69, status(mapOf(me, "GET /api/v1/orgs" to (502 to "{}")), top = null))
        assertOut("Signed in   @maya-k · ostrom.example\n")
        assertTrue(console.err.endsWith("! Ostrom answers HTTP 502: some lines are unknown.\n"), console.err.toString())
    }

    @Test
    fun aFailingServerStillShowsTheDeviceAndJsonStaysJson() {
        signIn("/home/maya/.config/ostrom")
        pinKey()
        val failing = mapOf("GET /api/v1/auth/me" to (503 to "{}"))
        assertEquals(69, status(failing))
        assertEquals("This device ostrom $version · key a1b2c3d4 pinned\n", console.out.toString())
        assertTrue(console.err.endsWith("! Ostrom answers HTTP 503: server lines not shown.\n"), console.err.toString())
        console.out.clear()
        assertEquals(69, status(failing, "--json"))
        assertEquals(
            """{"login":"maya","organization":null,"role":null,"server":"https://ostrom.example",""" +
                """"repository":null,"harness":null,"waiting":null,"waitingComplete":null,""" +
                """"version":"$version","pinnedKey":"a1b2c3d4"}""" + "\n",
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
        assertEquals("This device ostrom $version · key a1b2c3d4 pinned\n", console.out.toString())
        assertTrue(console.err.endsWith("! Ostrom is unreachable: server lines not shown.\n"))
        assertEquals(65, status(mapOf("GET /api/v1/auth/me" to (401 to "{}"))))
        assertEquals(65, cli(environment = withServer).run(arrayOf("status", "--server", "https://other.example")))
    }

    @Test
    fun aServerAddressWithAPasswordIsNeverShown() {
        val secret = "https://bob:s3cret@ostrom.example"
        signIn("/home/maya/.config/ostrom", server = secret)
        val environment = mapOf("HOME" to "/home/maya", "OSTROM_URL" to secret)
        assertEquals(0, status(mapOf(me), "--json", environment = environment, top = null))
        assertOut(""""server":"https://ostrom.example"""")
        assertEquals(0, status(mapOf(me), environment = environment, top = null))
        assertOut("Signed in   @maya-k · ostrom.example\n")
        assertTrue(!console.out.contains("s3cret"), console.out.toString())
    }

    @Test
    fun theHomeScreenSaysWhoIsSignedIn() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(0, cli(environment = withServer).run(emptyArray()))
        assertOut("Ostrom $version\nSigned in as @maya\n")
        console.out.clear()
        assertEquals(0, cli(environment = emptyMap()).run(emptyArray()))
        assertOut("Not signed in. Next: ostrom login\n")
    }
}
