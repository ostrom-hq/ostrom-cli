// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom keys` and `ostrom auth sessions`, as TeamCode's `teamcode keys` and `teamcode auth sessions`. */
class IdentityCommandsTest : SetupKit() {
    private val keys = "/api/v1/service-keys"
    private val sessions = "/api/v1/auth/sessions"
    private val listed = """{"items":[{"id":"k1","name":"ci","scopes":["inference:use","usage:read"],""" +
        """"createdAt":"2026-11-01T00:00:00Z","expiresAt":"2026-12-01T00:00:00Z"},""" +
        """{"id":"k2","name":"forever","scopes":["sessions:write"],"createdAt":"2026-11-01T00:00:00Z"}]}"""

    @Test
    fun keysListShowsEachKeyWithItsScopesAndExpiryNeverTheSecret() {
        assertEquals(0, run(mapOf("GET $keys" to (200 to listed)), "keys", "list"))
        assertEquals(
            "k1\tci\tinference:use,usage:read\texpires 2026-12-01T00:00:00Z\n" +
                "k2\tforever\tsessions:write\texpires never\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, run(mapOf("GET $keys" to (200 to listed)), "keys"), "the list is the default")
        assertTrue(console.out.contains("k1\tci"))
        console.out.clear()
        assertEquals(0, run(mapOf("GET $keys" to (200 to listed)), "keys", "list", "--json"))
        assertEquals("$listed\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("GET $keys" to (200 to """{"items":[]}""")), "keys", "list"))
        assertErr("No service keys in acme.\n")
    }

    @Test
    fun keysCreateSendsTheNameScopesAndLifetimeAndPrintsTheSecretOnce() {
        val created = """{"id":"k3","name":"ci","scopes":["inference:use"],"createdAt":"x","secret":"oss_abc"}"""
        val routes = mapOf("POST $keys" to (201 to created))
        assertEquals(0, run(routes, "keys", "create", "--name", "ci"))
        assertEquals("oss_abc\n", console.out.toString())
        assertErr("not shown again")
        assertEquals("""{"name":"ci","scopes":["inference:use"],"expiresInDays":30}""", bodyOf("POST $keys"))
        console.out.clear()
        val scopes = "usage:read, inference:use,usage:read"
        assertEquals(0, run(routes, "keys", "create", "--name", "ci", "--scopes", scopes, "--no-expiry", "--json"))
        assertEquals("$created\n", console.out.toString())
        assertEquals("""{"name":"ci","scopes":["usage:read","inference:use"]}""", bodyOf("POST $keys"))
        assertEquals(0, run(routes, "keys", "create", "--name", "ci", "--days", "7"))
        assertTrue(bodyOf("POST $keys")!!.endsWith(""""expiresInDays":7}"""))
    }

    @Test
    fun keysCreateRefusesWhatCannotWorkBeforeAskingTheServer() {
        assertEquals(64, run(emptyMap(), "keys", "create", "--name", "ci", "--days", "3", "--no-expiry"))
        assertEquals(64, run(emptyMap(), "keys", "create", "--name", "ci", "--scopes", " , "))
        assertEquals(64, run(emptyMap(), "keys", "create", "--name", "ci", environment = inSession))
        assertEquals(64, run(emptyMap(), "keys", "revoke", "k1", environment = inSession))
        assertEquals(emptyList(), requests.map { it.second.path })
    }

    @Test
    fun keysRevokeEndsAKeyAndSaysWhenItIsUnknownOrRefused() {
        assertEquals(0, run(mapOf("DELETE $keys/k1" to (204 to "")), "keys", "revoke", "k1"))
        assertEquals("Service key revoked.\n", console.out.toString())
        assertEquals(66, run(emptyMap(), "keys", "revoke", "nope"))
        val refused = """{"error":{"type":"session_required","message":"Sign in on the console."}}"""
        assertEquals(77, run(mapOf("GET $keys" to (403 to refused)), "keys", "list"))
        assertErr("Sign in on the console.")
    }

    @Test
    fun authSessionsListAndRevoke() {
        val items = """{"items":[{"id":"s1","createdAt":"c","lastSeenAt":"2026-11-14T09:00:00Z","device":"Firefox",""" +
            """"current":false},{"id":"s2","createdAt":"c","current":true}]}"""
        assertEquals(0, run(mapOf("GET $sessions" to (200 to items)), "auth", "sessions", "list"))
        val said = "s1\tFirefox\tother\tlast seen 2026-11-14T09:00:00Z\ns2\t-\tcurrent\tlast seen -\n"
        assertEquals(said, console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("GET $sessions" to (200 to items)), "auth", "sessions"))
        assertTrue(console.out.contains("s1\tFirefox"))
        console.out.clear()
        assertEquals(0, run(mapOf("GET $sessions" to (200 to items)), "auth", "sessions", "list", "--json"))
        assertEquals("$items\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("DELETE $sessions/s1" to (204 to "")), "auth", "sessions", "revoke", "s1"))
        assertEquals("Session revoked.\n", console.out.toString())
        assertEquals(66, run(emptyMap(), "auth", "sessions", "revoke", "s9"))
        assertEquals(64, run(emptyMap(), "auth", "sessions", "revoke", "s1", environment = inSession))
    }

    @Test
    fun authAloneShowsItsHelp() {
        assertEquals(0, cli().run(arrayOf("auth")))
        assertTrue(console.out.contains("sessions"), console.out.toString())
    }
}
