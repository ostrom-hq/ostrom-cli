// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.Serializable

/**
 * A repository the preparation clones (the contract's `CloudRepository`).
 *
 * @property url `https://github.com/<owner>/<name>[.git]`.
 * @property path Relative to the workspace; the repository's name when absent (the server fills it).
 * @property ref The branch, tag or commit checked out after cloning, if any.
 * @property branch A local branch created at [ref] after cloning, if any.
 */
@Serializable
data class CloudRepository(
    val url: String,
    val path: String? = null,
    val ref: String? = null,
    val branch: String? = null,
) {
    /** Where it is cloned, relative to the workspace. */
    val directory: String get() = path?.trimEnd('/') ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
}

/**
 * The setup commands of a recipe (the contract's `CloudSetup`).
 *
 * @property create Run once per workspace, in order.
 * @property start Run on every execution, in order, after [create].
 */
@Serializable
data class CloudSetup(val create: List<String> = emptyList(), val start: List<String> = emptyList())

/**
 * A session's preparation recipe (the contract's `CloudEnvironment`); the runner reads only what it prepares.
 *
 * @property repositories Cloned on the first execution, fetched afterwards.
 * @property workingDirectory Relative to the workspace, where the setup commands and the agent start.
 * @property setup The setup commands.
 */
@Serializable
data class CloudEnvironment(
    val repositories: List<CloudRepository> = emptyList(),
    val workingDirectory: String? = null,
    val setup: CloudSetup = CloudSetup(),
)

/**
 * The owner's Git identity (the contract's `RunnerGitIdentity`).
 *
 * @property name The `user.name`.
 * @property email The `user.email`.
 */
@Serializable
data class GitIdentity(val name: String, val email: String)

/**
 * What the runner prepares for its execution (the contract's `RunnerEnvironment`): holds the owner's values, never
 * logged.
 *
 * @property environment The recipe.
 * @property git The owner's Git identity.
 * @property variables The owner's environment variables, by name.
 * @property unavailable The variables withheld, by name.
 */
@Serializable
class RunnerEnvironment(
    val environment: CloudEnvironment,
    val git: GitIdentity,
    val variables: Map<String, String> = emptyMap(),
    val unavailable: List<String> = emptyList(),
) {
    /** Names only, never a value. */
    override fun toString(): String =
        "RunnerEnvironment(${environment.repositories.size} repositories, variables=${variables.keys.sorted()})"
}

/**
 * A failed preparation step, as structured fields only (the contract's `RunnerPreparationFailure`): no output, no
 * runner text.
 *
 * @property step `git_identity`, `clone`, `checkout`, `fetch`, `working_directory`, `setup_create`, `setup_start` or
 *   `runner`.
 * @property index The repository or the setup command, from 0.
 * @property exitCode The command's exit code, 0 to 255.
 */
@Serializable
data class PreparationFailure(val step: String, val index: Int? = null, val exitCode: Int? = null)

/**
 * The server refused the preparation: another execution holds the session (`execution_fenced`), it no longer
 * prepares (`not_preparing`), its preparation failed (`preparation_failed`), or the request was refused.
 *
 * @property code The error's stable key, if the body had one.
 * @property status The HTTP status.
 */
class PreparationRefusedException(val code: String?, val status: Int) :
    Exception("The server refused the preparation (${code ?: "HTTP $status"}).")
