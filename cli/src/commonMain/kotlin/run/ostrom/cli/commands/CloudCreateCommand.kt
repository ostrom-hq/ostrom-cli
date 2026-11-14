// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.objectOf
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.serialization.json.JsonArray

/** What `ostrom cloud create` says to a server that takes no preparation recipe. */
internal const val NO_RECIPE =
    "This Ostrom server takes no session recipe: create the session without --repo, --branch or --workdir."

/** The agents a cloud session starts (the contract's `CloudAgentKind`). */
private val CLOUD_AGENT_KINDS = listOf("claude", "codex", "opencode", "gemini", "shell")

/**
 * `ostrom cloud create --agent A [--name N] [--model M] [--message TEXT|-] [--repo [PATH=]URL[#REF]]… [--branch B]
 * [--workdir DIR] [--json]`, as TeamCode's `teamcode cloud create`: creates a cloud session without attaching to it
 * (`ostrom attach ID` does), and prints it. `--repo`, `--branch` and `--workdir` are the session's preparation recipe
 * (the contract's `CloudEnvironment`), sent only to a server that announces `runners.cloud.environment.v1`: an older
 * one would ignore it and clone nothing.
 */
internal class CloudCreateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create a cloud session without attaching to it (ostrom attach does)"

    private val agent by option("--agent", help = "The agent the session starts: ${CLOUD_AGENT_KINDS.joinToString()}")
        .choice(CLOUD_AGENT_KINDS.associateWith { it }).required()
    private val name by option("--name", help = "The session's name (else its first repository's)")
    private val model by option("--model", help = "Model for the agent")
    private val message by option(
        "--message",
        help = "First message for the agent, delivered once the session is ready; - reads standard input",
    )
    private val repositories by option(
        "--repo",
        help = "[PATH=]URL[#REF]; repeatable. Cloned into /workspace/PATH (the repository's name by default)",
    ).multiple()
    private val branch by option(
        "--branch",
        help = "Create this local branch in every --repo after checking out its ref",
    )
    private val workdir by option("--workdir", help = "Working directory relative to /workspace")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val initial = message?.let { if (it == "-") generateSequence(dependencies.readLine).joinToString("\n") else it }
        if (initial != null && initial.isBlank()) throw stopWith(ExitCode.USAGE, "ostrom: the --message text is empty.")
        if (branch != null && repositories.isEmpty()) throw UsageError("Use --branch with --repo.")
        val parsed = repositories.map { parseRepository(it) }
        val environment = if (parsed.isNotEmpty() || workdir != null) {
            objectOf(
                "repositories" to parsed.takeIf { it.isNotEmpty() }?.let { list ->
                    JsonArray(list.map { it.toJson(branch) })
                },
                "workingDirectory" to workdir,
            )
        } else {
            null
        }
        val access = dependencies.orgAccess(server, org)
        val signed = access.signed
        dependencies.requireFeature(signed.serverUrl, signed.token, ServerFeatures.RUNNERS_CLOUD, NO_CLOUD_CREATE)
        // An older server ignores unknown fields: never let it drop the recipe silently.
        if (environment != null) {
            dependencies.requireFeature(
                signed.serverUrl,
                signed.token,
                ServerFeatures.RUNNERS_CLOUD_ENVIRONMENT,
                NO_RECIPE,
            )
        }
        val body = objectOf(
            "agent" to agent,
            "name" to name,
            "model" to model,
            "prompt" to initial,
            "environment" to environment,
        )
        val session = access.write("POST", CLOUD_SESSIONS_ROUTE, body, "No cloud sessions on ${signed.serverUrl}.")
        dependencies.console.out(if (json) "$session\n" else session.cloudLine(dependencies.clock()) + "\n")
    }

    private companion object {
        const val CLOUD_SESSIONS_ROUTE = "/api/v1/cloud/sessions"
        const val NO_CLOUD_CREATE = "This Ostrom server runs no cloud session: ask your admin to update it."
    }
}

/**
 * A repository of `--repo`.
 *
 * @property url Its `https://` URL.
 * @property path Where it is cloned, relative to `/workspace`.
 * @property ref The branch, tag or commit checked out, or null for the default branch.
 */
internal data class CloudRepositoryOption(val url: String, val path: String, val ref: String?) {
    /** The contract's `CloudRepository`, with the local [branch] to create, if any. */
    fun toJson(branch: String?) = objectOf("url" to url, "path" to path, "ref" to ref, "branch" to branch)
}

/**
 * `--repo [PATH=]URL[#REF]`, as TeamCode's `parseRepository`: PATH defaults to the repository's name. The server checks
 * the rest (a GitHub URL, a safe path, a ref that is no option).
 *
 * @param value The option's value.
 *
 * @return The repository.
 */
internal fun parseRepository(value: String): CloudRepositoryOption {
    val path = value.substringBefore('=', "").takeIf { it.isNotEmpty() && !it.contains("://") }
    val rest = if (path != null) value.substringAfter('=') else value
    val url = rest.substringBefore('#')
    val ref = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }
    if (!url.startsWith("https://")) throw UsageError("Use --repo [PATH=]https://host/owner/name[#REF].")
    val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    if (path == null && name.isEmpty()) throw UsageError("Name the directory: --repo PATH=$url.")
    return CloudRepositoryOption(url, path ?: name, ref)
}
