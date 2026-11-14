// SPDX-License-Identifier: Apache-2.0
// A failure of the network is logged by its class and the last values are kept; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The owner's environment variables as the server serves them (the contract's `EnvironmentValues`): never logged.
 *
 * @property variables By name.
 * @property unavailable Assigned variables withheld because their value cannot be read now, by name.
 */
@Serializable
class EnvironmentValues(val variables: Map<String, String> = emptyMap(), val unavailable: List<String> = emptyList()) {
    /** Names only. */
    override fun toString(): String = "EnvironmentValues(${variables.keys.sorted()}, unavailable=$unavailable)"
}

/** The server no longer serves this execution's variables: another execution holds the session, or it is refused. */
class VariablesRefusedException : Exception("The server refused the variables.")

/**
 * What one Bash command applies: the current variables and the names given earlier and no longer assigned.
 *
 * @property variables The variables to export.
 * @property withdrawn The names to unset.
 */
@Serializable
class EnvironmentAnswer(val variables: Map<String, String> = emptyMap(), val withdrawn: List<String> = emptyList()) {
    /** Names only. */
    override fun toString(): String = "EnvironmentAnswer(names=${variables.keys.sorted()}, withdrawn=$withdrawn)"
}

/**
 * The owner's environment variables, held in the runner's memory only, as TeamCode's `RunnerVariables`: an agent start
 * always asks the server ([forStart]); a Bash command asks again when the last answer is older than [maxAge]
 * ([forCommand]), so a rotated, added or removed variable reaches the agent's next command within that delay, and an
 * idle session costs no request. A failed read keeps the last values and is retried after [retry]; a server without
 * the route is not asked again, and a fenced or refused execution serves nothing.
 *
 * @param fetch Asks the server; null on a server without the route.
 * @param clock The time.
 * @param log Writes a line to the runner's log (names only, never a value).
 * @param maxAge How long an answer serves the commands.
 * @param retry Before a failed read is tried again.
 * @param commandWait How long a command waits for the server before it runs with the last values.
 * @param startWait How long an agent start waits for the server.
 */
@Suppress("LongParameterList") // A source and its tunings, with defaults.
class RunnerVariables(
    private val fetch: suspend () -> EnvironmentValues?,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val maxAge: Duration = 30.seconds,
    private val retry: Duration = 15.seconds,
    private val commandWait: Duration = 3.seconds,
    private val startWait: Duration = 20.seconds,
) {
    private val lock = Mutex()
    private var current: Map<String, String> = emptyMap()
    private var nextRead: Instant? = null
    private var stopped = false
    private var failing = false

    /** Every name handed to the agent, by its environment or by a command: a name that disappears is unset. */
    private val given = mutableSetOf<String>()

    /**
     * The variables for an agent starting now, asked of the server first.
     *
     * @return The accepted variables.
     */
    suspend fun forStart(): Map<String, String> = lock.withLock {
        read(startWait)
        current.also { given += it.keys }
    }

    /**
     * The answer for one Bash command.
     *
     * @return The variables and the names to unset.
     */
    suspend fun forCommand(): EnvironmentAnswer = lock.withLock {
        if (nextRead.let { it == null || clock.now() >= it }) read(commandWait)
        EnvironmentAnswer(current, (given - current.keys).sorted()).also { given += current.keys }
    }

    /** Asks the server, under [lock]; any failure keeps [current]. */
    @Suppress("ReturnCount") // One per way the server may not answer.
    private suspend fun read(wait: Duration) {
        if (stopped) return
        val values = try {
            withTimeout(wait) { fetch() }
        } catch (_: TimeoutCancellationException) {
            failed()
            return
        } catch (error: CancellationException) {
            throw error
        } catch (_: VariablesRefusedException) {
            stopped = true
            current = emptyMap()
            log("the server no longer serves the variables: the agent's commands get none")
            return
        } catch (error: Exception) {
            log("the variables could not be read: ${error::class.simpleName}")
            failed()
            return
        }
        if (values == null) {
            stopped = true
            log("the server does not serve variables to running sessions; the agent gets none")
            return
        }
        if (failing) log("the variables are read again")
        failing = false
        val accepted = AgentEnvironment.accepted(values.variables)
        val refused = (values.variables.keys - accepted.keys).sorted()
        if (refused.isNotEmpty() && accepted.keys != current.keys) {
            log("variables not given to the agent (reserved names): ${refused.joinToString(", ")}")
        }
        if (current.isNotEmpty() && accepted != current) {
            log("variables changed: ${accepted.keys.joinToString(", ").ifEmpty { "none" }}")
        }
        current = accepted
        nextRead = clock.now() + maxAge
    }

    private fun failed() {
        failing = true
        nextRead = clock.now() + retry
    }
}

