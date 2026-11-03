// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject

/** A launch created by Ostrom. */
internal data class Launch(val launchId: String, val agentToken: String)

/** The calls of a launch to Ostrom before the agent runs, with the device token or the launch's agent token. */
internal class LaunchCalls(private val dependencies: LaunchDependencies) {
    private val trust = PackageTrust(dependencies.server, dependencies.files, dependencies.clock::now, dependencies.say)

    /**
     * `--dry-run`: every read a launch makes before the agent starts, and the harness that would apply (the package,
     * verified, says itself; then the MCP servers), with nothing written, no launch created and nothing started. A read
     * that would stop the launch stops the dry run with the same code. Not checked: the agent's own setup, which a
     * launch writes in a folder of its own.
     */
    suspend fun dryRun(agent: Agent, repo: String, model: String?, site: LaunchSite): Int {
        val pack = packageOf(repo, site, keep = false)
        instanceKeys()
        val mcp = (mcpServers(site) + pack?.mcpServers.orEmpty()).distinct()
        dependencies.say("ostrom: MCP servers: " + if (mcp.isEmpty()) "none" else mcp.joinToString())
        val modelPart = if (model == null) "" else ", model $model"
        val name = agent.displayName
        // what was not tried is said too: a dry run never reads as a launch that cannot fail
        dependencies.say(
            "ostrom: dry run for $repo$modelPart: the policy, the package and the instance's keys check out; no " +
                "launch was asked of Ostrom and $name was not started",
        )
        return 0
    }

    /**
     * The organisation's policy: stops a refused launch, says its note. A policy that cannot be read stops the launch
     * too: an error of Ostrom never lets an agent the organisation may refuse start (security audit S2-34).
     */
    suspend fun checkPolicy(agent: Agent, model: String?, site: LaunchSite) {
        val query = "agent=${Routes.encode(ostromName(agent))}&dev=${Routes.encode(site.credentials.login)}" +
            model?.let { "&model=${Routes.encode(it)}" }.orEmpty()
        val answer = dependencies.server.get("${Routes.POLICY}?$query", site.credentials.token, POLICY_MILLIS)
        if (answer is ServerAnswer.Failed) {
            val (code, message) = when {
                answer.status == UNAUTHORIZED ->
                    SIGN_IN_REQUIRED to "not signed in to ${site.serverUrl}: run `ostrom login`"
                answer.status == FORBIDDEN -> REFUSED to "refused by your organisation's policy: ${answer.message}"
                // a member of several organisations who named none: the server cannot choose for them
                answer.type == ORG_REQUIRED -> USAGE to
                    "you are a member of several organisations: add --org <name>, set OSTROM_ORG, or run `ostrom init`"
                else -> UNREACHABLE to
                    "your organisation's policy could not be checked (${answer.message}): the agent is not started"
            }
            stop(code, message)
        }
        val body = (answer as ServerAnswer.Ok).body
        // only an explicit yes lets the agent start: a page that is not the policy (a proxy, a portal) refuses
        when (body.flag("allowed")) {
            true -> body.text("note")?.let { dependencies.say("ostrom: $it") }
            false -> stop(REFUSED, "refused by your organisation's policy: ${body.text("reason") ?: "not allowed"}")
            null -> stop(UNREACHABLE, "your organisation's policy could not be read: the agent is not started")
        }
    }

    /** The launch and its agent token, or the launch stops. */
    suspend fun createLaunch(repo: String, agent: Agent, model: String?, site: LaunchSite): Launch {
        val body = objectOf("repo" to repo, "agent" to ostromName(agent), "model" to model)
        val answer = dependencies.server.post(Routes.LAUNCHES, body, site.credentials.token, LAUNCH_MILLIS)
        if (answer is ServerAnswer.Failed) {
            val (code, message) = when (answer.status) {
                null -> UNREACHABLE to "Ostrom is unreachable at ${site.serverUrl} (${answer.message})"
                UNAUTHORIZED -> SIGN_IN_REQUIRED to "not signed in to ${site.serverUrl}: run `ostrom login`"
                FORBIDDEN -> REFUSED to "launch refused: ${answer.message}"
                else -> UNREACHABLE to "launch not created: ${answer.message}"
            }
            stop(code, message)
        }
        val launch = (answer as ServerAnswer.Ok).body
        val id = launch.text("launchId")
        val token = launch.text("agentToken")
        if (id.isNullOrEmpty() || token.isNullOrEmpty()) stop(UNREACHABLE, "Ostrom gave no launch for this session")
        return Launch(id, token)
    }

