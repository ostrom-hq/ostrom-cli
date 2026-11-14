// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/**
 * Whether a launch's agent gets the `ostrom-artifacts` MCP server (TeamCode's `teamcode run`): only on a server that
 * serves the artifacts ([ARTIFACTS]), when the publication policy, asked with the launch's agent token (the credential
 * the agent publishes with), answers `canPublish`. A refusal the member can act on (no storage, an untested one,
 * artifacts off, publication reserved to administrators) is said; a policy that cannot be read starts the agent
 * without the server, silently. `OSTROM_ARTIFACTS=off` leaves it out (TeamCode's `--no-artifacts`).
 *
 * @param server Ostrom.
 */
internal class ArtifactsPolicy(private val server: ServerJson) {
    /**
     * Asks the policy.
     *
     * @param served The features the server announces.
     * @param site Where the launch runs (its environment).
     * @param launch The launch, whose agent token asks.
     *
     * @return Whether to connect the server, and the note to say, if any.
     */
    suspend fun ask(served: Set<String>, site: LaunchSite, launch: Launch): Pair<Boolean, String?> {
        val asked = ARTIFACTS in served && site.variable(VARIABLE)?.lowercase() !in OFF
        val answer = if (asked) server.get(Routes.ARTIFACTS_POLICY, launch.agentToken, MILLIS) else null
        val body = (answer as? ServerAnswer.Ok)?.body
        val refusal = body?.text("reason")?.let { REFUSALS[it] }
        return when {
            body?.flag("canPublish") == true -> true to null
            refusal != null -> false to "ostrom: artifacts are not connected: $refusal"
            else -> false to null
        }
    }
}

private const val VARIABLE = "OSTROM_ARTIFACTS"
private const val MILLIS = 5_000L
private val OFF = setOf("off", "0", "false")

/** The refusals the member can act on, said when the agent starts without the server. */
private val REFUSALS = mapOf(
    "artifact_storage_missing" to "your organisation has no artifact storage",
    "artifact_storage_unverified" to "your organisation's artifact storage is not tested yet",
    "artifacts_disabled" to "your organisation turned artifacts off",
    "artifact_publish_forbidden" to "only administrators publish artifacts in your organisation",
)