/**
 * The owner's variables as the agent receives them, as TeamCode's `AgentEnvironment`: in its process environment when
 * it starts, and for Claude Code through `CLAUDE_ENV_FILE` before each Bash command (a script that holds no value and
 * asks the runner). Values are never logged and never placed on a command line.
 */
object AgentEnvironment {
    /** The variable Claude Code reads its per-command environment script from. */
    const val CLAUDE_ENV_FILE = "CLAUDE_ENV_FILE"

    /** What the server accepts as a variable's name, checked again here since values reach a shell. */
    private val NAME = Regex("[A-Z_][A-Z0-9_]{0,127}")

    /**
     * Names that would steer the shell, the loader, git, `gh` or the agent's own connection: TeamCode's list. A value
     * from an older server or a tampered answer never sets them.
     */
    private val RESERVED = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV",
        "PROMPT_COMMAND", "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
        "PYTHONPATH", "PYTHONSTARTUP", "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH",
        "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE", "GIT_TERMINAL_PROMPT",
        "GH_CONFIG_DIR", "SHELLOPTS", "BASHOPTS", "CDPATH", "GLOBIGNORE", "PS4",
    )

    /** TeamCode's prefixes, with Ostrom's own variables (`OSTROM_`). */
    private val RESERVED_PREFIXES = listOf(
        "LD_", "DYLD_", "OSTROM_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_",
        "BASH_FUNC_",
    )

    /**
     * Whether a variable may reach the agent.
     *
     * @param name Its name.
     *
     * @return Whether it is valid and not reserved.
     */
    fun accepted(name: String): Boolean =
        NAME.matches(name) && name !in RESERVED && RESERVED_PREFIXES.none(name::startsWith)

    /**
     * The variables that may reach the agent: valid, unreserved names and values without a NUL, sorted by name.
     *
     * @param variables The owner's variables.
     *
     * @return Those accepted.
     */
    fun accepted(variables: Map<String, String>): Map<String, String> = variables
        .filter { (name, value) -> accepted(name) && '\u0000' !in value }
        .entries.sortedBy { it.key }.associate { it.key to it.value }

    /**
     * Shell code that sets [variables] and unsets [withdrawn] (names given earlier and no longer assigned).
     *
     * @param variables The variables.
     * @param withdrawn The names to unset.
     *
     * @return The code, one line per variable.
     */
    fun script(variables: Map<String, String>, withdrawn: Collection<String> = emptyList()): String = buildString {
        appendLine("# Written by Ostrom for this session: your assigned environment variables. Do not edit.")
        withdrawn.filter { accepted(it) && it !in variables }.sorted().forEach { appendLine("unset $it") }
        accepted(variables).forEach { (name, value) -> appendLine("export $name=${quote(value)}") }
    }

    /**
     * A shell word for [value]: single quotes when it has none, otherwise double quotes with `\`, `$`, `` ` `` and
     * `"` escaped. Both read back as exactly [value], with nothing expanded.
     *
     * @param value The value.
     *
     * @return The word.
     */
    fun quote(value: String): String = if ('\'' !in value) {
        "'$value'"
    } else {
        "\"" + value.replace("\\", "\\\\").replace("$", "\\$").replace("`", "\\`").replace("\"", "\\\"") + "\""
    }

    /**
     * The `CLAUDE_ENV_FILE` script: no value, only a call to the runner, which answers with [script]. A failure
     * prints nothing, so a Bash command never fails because of it.
     *
     * @param binary The `ostrom` binary.
     * @param socket The runner's environment socket.
     *
     * @return The script.
     */
    fun cloudScript(binary: String, socket: String): String = buildString {
        appendLine("# Written by Ostrom: asks the session runner for the owner's current environment variables.")
        appendLine("eval \"\$(${quote(binary)} cloud environment --socket ${quote(socket)} 2>/dev/null)\"")
    }
}
