// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlin.uuid.Uuid

/**
 * What the runner learns from its pod (`RunnerEnvironment` on the server writes these variables).
 *
 * @property serverUrl The server, as the pod reaches it (`http://`: the in-cluster or local URL).
 * @property sessionId The cloud session.
 * @property epoch The execution.
 * @property agentCommand The agent's command (`claude`, `codex`, `opencode`, `gemini`, `bash`).
 * @property repositoryUrl The repository to clone, if any.
 * @property repositoryRef The branch, tag or commit to check out, if any.
 * @property token The runner token.
 * @property workspace The workspace volume.
 * @property model The model the agent starts with, if chosen.
 * @property prompt The agent's first message, if any (first execution only).
 * @property agent The agent's kind (`claude-code`, `codex`, …, `shell`), if the server said it.
 * @property checksSocket Where the runner serves the hooks' checks (`OSTROM_RUNNER_CHECKS`), in the socket directory
 *   the agent's container mounts.
 * @property checkHostSocket Where the check host listens (`OSTROM_CHECK_HOST_SOCKET`), in a directory the agent's
 *   container does not mount.
 * @property credentialSocket Where the runner serves git's credentials (`OSTROM_CREDENTIAL_SOCKET`), in the socket
 *   directory the agent's container mounts.
 * @property stateSocket Where the runner hears of the agent's background work (`OSTROM_AGENT_STATE_SOCKET`,
 *   [AgentState]), in the socket directory the agent's container mounts.
 * @property gitHubConfigDirectory The agent's `gh` configuration (`GH_CONFIG_DIR`), which the runner writes, in the
 *   same directory.
 * @property binary The `ostrom` binary every container of the session runs (the init container copied it there): git's
 *   credential helper in the agent's container runs it too.
 */
