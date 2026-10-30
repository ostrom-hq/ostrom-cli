// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject

/** A launch created by Ostrom. */
internal data class Launch(val launchId: String, val agentToken: String)

/** The calls of a launch to Ostrom before the agent runs, with the device token or the launch's agent token. */
internal class LaunchCalls(private val dependencies: LaunchDependencies) {
    private val trust = PackageTrust(dependencies.server, dependencies.files, dependencies.clock::now, dependencies.say)

    /**
     * The organisation's policy: stops a refused launch, says its note. A policy that cannot be read stops the launch
     * too: an error of Ostrom never lets an agent the organisation may refuse start (security audit S2-34).
     */
    suspend fun checkPolicy(agent: Agent, model: String?, site: LaunchSite) {
        val query = "agent=${Routes.encode(ostromName(agent))}&dev=${Routes.encode(site.credentials.login)}" +
            model?.let { "&model=${Routes.encode(it)}" }.orEmpty()
        val answer = dependencies.server.get("${Routes.POLICY}?$query", site.credentials.token, POLICY_MILLIS)
        if (answer is ServerAnswer.Failed) {
            val reason = "your organisation's policy could not be checked (${answer.message}): the agent is not started"
            stop(UNREACHABLE, reason)
        }
        val body = (answer as ServerAnswer.Ok).body
        if (body.flag("allowed") == false) {
            stop(REFUSED, "refused by your organisation's policy: ${body.text("reason") ?: "not allowed"}")
        }
        body.text("note")?.let { dependencies.say("ostrom: $it") }
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

    /** The verified session package of [repo], null without one; a refused one stops the launch. */
    suspend fun packageOf(repo: String, site: LaunchSite): SessionPackage? =
        when (val outcome = trust.fetch(site.serverUrl, site.credentials.token, repo, site.configDirectory)) {
            is PackageOutcome.Verified -> outcome.pack.also { dependencies.say("ostrom: ${it.summary()}") }
            PackageOutcome.None -> null.also {
                dependencies.say("ostrom: no session package for $repo; your organisation's gates apply")
            }
            is PackageOutcome.Refused ->
                stop(SIGNATURE_REFUSED, "session package refused: ${outcome.reason}. The agent is not started.")
        }

    /** The organisation's MCP servers the relay serves, by name. */
    suspend fun mcpServers(site: LaunchSite): List<String> =
        when (val answer = dependencies.server.get(Routes.MCP_SERVERS, site.credentials.token, MCP_MILLIS)) {
            is ServerAnswer.Ok -> answer.body.list("items").filterIsInstance<JsonObject>()
                .filter { it.flag("enabled") != false }
                .mapNotNull { it.text("name") }
                .filter { MCP_NAME.matches(it) }
            is ServerAnswer.Failed -> emptyList<String>().also {
                dependencies.say("ostrom: your organisation's MCP servers are not listed (${answer.message})")
            }
        }

    private companion object {
        const val UNREACHABLE = 69
        const val SIGN_IN_REQUIRED = 65
        const val REFUSED = 77
        const val SIGNATURE_REFUSED = 78
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val POLICY_MILLIS = 5_000L
        const val LAUNCH_MILLIS = 10_000L
        const val MCP_MILLIS = 5_000L
        val MCP_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
