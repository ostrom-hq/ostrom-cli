// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The body of `POST /api/v1/import/teamcode` (ostrom-server#28), built from a TeamCode team's tables: its MCP
 * variants with their tool policies and secrets, its assignments, its shared accounts. Secrets are opened here with
 * TeamCode's key, sent once over TLS in this body, and never printed nor written to disk.
 *
 * @param team The TeamCode team id: TeamCode binds every secret to it.
 */
class TeamCodePlan(private val key: TeamCodeKey, private val team: String) {
    /**
     * @param memberMap TeamCode member id to Ostrom member id.
     * @throws TeamCodeReadException When a secret does not open with the key (another key file, or another team).
     */
    fun request(tables: TeamCodeTables, memberMap: Map<String, String>): JsonObject {
        val policies = tables.policies.associateBy { it["variant_id"] }
        return objectOf(
            "members" to tables.members.map { mapOf("id" to it["id"], "username" to it["username"]) },
            "mcpServers" to tables.variants.map { server(it, policies[it["id"]]) },
            "assignments" to tables.assignments.map(::assignment),
            "accounts" to tables.accounts.map(::account),
            "memberMap" to memberMap,
        )
    }

    private fun server(variant: Row, policy: Row?): Map<String, Any?> {
        val id = variant["id"].orEmpty()
        val config = parseObject(variant["config"]) ?: JsonObject(emptyMap())
        val stdio = (variant["transport"] ?: config.text("transport")).equals("stdio", ignoreCase = true)
        val secret = variant["shared_secret"]?.let { open(it, "variant:$team:$id", "MCP server ${variant["target"]}") }
        return mapOf(
            "id" to id,
            "target" to variant["target"],
            "variant" to variant["name"],
            "perMember" to (variant["binding"] == "PER_MEMBER"),
            "url" to variant["endpoint"],
            "credentialHeader" to (config.text("CREDENTIAL_HEADER") ?: config.text("credentialHeader")),
            "sharedSecret" to secret.takeUnless { stdio },
            "stdio" to if (stdio) stdio(variant, config, secret) else null,
            "toolMode" to policy?.get("mode"),
            "tools" to strings(policy?.get("tools")),
        )
    }

    /** A stdio variant: its package is the first argument; its secret is a JSON object of variables. */
    private fun stdio(variant: Row, config: JsonObject, secret: String?): Map<String, Any?> {
        val command = (variant["command"] ?: config.text("command")).orEmpty().substringAfterLast('/')
        val arguments = strings(variant["args"] ?: config["args"]?.toString())
        // only plain values are variables; anything else in TeamCode's object is not one
        val variables = parseObject(secret).orEmpty()
            .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.let { name to it.content } }
            .toMap()
        return mapOf(
            "launcher" to LAUNCHERS[command],
            "packageName" to arguments.firstOrNull(),
            "args" to arguments.drop(1),
            "secretEnv" to variables,
        )
    }

    private fun assignment(row: Row): Map<String, Any?> {
        val variant = row["variant_id"].orEmpty()
        val member = row["member_id"].orEmpty()
        val context = "variant:$team:$variant:$member"
        return mapOf(
            "memberId" to member,
            "serverId" to variant,
            "memberSecret" to row["member_secret"]?.let { open(it, context, "the secret of member $member") },
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun account(row: Row): Map<String, Any?> {
        val label = row["display_name"].orEmpty()
        val credential = parseObject(open(row["credential_envelope"].orEmpty(), "$team:${row["id"]}", "account $label"))
            ?: throw TeamCodeReadException("account $label: its credential is not the JSON TeamCode writes.")
        val expires = (credential["expiresAtMillis"] as? JsonPrimitive)?.longOrNull ?: 0L
        return mapOf(
            "provider" to row["provider"],
            "providerAccountId" to row["provider_account_id"],
            "label" to label,
            "email" to row["email"],
            "accessToken" to credential.text("accessToken").orEmpty(),
            "refreshToken" to credential.text("refreshToken").orEmpty(),
            "expiresAt" to Instant.fromEpochMilliseconds(expires).toString(),
            "disabled" to (row["disabled"] == "1"),
        )
    }

    private fun open(envelope: String, context: String, what: String): String = key.open(envelope, context)
        ?: throw TeamCodeReadException("$what: its secret does not open with this key file (another key, or team?).")

    /** The registries and lists TeamCode stores. */
    companion object {
        /** The registry an MCP host installs a stdio server's package from, by the command TeamCode runs. */
        private val LAUNCHERS =
            mapOf("npx" to "npm", "npm" to "npm", "bunx" to "npm", "uvx" to "pypi", "pipx" to "pypi")

        /** A JSON array of strings, as TeamCode stores tools and arguments; empty when it is not one. */
        private fun strings(text: String?): List<String> {
            val array = try {
                text?.let { Json.parseToJsonElement(it) as? JsonArray }
            } catch (_: SerializationException) {
                null
            }
            return array.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        }
    }
}