data class RunnerConfig(
    val serverUrl: String,
    val sessionId: Uuid,
    val epoch: Long,
    val agentCommand: String,
    val repositoryUrl: String?,
    val repositoryRef: String?,
    val token: String,
    val workspace: String,
    val model: String? = null,
    val prompt: String? = null,
    val agent: String? = null,
    val checksSocket: String = DEFAULT_CHECKS_SOCKET,
    val checkHostSocket: String = DEFAULT_CHECK_HOST_SOCKET,
    val credentialSocket: String = DEFAULT_CREDENTIAL_SOCKET,
    val stateSocket: String = DEFAULT_STATE_SOCKET,
    val gitHubConfigDirectory: String = DEFAULT_GITHUB_CONFIG_DIRECTORY,
    val binary: String = BINARY,
) {
    /** Whether the agent runs under a launch: every agent but the plain shell. */
    val governed: Boolean get() = agent != null && agent != SHELL_AGENT

    /** The token never appears in logs. */
    override fun toString(): String =
        "RunnerConfig(serverUrl=$serverUrl, sessionId=$sessionId, epoch=$epoch, agent=$agentCommand)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Variables only the runner reads; never passed to the agent. */
        val PRIVATE_VARIABLES = setOf(
            "OSTROM_RUNNER_TOKEN",
            "OSTROM_RUNNER_TOKEN_FILE",
            "OSTROM_AGENT_PROMPT",
            "OSTROM_AGENT_PROMPT_FILE",
        )

        /**
         * Reads the configuration.
         *
         * @param environment The process environment.
         * @param readFile Reads the token file.
         *
         * @return The configuration.
         *
         * @throws IllegalArgumentException When a variable is missing or malformed.
         */
        fun from(environment: Map<String, String>, readFile: (String) -> String): RunnerConfig {
            fun optional(name: String) = environment[name]?.takeIf { it.isNotBlank() }
            fun required(name: String) = requireNotNull(optional(name)) { "$name is required." }
            val serverUrl = required("OSTROM_SERVER_URL").trimEnd('/')
            require(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                "OSTROM_SERVER_URL must be an http(s) URL."
            }
            val token = requireNotNull(
                optional("OSTROM_RUNNER_TOKEN") ?: optional("OSTROM_RUNNER_TOKEN_FILE")?.let { readFile(it).trim() },
            ) { "OSTROM_RUNNER_TOKEN or OSTROM_RUNNER_TOKEN_FILE is required." }
            require(token.isNotEmpty()) { "The runner token is empty." }
            return RunnerConfig(
                serverUrl = serverUrl,
                sessionId = requireNotNull(Uuid.parseOrNull(required("OSTROM_SESSION_ID"))) {
                    "OSTROM_SESSION_ID is not a UUID."
                },
                epoch = requireNotNull(required("OSTROM_EPOCH").toLongOrNull()) { "OSTROM_EPOCH is not a number." },
                agentCommand = required("OSTROM_AGENT_COMMAND"),
                repositoryUrl = optional("OSTROM_REPOSITORY_URL"),
                repositoryRef = optional("OSTROM_REPOSITORY_REF"),
                token = token,
                workspace = optional("OSTROM_WORKSPACE") ?: DEFAULT_WORKSPACE,
                model = optional("OSTROM_AGENT_MODEL"),
                prompt = optional("OSTROM_AGENT_PROMPT")
                    ?: optional("OSTROM_AGENT_PROMPT_FILE")?.let { readFile(it).takeIf(String::isNotBlank) },
                agent = optional("OSTROM_AGENT"),
                checksSocket = optional(CHECKS_VARIABLE) ?: DEFAULT_CHECKS_SOCKET,
                checkHostSocket = optional("OSTROM_CHECK_HOST_SOCKET") ?: DEFAULT_CHECK_HOST_SOCKET,
                credentialSocket = optional(CREDENTIAL_VARIABLE) ?: DEFAULT_CREDENTIAL_SOCKET,
                stateSocket = optional(STATE_VARIABLE) ?: DEFAULT_STATE_SOCKET,
                gitHubConfigDirectory = optional("OSTROM_GH_CONFIG_DIR") ?: DEFAULT_GITHUB_CONFIG_DIRECTORY,
            )
        }

        const val DEFAULT_WORKSPACE = "/workspace"

        /** The agent kind of a plain shell, which no launch governs. */
        const val SHELL_AGENT = "shell"

        /** The variable that tells the agent's hooks where to ask for their checks. */
        const val CHECKS_VARIABLE = "OSTROM_RUNNER_CHECKS"

        /** The runner's sockets: the agent only learns the checks', and only with a launch. */
        val RUNNER_SOCKETS = setOf(CHECKS_VARIABLE, "OSTROM_CHECK_HOST_SOCKET")

        /** Where the runner serves the checks: the agent socket's directory, which the agent's container mounts. */
        const val DEFAULT_CHECKS_SOCKET = "/run/ostrom/checks.sock"

        /** Where the check host listens: a directory only the runner's and the check host's containers mount. */
        const val DEFAULT_CHECK_HOST_SOCKET = "/run/ostrom-check/check.sock"

        /** The variable that tells git's helper where to ask for its credential. */
        const val CREDENTIAL_VARIABLE = "OSTROM_CREDENTIAL_SOCKET"

        /** Where the runner serves git's credentials: the agent socket's directory. */
        const val DEFAULT_CREDENTIAL_SOCKET = "/run/ostrom/credential.sock"

        /** The variable that tells the agent's `Stop` hook where to report its background work. */
        const val STATE_VARIABLE = "OSTROM_AGENT_STATE_SOCKET"

        /** Where the runner hears of the agent's background work: the agent socket's directory. */
        const val DEFAULT_STATE_SOCKET = "/run/ostrom/state.sock"

        /** The variable that holds Claude Code's `--settings` in a cloud session ([AgentState.claudeSettings]). */
        const val SETTINGS_VARIABLE = "OSTROM_AGENT_SETTINGS"

        /** The agent's `gh` configuration: the agent socket's directory, which no workspace file reaches. */
        const val DEFAULT_GITHUB_CONFIG_DIRECTORY = "/run/ostrom/gh"

        /**
         * Where the session's init container copies the `ostrom` binary, in a volume every container mounts at the same
         * path: git's helper runs it.
         */
        const val BINARY = "/opt/ostrom/ostrom"
    }
}