    /**
     * Ends the launch when `ostrom <agent>` ends (AUDIT-SECU-RUNNER-2 R2b): its agent token is revoked at once instead
     * of living out its 24 hours. Never stops anything: a token not revoked expires on its own, and is said. An older
     * server (404, 405) or a token already refused (401) has nothing to say.
     */
    suspend fun endLaunch(launch: Launch) {
        val path = Routes.launch(launch.launchId)
        val answer = dependencies.server.call("DELETE", path, null, launch.agentToken, END_MILLIS)
        if (answer is ServerAnswer.Failed && answer.status !in SILENT_END) {
            val reason = answer.message
            dependencies.say("ostrom: the launch's agent token was not revoked ($reason): it expires on its own")
        }
    }

    /** The verified session package of [repo], null without one; a refused one stops the launch. */
    suspend fun packageOf(repo: String, site: LaunchSite, keep: Boolean): SessionPackage? =
        when (val outcome = trust.fetch(site.serverUrl, site.credentials.token, repo, site.configDirectory, keep)) {
            is PackageOutcome.Verified -> outcome.pack.also { dependencies.say("ostrom: ${it.summary()}") }
            PackageOutcome.None -> null.also {
                dependencies.say("ostrom: no session package for $repo; your organisation's gates apply")
            }
            is PackageOutcome.Refused ->
                stop(SIGNATURE_REFUSED, "session package refused: ${outcome.reason}. The agent is not started.")
        }

    /**
     * The instance's signing keys, the body of `GET /api/v1/instance/keys`, which the hooks check the gate policy
     * with (S2-20): read before the agent starts, never pinned on first use. Unreadable, the launch stops: no gate
     * policy of the session could be trusted, and every action would be held.
     */
    suspend fun instanceKeys(): String =
        when (val answer = dependencies.server.get(Routes.INSTANCE_KEYS, null, KEYS_MILLIS)) {
            is ServerAnswer.Ok -> answer.body.toString()
            is ServerAnswer.Failed ->
                stop(UNREACHABLE, "the instance's keys could not be read (${answer.message}): the agent is not started")
        }

    /** The organisation's MCP servers the relay serves to this agent (`?mine=true`), by name. */
    suspend fun mcpServers(site: LaunchSite): List<String> =
        when (val answer = dependencies.server.get(Routes.MCP_MINE, site.credentials.token, MCP_MILLIS)) {
            is ServerAnswer.Ok -> answer.body.list("items").filterIsInstance<JsonObject>()
                .filter { it.flag("enabled") != false }
                .mapNotNull { it.text("name") }
                .filter { MCP_NAME.matches(it) }
            is ServerAnswer.Failed -> emptyList<String>().also {
                dependencies.say("ostrom: your organisation's MCP servers are not listed (${answer.message})")
            }
        }

    /** The routes, limits and names of a launch. */
    companion object {
        private const val UNREACHABLE = 69
        private const val SIGN_IN_REQUIRED = 65
        private const val REFUSED = 77
        private const val SIGNATURE_REFUSED = 78
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val NOT_ALLOWED = 405
        private const val USAGE = 64
        private const val ORG_REQUIRED = "org_required"
        private const val POLICY_MILLIS = 5_000L
        private const val LAUNCH_MILLIS = 10_000L
        private const val MCP_MILLIS = 5_000L
        private const val KEYS_MILLIS = 5_000L
        private const val END_MILLIS = 5_000L
        private val SILENT_END = setOf<Int?>(UNAUTHORIZED, NOT_FOUND, NOT_ALLOWED)
        private val MCP_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
