// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.Download
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.platform.SecretStoreException
import run.ostrom.cli.update.ReleaseVectors
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server's capabilities in the commands: login refuses a server whose API this ostrom does not speak, the doctor
 * says which API the server speaks, update checks the new binary against it, and the commands of an optional feature
 * stop on a server that does not serve it.
 */
class CapabilitiesCommandsTest : CliKit() {
    private val home = "/home/maya/.config/ostrom"
    private val capabilitiesRoute = "GET /api/v1/capabilities"
    private val health = "GET /api/v1/health" to
        (200 to """{"status":"ok","version":"${BuildInfo.CONTRACT_VERSION.ifBlank { "0.1.0" }}"}""")

    /** The capabilities asked, by token. */
    private fun asked() = requests.filter { it.second.path == "/api/v1/capabilities" }.map { it.second.token }

    /** [routes], the capabilities route answering a server that cannot be reached. */
    private fun unreachable(routes: Map<String, Pair<Int, String>>): (String) -> OstromApi = { url ->
        val server = serverOf(routes)(url)
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                if (request.path == "/api/v1/capabilities") {
                    throw ServerUnreachableException(url, IOException("connection reset"))
                }
                return server.send(request)
            }
        }
    }

    private fun err() = console.err.toString()

    @Test
    fun loginReadsTheCapabilitiesWithTheNewTokenBeforeKeepingIt() {
        assertEquals(0, cli(environment = withServer, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertEquals(listOf("ost_new"), asked())
        assertEquals("ost_new", secretsKept["https://ostrom.example"])
    }

    @Test
    fun loginToAServerOfAnotherApiKeepsNothingAndRevokesTheToken() {
        val newer = deviceFlow + capabilities(apiVersion = 2)
        assertEquals(69, cli(environment = withServer, api = serverOf(newer)).run(arrayOf("login")))
        val said = "ostrom: https://ostrom.example speaks the Ostrom API v2 (Ostrom 1.4.0), and ostrom " +
            "${BuildInfo.VERSION} speaks v1: update this ostrom: ostrom update. Nothing was kept.\n"
        assertTrue(err().endsWith(said), err())
        assertTrue(secretsKept.isEmpty(), "no token kept")
        assertFalse(fileSystem.exists("$home/config.toml".toPath()), "nor the server")
        val revoked = requests.filter { it.second.path == "/api/v1/auth/logout" }.map { it.second.token }
        assertEquals(listOf("ost_new"), revoked)
        // an older one: the server is the side to update
        val older = deviceFlow + capabilities(apiVersion = 0)
        assertEquals(69, cli(environment = withServer, api = serverOf(older)).run(arrayOf("login")))
        assertTrue(err().contains("ask your admin to update the server. Nothing was kept."), err())
        // nor is an admin token kept
        assertEquals(69, cli(environment = withServer, api = serverOf(newer)).run(arrayOf("login", "--admin")))
        assertFalse(fileSystem.exists("$home/admin.json".toPath()))
    }

    @Test
    fun loginToAServerThatCannotSayWhatItSpeaksKeepsNothing() {
        val failing = deviceFlow + (capabilitiesRoute to (500 to """{"error":{"type":"internal","message":"boom"}}"""))
        assertEquals(69, cli(environment = withServer, api = serverOf(failing)).run(arrayOf("login")))
        assertTrue(err().endsWith("ostrom: https://ostrom.example did not say which Ostrom API it speaks (boom). " +
            "Run ostrom login again. Nothing was kept.\n"), err())
        assertTrue(secretsKept.isEmpty())
    }

    @Test
    fun loginToAServerFromBeforeTheCapabilitiesSaysSoAndSignsIn() {
        // ostrom-server builds of November 2026 before it served the route: refusing would sign nobody in to them
        val older = deviceFlow - capabilitiesRoute
        assertEquals(0, cli(environment = withServer, api = serverOf(older)).run(arrayOf("login")))
        assertTrue(err().contains("! https://ostrom.example does not say which Ostrom API it speaks: it is older " +
            "than this ostrom. Ask your admin to update it.\n"), err())
        assertEquals("ost_new", secretsKept["https://ostrom.example"])
    }

    private fun doctor(api: (String) -> OstromApi, environment: Map<String, String> = withServer) =
        cli(environment = environment, api = api).run(arrayOf("doctor"))

    @Test
    fun theDoctorSaysWhichApiTheServerSpeaks() {
        signIn(home)
        assertEquals(0, doctor(serverOf(mapOf(health, capabilities()))))
        assertOut("  ✓ Signed in as @maya\n")
        assertOut("  ✓ ostrom.example speaks the Ostrom API v1, as this ostrom does (Ostrom 1.4.0)\n")
        assertEquals(listOf("ost_1"), asked())
    }

    @Test
    fun aServerOfAnotherApiIsAProblemForTheDoctor() {
        signIn(home)
        assertEquals(1, doctor(serverOf(mapOf(health, capabilities(apiVersion = 2)))))
        assertOut("  ✗ ostrom.example speaks the Ostrom API v2 (Ostrom 1.4.0), and ostrom ${BuildInfo.VERSION} " +
            "speaks v1: update this ostrom: ostrom update.\n")
    }

    @Test
    fun aServerThatDoesNotSayWhatItSpeaksIsAWarningForTheDoctor() {
        signIn(home)
        assertEquals(0, doctor(serverOf(mapOf(health))))
        assertOut("  ! ostrom.example does not say which Ostrom API it speaks: it is older than this ostrom. " +
            "Ask your admin to update it.\n")
        val refused = capabilitiesRoute to (401 to """{"error":{"type":"unauthorized","message":"no"}}""")
        assertEquals(0, doctor(serverOf(mapOf(health, refused))))
        assertOut("  ! ostrom.example refused this device's token: run ostrom login.\n")
        assertEquals(0, doctor(serverOf(mapOf(health, capabilitiesRoute to (500 to "")))))
        assertOut("  ! ostrom.example did not say which Ostrom API it speaks (HTTP 500).\n")
        assertEquals(0, doctor(unreachable(mapOf(health))))
        assertOut("  ! ostrom.example did not say which Ostrom API it speaks (unreachable).\n")
    }

    @Test
    fun theDoctorReadsTheApiOnlyOfAnOstromServerThisDeviceIsSignedInToOverHttps() {
        assertEquals(0, doctor(serverOf(mapOf(health, capabilities()))))
        assertOut("  ! Not signed in. Run ostrom login.\n")
        signIn(home)
        assertEquals(1, doctor(serverOf(mapOf("GET /api/v1/health" to (503 to "")))), "a server in trouble")
        // the token never travels in clear
        signIn(home, server = "http://ostrom.example")
        val clear = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "http://ostrom.example")
        assertEquals(0, doctor(serverOf(mapOf(health, capabilities())), clear))
        // a secret store that does not answer: its own line says so
        val locked = cli(environment = withServer, api = serverOf(mapOf(health, capabilities())), secrets = {
            throw SecretStoreException("the keyring is locked")
        })
        assertEquals(1, locked.run(arrayOf("doctor")))
        // no CLI folder: no credentials to read
        assertEquals(1, doctor(serverOf(mapOf(health)), mapOf("OSTROM_URL" to "https://ostrom.example")))
        assertTrue(asked().isEmpty(), "never asked: ${asked()}")
    }

    /** Serves the signed release 9.9.9 at `https://releases.example`, and the binary to replace at /opt/ostrom. */
    private fun released(): Map<String, String> {
        val manifest = ReleaseVectors.MANIFESTS.getValue("LATEST")
        val latest = "https://releases.example/latest/download/latest.json"
        releases[latest] = Download.Bytes(manifest.first.encodeToByteArray())
        releases["$latest.sig"] = Download.Bytes(manifest.second.encodeToByteArray())
        releases["https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"] =
            Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray())
        fileSystem.createDirectories("/opt".toPath())
        fileSystem.write("/opt/ostrom".toPath()) { writeUtf8("old") }
        return withServer + ("OSTROM_RELEASES_URL" to "https://releases.example")
    }

    @Test
    fun updateChecksTheNewBinaryAgainstTheApiOfTheServerSignedInTo() {
        val environment = released()
        signIn(home)
        assertEquals(0, cli(environment = environment, api = serverOf(mapOf(capabilities(apiVersion = 2))))
            .run(arrayOf("update")))
        assertEquals(listOf(Triple("/opt/ostrom.update/ostrom", "9.9.9", 2)), probes)
        assertEquals(listOf("ost_1"), asked())
        assertOut("✓ Updated ostrom ${BuildInfo.VERSION} → 9.9.9 (signature checked).\n")
    }

    @Test
    fun updateWithoutTheServerChecksTheNewBinaryAgainstThisOstromsApi() {
        // not signed in: nothing to ask
        val environment = released()
        assertEquals(0, cli(environment = environment, api = serverOf(emptyMap())).run(arrayOf("update")))
        // signed in, the server cannot say: said, and the update goes on (no server at hand is legitimate)
        signIn(home)
        assertEquals(0, cli(environment = environment, api = serverOf(emptyMap())).run(arrayOf("update")))
        assertOut("! https://ostrom.example did not say which Ostrom API it speaks (it is older than this ostrom): " +
            "the new ostrom is checked against the API v1 this ostrom speaks.\n")
        assertEquals(0, cli(environment = environment, api = unreachable(emptyMap())).run(arrayOf("update")))
        assertOut("(unreachable): the new ostrom")
        val failing = mapOf(capabilitiesRoute to (503 to ""))
        assertEquals(0, cli(environment = environment, api = serverOf(failing)).run(arrayOf("update")))
        assertOut("(HTTP 503): the new ostrom")
        // a server in clear is never sent the token
        signIn(home, server = "http://ostrom.example")
        val clear = environment + ("OSTROM_URL" to "http://ostrom.example")
        assertEquals(0, cli(environment = clear, api = serverOf(emptyMap())).run(arrayOf("update")))
        // no CLI folder: no sign-in
        val noFolder = environment - "HOME"
        assertEquals(0, cli(environment = noFolder, api = serverOf(emptyMap())).run(arrayOf("update")))
        assertEquals(List(6) { 1 }, probes.map { it.third })
        assertEquals(List(2) { "ost_1" }, asked(), "the unreachable read never arrived")
    }

    @Test
    fun aCloudSessionNeedsAServerThatRunsThem() {
        signIn(home)
        val arguments = arrayOf("claude", "--cloud")
        assertEquals(69, cli(environment = withServer, api = serverOf(mapOf(capabilities()))).run(arguments))
        assertTrue(err().endsWith("ostrom: This Ostrom server runs no cloud session: start the agent here, without " +
            "--cloud.\n"), err())
        val noCloud = serverOf(mapOf(capabilities()))
        assertEquals(69, cli(environment = withServer, api = noCloud).run(arrayOf("attach", "c")))
        assertTrue(clouds.isEmpty() && attached.isEmpty(), "nothing started nor attached")
        // a server of another API stops it too, and one that cannot be reached
        val newer = serverOf(mapOf(capabilities("runners.cloud.v1", apiVersion = 2)))
        assertEquals(69, cli(environment = withServer, api = newer).run(arguments))
        assertTrue(err().contains("speaks the Ostrom API v2"), err())
        assertEquals(69, cli(environment = withServer, api = unreachable(emptyMap())).run(arguments))
        assertTrue(err().endsWith("ostrom: Ostrom is unreachable at https://ostrom.example. Check your network, or " +
            "the --server URL.\n"), err())
        // a server that fails to answer, or from before the route: its own route answers
        val failing = serverOf(mapOf(capabilitiesRoute to (500 to "")))
        assertEquals(5, cli(environment = withServer, api = failing).run(arguments))
        assertEquals(6, cli(environment = withServer, api = serverOf(emptyMap())).run(arrayOf("attach", "c")))
        assertEquals(1, clouds.size)
        assertEquals(1, attached.size)
    }

    @Test
    fun theImportNeedsAServerThatImportsTeamCodeTeams() {
        val admin = withServer + ("OSTROM_TOKEN" to "ost_console")
        val arguments = arrayOf("import", "teamcode", "--db", "mysql://ro@db/teamcode", "--key-file", "/keys/tc.key")
        assertEquals(69, cli(environment = admin, api = serverOf(mapOf(capabilities()))).run(arguments))
        assertTrue(err().endsWith("ostrom: This Ostrom server does not import TeamCode teams: ask your admin to " +
            "update it.\n"), err())
        assertEquals(listOf("ost_console"), asked())
        assertEquals(listOf("/api/v1/capabilities"), requests.map { it.second.path }, "nothing else asked")
    }
}
