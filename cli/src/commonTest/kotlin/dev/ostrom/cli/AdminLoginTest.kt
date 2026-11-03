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
        // no device token yet: the admin token alone, nobody else's to warn about
        assertEquals(0, login(admin(null)))
        assertFalse(console.err.contains("The admin token is"), console.err.toString())
        assertEquals(null, secretsKept[server])
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
        // approved in a browser signed in as someone else than this device: said
        assertTrue(console.err.contains("! The admin token is @maya-k's, not @maya's (this device): the policy and " +
            "the harness change in @maya-k's name."), console.err.toString())
        // a sooner expiry is the server's; one that is not an instant, or none, is 8 hours
        assertEquals(0, login(admin("1970-01-11T01:00:00Z")))
        assertEquals("""{"$server":${now + hour}}""", adminFile())
        for (expiry in listOf("soon", null)) {
            assertEquals(0, login(admin(expiry)))
            assertEquals("""{"$server":${now + 8 * hour}}""", adminFile())
        }
        // the ordinary sign-in asks for no scope; the same person then gets no warning
        assertEquals(0, cli(environment = withServer, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertEquals("{}", requests.last { it.second.path == "/api/v1/auth/device" }.second.body)
        console.err.clear()
        assertEquals(0, login(admin(null)))
        assertFalse(console.err.contains("The admin token is"), console.err.toString())
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
        // the token it was answered is revoked, not left open on the server
        val revoked = requests.filter { it.second.path == "/api/v1/auth/logout" }.map { it.second.token }
        assertEquals(listOf("ost_adm", "ost_adm"), revoked)
        // a token already expired (a clock ahead, a server behind) is kept no more
        console.err.clear()
        assertEquals(69, login(admin("1970-01-10T23:00:00Z")))
        assertTrue(console.err.contains("answered a token that has already expired (check this machine's clock). " +
            "Nothing was kept."), console.err.toString())
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
        // the policy it rewrites is read with the same token: the device's token cannot read it (inference:read)
        assertEquals("ost_adm", requests.last { it.second.method == "GET" }.second.token, "read as the write goes")
        // the self-approval policy and the harness too
        val gates = mapOf("PUT /api/v1/gates/policy" to (200 to "{}"))
        assertEquals(0, run(gates, "policy", "set", "self-approval", "never"))
        assertEquals("ost_adm", requests.last().second.token)
        val workflow = """{"id":"w1","name":"git-safety","status":"proposed","version":3}"""
        val enforce =
            mapOf("GET /api/v1/workflows/w1" to (200 to workflow), "PUT /api/v1/workflows/w1" to (200 to "{}"))
        assertEquals(0, run(enforce, "harness", "enforce", "w1"))
        assertEquals("ost_adm", requests.last().second.token)
        assertEquals("ost_1", requests.last { it.second.method == "GET" }.second.token, "another read: the device")
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
        // a console token set on purpose is never swapped for a valid admin token, and is refused all the same (S2-52)
        console.err.clear()
        keptAdmin(now + hour)
        assertEquals(77, run(refused, "policy", "set", "self-approval", "never", environment = withServer +
            ("OSTROM_TOKEN" to "ost_console")))
        assertEquals("ost_console", requests.last().second.token)
        assertErr(elevate)
        // nor for the read of the model policy before its write
        val policy = mapOf("GET /api/v1/policy/models" to (200 to """{"allowed":["sonnet"],"default":"sonnet"}"""))
        run(policy, "policy", "set", "models", "sonnet", environment = withServer + ("OSTROM_TOKEN" to "ost_console"))
        val read = requests.first { it.second.method == "GET" && "models" in it.second.path }.second
        assertEquals("ost_console", read.token)
    }

    @Test
    fun withoutAnAdminTokenTheReadOfTheModelPolicySaysToRunLoginAdmin() {
        val lacks = """{"error":{"message":"This credential does not allow inference:read."}}"""
        val refused = mapOf("GET /api/v1/policy/models" to (403 to lacks))
        assertEquals(77, run(refused, "policy", "set", "models", "sonnet"))
        assertErr("This credential does not allow inference:read.\nChanging the policy or the harness of acme " +
            "takes an admin token. Run ostrom login --admin")
        assertEquals("ost_1", requests.last().second.token)
        assertTrue(requests.none { it.second.method == "PUT" }, "nothing written")
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
        // an admin token approved before the server gave it the model policy's read: run ostrom login --admin again
        console.err.clear()
        val lacks = """{"error":{"message":"This credential does not allow inference:read."}}"""
        val older = mapOf("GET /api/v1/policy/models" to (403 to lacks))
        assertEquals(77, run(older, "policy", "set", "models", "sonnet"))
        assertErr("This credential does not allow inference:read.\nYour admin token is older than this right: " +
            "Run ostrom login --admin")
        assertTrue(requests.none { it.second.method == "PUT" }, "nothing written")
        // a refusal of the read for its person: the server's word only, the token kept
        console.err.clear()
        val notAdminRead = mapOf("GET /api/v1/policy/models" to (403 to only))
        assertEquals(77, run(notAdminRead, "policy", "set", "models", "sonnet"))
        assertErr("Only an owner or admin.")
        assertFalse(console.err.contains("older than this right"), console.err.toString())
        // refused at the read of the model policy, before its write: the same words
        console.err.clear()
        val gone = mapOf("GET /api/v1/policy/models" to (401 to """{"error":{"message":"revoked"}}"""))
        assertEquals(65, run(gone, "policy", "set", "models", "sonnet"))
        assertErr("Your admin token is no longer valid. Run ostrom login --admin")
        assertEquals(null, secretsKept[adminSecret])
    }

    @Test
    fun logoutRevokesAndForgetsTheAdminTokenToo() {
        signIn(home)
        keptAdmin(now + hour)
        val logout = mapOf("POST /api/v1/auth/logout" to (204 to ""))
        assertEquals(0, cli(environment = withServer, api = serverOf(logout)).run(arrayOf("logout")))
        assertEquals(listOf("ost_adm", "ost_1"), requests.map { it.second.token })
        assertEquals(null, secretsKept[adminSecret])
        assertEquals("{}", adminFile())
        // an admin token alone, without the device's: revoked and forgotten all the same
        requests.clear()
        keptAdmin(now + hour)
        assertEquals(0, cli(environment = withServer, api = serverOf(logout)).run(arrayOf("logout")))
        assertEquals(listOf("ost_adm"), requests.map { it.second.token })
        assertOut("Not signed in. Nothing to do.\n")
        assertEquals(null, secretsKept[adminSecret])
        // a server in clear is not told: both tokens are forgotten here
        requests.clear()
        signIn(home, server = "http://ostrom.example")
        fileSystem.write(adminFile.toPath()) { writeUtf8("""{"http://ostrom.example":${now + hour}}""") }
        secretsKept["http://ostrom.example#admin"] = "ost_adm"
        val clear = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "http://ostrom.example")
        assertEquals(64, cli(environment = clear, api = serverOf(logout)).run(arrayOf("logout")))
        assertTrue(requests.isEmpty())
        assertEquals(null, secretsKept["http://ostrom.example#admin"])
    }
}