/** How the session's terminal starts: a shell script that prepares the workspace then runs the agent. */
object SessionScript {
    /**
     * The script of the first terminal: clone the repository once (never reset afterwards), enter it, run the
     * agent (with its model and first message, if any; Claude Code with the settings of its background-work hooks),
     * then leave a login shell so the member can start the agent again. Values come from the environment, never from
     * the script's text. The clone and checkout run with [GIT_HARDENING] (no hook, no fsmonitor, HTTPS only, no
     * submodule), so a repository's content runs nothing.
     */
    const val FIRST = """
set -u
mkdir -p "${'$'}HOME"
if [ -n "${'$'}{OSTROM_REPOSITORY_URL:-}" ]; then
  dir="${'$'}OSTROM_WORKSPACE/${'$'}(basename "${'$'}OSTROM_REPOSITORY_URL" .git)"
  if [ ! -d "${'$'}dir/.git" ]; then
    rm -rf "${'$'}dir.clone"
    echo "Cloning ${'$'}OSTROM_REPOSITORY_URL…"
    if git ${'$'}OSTROM_GIT_HARDENING clone --no-recurse-submodules -- "${'$'}OSTROM_REPOSITORY_URL" "${'$'}dir.clone"; then
      case "${'$'}{OSTROM_REPOSITORY_REF:-}" in
        "") ;;
        -*) echo "Refused to check out ${'$'}OSTROM_REPOSITORY_REF." ;;
        *) git ${'$'}OSTROM_GIT_HARDENING -C "${'$'}dir.clone" checkout "${'$'}OSTROM_REPOSITORY_REF" || echo "Could not check out ${'$'}OSTROM_REPOSITORY_REF." ;;
      esac
      mv "${'$'}dir.clone" "${'$'}dir"
    else
      echo "The clone failed: the session starts in ${'$'}OSTROM_WORKSPACE."
    fi
  fi
  [ -d "${'$'}dir" ] && cd "${'$'}dir"
fi
if [ -n "${'$'}{OSTROM_LAUNCH_REFUSED:-}" ]; then
  echo "Ostrom did not open this launch: ${'$'}OSTROM_LAUNCH_REFUSED"
  echo "${'$'}OSTROM_AGENT_COMMAND does not start; this shell is yours."
  exec bash -l
fi
set --
if [ "${'$'}OSTROM_AGENT_COMMAND" = claude ] && [ -n "${'$'}{OSTROM_AGENT_SETTINGS:-}" ]; then
  set -- --settings "${'$'}OSTROM_AGENT_SETTINGS"
fi
if [ -n "${'$'}{OSTROM_AGENT_MODEL:-}" ]; then
  case "${'$'}OSTROM_AGENT_COMMAND" in
    bash) ;;
    gemini) set -- "${'$'}@" -m "${'$'}OSTROM_AGENT_MODEL" ;;
    *) set -- "${'$'}@" --model "${'$'}OSTROM_AGENT_MODEL" ;;
  esac
fi
if [ -n "${'$'}{OSTROM_AGENT_PROMPT:-}" ]; then
  case "${'$'}OSTROM_AGENT_COMMAND" in
    bash) ;;
    gemini) set -- "${'$'}@" -i "${'$'}OSTROM_AGENT_PROMPT" ;;
    opencode) set -- "${'$'}@" --prompt "${'$'}OSTROM_AGENT_PROMPT" ;;
    *) set -- "${'$'}@" "${'$'}OSTROM_AGENT_PROMPT" ;;
  esac
fi
"${'$'}OSTROM_AGENT_COMMAND" "${'$'}@"
echo
echo "The agent exited. Run ${'$'}OSTROM_AGENT_COMMAND to start it again."
exec bash -l
"""

