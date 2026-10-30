// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture

/** A `mysql` that prints the queued outputs, one per run, and keeps the commands it was given. */
class FakeMysql(vararg results: CaptureResult) : ProcessCapture {
    val commands = mutableListOf<List<String>>()
    private val queue = ArrayDeque(results.toList())

    override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
        commands += command
        return queue.removeFirst()
    }

    override fun detach(command: List<String>): Boolean = false
}

/** What `mysql --batch` prints for the marker of [name] and its rows (nothing at all for no rows). */
fun batch(name: String, header: List<String>, vararg rows: List<String?>): String {
    val lines = if (rows.isEmpty()) {
        ""
    } else {
        val body = rows.joinToString("") { row -> row.joinToString("\t") { it ?: "NULL" } + "\n" }
        header.joinToString("\t") + "\n" + body
    }
    return "${MysqlBatch.MARKER}\n$name\n$lines"
}

/** The columns of a TeamCode schema, as the first query of the read prints them. */
fun columns(vararg tables: Pair<String, List<String>>): CaptureResult.Exited {
    val rows = tables.flatMap { (table, names) -> names.map { listOf(table, it) } }
    return CaptureResult.Exited(0, batch("columns", listOf("t", "c"), *rows.toTypedArray()))
}

private val VARIANT_COLUMNS =
    listOf("id", "target", "name", "binding", "config", "endpoint", "shared_secret", "transport", "command", "args")
private val ACCOUNT_COLUMNS =
    listOf("id", "provider", "provider_account_id", "display_name", "credential_envelope", "email", "disabled")

/** TeamCode's schema with every column the import reads. */
val FULL_SCHEMA = columns(
    "members" to listOf("id", "username", "email", "password_hash"),
    "variants" to VARIANT_COLUMNS,
    "variant_assignments" to listOf("member_id", "variant_id", "member_secret"),
    "mcp_tool_policies" to listOf("variant_id", "mode", "tools"),
    "accounts" to ACCOUNT_COLUMNS,
)

/** Envelopes sealed with [KEY] by Python's `cryptography` (AESGCM), as TeamCode's CredentialCipher seals them. */
object Sealed {
    const val KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    /** `sk-shared`, for `variant:default:v1`. */
    const val SHARED = "v1:ZGVmZ2hpamtsbW5vO3DzFRGIJPtaVI2f5egC4z6OzjesyhKBnQ"

    /** `tok-maya`, for `variant:default:v2:m1`. */
    const val MEMBER = "v1:ZGVmZ2hpamtsbW5vPHS1SxSIL_-vhCWKvb8YuVfQaWQvLwDH"

    /** `{"API_KEY": "k1", "NESTED": {"a": 1}}`, for `variant:default:v3`. */
    const val STDIO = "v1:ZGVmZ2hpamtsbW5vMzmfNjC2HdtnQGXI-A5b327iJETOP6c34_OWaICBxGquyXG9MTJStbCyo17ol9oEP1RZpik"

    /** `{"expiresAtMillis":"soon"}`, for `default:a3`. */
    const val LATE_ACCOUNT = "v1:ZGVmZ2hpamtsbW5vMzm7HgmAJPtNIyulswkGlDHgPCj4A5wchaxVqVRPg-5pbe92NPw88PMN"

    /** `{}`, for `default:a2`. */
    const val BARE_ACCOUNT = "v1:ZGVmZ2hpamtsbW5vM2arGjcoKrGKWZcKfAFmwCIg"

    /** `{"accessToken":"at","refreshToken":"rt","expiresAtMillis":1793232000000}`, for `default:a1`. */
    const val ACCOUNT = "v1:ZGVmZ2hpamtsbW5vMzm_BRqMJe1qDTSNtEdQ3WCjciinTNEAwrfeLYjL8Sf_jC7idjK5donRVB_fJ7K_cin9lGE-" +
        "DEGWncWlzbuFuz-16DzleQs1u1_dc2yCjSeMeYuYnzHKsZ8MhaTY"
}

/** A TeamCode team like Lyes's: a shared HTTP server, a per-member one, a stdio one, a shared account. */
val TEAM_TABLES = CaptureResult.Exited(
    0,
    batch(
        "members",
        listOf("id", "username", "email"),
        listOf("m1", "maya", "maya@acme.example"),
        listOf("m2", "tom", null),
    ) + batch(
        "variants",
        VARIANT_COLUMNS,
        listOf(
            "v1", "grafana", "prod", "SHARED", """{"CREDENTIAL_HEADER":"X-Api-Key"}""", "https://g.example/mcp",
            Sealed.SHARED, null, null, null,
        ),
        listOf("v2", "github", "personal", "PER_MEMBER", "{}", "https://gh.example/mcp", null, "http", null, null),
        listOf(
            "v3", "notion", "main", "SHARED", "{}", null, Sealed.STDIO, "stdio", "/usr/bin/npx",
            """["@notionhq/mcp","--quiet"]""",
        ),
    ) + batch(
        "variant_assignments",
        listOf("member_id", "variant_id", "member_secret"),
        listOf("m1", "v2", Sealed.MEMBER),
        listOf("m2", "v1", null),
    ) + batch(
        "mcp_tool_policies",
        listOf("variant_id", "mode", "tools"),
        listOf("v1", "deny", """["delete_dashboard"]"""),
    ) + batch(
        "accounts",
        ACCOUNT_COLUMNS,
        listOf("a1", "anthropic", "acc-1", "Team Max", Sealed.ACCOUNT, "ops@acme.example", "0"),
    ),
)
