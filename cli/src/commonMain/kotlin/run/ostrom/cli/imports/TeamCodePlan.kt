// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
     * The body, and what it leaves out: a stdio server Ostrom cannot install, or an account without its provider or
     * its tokens. One such row is said and skipped, never sent: the server would refuse the whole import for it.
     *
     * @param memberMap TeamCode member id to Ostrom member id.
     * @throws TeamCodeReadException When a secret does not open with the key (another key file, or another team).
     */
    fun request(tables: TeamCodeTables, memberMap: Map<String, String>): PlannedImport {
        val policies = tables.policies.associateBy { it["variant_id"] }
        val skipped = mutableListOf<String>()
        val left = mutableSetOf<String?>()
        val servers = tables.variants.mapNotNull { variant ->
            val policy = policies[variant["id"]]
            // a tool list that cannot be read is never sent as "no tool": a deny list would then deny nothing
            val tools = strings(policy?.get("tools"))
            val mode = policy?.let { modeOf(it["mode"]) }
            val server = when {
                tools == null || policy != null && mode == null -> null
                else -> server(variant, mode, tools)
            }
            if (server == null) {
                skipped += when {
                    tools == null -> unreadablePolicy(variant)
                    policy != null && mode == null -> unknownMode(variant, policy)
                    else -> unsupported(variant)
                }
                left += variant["id"]
            }
            server
        }
        val accounts = tables.accounts.mapNotNull { row ->
            account(row).also { if (it == null) skipped += "account ${row["display_name"].orEmpty()}: $INCOMPLETE" }
        }
        val body = objectOf(
            "members" to tables.members.map { mapOf("id" to it["id"], "username" to it["username"]) },
            "mcpServers" to servers,
            // the assignments of a server left out go with it, and those of a member no one in Ostrom stands for:
            // their secrets are never opened nor sent
            "assignments" to tables.assignments
                .filter { it["variant_id"] !in left && it["member_id"] in memberMap }
                .map(::assignment),
            "accounts" to accounts,
            "memberMap" to memberMap,
        )
        // a TeamCode before workflows and automations adds nothing; one with them adds TeamCode's rows as they are
        return PlannedImport(JsonObject(body + workflowParts(tables, skipped)), skipped)
    }

    private fun unsupported(variant: Row) = "MCP server ${variant["target"]} (${variant["name"]}): its command " +
        "(${commandOf(variant, configOf(variant)).ifEmpty { "none" }}) is not one Ostrom installs a package with " +
        "(${LAUNCHERS.keys.joinToString()}), or it names no package."

    private fun configOf(variant: Row) = parseObject(variant["config"]) ?: JsonObject(emptyMap())

    private fun server(variant: Row, mode: String?, tools: List<String>): Map<String, Any?>? {
        // id, variant_id and member_id are required columns: a row read always has them
        val id = variant["id"]
        val config = configOf(variant)
        val stdio = (variant["transport"] ?: config.text("transport")).equals("stdio", ignoreCase = true)
        val launch = if (stdio) launchOf(variant, config) ?: return null else null
        val secret = variant["shared_secret"]?.let { open(it, "variant:$team:$id", "MCP server ${variant["target"]}") }
        return mapOf(
            "id" to id,
            "target" to variant["target"],
            "variant" to variant["name"],
            "perMember" to (variant["binding"] == "PER_MEMBER"),
            "url" to variant["endpoint"],
            "credentialHeader" to (config.text("CREDENTIAL_HEADER") ?: config.text("credentialHeader")),
            "sharedSecret" to secret.takeUnless { stdio },
            "stdio" to launch?.let { stdio(it, secret) },
            "toolMode" to mode,
            "tools" to tools,
        )
    }

    /** The command TeamCode runs, by its name alone: `/usr/bin/npx`, `C:\\…\\npx.cmd` and `npx` are `npx`. */
    private fun commandOf(variant: Row, config: JsonObject): String =
        (variant["command"] ?: config.text("command")).orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .lowercase().removeSuffix(".cmd").removeSuffix(".exe")

    /**
     * The registry and the arguments of a stdio variant, its package first, or null when Ostrom cannot install it. The
     * launcher's own flags before the package (`npx -y`, `uvx --quiet`) are the launcher's, not the server's.
     */
    private fun launchOf(variant: Row, config: JsonObject): Pair<String, List<String>>? {
        val launcher = LAUNCHERS[commandOf(variant, config)]
        val arguments = strings(variant["args"] ?: config["args"]?.toString())?.dropWhile { it.startsWith("-") }
        return if (launcher == null || arguments.isNullOrEmpty()) null else launcher to arguments
    }

    /** A stdio variant: its package is the first argument; its secret is a JSON object of variables. */
    private fun stdio(launch: Pair<String, List<String>>, secret: String?): Map<String, Any?> {
        val variables = variablesOf(secret)
        return mapOf(
            "launcher" to launch.first,
            "packageName" to launch.second.first(),
            "args" to launch.second.drop(1),
            "secretEnv" to variables,
        )
    }

    private fun assignment(row: Row): Map<String, Any?> {
        val variant = row["variant_id"]
        val member = row["member_id"]
        val context = "variant:$team:$variant:$member"
        return mapOf(
            "memberId" to member,
            "serverId" to variant,
            "memberSecret" to row["member_secret"]?.let { open(it, context, "the secret of member $member") },
        )
    }

    /** A shared account, or null when it lacks what Ostrom needs to sign in with it (said by [request]). */
    @OptIn(ExperimentalTime::class)
    private fun account(row: Row): Map<String, Any?>? {
        val label = row["display_name"].orEmpty()
        // an account TeamCode never finished (no credential at all) is incomplete, not a key that does not open
        val envelope = row["credential_envelope"]?.takeIf { it.isNotEmpty() }
        val credential = envelope?.let {
            parseObject(open(it, "$team:${row["id"]}", "account $label"))
                ?: throw TeamCodeReadException("account $label: its credential is not the JSON TeamCode writes.")
        }
        val expires = (credential?.get("expiresAtMillis") as? JsonPrimitive)?.longOrNull
        val fields = listOf(
            row["provider"]?.trim(),
            row["provider_account_id"],
            credential?.text("accessToken"),
            credential?.text("refreshToken"),
        )
        if (expires == null || fields.any { it.isNullOrEmpty() }) return null
        return mapOf(
            "provider" to providerOf(fields[0].toString()),
            "providerAccountId" to fields[1],
            "label" to label,
            "email" to row["email"],
            "accessToken" to fields[2],
            "refreshToken" to fields.last(),
            "expiresAt" to Instant.fromEpochMilliseconds(expires).toString(),
            "disabled" to (row["disabled"] == "1"),
        )
    }

    private fun open(envelope: String, context: String, what: String): String = key.open(envelope, context)
        ?: throw TeamCodeReadException("$what: its secret does not open with this key file (another key, or team?).")

    /** The registries and lists TeamCode stores. */
    companion object {
        private fun unreadablePolicy(variant: Row) = "MCP server ${variant["target"]} (${variant["name"]}): its " +
            "tool policy is not a list of tool names in TeamCode."

        private fun unknownMode(variant: Row, policy: Row) = "MCP server ${variant["target"]} (${variant["name"]}): " +
            "its tool policy's mode (${policy["mode"]}) is neither ALLOWLIST nor DENYLIST in TeamCode."

        /**
         * Ostrom's provider (`claude` or `codex`, the contract's `TeamCodeAccount.provider`) of TeamCode's: TeamCode
         * names the company (`anthropic`, `openai`). Any other name goes as it is, and the server says it does not
         * pool it.
         */
        internal fun providerOf(teamCode: String): String =
            teamCode.trim().lowercase().let { PROVIDERS[it] ?: it }

        private val PROVIDERS =
            mapOf("anthropic" to "claude", "claude" to "claude", "openai" to "codex", "codex" to "codex")

        /**
         * Ostrom's tool mode (`allow` or `deny`, the contract's `TeamCodeMcpServer.toolMode`) of TeamCode's policy
         * mode, which TeamCode writes `ALLOWLIST` or `DENYLIST`; null for any other, whose server is said and skipped
         * (the server would refuse the whole import for it, and its tool list means nothing without its mode).
         */
        internal fun modeOf(teamCode: String?): String? = teamCode?.let { MODES[it.trim().lowercase()] }

        private val MODES = mapOf("allowlist" to "allow", "allow" to "allow", "denylist" to "deny", "deny" to "deny")

        private const val INCOMPLETE = "no provider, account id, tokens or expiry in TeamCode: it could never sign in."

        /** The registry an MCP host installs a stdio server's package from, by the command TeamCode runs. */
        private val LAUNCHERS =
            mapOf("npx" to "npm", "npm" to "npm", "bunx" to "npm", "uvx" to "pypi", "pipx" to "pypi")

        /** A stdio secret's variables: only plain values are; anything else in TeamCode's object (null too) is not. */
        internal fun variablesOf(secret: String?): Map<String, String> = parseObject(secret).orEmpty()
            .mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { name to it.content }
            }
            .toMap()

        /**
         * A JSON array of strings, as TeamCode stores tools and arguments: empty when there is none, null when there
         * is one that is not a list of strings (a number in it, or no JSON at all).
         */
        private fun strings(text: String?): List<String>? {
            val array = try {
                if (text == null) JsonArray(emptyList()) else Json.parseToJsonElement(text) as? JsonArray
            } catch (_: SerializationException) {
                null
            }
            val items = array?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            return items?.takeIf { null !in it }?.filterNotNull()
        }
    }
}

/**
 * The body of the import, and the rows it leaves out, each with why.
 *
 * @property body `TeamCodeImportRequest`.
 * @property skipped One line per row left out.
 */
data class PlannedImport(val body: JsonObject, val skipped: List<String>)