    /** The script of a terminal started again after its shell ended. */
    const val AGAIN = """
cd "${'$'}OSTROM_WORKSPACE"
exec bash -l
"""

    /**
     * The agent's environment: the runner's, without its private variables, with the workspace and terminal.
     *
     * @param environment The runner's environment.
     * @param config The runner's configuration.
     *
     * @return The variables.
     */
    fun environment(environment: Map<String, String>, config: RunnerConfig): Map<String, String> =
        environment.filterKeys { it !in RunnerConfig.PRIVATE_VARIABLES } +
            listOfNotNull(config.prompt?.let { "OSTROM_AGENT_PROMPT" to it }) + mapOf(
            "OSTROM_WORKSPACE" to config.workspace,
            "HOME" to (environment["HOME"]?.takeIf { it.isNotBlank() } ?: "${config.workspace}/.home"),
            "TERM" to "xterm-256color",
            "LANG" to (environment["LANG"] ?: "C.UTF-8"),
            "PATH" to path(environment["PATH"], config.binary),
            RunnerConfig.STATE_VARIABLE to config.stateSocket,
            RunnerConfig.SETTINGS_VARIABLE to AgentState.claudeSettings(config.binary),
        ) + gitHub(config)

    /**
     * The agent's `PATH`: the directory of the `ostrom` binary first, so the agent's hooks and MCP servers
     * (`ostrom …`) run the binary the session was given, whatever the team's image holds.
     *
     * @param path The runner's `PATH`, if any.
     * @param binary The `ostrom` binary.
     *
     * @return The `PATH`.
     */
    fun path(path: String?, binary: String): String {
        val directory = binary.substringBeforeLast('/', "").ifEmpty { "/" }
        val rest = path?.split(':').orEmpty().filter { it.isNotEmpty() && it != directory }
        return (listOf(directory) + rest.ifEmpty { DEFAULT_PATH }).joinToString(":")
    }

    /** The `PATH` of a POSIX shell when the runner has none. */
    private val DEFAULT_PATH = listOf("/usr/local/bin", "/usr/bin", "/bin")

    /**
     * Git and `gh` against GitHub, with no token in the environment: git's helpers from the image are reset, then the
     * runner's helper answers for `https://github.com` only; git never prompts; `gh` reads the directory the runner
     * writes. These are paths, never a credential.
     *
     * @param config The runner's configuration.
     *
     * @return The variables.
     */
    fun gitHub(config: RunnerConfig): Map<String, String> = mapOf(
        "GIT_CONFIG_COUNT" to "2",
        "GIT_CONFIG_KEY_0" to "credential.helper",
        "GIT_CONFIG_VALUE_0" to "",
        "GIT_CONFIG_KEY_1" to "credential.https://github.com.helper",
        "GIT_CONFIG_VALUE_1" to "${config.binary} cloud credential",
        "GIT_TERMINAL_PROMPT" to "0",
        RunnerConfig.CREDENTIAL_VARIABLE to config.credentialSocket,
        "GH_CONFIG_DIR" to config.gitHubConfigDirectory,
        "OSTROM_GIT_HARDENING" to GIT_HARDENING,
    )

    /** The options of every git the runner's scripts start: no hook, no fsmonitor, HTTPS only, no submodule. */
    const val GIT_HARDENING = "-c core.hooksPath=/dev/null -c core.fsmonitor=false -c protocol.allow=never " +
        "-c protocol.https.allow=always -c fetch.recurseSubmodules=false -c submodule.recurse=false"
}

/**
 * Parses a NUL-separated environment block (`/proc/self/environ`).
 *
 * @param block The block.
 *
 * @return The variables; entries without a name are skipped.
 */
fun parseEnvironment(block: String): Map<String, String> = block.split('\u0000').mapNotNull { entry ->
    val separator = entry.indexOf('=')
    if (separator > 0) entry.substring(0, separator) to entry.substring(separator + 1) else null
}.toMap()
