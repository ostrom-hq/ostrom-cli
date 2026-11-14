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
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.objectOf
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What `ostrom cloud create` says to a server that takes no preparation recipe. */
internal const val NO_RECIPE =
    "This Ostrom server takes no session recipe: create the session without --repo, --branch or --workdir."

/** What `ostrom cloud create` says to a server whose sessions do not start from a profile. */
internal const val NO_PROFILES =
    "This Ostrom server does not start sessions from a cloud profile: create the session without --profile or --image."

/** `ServerFeatures.RUNNERS_CLOUD_PROFILES` of ostrom-core#231, newer than the core this CLI pins. */
private const val RUNNERS_CLOUD_PROFILES = "runners.cloud.profiles.v1"

/** The shape of the profile ids the server generates (random UUIDs). */
private val PROFILE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The agents a cloud session starts (the contract's `CloudAgentKind`). */
private val CLOUD_AGENT_KINDS = listOf("claude", "codex", "opencode", "gemini", "shell")

/**
 * `ostrom cloud create --agent A [--profile ID|NAME] [--image REF] [--name N] [--model M] [--message TEXT|-]
 * [--repo [PATH=]URL[#REF]]… [--branch B] [--workdir DIR] [--json]`, as TeamCode's `teamcode cloud create`: creates a
 * cloud session without attaching to it (`ostrom attach ID` does), and prints it. `--profile` and `--image` are sent
 * only to a server that announces `runners.cloud.profiles.v1`; without `--profile`, the server takes the one profile
 * the caller may use (TeamCode requires it). `--repo`, `--branch` and `--workdir` are the session's preparation recipe
 * (the contract's `CloudEnvironment`), sent only to a server that announces `runners.cloud.environment.v1`: an older
 * one would ignore it and clone nothing.
 */
internal class CloudCreateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create a cloud session without attaching to it (ostrom attach does)"

    private val agent by option("--agent", help = "The agent the session starts: ${CLOUD_AGENT_KINDS.joinToString()}")
        .choice(CLOUD_AGENT_KINDS.associateWith { it }).required()
    private val profile by option("--profile", help = "Profile id or name; the only one you may use when absent")
    private val image by option("--image", help = "Image override allowed by the profile")
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

    /** The preparation recipe of `--repo`, `--branch` and `--workdir`, or null when none is given. */
    private fun recipe(): JsonObject? {
        val parsed = repositories.map { parseRepository(it) }
        if (parsed.isEmpty() && workdir == null) return null
        return objectOf(
            "repositories" to parsed.takeIf { it.isNotEmpty() }?.let { list ->
                JsonArray(list.map { it.toJson(branch) })
            },
            "workingDirectory" to workdir,
        )
    }

    override fun run() {
        val initial = message?.let { if (it == "-") generateSequence(dependencies.readLine).joinToString("\n") else it }
        if (initial != null && initial.isBlank()) throw stopWith(ExitCode.USAGE, "ostrom: the --message text is empty.")
        if (branch != null && repositories.isEmpty()) throw UsageError("Use --branch with --repo.")
        if (profile?.isBlank() == true) throw UsageError("Name the profile: --profile ID|NAME.")
        val environment = recipe()
        val access = dependencies.orgAccess(server, org)
        val signed = access.signed
        dependencies.requireFeature(signed.serverUrl, signed.token, ServerFeatures.RUNNERS_CLOUD, NO_CLOUD_SESSIONS)
        // An older server ignores unknown fields: never let it drop the recipe silently.
        if (environment != null) {
            dependencies.requireFeature(
                signed.serverUrl,
                signed.token,
                ServerFeatures.RUNNERS_CLOUD_ENVIRONMENT,
                NO_RECIPE,
            )
        }
        // An older server ignores unknown fields: never let it run the session on another image silently.
        if (profile != null || image != null) {
            dependencies.requireFeature(signed.serverUrl, signed.token, RUNNERS_CLOUD_PROFILES, NO_PROFILES)
        }
        val body = objectOf(
            "agent" to agent,
            "profileId" to profile?.let { profileId(access, it) },
            "image" to image,
            "name" to name,
            "model" to model,
            "prompt" to initial,
            "environment" to environment,
        )
        val session = access.write("POST", CLOUD_SESSIONS, body, "No cloud sessions on ${signed.serverUrl}.")
        dependencies.console.out(if (json) "$session\n" else session.cloudLine(dependencies.clock()) + "\n")
    }
}

/**
 * `--profile`, as TeamCode's `resolveProfile`: an id is sent as is; a name is looked up among the profiles listed to
 * the caller, the exact name before the name ignoring case, so a profile not assigned to them is never found by name.
 * A name no listed profile has goes as is, and the server answers `cloud_profile_not_found`.
 */
private fun profileId(access: OrgAccess, value: String): String {
    if (PROFILE_ID.matches(value)) return value
    // A listing that cannot be read sends the value as an id, and the server answers (TeamCode's).
    val answer = runBlocking { access.server.get(CLOUD_PROFILES, access.signed.token) }
    val body = if (answer is ServerAnswer.Ok) answer.body else JsonObject(emptyMap())
    val listed = (body["profiles"] as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>()
        .mapNotNull { profile -> text(profile["id"])?.let { id -> id to text(profile["name"]) } }
    val named = listed.filter { it.second == value }
        .ifEmpty { listed.filter { it.second.equals(value, ignoreCase = true) } }
    return when (named.size) {
        0 -> value
        1 -> named.single().first
        else -> throw stopWith(
            ExitCode.USAGE,
            "ostrom: several profiles are named \"$value\": " + named.joinToString { it.first } +
                ". Pass one of these ids to --profile.",
        )
    }
}

/** A JSON string's text, or null for anything else. */
private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The organization's cloud profiles the caller may use. */
private const val CLOUD_PROFILES = "/api/v1/cloud/profiles"

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
