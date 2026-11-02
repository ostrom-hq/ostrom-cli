// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ostrom login --admin` and the governance writes it allows (decision of 02/11/2026, « écritures de gouvernance
 * depuis la CLI »): the token is kept apart, 8 hours at most, and only the policy and the harness writes use it.
 */
class AdminLoginTest : SetupKit() {
    private val home = "/home/maya/.config/ostrom"
    private val adminFile = "$home/admin.json"
    private val server = "https://ostrom.example"
    private val adminSecret = "$server#admin"
    private val hour = 60 * 60 * 1_000L
    private val scopes = """["policy:write","harness:write","gates:policy"]"""

    /** The device flow, its token answered with [token]. */
    private fun flow(token: String) =
        deviceFlow + ("POST /api/v1/auth/device/token" to (200 to token))

    private fun admin(expiresAt: String?, granted: String = scopes): Map<String, Pair<Int, String>> {
        val expiry = expiresAt?.let { ""","expiresAt":"$it"""" }.orEmpty()
        return flow("""{"token":"ost_adm","login":"maya-k","scopes":$granted$expiry}""")
    }

    private fun login(routes: Map<String, Pair<Int, String>>) =
        cli(environment = withServer, api = serverOf(routes)).run(arrayOf("login", "--admin"))

    private fun adminFile(): String? = fileSystem.takeIf { it.exists(adminFile.toPath()) }
        ?.read(adminFile.toPath()) { readUtf8() }

    /** An admin token `ost_adm` kept until [expiresAtMillis]. */
    private fun keptAdmin(expiresAtMillis: Long) {
        fileSystem.createDirectories(home.toPath())
        fileSystem.write(adminFile.toPath()) { writeUtf8("""{"$server":$expiresAtMillis}""") }
        secretsKept[adminSecret] = "ost_adm"
    }

    @Test
    fun loginAdminAsksForTheGovernanceScopesAndKeepsTheTokenApart() {
        signIn(home)
        assertEquals(0, login(admin("1970-01-12T00:00:00Z")))
        val start = requests.first { it.second.path == "/api/v1/auth/device" }.second
        assertEquals("""{"scopes":$scopes}""", start.body)
        assertTrue(console.err.contains("! Asking for an admin token: policy:write, harness:write, gates:policy. " +
            "An owner or admin approves it in the browser"), console.err.toString())
        // apart from the device's token, which stays as it was; 8 hours at most, whatever the server says
        assertEquals("ost_1", secretsKept[server])
        assertEquals("ost_adm", secretsKept[adminSecret])
        assertEquals("""{"$server":${now + 8 * hour}}""", adminFile())
        assertOut("✓ Admin token for @maya-k on $server until 1970-01-11T08:00:00Z, never renewed.\n")
        assertOut("It changes the policy and the harness: ostrom policy set, ostrom harness enforce\n")
        assertFalse(console.out.contains("ost_adm"), "the token is never printed")
        // a sooner expiry is the server's; one that is not an instant, or none, is 8 hours
        assertEquals(0, login(admin("1970-01-11T01:00:00Z")))
        assertEquals("""{"$server":${now + hour}}""", adminFile())
        for (expiry in listOf("soon", null)) {
            assertEquals(0, login(admin(expiry)))
            assertEquals("""{"$server":${now + 8 * hour}}""", adminFile())
        }
        // the ordinary sign-in asks for no scope
        assertEquals(0, cli(environment = withServer, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertEquals("{}", requests.last { it.second.path == "/api/v1/auth/device" }.second.body)
    }

    @Test
    fun aServerThatGrantsLessKeepsNothing() {
        // a server that does not serve --admin yet answers an ordinary token
        for (granted in listOf("[]", """["policy:write","harness:write"]""")) {
            console.err.clear()
            assertEquals(69, login(admin(null, granted)))
            assertTrue(console.err.contains("did not grant"), console.err.toString())
            assertTrue(console.err.contains("it does not serve ostrom login --admin yet. Nothing was kept."))
        }
        assertTrue(console.err.contains("did not grant gates:policy:"), console.err.toString())
        assertEquals(null, secretsKept[adminSecret])
        assertEquals(null, adminFile())
        // nor is a refused sign-in
        val denied = 400 to """{"error":{"type":"access_denied"}}"""
        val refused = deviceFlow + ("POST /api/v1/auth/device/token" to denied)
        assertEquals(2, login(refused))
    }

    @Test
    fun aGovernanceWriteGoesWithTheAdminTokenWhileItIsValid() {
        keptAdmin(now + hour)
        val models = "GET /api/v1/policy/models"
        val put = "PUT /api/v1/policy/models"
        val policy = """{"allowed":["sonnet","opus"],"default":"sonnet"}"""
        val routes = mapOf(models to (200 to policy), put to (200 to "{}"))
        assertEquals(0, run(routes, "policy", "set", "default-model", "opus"))
        assertEquals("ost_adm", requests.last { it.second.method == "PUT" }.second.token)
        assertEquals("ost_1", requests.last { it.second.method == "GET" }.second.token, "reads go as the device")
        // the self-approval policy and the harness too
        val gates = mapOf("PUT /api/v1/gates/policy" to (200 to "{}"))
        assertEquals(0, run(gates, "policy", "set", "self-approval", "never"))
        assertEquals("ost_adm", requests.last().second.token)
        val workflow = """{"id":"w1","name":"git-safety","status":"proposed","version":3}"""
        val enforce =
            mapOf("GET /api/v1/workflows/w1" to (200 to workflow), "PUT /api/v1/workflows/w1" to (200 to "{}"))
        assertEquals(0, run(enforce, "harness", "enforce", "w1"))
        assertEquals("ost_adm", requests.last().second.token)
        // another write is no governance: it goes as the device
        val invite = mapOf("POST /api/v1/orgs/o1/members/invites" to (200 to """{"invited":["lena@acme.dev"]}"""))
        run(invite, "invite", "lena@acme.dev")
        assertEquals("ost_1", requests.last().second.token)
    }

    @Test
    fun withoutAValidAdminTokenARefusedGovernanceWriteSaysToRunLoginAdmin() {
        val refused = mapOf("PUT /api/v1/gates/policy" to (403 to """{"error":{"message":"gates:policy is needed"}}"""))
        val elevate = "Changing the policy or the harness of acme takes an admin token. Run ostrom login --admin: " +
            "an owner or admin approves it in the browser, for 8 hours at most."
        assertEquals(77, run(refused, "policy", "set", "self-approval", "never"))
        assertErr("gates:policy is needed\n$elevate")
        assertEquals("ost_1", requests.last().second.token)
        // an expired one is forgotten, never renewed, and the device's token goes instead
        console.err.clear()
        keptAdmin(now)
        assertEquals(77, run(refused, "policy", "set", "self-approval", "never"))
        assertEquals("ost_1", requests.last().second.token)
        assertErr(elevate)
        assertEquals(null, secretsKept[adminSecret])
        assertEquals("{}", adminFile())
        // an expiry that does not read is no admin token either; nor is a console token (S2-52)
        fileSystem.write(adminFile.toPath()) { writeUtf8("""{"$server":"tomorrow"}""") }
        assertEquals(77, run(refused, "policy", "set", "self-approval", "never"))
        assertEquals("ost_1", requests.last().second.token)
        console.err.clear()
        assertEquals(77, run(refused, "policy", "set", "self-approval", "never", environment = withServer +
            ("OSTROM_TOKEN" to "ost_console")))
        assertErr(elevate)
    }

    @Test
    fun anAdminTokenTheServerNoLongerTakesIsForgotten() {
        keptAdmin(now + hour)
        val revoked = mapOf("PUT /api/v1/gates/policy" to (401 to """{"error":{"message":"revoked"}}"""))
        assertEquals(65, run(revoked, "policy", "set", "self-approval", "never"))
        assertErr("Your admin token is no longer valid. Run ostrom login --admin")
        assertEquals(null, secretsKept[adminSecret])
        // an admin token refused for its person (no longer an admin) says the server's word only
        console.err.clear()
        keptAdmin(now + hour)
        val only = """{"error":{"message":"Only an owner or admin."}}"""
        val notAdmin = mapOf("PUT /api/v1/gates/policy" to (403 to only))
        assertEquals(77, run(notAdmin, "policy", "set", "self-approval", "never"))
        assertErr("Only an owner or admin.")
        assertFalse(console.err.contains("takes an admin token"), console.err.toString())
        assertEquals("ost_adm", secretsKept[adminSecret], "kept: it is the person who may not")
    }

    @Test
    fun logoutRevokesAndForgetsTheAdminTokenToo() {
        signIn(home)
        keptAdmin(now + hour)
        val logout = mapOf("POST /api/v1/auth/logout" to (204 to ""))
        assertEquals(0, cli(environment = withServer, api = serverOf(logout)).run(arrayOf("logout")))
        assertEquals(listOf("ost_1", "ost_adm"), requests.map { it.second.token })
        assertEquals(null, secretsKept[adminSecret])
        assertEquals("{}", adminFile())
    }
}
